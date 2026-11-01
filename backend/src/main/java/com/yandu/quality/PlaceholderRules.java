package com.yandu.quality;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared named-placeholder syntax and comparison rules used by translation and release gates. */
public final class PlaceholderRules {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private PlaceholderRules() { }

    public static Comparison compare(String source, String translation) {
        var sourceTokens = parse(source);
        var translationTokens = parse(translation);
        return new Comparison(sourceTokens.supported(), translationTokens.supported(),
                sourceTokens.counts(), translationTokens.counts());
    }

    private static Parsed parse(String text) {
        var counts = new TreeMap<String, Integer>();
        var supported = text != null;
        for (var index = 0; text != null && index < text.length();) {
            var current = text.charAt(index);
            if (current == '{') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '{') {
                    index += 2;
                    continue;
                }
                var end = text.indexOf('}', index + 1);
                if (end < 0) {
                    supported = false;
                    index++;
                    continue;
                }
                var name = text.substring(index + 1, end);
                if (NAME.matcher(name).matches()) counts.merge(name, 1, Integer::sum);
                else supported = false;
                index = end + 1;
                continue;
            }
            if (current == '}') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '}') index += 2;
                else {
                    supported = false;
                    index++;
                }
                continue;
            }
            index++;
        }
        return new Parsed(supported, Map.copyOf(counts));
    }

    private record Parsed(boolean supported, Map<String, Integer> counts) { }

    public record Comparison(boolean sourceSupported, boolean translationSupported,
                             Map<String, Integer> source, Map<String, Integer> translation) { }
}
