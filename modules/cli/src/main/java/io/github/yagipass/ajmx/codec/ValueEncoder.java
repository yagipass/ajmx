package io.github.yagipass.ajmx.codec;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.Attribute;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.json.Json;

public final class ValueEncoder {
    private static final int MAX_DEPTH = 32;

    private record Keyed(String key, @Nullable Object value, long count) {
    }

    private static final Comparator<Keyed> BY_KEY = Comparator.comparing(Keyed::key);

    private final int limit;
    private final long maxBytes;
    private final Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
    private long count;
    private boolean truncated;

    public ValueEncoder(int limit, long maxBytes) {
        this.limit = limit;
        this.maxBytes = maxBytes;
    }

    public boolean truncated() {
        return truncated;
    }

    public @Nullable Object encode(@Nullable Object v) {
        try {
            return encode(v, 0);
        } catch (RuntimeException | Error e) {
            throw AjmxException.encodingFailed(e, maxBytes);
        }
    }

    private @Nullable Object encode(@Nullable Object v, int depth) {
        count++;
        if (v == null || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Number n) {
            return number(n);
        }
        if (v instanceof Character c) {
            return String.valueOf(c);
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof ObjectName on) {
            return on.toString();
        }
        if (v instanceof byte[] b) {
            return Map.of("$base64", Base64.getEncoder().encodeToString(b));
        }
        if (depth > MAX_DEPTH || count > maxBytes || !ancestors.add(v)) {
            truncated = true;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$truncated", true);
            m.put("$type", v.getClass().getName());
            return m;
        }
        try {
            return container(v, depth);
        } finally {
            ancestors.remove(v);
        }
    }

    private Object container(Object v, int depth) {
        if (v instanceof CompositeData cd) {
            return composite(cd, depth);
        }
        if (v instanceof TabularData td) {
            return sorted(td.values(), depth);
        }
        if (v instanceof Attribute a) {
            return attribute(a, depth);
        }
        if (v instanceof Set<?> s) {
            return sorted(s, depth);
        }
        if (v instanceof Collection<?> c) {
            return list(c, depth);
        }
        if (v instanceof Map<?, ?> m) {
            return map(m, depth);
        }
        if (v.getClass().isArray()) {
            return list(arrayElements(v), depth);
        }
        return fallback(v);
    }

    private static Object number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger || n instanceof BigDecimal) {
            return n;
        }
        if (n instanceof Double || n instanceof Float) {
            return Double.isFinite(n.doubleValue()) ? n : n.toString();
        }
        if (n instanceof AtomicInteger || n instanceof AtomicLong) {
            return n.longValue();
        }
        return fallback(n);
    }

    private Map<String, Object> composite(CompositeData cd, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : cd.getCompositeType().keySet()) {
            m.put(key, encode(cd.get(key), depth + 1));
        }
        return m;
    }

    private Map<String, Object> attribute(Attribute a, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.getName());
        m.put("value", encode(a.getValue(), depth + 1));
        return m;
    }

    private Object sorted(Collection<?> values, int depth) {
        long base = count;
        @Var long kept = 0;
        PriorityQueue<Keyed> first = new PriorityQueue<>(BY_KEY.reversed());
        for (Object v : values) {
            count = base;
            Object encoded = encode(v, depth + 1);
            first.add(new Keyed(Json.write(encoded), encoded, count - base));
            kept += count - base;
            if (first.size() > limit) {
                kept -= first.poll().count();
            }
        }
        count = base + kept;
        List<Object> encoded = first.stream().sorted(BY_KEY).map(Keyed::value).toList();
        return truncateList(encoded, values.size());
    }

    private Object list(Collection<?> values, int depth) {
        List<Object> encoded = new ArrayList<>(Math.min(values.size(), limit));
        Iterator<?> it = values.iterator();
        while (encoded.size() < limit && it.hasNext()) {
            encoded.add(encode(it.next(), depth + 1));
        }
        return truncateList(encoded, values.size());
    }

    private Object truncateList(List<Object> encoded, int total) {
        if (total <= limit) {
            return encoded;
        }
        truncated = true;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$truncated", true);
        m.put("$total", total);
        m.put("$items", encoded);
        return m;
    }

    private Object map(Map<?, ?> source, int depth) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (entries.size() == limit) {
                break;
            }
            entries.put(e.getKey(), encode(e.getValue(), depth + 1));
        }
        if (sorted.size() <= limit) {
            return entries;
        }
        truncated = true;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$truncated", true);
        m.put("$total", sorted.size());
        m.put("$entries", entries);
        return m;
    }

    private static List<?> arrayElements(Object v) {
        if (v instanceof Object[] a) {
            return Arrays.asList(a);
        }
        return new AbstractList<>() {
            @Override
            public Object get(int i) {
                return Array.get(v, i);
            }

            @Override
            public int size() {
                return Array.getLength(v);
            }
        };
    }

    private static Map<String, Object> fallback(Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$type", v.getClass().getName());
        @Var String s;
        try {
            s = String.valueOf(v);
        } catch (RuntimeException e) {
            s = null;
        }
        m.put("$string", s);
        return m;
    }
}
