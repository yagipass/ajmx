package io.github.yagipass.ajmx.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.Execution;

public final class Outcome {
    public enum Shape {
        PLAIN, ITEMS, BATCH
    }

    private final Map<String, Object> result;
    private final Shape shape;
    private final boolean partial;
    private final Map<String, AjmxException> failuresByPath;
    private final Execution execution;

    private Outcome(Map<String, Object> result, Shape shape, boolean partial, Map<String, AjmxException> failuresByPath,
            Execution execution) {
        this.result = result;
        this.shape = shape;
        this.partial = partial;
        this.failuresByPath = failuresByPath;
        this.execution = execution;
    }

    public static Outcome of(Map<String, Object> result) {
        return new Outcome(result, Shape.PLAIN, false, Map.of(), Execution.NOT_EXECUTED);
    }

    static Outcome of(Map<String, Object> result, Map<String, AjmxException> failuresByPath, Execution execution) {
        boolean truncated = Objects.equals(result.get("truncated"), true);
        return new Outcome(result, Shape.PLAIN, truncated || !failuresByPath.isEmpty(), failuresByPath, execution);
    }

    public static Outcome items(List<?> all, int limit) {
        boolean truncated = all.size() > limit;
        return new Outcome(itemsResult(truncated ? all.subList(0, limit) : all, truncated), Shape.ITEMS, truncated,
                Map.of(), Execution.NOT_EXECUTED);
    }

    public static Map<String, Object> itemsResult(List<?> items, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", new ArrayList<Object>(items));
        result.put("returned", items.size());
        result.put("truncated", truncated);
        return result;
    }

    static Outcome batch(Map<String, Object> result, boolean partial, Map<String, AjmxException> failuresByPath,
            Execution execution) {
        return new Outcome(result, Shape.BATCH, partial, failuresByPath, execution);
    }

    public Map<String, Object> result() {
        return result;
    }

    public Shape shape() {
        return shape;
    }

    public boolean partial() {
        return partial;
    }

    public Map<String, AjmxException> failuresByPath() {
        return failuresByPath;
    }

    public Execution execution() {
        return execution;
    }
}
