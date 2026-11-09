package org.example;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point. Configuration:
 * <ul>
 *   <li>{@code TELEGRAM_BOT_TOKEN} (or first program argument) - token from @BotFather, required</li>
 *   <li>{@code BOT_DATA_DIR} - folder of the SQLite database {@code bot.db}, default {@code data}</li>
 *   <li>{@code MAX_NEW_WORDS_PER_DAY} - new words a user may add per 24 hours, default 100</li>
 *   <li>{@code MAX_IMPORTS_PER_DAY} - imports (Mini App or file) a user may do per 24 hours, default 10</li>
 *   <li>{@code ADMIN_USER_IDS} - comma-separated Telegram user IDs allowed to use /admin</li>
 *   <li>{@code WEBAPP_URL} - public https:// address of the Mini App (e.g. a cloudflared tunnel to
 *       {@code WEBAPP_PORT}); without it only the file import is offered</li>
 *   <li>{@code WEBAPP_PORT} - local port of the Mini App server, default 8080</li>
 *   <li>{@code ANTHROPIC_API_KEY} - optional; enables context-aware translations with Claude
 *       (otherwise the free MyMemory service is used)</li>
 *   <li>{@code TRANSLATION_LANG} - translation language when the data doesn't say, default {@code ru}</li>
 * </ul>
 * Settings are read as system properties ({@code -DNAME=value}) first, then environment variables.
 */
public class Main {
    private static String config(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public static void main(String[] args) throws Exception {
        String token = args.length > 0 ? args[0] : System.getProperty("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Set the TELEGRAM_BOT_TOKEN environment variable (token from @BotFather).");
            System.exit(1);
        }
        String dataDir = config("BOT_DATA_DIR", "data");
        String webAppUrl = config("WEBAPP_URL", null);
        int webAppPort = Integer.parseInt(config("WEBAPP_PORT", "8080"));

        String anthropicKey = config("ANTHROPIC_API_KEY", null);
        String defaultLang = config("TRANSLATION_LANG", "ru");

        // Words without a translation are translated by the first translator that manages it.
        List<Translator> translators = new ArrayList<>();
        if (anthropicKey != null) {
            translators.add(new ClaudeTranslator(anthropicKey));
        }
        translators.add(new MyMemoryTranslator());
        System.out.println("Translations: " + (anthropicKey != null
                ? "Claude, falling back to MyMemory"
                : "MyMemory (free). Set ANTHROPIC_API_KEY for better, context-aware translations."));

        QuizBot.Limits limits = new QuizBot.Limits(
                Integer.parseInt(config("MAX_NEW_WORDS_PER_DAY", "100")),
                Integer.parseInt(config("MAX_IMPORTS_PER_DAY", "10")));
        Set<Long> adminIds = new HashSet<>();
        for (String id : config("ADMIN_USER_IDS", "").split("[,;\\s]+")) {
            if (!id.isBlank()) {
                adminIds.add(Long.parseLong(id.strip()));
            }
        }

        Database db = new Database(Path.of(dataDir, "bot.db"));
        int migrated = db.migrateFromJson(Path.of(dataDir, "state.json"));
        if (migrated > 0) {
            System.out.println("Moved " + migrated + " words from state.json into the database.");
        }
        System.out.println("Limits per user and 24 h: " + limits.maxNewWordsPerDay() + " new words, "
                + limits.maxImportsPerDay() + " imports. Admins: "
                + (adminIds.isEmpty() ? "none (set ADMIN_USER_IDS to use /admin)" : adminIds));

        QuizBot bot = new QuizBot(new OkHttpTelegramClient(token), db, webAppUrl, translators, defaultLang,
                limits, adminIds);

        WebAppServer webApp = null;
        if (webAppUrl != null) {
            if (!webAppUrl.startsWith("https://")) {
                System.err.println("WEBAPP_URL must start with https:// (Telegram requires it).");
                System.exit(1);
            }
            webApp = new WebAppServer(webAppPort, token, bot);
            webApp.start();
            System.out.println("Mini App served on http://localhost:" + webAppPort + " -> " + webAppUrl);
        } else {
            System.out.println("""
                    *** WEBAPP_URL is not set, so the "Connect ororo.tv" Mini App is OFF. ***
                    Only the dictionary-file import is offered. To turn the Mini App on, run a tunnel
                    (e.g. `cloudflared tunnel --url http://localhost:8080`) and add
                    -DWEBAPP_URL=https://<tunnel address> to the VM options.""");
        }
        bot.installMenuButton();

        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, bot);
            System.out.println("Bot started. Press Ctrl+C to stop.");
            Thread.currentThread().join();
        } finally {
            if (webApp != null) {
                webApp.stop();
            }
            db.close();
        }
    }
}
