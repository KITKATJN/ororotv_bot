package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite storage (one file, {@code data/bot.db}): users, their words with per-word answer counters,
 * an import log and an answer log. All times are epoch seconds. Methods are synchronized because
 * the Telegram thread, the Mini App server and the translation thread share one connection.
 */
public class Database implements AutoCloseable {

    /** How a quiz question ended. */
    public enum Result { CORRECT, WRONG, SKIPPED }

    /** A word as the quiz sees it. */
    public record QuizWord(String key, String word, String translation, String context,
                           int correct, int wrong) {
        WordEntry entry() {
            return new WordEntry(word, translation, context);
        }
    }

    /** One finished (or rejected) import, for the log and the limits. */
    public record ImportLog(long chatId, String source, int received, int added, int untranslated,
                            int deferred, String translator) {
    }

    public record Counts(int correct, int wrong, int skipped) {
        int total() {
            return correct + wrong + skipped;
        }

        int percentRight() {
            return total() == 0 ? 0 : Math.round(100f * correct / total());
        }
    }

    public record HardWord(String word, int correct, int wrong) {
    }

    public record UserStats(int activeWords, int addedLast24h, Counts today, Counts allTime,
                            int streakDays, List<HardWord> hardest) {
    }

    public record UserRow(long chatId, String username, String firstName) {
        String label() {
            if (username != null && !username.isBlank()) {
                return "@" + username;
            }
            if (firstName != null && !firstName.isBlank()) {
                return firstName + " (" + chatId + ")";
            }
            return String.valueOf(chatId);
        }
    }

    public record TopUser(UserRow user, int answers, int correct) {
    }

    public record RecentImport(long at, UserRow user, String source, int received, int added,
                               int deferred, String translator) {
    }

    public record AdminReport(int users, int newUsers24h, int activeUsers24h, int storedWords,
                              int imports24h, int wordsReceived24h, int wordsAdded24h,
                              Counts answers24h, List<TopUser> top7d, List<RecentImport> recentImports) {
    }

    private static final long DAY = 24 * 3600;

    private final Connection db;
    private final ZoneId zone = ZoneId.systemDefault();

