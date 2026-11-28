package org.adaptivefit.mobile;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ExerciseNameMatcher {
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Z}\\p{Pd}]+");
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final int MAX_ALIAS_LENGTH;

    static {
        add("레그 프레스", "leg press");
        add("벤치 프레스", "bench press");
        add("랫 풀다운", "lat pulldown");
        add("스쿼트", "squat");
        MAX_ALIAS_LENGTH = ALIASES.keySet().stream().mapToInt(String::length).max().getAsInt();
    }

    private ExerciseNameMatcher() {}

    private static void add(String key, String english) {
        ALIASES.put(normalize(key), key);
        ALIASES.put(normalize(english), key);
    }

    private static String normalize(String line) {
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SEPARATORS.matcher(normalized).replaceAll("");
    }

    static String match(List<List<String>> blocks, String[] catalog) {
        String candidate = null;
        for (List<String> block : blocks) {
            String[] lines = block.stream().map(ExerciseNameMatcher::normalize).toArray(String[]::new);
            for (int start = 0; start < lines.length; start++) {
                StringBuilder label = new StringBuilder();
                for (int end = start; end < lines.length; end++) {
                    if (lines[end].isEmpty()) break;
                    label.append(lines[end]);
                    int left = 0, right = label.length();
                    while (left < right && label.charAt(left) == '|') left++;
                    while (right > left && label.charAt(right - 1) == '|') right--;
                    if (left == right || right - left > MAX_ALIAS_LENGTH) break;
                    String key = ALIASES.get(label.substring(left, right));
                    if (key != null) {
                        if (candidate != null && !candidate.equals(key)) return null;
                        candidate = key;
                    }
                    // Joining another line here would move this border inside the name.
                    if (right < label.length()) break;
                }
            }
        }
        return candidate != null && Arrays.asList(catalog).contains(candidate) ? candidate : null;
    }
}
