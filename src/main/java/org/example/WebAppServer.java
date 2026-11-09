package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server for the Telegram Mini App.
 * <ul>
 *   <li>{@code GET /} serves the Mini App page (src/main/resources/webapp/index.html)</li>
 *   <li>{@code POST /api/import} receives {@code {"initData": "...", "data": "<raw dictionary JSON>"}}</li>
 * </ul>
 * The ororo.tv email and password never reach this server: the page calls the ororo.tv API directly
 * from the user's phone and only posts the resulting word list here.
 */
public class WebAppServer {

    private static final int MAX_BODY_BYTES = 6 * 1024 * 1024;
    private static final Duration INIT_DATA_MAX_AGE = Duration.ofHours(24);

    private final ObjectMapper json = new ObjectMapper();
    private final String botToken;
    private final QuizBot bot;
    private final HttpServer server;

    public WebAppServer(int port, String botToken, QuizBot bot) throws IOException {
        this.botToken = botToken;
        this.bot = bot;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handlePage);
        server.createContext("/api/import", this::handleImport);
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void handlePage(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!"GET".equals(ex.getRequestMethod()) || !(path.equals("/") || path.equals("/index.html"))) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream in = WebAppServer.class.getResourceAsStream("/webapp/index.html")) {
                if (in == null) {
                    send(ex, 500, "text/plain; charset=utf-8", "index.html missing".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        }
    }

    private void handleImport(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, false, "Use POST");
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                reply(ex, 413, false, "Dictionary is too big");
                return;
            }
            JsonNode request;
            try {
                request = json.readTree(body);
            } catch (IOException e) {
                reply(ex, 400, false, "Bad request");
                return;
            }
            OptionalLong userId = TelegramInitData.validateUserId(
                    request.path("initData").asText(""), botToken, INIT_DATA_MAX_AGE);
            if (userId.isEmpty()) {
                reply(ex, 401, false, "Please open this page from the bot in Telegram.");
                return;
            }
            // In a private chat the chat id equals the user id.
            QuizBot.ImportResult result = bot.importWords(userId.getAsLong(), request.path("data").asText(""));
            reply(ex, 200, result.ok(), result.message());
        } catch (Exception e) {
            e.printStackTrace();
            reply(ex, 500, false, "Server error: " + e.getMessage());
        }
    }

    private void reply(HttpExchange ex, int status, boolean ok, String message) throws IOException {
        byte[] out = json.writeValueAsBytes(Map.of("ok", ok, "message", message));
        send(ex, status, "application/json; charset=utf-8", out);
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
