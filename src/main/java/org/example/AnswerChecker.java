package org.example;

import java.util.ArrayList;
import java.util.List;

/** Decides whether a user's answer matches one of the translations of a word. */
public final class AnswerChecker {

    private static final String SEPARATORS = "[,;/|\\n]";

    private AnswerChecker() {
    }

    public static boolean isCorrect(String answer, String translation) {
        List<String> accepted = variants(translation);
        for (String given : variants(answer)) {
            for (String variant : accepted) {
                if (given.equals(variant)) {
                    return true;
                }
                // tolerate a single typo in longer words
                if (variant.length() >= 5 && given.length() >= 5 && levenshtein(given, variant) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Splits "a, b; c (note)" into normalized alternatives. */
    static List<String> variants(String text) {
        List<String> out = new ArrayList<>();
        String noParens = text.replaceAll("\\(.*?\\)", " ");
        for (String part : noParens.split(SEPARATORS)) {
            String n = normalize(part);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    static String normalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().replace('ё', 'е').toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
