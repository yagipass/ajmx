package io.github.yagipass.ajmx.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class RequestParser {
    private RequestParser() {
    }

    static Request parse(Map<String, Object> fields) {
        String token = string(fields, "op");
        Op op = Token.find(Op.values(), token).orElseThrow(() -> new AjmxException(ErrorCode.INVALID_ARGUMENT, "Unknown op")
                .with("op", token).with("allowed", Token.tokens(Op.values())));
        List<String> allowed = new ArrayList<>(List.of("id", "op"));
        allowed.addAll(op.fields());
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) {
                throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Unknown field in request")
                        .with("field", key).with("op", op.token()).with("allowed", allowed);
            }
        }
        return switch (op) {
            case PING -> new Request.Ping();
            case SEARCH -> Request.search(fields.containsKey("pattern") ? string(fields, "pattern") : null);
            case DESCRIBE -> Request.describe(string(fields, "mbean"));
            case READ -> Request.read(string(fields, "mbean"), strings(fields, "attributes"));
            case WRITE -> Request.write(string(fields, "mbean"), string(fields, "attribute"), value(fields, "value"));
            case INVOKE -> Request.invoke(string(fields, "mbean"), string(fields, "operation"),
                    fields.containsKey("args") ? list(fields, "args") : List.of(),
                    fields.containsKey("signature") ? strings(fields, "signature") : null);
        };
    }

    private static String string(Map<String, Object> fields, String key) {
        if (fields.get(key) instanceof String s) {
            return s;
        }
        throw fields.containsKey(key) ? wrongType(key, "string") : missing(key);
    }

    private static Object value(Map<String, Object> fields, String key) {
        if (!fields.containsKey(key)) {
            throw missing(key);
        }
        return fields.get(key);
    }

    private static List<Object> list(Map<String, Object> fields, String key) {
        if (fields.get(key) instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        throw fields.containsKey(key) ? wrongType(key, "array") : missing(key);
    }

    private static List<String> strings(Map<String, Object> fields, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(fields, key)) {
            if (!(o instanceof String s)) {
                throw wrongType(key, "array of strings");
            }
            out.add(s);
        }
        return out;
    }

    private static AjmxException missing(String key) {
        return new AjmxException(ErrorCode.INVALID_ARGUMENT, "Missing field in request").with("field", key);
    }

    private static AjmxException wrongType(String key, String expected) {
        return new AjmxException(ErrorCode.INVALID_ARGUMENT, "Wrong field type in request")
                .with("field", key).with("expected", expected);
    }
}
