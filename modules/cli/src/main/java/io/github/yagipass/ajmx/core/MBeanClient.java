package io.github.yagipass.ajmx.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMX;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ObjectName;

import io.github.yagipass.ajmx.codec.ValueDecoder;
import io.github.yagipass.ajmx.codec.ValueEncoder;
import io.github.yagipass.ajmx.connection.JmxSession;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.error.Execution;
import io.github.yagipass.ajmx.json.Json;

public final class MBeanClient {
    private static final Set<ErrorCode> NOT_ATTRIBUTE_SPECIFIC = EnumSet.of(ErrorCode.MBEAN_NOT_FOUND,
            ErrorCode.CONNECTION_TIMEOUT, ErrorCode.AUTH_FAILED);
    private static final Set<ErrorCode> UNREADABLE_REPLY = EnumSet.of(ErrorCode.UNSUPPORTED_TYPE,
            ErrorCode.INTERNAL_ERROR);

    private record Fetched(Map<String, Object> values, Map<String, AjmxException> failures) {
    }

    private final JmxSession session;
    private final int limit;
    private final long maxBytes;
    private final Map<ObjectName, MBeanInfo> infoCache = new HashMap<>();

    public MBeanClient(JmxSession session, int limit, long maxBytes) {
        this.session = session;
        this.limit = limit;
        this.maxBytes = maxBytes;
    }

    public Outcome run(Request request) {
        return switch (request) {
            case Request.Ping() -> ping();
            case Request.Search(ObjectName pattern) -> search(pattern);
            case Request.Describe(ObjectName mbean) -> describe(mbean);
            case Request.Read(ObjectName mbean, List<String> attributes) -> read(mbean, attributes);
            case Request.Write(ObjectName mbean, String attribute, Object value) -> write(mbean, attribute, value);
            case Request.Invoke(ObjectName mbean, String operation, List<Object> args, List<String> signature) ->
                invoke(mbean, operation, args, signature);
        };
    }

    private Outcome ping() {
        session.call(Map.of(), c -> c.getMBeanCount());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", true);
        return Outcome.of(result);
    }

    private Outcome search(ObjectName pattern) {
        Set<ObjectName> names = session.call(Map.of("pattern", pattern.toString()), c -> c.queryNames(pattern, null));
        return Outcome.items(names.stream().map(ObjectName::toString).sorted().toList(), limit);
    }

    private Outcome describe(ObjectName name) {
        MBeanInfo info = info(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mbean", name.toString());
        result.put("className", info.getClassName());
        result.put("attributes", Arrays.stream(info.getAttributes())
                .sorted(Comparator.comparing(MBeanAttributeInfo::getName)).map(MBeanClient::attributeJson).toList());
        result.put("operations", Arrays.stream(info.getOperations())
                .sorted(OperationResolver.BY_NAME_AND_SIGNATURE).map(MBeanClient::operationJson).toList());
        return Outcome.of(result);
    }

    private static Map<String, Object> attributeJson(MBeanAttributeInfo attribute) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", attribute.getName());
        m.put("type", attribute.getType());
        m.put("readable", attribute.isReadable());
        m.put("writable", attribute.isWritable());
        return m;
    }

    private static Map<String, Object> operationJson(MBeanOperationInfo operation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", operation.getName());
        m.put("returnType", operation.getReturnType());
        m.put("signature", Arrays.stream(operation.getSignature()).map(MBeanClient::parameterJson).toList());
        return m;
    }

    private static Map<String, Object> parameterJson(MBeanParameterInfo parameter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", parameter.getName());
        m.put("type", parameter.getType());
        return m;
    }

