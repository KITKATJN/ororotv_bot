package org.example;

import java.util.List;
import java.util.Map;

/** Fills in translations for dictionary words that arrived without one, and explains wrong answers. */
public interface Translator {

    /** Short name for logs and chat messages. */
    String name();

    /**
     * @param words      words to translate (their {@code context} may help pick the right sense)
     * @param targetLang ISO code of the learner's language, e.g. "ru"
     * @return word -> translation ("a, b, c" when there are several good ones); missing keys = not translated
     */
    Map<String, String> translate(List<WordEntry> words, String targetLang) throws Exception;

    /**
     * Translates a learner's answer back into English, e.g. "Незначительные" -> "insignificant, minor".
     *
     * @return "a, b" or null when there is no translation
     */
    String toEnglish(String text, String fromLang) throws Exception;
}
