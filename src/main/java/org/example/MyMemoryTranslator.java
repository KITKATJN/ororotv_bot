package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free translator, no API key: https://mymemory.translated.net (about 5000 characters a day per IP
 * anonymously). Plain word-by-word machine translation, so it ignores the subtitle context.
 */
public class MyMemoryTranslator implements Translator {

    private static final int MIN_QUALITY = 70; // drops the crowd-sourced junk MyMemory mixes into "matches"

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String name() {
        return "MyMemory";
    }

    @Override
    public Map<String, String> translate(List<WordEntry> words, String targetLang) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (WordEntry w : words) {
            String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(w.word(), StandardCharsets.UTF_8)
                    + "&langpair=" + URLEncoder.encode("en|" + targetLang, StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(response.body());
            if (response.statusCode() != 200 || root.path("quotaFinished").asBoolean(false)
                    || root.path("responseStatus").asInt(200) != 200) {
                System.err.println("MyMemory stopped at '" + w.word() + "': " + root.path("responseDetails").asText());
                break; // quota or outage: the rest stay untranslated
            }
            List<String> variants = new ArrayList<>();
            addVariant(variants, w.word(), root.path("responseData").path("translatedText").asText(""));
            for (JsonNode match : root.path("matches")) {
                if (match.path("quality").asInt(0) >= MIN_QUALITY) {
                    addVariant(variants, w.word(), match.path("translation").asText(""));
                }
            }
            if (!variants.isEmpty()) {
                out.put(w.word(), String.join(", ", variants.subList(0, Math.min(3, variants.size()))));
            }
        }
        return out;
    }

    private static void addVariant(List<String> variants, String word, String candidate) {
        String c = candidate.strip();
        if (!c.isEmpty() && !c.equalsIgnoreCase(word) && c.length() <= 80
                && variants.stream().noneMatch(v -> v.equalsIgnoreCase(c))) {
            variants.add(c.toLowerCase());
        }
    }
}
