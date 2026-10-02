package io.github.yagipass.ajmx.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.errorprone.annotations.Var;

public final class Json {
    private static final int MAX_DEPTH = 512;

    private Json() {
    }

    @FunctionalInterface
    private interface Sink {
        void append(char c);

        default void text(String s) {
            for (int i = 0; i < s.length(); i++) {
                append(s.charAt(i));
            }
        }
    }

    private static final class Utf8Counter implements Sink {
        private long bytes;

        @Override
        public void append(char c) {
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
            } else if (!Character.isLowSurrogate(c)) {
                bytes += 3;
            }
        }
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(c -> sb.append(c), value);
        return sb.toString();
    }

    public static long size(Object value) {
        Utf8Counter counter = new Utf8Counter();
        writeValue(counter, value);
        return counter.bytes;
    }

    private static void writeValue(Sink sink, Object value) {
        if (value == null) {
            sink.text("null");
        } else if (value instanceof String s) {
            writeString(sink, s);
        } else if (value instanceof Boolean b) {
            sink.text(b.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d)) {
                sink.text(value.toString());
            } else {
                writeString(sink, value.toString());
            }
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            sink.text(value.toString());
        } else if (value instanceof BigDecimal bd) {
            sink.text(bd.toString());
        } else if (value instanceof Map<?, ?> map) {
            sink.append('{');
            @Var boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sink.append(',');
                }
                first = false;
                writeString(sink, (String) e.getKey());
                sink.append(':');
                writeValue(sink, e.getValue());
            }
            sink.append('}');
        } else if (value instanceof List<?> list) {
            sink.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sink.append(',');
                }
                writeValue(sink, list.get(i));
            }
            sink.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON model value: " + value.getClass().getName());
        }
    }

    private static void writeString(Sink sink, String s) {
        sink.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sink.text("\\\"");
                case '\\' -> sink.text("\\\\");
                case '\n' -> sink.text("\\n");
                case '\r' -> sink.text("\\r");
                case '\t' -> sink.text("\\t");
                case '\b' -> sink.text("\\b");
                case '\f' -> sink.text("\\f");
                default -> {
                    boolean loneSurrogate = Character.isSurrogate(c) && !isPairedSurrogate(s, i);
                    if (c < 0x20 || loneSurrogate) {
                        sink.text(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sink.append(c);
                    }
                }
            }
        }
        sink.append('"');
    }

    private static boolean isPairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    public static Object parse(String text) {
        return new Parser(text).document();
    }

    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        private Parser(String s) {
            this.s = s;
        }

        private Object document() {
            skipWhitespace();
            Object value = value(0);
            skipWhitespace();
            if (pos != s.length()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting too deep");
            }
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected object key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object v = value(depth + 1);
                if (map.containsKey(key)) {
                    throw error("Duplicate key \"" + key + "\"");
                }
                map.put(key, v);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("Unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("Truncated unicode escape");
                        }
                        try {
                            sb.append((char) HexFormat.fromHexDigits(s, pos, pos + 4));
                        } catch (IllegalArgumentException _) {
                            throw error("Invalid unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("Invalid escape '\\" + e + "'");
                }
            }
        }

        private BigDecimal number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                while (isDigit(peek())) {
                    pos++;
                }
            } else {
                throw error("Invalid number");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw error("Invalid number");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("Invalid number");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException _) {
                pos = start;
                throw error("Number out of range");
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("Invalid literal");
            }
            pos += word.length();
            return value;
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    break;
                }
                pos++;
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("Expected '" + c + "'");
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private ParseException error(String message) {
            return new ParseException(message + " at offset " + pos);
        }
    }
}
