package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonDefault;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vocabulary trainer: sends a word from the user's ororo.tv dictionary (weak words more often), the
 * user replies with a translation, and the bot reacts to that reply and sends the next word.
 *
 * <p>Words arrive through the "Connect ororo.tv" Mini App (see {@link WebAppServer}) or as a
 * dictionary file sent to the chat. Everything is recorded in {@link Database}: users, imports,
 * answers. Imports are limited per user and 24 hours (new words and number of imports).
 */
public class QuizBot implements LongPollingSingleThreadUpdateConsumer {

    public static final String EXPORT_URL = "https://ororo.tv/en/users/export_dictionary";
    private static final long MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    private static final long DAY = 24 * 3600;
    private static final Logger LOG = LoggerFactory.getLogger("bot");

    private static final String COMMANDS =
            "Commands: /quiz - next word, /skip - show the answer, /stats - your progress, /sync - reload words, /help";

    private static final String WELCOME = """
            Hi! I quiz you on the words from your ororo.tv dictionary.
            I send a word, you reply with its translation.

            Tap "Connect ororo.tv" to load your words.

            Signed up with Google and have no ororo password? Tap "Download dictionary file", \
            then send the downloaded file to me here.

            """ + COMMANDS;

    private static final String WELCOME_NO_WEBAPP = """
            Hi! I quiz you on the words from your ororo.tv dictionary.
            I send a word, you reply with its translation.

            To load your words: tap "Download dictionary file" (while signed in to ororo.tv), \
            then send the downloaded file to me here.

            """ + COMMANDS;

    /** Outcome of an import, reported back to the Mini App. */
    public record ImportResult(boolean ok, String message) {
    }

    /** Per-user protections, counted over a rolling 24 hours. */
    public record Limits(int maxNewWordsPerDay, int maxImportsPerDay) {
    }

    /** Everything decided about an import before the (possibly slow) translation step. */
    private record ImportPlan(long chatId, String source, int received, List<String> keep,
                              Map<String, WordEntry> updated, List<WordEntry> accepted, int deferred, String lang) {
    }

    private final TelegramClient telegram;
    private final Database db;
    private final String webAppUrl; // null when the Mini App is not configured
    private final List<Translator> translators; // tried in order for words that arrive without a translation
    private final String defaultLang;
    private final Limits limits;
    private final Set<Long> adminIds;

    private final Map<Long, Database.QuizWord> current = new ConcurrentHashMap<>();
    private final Set<Long> importing = ConcurrentHashMap.newKeySet();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    public QuizBot(TelegramClient telegram, Database db, String webAppUrl, List<Translator> translators,
                   String defaultLang, Limits limits, Set<Long> adminIds) {
        this.telegram = telegram;
        this.db = db;
        this.webAppUrl = webAppUrl == null || webAppUrl.isBlank() ? null : webAppUrl;
        this.translators = List.copyOf(translators);
        this.defaultLang = defaultLang;
        this.limits = limits;
        this.adminIds = Set.copyOf(adminIds);
    }