    public Database(Path file) throws SQLException, IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      chat_id    INTEGER PRIMARY KEY,
                      username   TEXT,
                      first_name TEXT,
                      first_seen INTEGER NOT NULL,
                      last_seen  INTEGER NOT NULL)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS words (
                      chat_id     INTEGER NOT NULL,
                      word_key    TEXT    NOT NULL,
                      word        TEXT    NOT NULL,
                      translation TEXT    NOT NULL,
                      context     TEXT,
                      added_at    INTEGER NOT NULL,
                      active      INTEGER NOT NULL DEFAULT 1,
                      correct     INTEGER NOT NULL DEFAULT 0,
                      wrong       INTEGER NOT NULL DEFAULT 0,
                      last_asked  INTEGER,
                      PRIMARY KEY (chat_id, word_key))""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS imports (
                      id           INTEGER PRIMARY KEY AUTOINCREMENT,
                      chat_id      INTEGER NOT NULL,
                      at           INTEGER NOT NULL,
                      source       TEXT    NOT NULL,
                      received     INTEGER NOT NULL,
                      added        INTEGER NOT NULL,
                      untranslated INTEGER NOT NULL,
                      deferred     INTEGER NOT NULL,
                      translator   TEXT)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS answers (
                      id       INTEGER PRIMARY KEY AUTOINCREMENT,
                      chat_id  INTEGER NOT NULL,
                      word_key TEXT    NOT NULL,
                      at       INTEGER NOT NULL,
                      result   TEXT    NOT NULL)""");
            s.execute("CREATE INDEX IF NOT EXISTS imports_chat_at ON imports(chat_id, at)");
            s.execute("CREATE INDEX IF NOT EXISTS answers_chat_at ON answers(chat_id, at)");
            s.execute("CREATE INDEX IF NOT EXISTS answers_at ON answers(at)");
        }
    }

    static long now() {
        return Instant.now().getEpochSecond();
    }

    static String key(String word) {
        return word.strip().toLowerCase();
    }

    // --------------------------------------------------------------- users

    /** Creates or refreshes a user; null names keep what is already stored. */
    public synchronized void touchUser(long chatId, String username, String firstName) throws SQLException {
        long now = now();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO users(chat_id, username, first_name, first_seen, last_seen) VALUES (?,?,?,?,?)
                ON CONFLICT(chat_id) DO UPDATE SET
                  username = COALESCE(excluded.username, users.username),
                  first_name = COALESCE(excluded.first_name, users.first_name),
                  last_seen = excluded.last_seen""")) {
            ps.setLong(1, chatId);
            ps.setString(2, username);
            ps.setString(3, firstName);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public synchronized boolean isKnownUser(long chatId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM users WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --------------------------------------------------------------- words

    /** Every word this chat ever had (also removed ones), by key: used as a translation cache. */
    public synchronized Map<String, WordEntry> allWords(long chatId) throws SQLException {
        Map<String, WordEntry> out = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT word_key, word, translation, context FROM words WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new WordEntry(rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    public synchronized List<QuizWord> activeWords(long chatId) throws SQLException {
        List<QuizWord> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT word_key, word, translation, context, correct, wrong
                FROM words WHERE chat_id=? AND active=1""")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new QuizWord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getInt(6)));
                }
            }
        }
        return out;
    }

    /**
     * Saves an import in one transaction: the chat's dictionary becomes exactly {@code keepKeys}
     * plus {@code newWords} (words no longer exported are deactivated but keep their statistics).
     */
    public synchronized void applyImport(long chatId, Collection<String> keepKeys, List<WordEntry> newWords,
                                         Map<String, WordEntry> updatedTranslations, ImportLog log) throws SQLException {
        long now = now();
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            try (PreparedStatement ps = db.prepareStatement("UPDATE words SET active=0 WHERE chat_id=?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE words SET active=1 WHERE chat_id=? AND word_key=?")) {
                for (String k : keepKeys) {
                    ps.setLong(1, chatId);
                    ps.setString(2, k);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = db.prepareStatement("""
                    UPDATE words SET translation=?, context=COALESCE(?, context) WHERE chat_id=? AND word_key=?""")) {
                for (Map.Entry<String, WordEntry> e : updatedTranslations.entrySet()) {
                    ps.setString(1, e.getValue().translation());
                    ps.setString(2, e.getValue().context());
                    ps.setLong(3, chatId);
                    ps.setString(4, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = db.prepareStatement("""
                    INSERT OR IGNORE INTO words(chat_id, word_key, word, translation, context, added_at, active)
                    VALUES (?,?,?,?,?,?,1)""")) {
                for (WordEntry w : newWords) {
                    ps.setLong(1, chatId);
                    ps.setString(2, key(w.word()));
                    ps.setString(3, w.word());
                    ps.setString(4, w.translation());
                    ps.setString(5, w.context());
                    ps.setLong(6, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            insertImportLog(log, now);
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(auto);
        }
    }

    /** Logs an import that changed nothing (no words found, nothing translatable, rate-limited...). */
    public synchronized void logImport(ImportLog log) throws SQLException {
        insertImportLog(log, now());
    }

    private void insertImportLog(ImportLog log, long at) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO imports(chat_id, at, source, received, added, untranslated, deferred, translator)
                VALUES (?,?,?,?,?,?,?,?)""")) {
            ps.setLong(1, log.chatId());
            ps.setLong(2, at);
            ps.setString(3, log.source());
            ps.setInt(4, log.received());
            ps.setInt(5, log.added());
            ps.setInt(6, log.untranslated());
            ps.setInt(7, log.deferred());
            ps.setString(8, log.translator());
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------- limits

    /** New words added to this chat since {@code since}. */
    public synchronized int wordsAddedSince(long chatId, long since) throws SQLException {
        return intQuery("SELECT COUNT(*) FROM words WHERE chat_id=? AND added_at>=?", chatId, since);
    }

    /** When the oldest word inside the window was added (the limit frees up 24h after that), or 0. */
    public synchronized long oldestWordAddedSince(long chatId, long since) throws SQLException {
        return longQuery("SELECT COALESCE(MIN(added_at),0) FROM words WHERE chat_id=? AND added_at>=?", chatId, since);
    }

    public synchronized int importsSince(long chatId, long since) throws SQLException {
        return intQuery("SELECT COUNT(*) FROM imports WHERE chat_id=? AND at>=?", chatId, since);
    }

    public synchronized long oldestImportSince(long chatId, long since) throws SQLException {
        return longQuery("SELECT COALESCE(MIN(at),0) FROM imports WHERE chat_id=? AND at>=?", chatId, since);
    }

    // ------------------------------------------------------------- answers

    public synchronized void recordAnswer(long chatId, String wordKey, Result result) throws SQLException {
        long now = now();
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO answers(chat_id, word_key, at, result) VALUES (?,?,?,?)")) {
            ps.setLong(1, chatId);
            ps.setString(2, wordKey);
            ps.setLong(3, now);
            ps.setString(4, result.name());
            ps.executeUpdate();
        }
        String column = result == Result.CORRECT ? "correct" : "wrong"; // a skip counts as not known
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE words SET " + column + "=" + column + "+1, last_asked=? WHERE chat_id=? AND word_key=?")) {
            ps.setLong(1, now);
            ps.setLong(2, chatId);
            ps.setString(3, wordKey);
            ps.executeUpdate();
        }
    }

    /** Remembers another accepted answer for a word (e.g. a synonym confirmed by back-translation). */
    public synchronized void addTranslationVariant(long chatId, String wordKey, String variant) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE words SET translation = translation || ', ' || ? WHERE chat_id=? AND word_key=?")) {
            ps.setString(1, variant);
            ps.setLong(2, chatId);
            ps.setString(3, wordKey);
            ps.executeUpdate();
        }
    }

    // --------------------------------------------------------------- stats

    public synchronized UserStats userStats(long chatId) throws SQLException {
        long now = now();
        long midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond();
        int active = intQuery("SELECT COUNT(*) FROM words WHERE chat_id=? AND active=1", chatId);
        int added = intQuery("SELECT COUNT(*) FROM words WHERE chat_id=? AND added_at>=?", chatId, now - DAY);
        Counts today = counts("SELECT result, COUNT(*) FROM answers WHERE chat_id=? AND at>=? GROUP BY result",
                chatId, midnight);
        Counts all = counts("SELECT result, COUNT(*) FROM answers WHERE chat_id=? GROUP BY result", chatId);

        List<HardWord> hardest = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT word, correct, wrong FROM words
                WHERE chat_id=? AND active=1 AND wrong>0
                ORDER BY (wrong - correct) DESC, wrong DESC LIMIT 5""")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hardest.add(new HardWord(rs.getString(1), rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return new UserStats(active, added, today, all, streak(chatId), hardest);
    }

    /** Consecutive days (ending today or yesterday) with at least one answer. */
    private int streak(long chatId) throws SQLException {
        List<LocalDate> days = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT DISTINCT date(at, 'unixepoch', 'localtime') AS d FROM answers
                WHERE chat_id=? ORDER BY d DESC LIMIT 1000""")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(LocalDate.parse(rs.getString(1)));
                }
            }
        }
        LocalDate expected = LocalDate.now(zone);
        if (!days.isEmpty() && days.get(0).equals(expected.minusDays(1))) {
            expected = expected.minusDays(1); // nothing yet today, but the streak is still alive
        }
        int streak = 0;
        for (LocalDate d : days) {
            if (!d.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    public synchronized AdminReport adminReport() throws SQLException {
        long since = now() - DAY;
        int users = intQuery("SELECT COUNT(*) FROM users");
        int newUsers = intQuery("SELECT COUNT(*) FROM users WHERE first_seen>=?", since);
        int activeUsers = intQuery("""
                SELECT COUNT(*) FROM (SELECT chat_id FROM answers WHERE at>=?
                                      UNION SELECT chat_id FROM imports WHERE at>=?)""", since, since);
        int stored = intQuery("SELECT COUNT(*) FROM words WHERE active=1");
        int imports = intQuery("SELECT COUNT(*) FROM imports WHERE at>=?", since);
        int received = intQuery("SELECT COALESCE(SUM(received),0) FROM imports WHERE at>=?", since);
        int added = intQuery("SELECT COALESCE(SUM(added),0) FROM imports WHERE at>=?", since);
        Counts answers = counts("SELECT result, COUNT(*) FROM answers WHERE at>=? GROUP BY result", since);

        List<TopUser> top = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT a.chat_id, u.username, u.first_name, COUNT(*) AS n, SUM(a.result='CORRECT')
                FROM answers a LEFT JOIN users u ON u.chat_id = a.chat_id
                WHERE a.at>=? GROUP BY a.chat_id ORDER BY n DESC LIMIT 5""")) {
            ps.setLong(1, now() - 7 * DAY);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    top.add(new TopUser(new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3)),
                            rs.getInt(4), rs.getInt(5)));
                }
            }
        }
        List<RecentImport> recent = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT i.at, i.chat_id, u.username, u.first_name, i.source, i.received, i.added, i.deferred, i.translator
                FROM imports i LEFT JOIN users u ON u.chat_id = i.chat_id
                ORDER BY i.at DESC, i.id DESC LIMIT 5""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    recent.add(new RecentImport(rs.getLong(1),
                            new UserRow(rs.getLong(2), rs.getString(3), rs.getString(4)),
                            rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9)));
                }
            }
        }
        return new AdminReport(users, newUsers, activeUsers, stored, imports, received, added, answers, top, recent);
    }

    // ----------------------------------------------------------- migration

    /** One-time move of words from the old {@code state.json} into the database (renamed afterwards). */
    public synchronized int migrateFromJson(Path stateJson) throws IOException, SQLException {
        if (!Files.exists(stateJson)) {
            return 0;
        }
        JsonNode root = new ObjectMapper().readTree(stateJson.toFile());
        int count = 0;
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT OR IGNORE INTO words(chat_id, word_key, word, translation, context, added_at, active)
                VALUES (?,?,?,?,?,0,1)""")) { // added_at=0: migrated words don't count against today's limit
            var chats = root.fields();
            while (chats.hasNext()) {
                var chat = chats.next();
                long chatId = Long.parseLong(chat.getKey());
                for (JsonNode w : chat.getValue().path("words")) {
                    String word = w.path("word").asText("");
                    String translation = w.path("translation").asText("");
                    if (word.isBlank() || translation.isBlank()) {
                        continue;
                    }
                    ps.setLong(1, chatId);
                    ps.setString(2, key(word));
                    ps.setString(3, word);
                    ps.setString(4, translation);
                    ps.setString(5, w.hasNonNull("context") ? w.get("context").asText() : null);
                    ps.addBatch();
                    count++;
                }
                touchUser(chatId, null, null);
            }
            ps.executeBatch();
        }
        Files.move(stateJson, stateJson.resolveSibling(stateJson.getFileName() + ".migrated"),
                StandardCopyOption.REPLACE_EXISTING);
        return count;
    }

    // ------------------------------------------------------------- helpers

    private int intQuery(String sql, Object... params) throws SQLException {
        return (int) longQuery(sql, params);
    }

    private long longQuery(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private Counts counts(String sql, Object... params) throws SQLException {
        int c = 0, w = 0, s = 0;
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                switch (rs.getString(1)) {
                    case "CORRECT" -> c = rs.getInt(2);
                    case "WRONG" -> w = rs.getInt(2);
                    case "SKIPPED" -> s = rs.getInt(2);
                    default -> { }
                }
            }
        }
        return new Counts(c, w, s);
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
