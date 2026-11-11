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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds a short example sentence for a word, with its translation. Used for words that have no subtitle line,
 * e.g. ones saved from ororo's translator page. Sources, best first:
 * <ol>
 *   <li>Tatoeba (https://tatoeba.org, CC BY 2.0 FR) sentence that already has a human translation;</li>
 *   <li>any Tatoeba sentence with the word;</li>
 *   <li>a usage example from Wiktionary via the Free Dictionary API (https://dictionaryapi.dev, CC BY-SA).</li>
 * </ol>
 * Sentences without a human translation are machine-translated by the configured translators.
 */
public class ExampleFinder {

    /** An English sentence and its translation into the learner's language (may be null). */
    public record Example(String sentence, String translation) {
    }

    /** Tatoeba uses 3-letter language codes. */
    private static final Map<String, String> TATOEBA_LANG = Map.of(
            "ru", "rus", "uk", "ukr", "es", "spa", "it", "ita", "pl", "pol",
            "pt", "por", "tr", "tur", "de", "deu", "fr", "fra");
    private static final int MAX_LENGTH = 140; // longer sentences don't fit a quiz message well

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Translator> translators;

    /** @param translators used to translate sentences that have no human translation (may be empty) */
    public ExampleFinder(List<Translator> translators) {
        this.translators = translators;
    }

    /** @return an example, or null when no source has one */
    public Example find(String word, String lang) throws Exception {
        String target = TATOEBA_LANG.getOrDefault(lang, lang);
        // Searches also match other forms ("lashes" for "lashing"), often in another sense: exact word only.
        Pattern exact = Pattern.compile("(?i)(?<!\\p{L})" + Pattern.quote(word) + "(?!\\p{L})");

        Example withTranslation = tatoeba(word, exact, target, true);
        if (withTranslation != null) {
            return withTranslation;
        }
        String sentence = tatoebaSentence(word, exact);
        if (sentence == null) {
            sentence = dictionaryExample(word, exact);
        }
        return sentence == null ? null : new Example(sentence, translate(sentence, lang));
    }

    private Example tatoeba(String word, Pattern exact, String target, boolean needTranslation) throws Exception {
        String url = "https://api.tatoeba.org/unstable/sentences?lang=eng&sort=relevance&limit=20"
                + "&q=" + URLEncoder.encode(word, StandardCharsets.UTF_8)
                + (needTranslation ? "&" + URLEncoder.encode("trans:lang", StandardCharsets.UTF_8) + "=" + target : "");
        JsonNode root = get(url);
        Example best = null;
        for (JsonNode s : root == null ? json.createArrayNode() : root.path("data")) {
            String sentence = s.path("text").asText("");
            if (sentence.isBlank() || sentence.length() > MAX_LENGTH || !exact.matcher(sentence).find()) {
                continue;
            }
            String translation = null;
            for (JsonNode t : s.path("translations")) {
                if (t.path("lang").asText().equals(target)) {
                    translation = t.path("text").asText(null);
                    break;
                }
            }
            if (needTranslation && translation == null) {
                continue;
            }
            if (best == null || sentence.length() < best.sentence().length()) {
                best = new Example(sentence, translation); // the shortest is usually the clearest
            }
        }
        return best;
    }

    private String tatoebaSentence(String word, Pattern exact) throws Exception {
        Example e = tatoeba(word, exact, null, false);
        return e == null ? null : e.sentence();
    }

    /** A usage example from the Free Dictionary API (Wiktionary data); 404 when the word isn't there. */
    private String dictionaryExample(String word, Pattern exact) throws Exception {
        JsonNode root = get("https://api.dictionaryapi.dev/api/v2/entries/en/"
                + URLEncoder.encode(word, StandardCharsets.UTF_8).replace("+", "%20"));
        if (root == null || !root.isArray()) {
            return null;
        }
        String best = null;
        for (JsonNode entry : root) {
            for (JsonNode meaning : entry.path("meanings")) {
                for (JsonNode def : meaning.path("definitions")) {
                    String example = def.path("example").asText("").strip();
                    if (!example.isEmpty() && example.length() <= MAX_LENGTH && exact.matcher(example).find()
                            && (best == null || example.length() < best.length())) {
                        best = example;
                    }
                }
            }
        }
        return best;
    }

    /** @return the parsed body, or null on 404 */
    private JsonNode get(String url) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(URI.create(url).getHost() + " answered HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    /** Machine translation of the sentence; null if no translator manages it (the sentence is still shown). */
    private String translate(String sentence, String lang) {
        for (Translator t : translators) {
            try {
                String result = t.translateSentence(sentence, lang);
                if (result != null && !result.isBlank()) {
                    return result.strip();
                }
            } catch (Exception e) {
                // next translator
            }
        }
        return null;
    }
}
