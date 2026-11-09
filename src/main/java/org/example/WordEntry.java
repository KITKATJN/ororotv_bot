package org.example;

/**
 * One dictionary entry from ororo.tv.
 *
 * @param word        the English word / phrase
 * @param translation raw translation text; may hold several variants ("бежать, управлять"); may be empty
 * @param context     the subtitle line the word was saved from, or null
 */
public record WordEntry(String word, String translation, String context) {

    public boolean hasTranslation() {
        return translation != null && !translation.isBlank();
    }
}
