package io.researchrunner.document;

import java.util.ArrayList;
import java.util.List;

/**
 * A4-08: recursive character splitter aligned with Python's
 * {@code langchain_text_splitters.RecursiveCharacterTextSplitter}
 * (separators: paragraph, line, space, character).
 *
 * <p>Lengths are counted in Unicode code points so emoji/CJK are never cut in half. The merge
 * step keeps a bounded overlap (code points) between consecutive chunks.
 */
public final class TextSplitter {
    private static final List<String> SEPARATORS = List.of("\n\n", "\n", " ", "");

    private final int size;
    private final int overlap;

    public TextSplitter(int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("分块参数无效");
        this.size = size;
        this.overlap = overlap;
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> pieces = splitRecursive(text, 0);
        List<String> chunks = merge(pieces);
        return chunks.stream().map(String::strip).filter(chunk -> !chunk.isEmpty()).toList();
    }

    /** Splits into atomic pieces no longer than size (or into characters at the last separator). */
    private List<String> splitRecursive(String text, int separatorIndex) {
        if (length(text) <= size) return List.of(text);
        if (separatorIndex >= SEPARATORS.size()) return List.of(text);
        String separator = SEPARATORS.get(separatorIndex);
        List<String> pieces = new ArrayList<>();
        if (separator.isEmpty()) {
            text.codePoints().forEach(point -> pieces.add(new String(Character.toChars(point))));
            return pieces;
        }
        List<String> parts = splitKeepingSeparator(text, separator);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (length(part) <= size) result.add(part);
            else if (separatorIndex + 1 < SEPARATORS.size()) result.addAll(splitRecursive(part, separatorIndex + 1));
            else result.add(part);
        }
        return result;
    }

    private static List<String> splitKeepingSeparator(String text, String separator) {
        List<String> result = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int found = text.indexOf(separator, position);
            if (found < 0) {
                result.add(text.substring(position));
                break;
            }
            result.add(text.substring(position, found + separator.length()));
            position = found + separator.length();
        }
        return result;
    }

    private List<String> merge(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (length(current) > 0 && length(current) + length(piece) > size) {
                String chunk = current.toString().strip();
                if (!chunk.isEmpty()) chunks.add(chunk);
                current = new StringBuilder(tail(chunk, overlap));
            }
            current.append(piece);
        }
        String last = current.toString().strip();
        if (!last.isEmpty()) chunks.add(last);
        return chunks;
    }

    private static String tail(String value, int count) {
        if (count <= 0 || value.isEmpty()) return "";
        int points = value.codePointCount(0, value.length());
        if (points <= count) return value;
        int start = value.offsetByCodePoints(0, points - count);
        return value.substring(start);
    }

    private static int length(CharSequence value) {
        return Character.codePointCount(value, 0, value.length());
    }
}
