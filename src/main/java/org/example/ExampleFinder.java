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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds a short example sentence for a word, with its translation, from Tatoeba
 * (https://tatoeba.org, sentences licensed CC BY 2.0 FR). Used for words that have no subtitle line,
 * e.g. ones saved from ororo's translator page.
 */
public class ExampleFinder {

    /** An English sentence and its translation into the learner's language (may be null). */
    public record Example(String sentence, String translation) {
    }

    /** Tatoeba uses 3-letter language codes. */
    private static final Map<String, String> TATOEBA_LANG = Map.of(
            "ru", "rus", "uk", "ukr", "es", "spa", "it", "ita", "pl", "pol",
            "pt", "por", "tr", "tur", "de", "deu", "fr", "fra");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** @return an example, or null when Tatoeba has none (or can't be reached) */
    public Example find(String word, String lang) throws Exception {
        String target = TATOEBA_LANG.getOrDefault(lang, lang);
        String url = "https://api.tatoeba.org/unstable/sentences?lang=eng&sort=relevance&limit=10"
                + "&q=" + URLEncoder.encode(word, StandardCharsets.UTF_8)
                + "&" + URLEncoder.encode("trans:lang", StandardCharsets.UTF_8) + "=" + target;
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tatoeba answered HTTP " + response.statusCode());
        }
        // Tatoeba's search also matches other forms ("lashes" for "lashing"), often in another sense:
        // only sentences with the exact word are used.
        Pattern exact = Pattern.compile("(?i)(?<!\\p{L})" + Pattern.quote(word) + "(?!\\p{L})");
        for (JsonNode s : json.readTree(response.body()).path("data")) {
            String sentence = s.path("text").asText("");
            if (sentence.isBlank() || sentence.length() > 160) {
                continue;
            }
            String translation = null;
            for (JsonNode t : s.path("translations")) {
                if (target.equals(t.path("lang").asText())) {
                    translation = t.path("text").asText(null);
                    break;
                }
            }
            if (exact.matcher(sentence).find()) {
                return new Example(sentence, translation);
            }
        }
        return null;
    }
}