    private Outcome read(ObjectName name, List<String> attributes) {
        Fetched fetched = fetch(name, attributes);
        throwIfNothingWasRead(attributes, fetched.failures());

        ValueEncoder encoder = encoder();
        Map<String, Object> encoded = new LinkedHashMap<>();
        for (String attribute : attributes) {
            if (fetched.values().containsKey(attribute)) {
                encoded.put(attribute, encode(encoder, fetched.values().get(attribute), Execution.NOT_EXECUTED));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mbean", name.toString());
        result.put("attributes", encoded);
        Map<String, AjmxException> failuresByPath = new LinkedHashMap<>();
        if (!fetched.failures().isEmpty()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            fetched.failures().forEach((attribute, e) -> {
                errors.put(attribute, e.toJson());
                failuresByPath.put(".errors[" + Json.write(attribute) + "]", e);
            });
            result.put("errors", errors);
        }
        return outcome(result, encoder, failuresByPath, Execution.NOT_EXECUTED);
    }

    private Fetched fetch(ObjectName name, List<String> attributes) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, AjmxException> failures = new LinkedHashMap<>();
        try {
            AttributeList list = session.call(context(name), c -> c.getAttributes(name, attributes.toArray(String[]::new)));
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof Attribute a) {
                        values.put(a.getName(), a.getValue());
                    }
                }
            }
        } catch (AjmxException e) {
            if (NOT_ATTRIBUTE_SPECIFIC.contains(e.code())) {
                throw e;
            }
            if (attributes.size() == 1) {
                failures.put(attributes.getFirst(), e.withContext(context(name, "attribute", attributes.getFirst())));
            }
        }

        Map<String, Supplier<Object>> pending = new LinkedHashMap<>();
        for (String attribute : attributes) {
            if (!values.containsKey(attribute) && !failures.containsKey(attribute)) {
                pending.put(attribute, session.start(context(name, "attribute", attribute), c -> c.getAttribute(name, attribute)));
            }
        }
        for (Map.Entry<String, Supplier<Object>> p : pending.entrySet()) {
            try {
                values.put(p.getKey(), p.getValue().get());
            } catch (AjmxException e) {
                if (NOT_ATTRIBUTE_SPECIFIC.contains(e.code())) {
                    throw e;
                }
                failures.put(p.getKey(), e);
            }
        }
        return new Fetched(values, failures);
    }

    private static void throwIfNothingWasRead(List<String> attributes, Map<String, AjmxException> failures) {
        if (attributes.size() == 1 && !failures.isEmpty()) {
            throw failures.get(attributes.getFirst());
        }
        if (failures.size() == attributes.size()
                && failures.values().stream().allMatch(e -> e.code() == ErrorCode.CONNECTION_FAILED)) {
            throw failures.get(attributes.getFirst());
        }
    }

    private Outcome write(ObjectName name, String attribute, Object value) {
        Map<String, Object> context = context(name, "attribute", attribute);
        MBeanAttributeInfo info = Arrays.stream(info(name).getAttributes()).filter(a -> a.getName().equals(attribute))
                .findFirst().orElseThrow(() -> new AjmxException(ErrorCode.ATTRIBUTE_NOT_FOUND, "Attribute was not found")
                        .withContext(context));
        if (!info.isWritable()) {
            throw new AjmxException(ErrorCode.ATTRIBUTE_NOT_WRITABLE, "Attribute is not writable").withContext(context);
        }
        Object decoded;
        try {
            decoded = ValueDecoder.decodeParsingStrings(value, info.getType());
        } catch (AjmxException e) {
            throw e.withContext(context);
        }
        mutate(context, c -> {
            c.setAttribute(name, new Attribute(attribute, decoded));
            return null;
        });

        ValueEncoder encoder = encoder();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mbean", name.toString());
        result.put("attribute", attribute);
        result.put("value", encode(encoder, decoded, Execution.EXECUTED));
        return outcome(result, encoder, Map.of(), Execution.EXECUTED);
    }

    private Outcome invoke(ObjectName name, String operation, List<Object> args, List<String> signature) {
        OperationResolver.Resolved resolved;
        try {
            resolved = OperationResolver.resolve(info(name).getOperations(), operation, args, signature);
        } catch (AjmxException e) {
            throw e.withContext(context(name));
        }
        Object returned = mutate(context(name, "operation", operation),
                c -> c.invoke(name, operation, resolved.params(), resolved.signature()));

        ValueEncoder encoder = encoder();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mbean", name.toString());
        result.put("operation", operation);
        result.put("signature", List.of(resolved.signature()));
        result.put("returnValue", encode(encoder, returned, Execution.EXECUTED));
        return outcome(result, encoder, Map.of(), Execution.EXECUTED);
    }

    private MBeanInfo info(ObjectName name) {
        MBeanInfo cached = infoCache.get(name);
        if (cached != null) {
            return cached;
        }
        MBeanInfo info = session.call(context(name), c -> c.getMBeanInfo(name));
        if (String.valueOf(info.getDescriptor().getFieldValue(JMX.IMMUTABLE_INFO_FIELD)).equals("true")) {
            infoCache.put(name, info);
        }
        return info;
    }

    private ValueEncoder encoder() {
        return new ValueEncoder(limit, maxBytes);
    }

    static Object encode(ValueEncoder encoder, Object value, Execution execution) {
        try {
            return encoder.encode(value);
        } catch (AjmxException e) {
            throw e.withExecution(execution);
        }
    }

    private static Outcome outcome(Map<String, Object> result, ValueEncoder encoder,
            Map<String, AjmxException> failuresByPath, Execution execution) {
        if (encoder.truncated()) {
            result.put("truncated", true);
        }
        return Outcome.of(result, failuresByPath, execution);
    }

    private <T> T mutate(Map<String, Object> context, JmxSession.JmxCall<T> call) {
        try {
            return session.call(context, call);
        } catch (AjmxException e) {
            if (e.retryable() || UNREADABLE_REPLY.contains(e.code())) {
                throw e.withExecution(Execution.UNKNOWN);
            }
            throw e;
        }
    }

    private static Map<String, Object> context(ObjectName name) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mbean", name.toString());
        return context;
    }

    private static Map<String, Object> context(ObjectName name, String key, String value) {
        Map<String, Object> context = context(name);
        context.put(key, value);
        return context;
    }
}
