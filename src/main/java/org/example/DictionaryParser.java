package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses ororo.tv dictionary data. Two sources are supported:
 * <ul>
 *   <li>the JSON API, {@code GET https://front.ororo.tv/api/v2/dictionary}:
 *       {@code {"dictionary":[{"word":"exhausting","context":"You're exhausting.","string":{...},...}]}}</li>
 *   <li>the website export, {@code /en/users/export_dictionary}: CSV / delimited text with the word in the
 *       first column and the translation in the second</li>
 * </ul>
 * Entries without a translation are kept (with an empty translation) so the caller can report them.
 */
public final class DictionaryParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> HEADER_WORDS =
            Set.of("word", "words", "term", "english", "original", "text", "слово");
    private static final List<String> WORD_KEYS = List.of("word", "text", "original", "term", "en", "source");
    private static final List<String> TRANSLATION_KEYS =
            List.of("translation", "translations", "string", "trans", "ru", "meaning", "target");
    private static final List<String> CONTEXT_KEYS = List.of("context", "example", "sentence");

    private DictionaryParser() {
    }

    public static List<WordEntry> parse(String body) throws IOException {
        String text = body == null ? "" : body.strip();
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1).strip();
        }
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.startsWith("[") || text.startsWith("{")) {
            return parseJson(text);
        }
        return parseDelimited(text);
    }

    /** The learner's translation language from the API data ({@code "lang":"ru"}), or the fallback. */
    public static String detectLanguage(String body, String fallback) {
        try {
            String text = body == null ? "" : body.strip();
            if (text.startsWith("{") || text.startsWith("[")) {
                JsonNode found = JSON.readTree(text).findValue("lang");
                if (found != null && found.isTextual() && found.asText().matches("[a-z]{2,3}")) {
                    return found.asText();
                }
            }
        } catch (IOException ignored) {
            // not JSON: fall through
        }
        return fallback;
    }

    // ---------------------------------------------------------------- JSON

    private static List<WordEntry> parseJson(String text) throws IOException {
        JsonNode root = JSON.readTree(text);
        JsonNode items = root;
        if (root.isObject()) {
            // {"dictionary":[...]} wrapper, otherwise treat the object as a word->translation map
            JsonNode wrapped = root.has("dictionary") && root.get("dictionary").isArray() ? root.get("dictionary") : null;
            if (wrapped == null) {
                for (JsonNode child : root) {
                    if (child.isArray()) {
                        wrapped = child;
                        break;
                    }
                }
            }
            if (wrapped != null) {
                items = wrapped;
            } else {
                List<WordEntry> out = new ArrayList<>();
                root.fields().forEachRemaining(e -> add(out, e.getKey(), flatten(e.getValue()), null));
                return out;
            }
        }
        List<WordEntry> out = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.isObject()) {
                add(out, firstText(item, WORD_KEYS), firstText(item, TRANSLATION_KEYS), firstText(item, CONTEXT_KEYS));
            } else if (item.isArray() && item.size() >= 2) {
                add(out, item.get(0).asText(), flatten(item.get(1)), null);
            }
        }
        return out;
    }

    private static String firstText(JsonNode obj, List<String> keys) {
        for (String key : keys) {
            JsonNode v = obj.get(key);
            if (v != null && !v.isNull()) {
                String s = flatten(v);
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    /** Turns a string / array / object (e.g. {"ru":"бежать"} or {"ru":["a","b"]}) into "a, b". */
    private static String flatten(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray() || node.isObject()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode n : node) {
                String s = flatten(n);
                if (!s.isBlank() && !parts.contains(s)) {
                    parts.add(s);
                }
            }
            return String.join(", ", parts);
        }
        return node.isTextual() ? node.asText() : "";
    }

    // ----------------------------------------------------------- delimited

    private static List<WordEntry> parseDelimited(String text) {
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        char delimiter = detectDelimiter(lines.get(0));
        List<WordEntry> out = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            List<String> cells = splitLine(line, delimiter);
            if (first) {
                first = false;
                if (!cells.isEmpty() && HEADER_WORDS.contains(cells.get(0).strip().toLowerCase())) {
                    continue;
                }
            }
            if (cells.size() >= 2) {
                add(out, cells.get(0), cells.get(1), cells.size() >= 3 ? cells.get(2) : null);
            }
        }
        return out;
    }

    private static char detectDelimiter(String line) {
        if (line.indexOf('\t') >= 0) {
            return '\t';
        }
        if (line.indexOf(';') >= 0) {
            return ';';
        }
        return ',';
    }

    /** Minimal CSV-style splitter: honours double quotes and "" escapes. */
    static List<String> splitLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().strip());
        return cells;
    }

    /**
     * The subtitle line, or null when there is none. Words saved from ororo's translator page come with
     * a context like "English => Russian", which is not a sentence and is dropped.
     */
    public static String cleanContext(String context) {
        if (context == null || context.isBlank()
                || context.strip().matches("(?i)[\\p{L} ]{2,30}\\s*=>\\s*[\\p{L} ]{2,30}")) {
            return null;
        }
        return context.strip();
    }

    private static void add(List<WordEntry> out, String word, String translation, String context) {
        if (word == null || word.isBlank()) {
            return;
        }
        String ctx = cleanContext(context);
        out.add(new WordEntry(word.strip(), translation == null ? "" : translation.strip(), ctx));
    }
}
