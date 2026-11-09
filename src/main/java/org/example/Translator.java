package org.example;

import java.util.List;
import java.util.Map;

/** Fills in translations for dictionary words that arrived without one. */
public interface Translator {

    /** Short name for logs and chat messages. */
    String name();

    /**
     * @param words      words to translate (their {@code context} may help pick the right sense)
     * @param targetLang ISO code of the learner's language, e.g. "ru"
     * @return word -> translation ("a, b, c" when there are several good ones); missing keys = not translated
     */
    Map<String, String> translate(List<WordEntry> words, String targetLang) throws Exception;
}
