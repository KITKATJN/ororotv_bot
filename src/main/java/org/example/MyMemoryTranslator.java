package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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

    /** Quota used up or service down: stop asking for the rest of this batch. */
    private static class UnavailableException extends IOException {
        UnavailableException(String message) {
            super(message);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String email; // sent as "de": raises the daily limit from ~5,000 to ~50,000 characters

    /** @param email contact address MyMemory can reach in case of problems, or null (anonymous, lower limit) */
    public MyMemoryTranslator(String email) {
        this.email = email == null || email.isBlank() ? null : email.strip();
    }

    @Override
    public String name() {
        return "MyMemory";
    }

    @Override
    public Map<String, String> translate(List<WordEntry> words, String targetLang) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (WordEntry w : words) {
            List<String> variants;
            try {
                variants = lookup(w.word(), "en|" + targetLang);
            } catch (UnavailableException e) {
                System.err.println("MyMemory stopped at '" + w.word() + "': " + e.getMessage());
                break; // the rest stay untranslated
            }
            if (!variants.isEmpty()) {
                out.put(w.word(), String.join(", ", variants));
            }
        }
        return out;
    }

    @Override
    public String toEnglish(String text, String fromLang) throws Exception {
        List<String> variants = lookup(text, fromLang + "|en");
        return variants.isEmpty() ? null : String.join(", ", variants);
    }

    @Override
    public String translateSentence(String sentence, String targetLang) throws Exception {
        String result = request(sentence, "en|" + targetLang).path("responseData").path("translatedText").asText("");
        // MyMemory returns the input unchanged (or in capitals) when it has nothing
        return result.isBlank() || result.contains("&#") || result.equalsIgnoreCase(sentence) ? null : result.strip();
    }

    /** Main translation plus up to two high-quality alternatives, lower-cased, without the source text. */
    private List<String> lookup(String text, String langPair) throws Exception {
        JsonNode root = request(text, langPair);
        List<String> variants = new ArrayList<>();
        addVariant(variants, text, root.path("responseData").path("translatedText").asText(""));
        for (JsonNode match : root.path("matches")) {
            // Only alternatives for exactly this text: matches for longer phrases ("bored stiff" -> "до смерти")
            // would teach the wrong meaning.
            boolean sameText = match.path("segment").asText("").strip().equalsIgnoreCase(text.strip());
            if (sameText && match.path("quality").asInt(0) >= MIN_QUALITY) {
                addVariant(variants, text, match.path("translation").asText(""));
            }
        }
        return variants.subList(0, Math.min(3, variants.size()));
    }

    private JsonNode request(String text, String langPair) throws Exception {
        String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode(langPair, StandardCharsets.UTF_8)
                + (email == null ? "" : "&de=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = json.readTree(response.body());
        if (response.statusCode() != 200 || root.path("quotaFinished").asBoolean(false)
                || root.path("responseStatus").asInt(200) != 200) {
            throw new UnavailableException(root.path("responseDetails").asText("HTTP " + response.statusCode()));
        }
        return root;
    }

    /**
     * Keeps short dictionary-like translations only. MyMemory's translation memory also returns whole
     * subtitle sentences, HTML entities and stray punctuation ("minor:"), which would confuse learners.
     */
    private static void addVariant(List<String> variants, String source, String candidate) {
        String c = candidate.strip().replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "").toLowerCase();
        boolean junk = c.isEmpty() || c.contains("&#") || c.contains("\n") || c.length() > 40
                || c.split("\\s+").length > 4 || c.matches(".*[.!?;].*") || c.equalsIgnoreCase(source.strip());
        if (!junk && !variants.contains(c)) {
            variants.add(c);
        }
    }
}
