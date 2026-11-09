package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Validates the {@code initData} string a Telegram Mini App receives, so the server knows which
 * Telegram user really sent a request. See
 * https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 */
public final class TelegramInitData {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TelegramInitData() {
    }

    /** @return the Telegram user id if {@code initData} is genuine and fresh, otherwise empty. */
    public static OptionalLong validateUserId(String initData, String botToken, Duration maxAge) {
        if (initData == null || initData.isBlank()) {
            return OptionalLong.empty();
        }
        Map<String, String> fields = new TreeMap<>(); // sorted by key, as the algorithm requires
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        String hash = fields.remove("hash");
        if (hash == null) {
            return OptionalLong.empty();
        }
        StringBuilder dataCheck = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!dataCheck.isEmpty()) {
                dataCheck.append('\n');
            }
            dataCheck.append(e.getKey()).append('=').append(e.getValue());
        }
        try {
            byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
            byte[] expected = hmac(secret, dataCheck.toString());
            byte[] given = HexFormat.of().parseHex(hash);
            if (!MessageDigest.isEqual(expected, given)) {
                return OptionalLong.empty();
            }
            long authDate = Long.parseLong(fields.getOrDefault("auth_date", "0"));
            if (Instant.ofEpochSecond(authDate).isBefore(Instant.now().minus(maxAge))) {
                return OptionalLong.empty();
            }
            JsonNode user = JSON.readTree(fields.getOrDefault("user", "{}"));
            return user.has("id") ? OptionalLong.of(user.get("id").asLong()) : OptionalLong.empty();
        } catch (IllegalArgumentException | GeneralSecurityException | java.io.IOException e) {
            return OptionalLong.empty();
        }
    }

    static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
