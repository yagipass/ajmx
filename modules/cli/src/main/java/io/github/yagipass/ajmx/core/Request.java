package io.github.yagipass.ajmx.core;

import java.util.LinkedHashSet;
import java.util.List;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

public sealed interface Request {
    String DEFAULT_SEARCH_PATTERN = "*:*";

    Op op();

    record Ping() implements Request {
        @Override
        public Op op() {
            return Op.PING;
        }
    }

    record Search(ObjectName pattern) implements Request {
        @Override
        public Op op() {
            return Op.SEARCH;
        }
    }

    record Describe(ObjectName mbean) implements Request {
        @Override
        public Op op() {
            return Op.DESCRIBE;
        }
    }

    record Read(ObjectName mbean, List<String> attributes) implements Request {
        @Override
        public Op op() {
            return Op.READ;
        }
    }

    record Write(ObjectName mbean, String attribute, Object value) implements Request {
        @Override
        public Op op() {
            return Op.WRITE;
        }
    }

    record Invoke(ObjectName mbean, String operation, List<Object> args, List<String> signature) implements Request {
        @Override
        public Op op() {
            return Op.INVOKE;
        }
    }

    static Search search(String pattern) {
        return new Search(objectName(pattern != null ? pattern : DEFAULT_SEARCH_PATTERN, "pattern"));
    }

    static Describe describe(String mbean) {
        return new Describe(mbean(mbean));
    }

    static Read read(String mbean, List<String> attributes) {
        ObjectName name = mbean(mbean);
        List<String> distinct = List.copyOf(new LinkedHashSet<>(attributes));
        if (distinct.isEmpty()) {
            throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "At least one attribute is required")
                    .with("field", "attributes");
        }
        return new Read(name, distinct);
    }

    static Write write(String mbean, String attribute, Object value) {
        return new Write(mbean(mbean), attribute, value);
    }

    static Invoke invoke(String mbean, String operation, List<Object> args, List<String> signature) {
        return new Invoke(mbean(mbean), operation, args, signature);
    }

    private static ObjectName mbean(String text) {
        ObjectName name = objectName(text, "mbean");
        if (name.isPattern()) {
            throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "An ObjectName pattern is not allowed here")
                    .with("mbean", text);
        }
        return name;
    }

    private static ObjectName objectName(String text, String detailKey) {
        try {
            return new ObjectName(text);
        } catch (MalformedObjectNameException | NullPointerException e) {
            throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Invalid ObjectName", e).with(detailKey, text);
        }
    }
}
