package org.example;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context-aware translations with Claude: each word is sent together with the subtitle line it was
 * saved from, so "run" in "Run the company" becomes "управлять", not "бежать". Several common
 * translations are returned, which makes answer checking more forgiving.
 *
 * <p>Uses {@code ANTHROPIC_API_KEY}. Server-side refusal fallback is enabled, so a request a safety
 * classifier declines is retried on another model automatically.
 */
public class ClaudeTranslator implements Translator {

    private static final String MODEL = "claude-opus-5-5";
    private static final int BATCH_SIZE = 100;

    private final ObjectMapper json = new ObjectMapper();
    private final AnthropicClient client;

    public ClaudeTranslator(String apiKey) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public String name() {
        return "Claude";
    }

    @Override
    public Map<String, String> translate(List<WordEntry> words, String targetLang) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < words.size(); i += BATCH_SIZE) {
            out.putAll(translateBatch(words.subList(i, Math.min(words.size(), i + BATCH_SIZE)), targetLang));
        }
        return out;
    }

    private Map<String, String> translateBatch(List<WordEntry> batch, String targetLang) throws Exception {
        StringBuilder list = new StringBuilder();
        for (WordEntry w : batch) {
            list.append("- ").append(w.word());
            if (w.context() != null) {
                list.append("  (subtitle: \"").append(w.context()).append("\")");
            }
            list.append('\n');
        }
        String prompt = """
                You are helping a learner of English review words they saved while watching TV shows.
                Translate each English word or phrase below into the language with ISO code "%s".
                Use the subtitle line to pick the sense that fits it, and give 1 to 4 common translations
                a learner might answer with, most fitting first, separated by ", ". Use dictionary forms.

                Reply with only a JSON object mapping each word exactly as written to its translations, e.g.
                {"exhausting": "утомительный, изнурительный, выматывающий"}

                Words:
                %s""".formatted(targetLang, list);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(prompt)
                .build();
        BetaMessage response = client.beta().messages().create(params);

        if (response.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false)) {
            System.err.println("Claude declined this batch; leaving it to the next translator.");
            return Map.of();
        }
        StringBuilder text = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        return parseJsonObject(text.toString());
    }

    private Map<String, String> parseJsonObject(String text) throws Exception {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        Map<String, String> out = new LinkedHashMap<>();
        if (start < 0 || end <= start) {
            return out;
        }
        JsonNode node = json.readTree(text.substring(start, end + 1));
        node.fields().forEachRemaining(e -> {
            String value = e.getValue().isArray()
                    ? String.join(", ", json.convertValue(e.getValue(), String[].class))
                    : e.getValue().asText("");
            if (!value.isBlank()) {
                out.put(e.getKey(), value.strip());
            }
        });
        return out;
    }
}