    /** Lets a running translation finish (up to 30 s) before the database is closed. */
    public void shutdown() {
        background.shutdown();
        try {
            if (!background.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("Translation still running at shutdown; that import is lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sets the bot's default menu button (next to the message field in every chat) to open the Mini App,
     * or resets it when the Mini App is off so a stale tunnel address is never shown.
     */
    public void installMenuButton() {
        try {
            telegram.execute(SetChatMenuButton.builder()
                    .menuButton(webAppUrl != null
                            ? MenuButtonWebApp.builder().text("Words").webAppInfo(new WebAppInfo(webAppUrl)).build()
                            : MenuButtonDefault.builder().build())
                    .build());
        } catch (TelegramApiException e) {
            LOG.warn("Could not set the menu button: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void consume(Update update) {
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        long chatId = message.getChatId();
        try {
            rememberUser(chatId, message.getFrom());
            if (message.hasDocument()) {
                importFile(chatId, message.getDocument());
                return;
            }
            if (!message.hasText()) {
                return;
            }
            String text = message.getText().strip();
            if (text.startsWith("/")) {
                handleCommand(chatId, text);
            } else {
                handleAnswer(chatId, message, text);
            }
        } catch (Exception e) {
            LOG.error("Failed to handle a message in chat {}", chatId, e);
            send(chatId, "Something went wrong, please try again.");
        }
    }

    /** Called by {@link WebAppServer} with the raw JSON the Mini App fetched from the ororo.tv API. */
    public synchronized ImportResult importWords(long chatId, String rawDictionary) {
        try {
            db.touchUser(chatId, null, null);
            return useWords(chatId, DictionaryParser.parse(rawDictionary), rawDictionary,
                    DictionaryParser.detectLanguage(rawDictionary, defaultLang), "miniapp");
        } catch (IOException e) {
            return new ImportResult(false, "Could not read the dictionary: " + e.getMessage());
        } catch (SQLException e) {
            LOG.error("Import failed for chat {}", chatId, e);
            return new ImportResult(false, "The bot could not save your words. Please try again later.");
        }
    }

    private void rememberUser(long chatId, User from) throws SQLException {
        boolean known = db.isKnownUser(chatId);
        db.touchUser(chatId, from == null ? null : from.getUserName(), from == null ? null : from.getFirstName());
        if (!known) {
            LOG.info("[user] new user {} (@{}, {})", chatId,
                    from == null ? "-" : from.getUserName(), from == null ? "-" : from.getFirstName());
        }
    }

    // ------------------------------------------------------------ commands

    private void handleCommand(long chatId, String text) throws SQLException {
        String command = text.split("\\s+", 2)[0].toLowerCase();
        int at = command.indexOf('@'); // "/quiz@MyBot" in group chats
        if (at > 0) {
            command = command.substring(0, at);
        }
        switch (command) {
            case "/start", "/help" -> send(chatId, webAppUrl != null ? WELCOME : WELCOME_NO_WEBAPP, loadButtons());
            case "/sync" -> send(chatId, "Tap a button to reload your words:", loadButtons());
            case "/quiz", "/word", "/next" -> askNextWord(chatId);
            case "/skip" -> skip(chatId);
            case "/stats" -> send(chatId, formatUserStats(chatId));
            case "/admin" -> send(chatId, adminIds.contains(chatId)
                    ? formatAdminReport()
                    : "This command is only for the bot admin. (Your Telegram ID is " + chatId + ".)");
            default -> send(chatId, "Unknown command. Try /help");
        }
    }

    private InlineKeyboardMarkup loadButtons() {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> keyboard = InlineKeyboardMarkup.builder();
        if (webAppUrl != null) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("📚 Connect ororo.tv")
                    .webApp(new WebAppInfo(webAppUrl))
                    .build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("📥 Download dictionary file")
                .url(EXPORT_URL)
                .build()));
        return keyboard.build();
    }

    // -------------------------------------------------------------- import

    /** The user downloaded the export in their own browser and sent it to us as a file. */
    private void importFile(long chatId, Document document) throws Exception {
        if (document.getFileSize() != null && document.getFileSize() > MAX_IMPORT_BYTES) {
            send(chatId, "That file is too big to be a dictionary export.");
            return;
        }
        var file = telegram.execute(GetFile.builder().fileId(document.getFileId()).build());
        String body;
        try (InputStream in = telegram.downloadFileAsStream(file)) {
            body = new String(in.readNBytes((int) MAX_IMPORT_BYTES), StandardCharsets.UTF_8);
        }
        useWords(chatId, DictionaryParser.parse(body), body, DictionaryParser.detectLanguage(body, defaultLang), "file");
    }

    /**
     * Applies the limits and decides what to do with each incoming word: known words keep their
     * translation and statistics, new words (up to today's allowance) are translated in the background.
     */
    private ImportResult useWords(long chatId, List<WordEntry> parsed, String rawBody, String lang, String source)
            throws SQLException {
        if (!importing.add(chatId)) {
            String msg = "I'm still working on your previous import, one moment please.";
            send(chatId, msg);
            return new ImportResult(false, msg);
        }
        boolean handedOff = false;
        try {
            long since = Database.now() - DAY;
            int importsToday = db.importsSince(chatId, since);
            if (importsToday >= limits.maxImportsPerDay()) {
                long retryAt = db.oldestImportSince(chatId, since) + DAY;
                String msg = "You've loaded your words " + importsToday + " times in the last 24 hours (the limit is "
                        + limits.maxImportsPerDay() + "). You can load them again after " + time(retryAt) + ".";
                LOG.info("[limit] chat {} hit the import limit ({})", chatId, importsToday);
                send(chatId, msg);
                return new ImportResult(false, msg);
            }
            if (parsed.isEmpty()) {
                db.logImport(new Database.ImportLog(chatId, source, 0, 0, 0, 0, null));
                send(chatId, "I could not find any words in that. If your ororo.tv dictionary is not empty, "
                        + "the format is different from what I expect. It starts with:\n" + preview(rawBody));
                return new ImportResult(false, "No words found. Details are in the chat.");
            }

            Map<String, WordEntry> incoming = new LinkedHashMap<>();
            for (WordEntry w : parsed) {
                incoming.putIfAbsent(Database.key(w.word()), w);
            }
            Map<String, WordEntry> existing = db.allWords(chatId);
            List<String> keep = new ArrayList<>();
            Map<String, WordEntry> updated = new HashMap<>();
            List<WordEntry> fresh = new ArrayList<>();
            for (Map.Entry<String, WordEntry> e : incoming.entrySet()) {
                WordEntry old = existing.get(e.getKey());
                if (old == null) {
                    fresh.add(e.getValue());
                } else {
                    keep.add(e.getKey());
                    if (e.getValue().hasTranslation() && !e.getValue().translation().equals(old.translation())) {
                        updated.put(e.getKey(), e.getValue()); // the file brought a (better) translation
                    }
                }
            }
            int allowance = Math.max(0, limits.maxNewWordsPerDay() - db.wordsAddedSince(chatId, since));
            List<WordEntry> accepted = List.copyOf(fresh.subList(0, Math.min(allowance, fresh.size())));
            ImportPlan plan = new ImportPlan(chatId, source, incoming.size(), keep, updated, accepted,
                    fresh.size() - accepted.size(), lang);

            List<WordEntry> missing = accepted.stream().filter(w -> !w.hasTranslation()).toList();
            if (missing.isEmpty() || translators.isEmpty()) {
                return finishImport(plan, Map.of(), null);
            }
            send(chatId, "Got " + incoming.size() + " words. Translating " + missing.size() + " new ones, one moment...");
            background.submit(() -> translateAndFinish(plan, missing));
            handedOff = true;
            return new ImportResult(true, "Got " + incoming.size() + " words! Translating " + missing.size()
                    + " new ones now; go back to the chat, the first word is on its way.");
        } finally {
            if (!handedOff) {
                importing.remove(chatId);
            }
        }
    }

    private void translateAndFinish(ImportPlan plan, List<WordEntry> missing) {
        Map<String, String> translated = new HashMap<>();
        Set<String> used = new LinkedHashSet<>();
        List<WordEntry> todo = missing;
        for (Translator translator : translators) {
            if (todo.isEmpty()) {
                break;
            }
            try {
                Map<String, String> result = translator.translate(todo, plan.lang());
                if (!result.isEmpty()) {
                    used.add(translator.name());
                }
                result.forEach((k, v) -> translated.put(Database.key(k), v));
            } catch (Exception e) {
                LOG.warn("{} translation failed: {}", translator.name(), e.getMessage());
            }
            todo = todo.stream().filter(w -> !translated.containsKey(Database.key(w.word()))).toList();
        }
        synchronized (this) {
            try {
                finishImport(plan, translated, used.isEmpty() ? null : String.join("+", used));
            } catch (SQLException e) {
                LOG.error("Saving import failed for chat {}", plan.chatId(), e);
                send(plan.chatId(), "Sorry, I could not save your words. Please try again later.");
            } finally {
                importing.remove(plan.chatId());
            }
        }
    }

    private ImportResult finishImport(ImportPlan plan, Map<String, String> translated, String translator)
            throws SQLException {
        long chatId = plan.chatId();
        List<WordEntry> newWords = new ArrayList<>();
        for (WordEntry w : plan.accepted()) {
            String t = w.hasTranslation() ? w.translation() : translated.get(Database.key(w.word()));
            if (t != null && !t.isBlank()) {
                newWords.add(new WordEntry(w.word(), t, w.context()));
            }
        }
        int untranslated = plan.accepted().size() - newWords.size();
        Database.ImportLog log = new Database.ImportLog(chatId, plan.source(), plan.received(), newWords.size(),
                untranslated, plan.deferred(), translator);
        LOG.info("[import] chat {} via {}: received {}, known {}, added {}, untranslated {}, deferred {}, translator {}",
                chatId, plan.source(), plan.received(), plan.keep().size(), newWords.size(), untranslated,
                plan.deferred(), translator);

        if (plan.keep().isEmpty() && newWords.isEmpty()) {
            db.logImport(log);
            String msg = plan.deferred() > 0
                    ? "You've reached the limit of " + limits.maxNewWordsPerDay() + " new words per 24 hours. "
                    + "Reload your words after " + time(db.oldestWordAddedSince(chatId, Database.now() - DAY) + DAY)
                    + " to add more."
                    : "I received " + plan.received() + " words, but couldn't get translations for them, so I can't "
                    + "check your answers. Try again later, or send me the dictionary file.";
            send(chatId, msg, loadButtons());
            return new ImportResult(false, msg);
        }

        db.applyImport(chatId, plan.keep(), newWords, plan.updated(), log);
        current.remove(chatId);

        int total = plan.keep().size() + newWords.size();
        StringBuilder msg = new StringBuilder("Loaded " + total + " words");
        msg.append(newWords.isEmpty() ? "." : " (" + newWords.size() + " new).");
        if (untranslated > 0) {
            msg.append(" ").append(untranslated).append(" couldn't be translated; I'll retry them next time.");
        }
        if (plan.deferred() > 0) {
            msg.append(" You've reached the limit of ").append(limits.maxNewWordsPerDay())
                    .append(" new words per 24 hours: ").append(plan.deferred())
                    .append(" more will be added if you reload after ")
                    .append(time(db.oldestWordAddedSince(chatId, Database.now() - DAY) + DAY)).append(".");
        }
        send(chatId, msg + " Let's go!");
        askNextWord(chatId);
        return new ImportResult(true, msg + " Go back to the chat to start.");
    }

    private static String preview(String body) {
        String s = body == null ? "" : body.strip();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ---------------------------------------------------------------- quiz

    /**
     * Picks the next word, favouring weak words: never-asked words and words answered wrong come up
     * more often, well-known words less. The word just asked is never repeated immediately.
     */
    private void askNextWord(long chatId) throws SQLException {
        List<Database.QuizWord> words = db.activeWords(chatId);
        if (words.isEmpty()) {
            send(chatId, "No words yet. Load them first:", loadButtons());
            return;
        }
        Database.QuizWord previous = current.get(chatId);
        double[] weights = new double[words.size()];
        double total = 0;
        for (int i = 0; i < words.size(); i++) {
            Database.QuizWord w = words.get(i);
            boolean repeat = words.size() > 1 && previous != null && w.key().equals(previous.key());
            weights[i] = repeat ? 0 : weight(w);
            total += weights[i];
        }
        double r = random.nextDouble() * total;
        Database.QuizWord next = words.get(words.size() - 1);
        for (int i = 0; i < words.size(); i++) {
            r -= weights[i];
            if (r < 0 && weights[i] > 0) {
                next = words.get(i);
                break;
            }
        }
        current.put(chatId, next);
        String text = "Translate: " + next.word();
        if (next.context() != null) {
            text += "\n💬 " + next.context();
        }
        send(chatId, text);
    }

    static double weight(Database.QuizWord w) {
        if (w.correct() + w.wrong() == 0) {
            return 3.0; // new word: show it soon
        }
        return (1.0 + 2.0 * w.wrong()) / (1.0 + w.correct());
    }

    private void skip(long chatId) throws SQLException {
        Database.QuizWord word = current.get(chatId);
        if (word == null) {
            send(chatId, "No active word. Use /quiz.");
            return;
        }
        db.recordAnswer(chatId, word.key(), Database.Result.SKIPPED);
        send(chatId, "⏭ " + word.word() + " = " + word.translation());
        askNextWord(chatId);
    }

    private void handleAnswer(long chatId, Message message, String answer) throws SQLException {
        Database.QuizWord word = current.get(chatId);
        if (word == null) {
            send(chatId, "Send /quiz to get a word (or /help).");
            return;
        }
        boolean correct = AnswerChecker.isCorrect(answer, word.translation());
        db.recordAnswer(chatId, word.key(), correct ? Database.Result.CORRECT : Database.Result.WRONG);
        react(chatId, message.getMessageId(), correct ? "👍" : "👎");
        if (correct) {
            send(chatId, "✅ Correct! " + word.word() + " = " + word.translation());
        } else {
            send(chatId, "❌ Not quite. " + word.word() + " = " + word.translation());
        }
        askNextWord(chatId);
    }

    // --------------------------------------------------------------- stats

    private String formatUserStats(long chatId) throws SQLException {
        Database.UserStats s = db.userStats(chatId);
        int left = Math.max(0, limits.maxNewWordsPerDay() - s.addedLast24h());
        StringBuilder out = new StringBuilder("📊 Your stats\n\n");
        out.append("📚 Words: ").append(s.activeWords()).append(" in your dictionary\n");
        out.append("   +").append(s.addedLast24h()).append(" new in the last 24 h (")
                .append(left).append(" more allowed)\n\n");
        out.append("Today:    ").append(counts(s.today())).append('\n');
        out.append("All time: ").append(counts(s.allTime())).append('\n');
        out.append("🔥 Streak: ").append(s.streakDays()).append(s.streakDays() == 1 ? " day" : " days").append('\n');
        if (!s.hardest().isEmpty()) {
            out.append("\n😬 Hardest words:\n");
            for (Database.HardWord w : s.hardest()) {
                out.append("• ").append(w.word()).append(" — ").append(w.correct()).append('/')
                        .append(w.correct() + w.wrong()).append(" right\n");
            }
        }
        return out.toString().strip();
    }

    private static String counts(Database.Counts c) {
        if (c.total() == 0) {
            return "no answers yet";
        }
        return "✅ " + c.correct() + "  ❌ " + c.wrong() + "  ⏭ " + c.skipped() + "  (" + c.percentRight() + "% right)";
    }

    private String formatAdminReport() throws SQLException {
        Database.AdminReport r = db.adminReport();
        StringBuilder out = new StringBuilder("🛠 Bot report (last 24 h)\n\n");
        out.append("👥 Users: ").append(r.users()).append(" total, ").append(r.newUsers24h()).append(" new, ")
                .append(r.activeUsers24h()).append(" active\n");
        out.append("📥 Imports: ").append(r.imports24h()).append(" (").append(r.wordsReceived24h())
                .append(" words received, ").append(r.wordsAdded24h()).append(" new words added)\n");
        out.append("📝 Answers: ").append(counts(r.answers24h())).append('\n');
        out.append("📚 Words stored: ").append(r.storedWords()).append('\n');
        if (!r.top7d().isEmpty()) {
            out.append("\n🏆 Most active (7 days):\n");
            for (Database.TopUser t : r.top7d()) {
                out.append("• ").append(t.user().label()).append(" — ").append(t.answers())
                        .append(t.answers() == 1 ? " answer, " : " answers, ")
                        .append(t.answers() == 0 ? 0 : Math.round(100f * t.correct() / t.answers())).append("% right\n");
            }
        }
        if (!r.recentImports().isEmpty()) {
            out.append("\n🕑 Recent imports:\n");
            for (Database.RecentImport i : r.recentImports()) {
                out.append("• ").append(time(i.at())).append(" ").append(i.user().label()).append(" via ")
                        .append(i.source()).append(": ").append(i.received()).append(" received, ")
                        .append(i.added()).append(" new");
                if (i.deferred() > 0) {
                    out.append(", ").append(i.deferred()).append(" over limit");
                }
                if (i.translator() != null) {
                    out.append(" [").append(i.translator()).append(']');
                }
                out.append('\n');
            }
        }
        return out.toString().strip();
    }

    private String time(long epochSeconds) {
        return timeFormat.format(Instant.ofEpochSecond(epochSeconds));
    }

    // ------------------------------------------------------------- helpers

    private void react(long chatId, int messageId, String emoji) {
        try {
            telegram.execute(SetMessageReaction.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .reactionTypes(List.of(ReactionTypeEmoji.builder().emoji(emoji).build()))
                    .build());
        } catch (TelegramApiException e) {
            LOG.warn("Could not set reaction: {}", e.getMessage());
        }
    }

    private void send(long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            telegram.execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            LOG.warn("Could not send message to {}: {}", chatId, e.getMessage());
        }
    }
}
