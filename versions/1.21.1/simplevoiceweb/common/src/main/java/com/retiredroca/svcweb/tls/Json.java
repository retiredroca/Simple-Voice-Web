package com.retiredroca.svcweb.tls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and string escaper.
 *
 * <p>The mod deliberately adds no dependencies. Minecraft ships Gson, but its version is not part of
 * the mod API and has changed across versions; the ACME client only needs a few fields, so a small
 * parser keeps the common sources free of any third-party type.
 */
public final class Json {

    private final String source;
    private int index;

    private Json(String source) {
        this.source = source;
    }

    public static Object parse(String json) {
        Json parser = new Json(json);
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.index != json.length()) {
            throw new IllegalArgumentException("trailing content at offset " + parser.index);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static String string(String json, String key) {
        Object value = asObject(parse(json)).get(key);
        return value == null ? null : value.toString();
    }

    public static String string(Object json, String key) {
        Object value = asObject(json).get(key);
        return value == null ? null : value.toString();
    }

    /** A nested object as its raw JSON text, or {@code null}. */
    public static String object(String json, String key) {
        Object value = asObject(parse(json)).get(key);
        return value == null ? null : write(value);
    }

    /** The elements of an array as raw JSON text. */
    public static List<String> array(String json, String key) {
        Object value = asObject(parse(json)).get(key);
        return arrayOf(value);
    }

    /** The elements of a JSON array document as raw JSON text. */
    public static List<String> array(String json) {
        return arrayOf(parse(json));
    }

    private static List<String> arrayOf(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object element : (List<?>) value) {
            out.add(write(element));
        }
        return out;
    }

    /** The string elements of an array. */
    public static List<String> stringArray(String json) {
        Object value = parse(json);
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object element : (List<?>) value) {
            out.add(String.valueOf(element));
        }
        return out;
    }

    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String write(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":")
                        .append(write(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(write(list.get(i)));
            }
            return out.append(']').toString();
        }
        return value.toString();
    }

    private Object value() {
        skipWhitespace();
        if (index >= source.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = source.charAt(index);
        return switch (c) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"' -> stringValue();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> numberValue();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = stringValue();
            skipWhitespace();
            expect(':');
            out.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (index - 1));
            }
        }
    }

    private List<Object> arrayValue() {
        List<Object> out = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return out;
        }
        while (true) {
            out.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (index - 1));
            }
        }
    }

    private String stringValue() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                    index += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + escaped);
            }
        }
    }

    private Object numberValue() {
        int start = index;
        while (index < source.length() && "+-0123456789.eE".indexOf(source.charAt(index)) >= 0) {
            index++;
        }
        if (start == index) {
            throw new IllegalArgumentException("unexpected character at offset " + index);
        }
        String text = source.substring(start, index);
        if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }

    private Object literal(String text, Object value) {
        if (!source.startsWith(text, index)) {
            throw new IllegalArgumentException("expected '" + text + "' at offset " + index);
        }
        index += text.length();
        return value;
    }

    private void skipWhitespace() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
    }

    private char peek() {
        return index < source.length() ? source.charAt(index) : '\0';
    }

    private char next() {
        if (index >= source.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return source.charAt(index++);
    }

    private void expect(char expected) {
        char actual = next();
        if (actual != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' but found '" + actual
                    + "' at offset " + (index - 1));
        }
    }
}
