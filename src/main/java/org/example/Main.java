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
 *   <li>{@code MYMEMORY_EMAIL} - contact e-mail sent to MyMemory: raises its free limit to ~50,000 characters a day</li>
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
        // -DTELEGRAM_BOT_TOKEN locally (IntelliJ VM options), TELEGRAM_BOT_TOKEN variable on Railway
        String token = args.length > 0 ? args[0] : config("TELEGRAM_BOT_TOKEN", null);
        if (token == null || token.isBlank()) {
            System.err.println("Set TELEGRAM_BOT_TOKEN (token from @BotFather) as -D option or environment variable.");
            System.exit(1);
        }
        String dataDir = config("BOT_DATA_DIR", "data");
        String webAppUrl = config("WEBAPP_URL", null);
        // Railway (and most hosts) tell the app which port to listen on via PORT
        int webAppPort = Integer.parseInt(config("WEBAPP_PORT", config("PORT", "8080")));

        String anthropicKey = config("ANTHROPIC_API_KEY", null);
        String defaultLang = config("TRANSLATION_LANG", "ru");

        // Words without a translation are translated by the first translator that manages it.
        List<Translator> translators = new ArrayList<>();
        if (anthropicKey != null) {
            translators.add(new ClaudeTranslator(anthropicKey));
        }
        String myMemoryEmail = config("MYMEMORY_EMAIL", null);
        translators.add(new MyMemoryTranslator(myMemoryEmail));
        if (myMemoryEmail == null) {
            System.out.println("MyMemory: anonymous (~5,000 characters/day). Set MYMEMORY_EMAIL for ~50,000.");
        }
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
                limits, adminIds, new ExampleFinder(translators));

        if (webAppUrl != null && !webAppUrl.startsWith("https://")) {
            System.err.println("WEBAPP_URL must start with https:// (Telegram requires it).");
            System.exit(1);
        }
        // The HTTP server always runs: it also answers the hosting platform's health check (/health).
        WebAppServer webApp = new WebAppServer(webAppPort, token, bot);
        webApp.start();
        if (webAppUrl != null) {
            System.out.println("Mini App served on port " + webAppPort + " -> " + webAppUrl);
        } else {
            System.out.println("""
                    *** WEBAPP_URL is not set, so the "Connect ororo.tv" Mini App is OFF. ***
                    Only the dictionary-file import is offered. Set WEBAPP_URL to the public https:// address
                    that forwards to port""" + " " + webAppPort + " (Railway domain, or a cloudflared tunnel locally).");
        }
        bot.installMenuButton();

        TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
        try {
            app.registerBot(token, bot);
        } catch (Exception e) {
            // Exit instead of running half-alive (web server up, bot dead): the host then reports a failed deploy.
            System.err.println("Could not connect to Telegram (check TELEGRAM_BOT_TOKEN): " + e.getMessage());
            webApp.stop();
            db.close();
            System.exit(1);
        }

        // Railway stops the old container with SIGTERM on every redeploy: finish work and close the database cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            try {
                app.close();
            } catch (Exception e) {
                System.err.println("Stopping Telegram polling failed: " + e.getMessage());
            }
            webApp.stop();
            bot.shutdown();
            try {
                db.close();
            } catch (Exception e) {
                System.err.println("Closing the database failed: " + e.getMessage());
            }
        }, "shutdown"));

        System.out.println("Bot started. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
