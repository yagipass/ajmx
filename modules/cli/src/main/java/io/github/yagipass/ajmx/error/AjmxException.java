package io.github.yagipass.ajmx.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public final class AjmxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final LinkedHashMap<String, Object> details = new LinkedHashMap<>();
    private Execution execution = Execution.NOT_EXECUTED;
    private boolean retryDisabled;

    public AjmxException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public AjmxException(ErrorCode code, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static AjmxException wrap(Throwable t) {
        if (t instanceof AjmxException e) {
            return e;
        }
        return new AjmxException(ErrorCode.INTERNAL_ERROR, "Unexpected internal error", t)
                .with("exceptionClass", t.getClass().getName());
    }

    public static AjmxException encodingFailed(Throwable t, long maxBytes) {
        if (t instanceof OutOfMemoryError) {
            return new AjmxException(ErrorCode.OUTPUT_TRUNCATED, "Not enough memory to encode the output", t)
                    .with("maxBytes", maxBytes);
        }
        return wrap(t);
    }

    public ErrorCode code() {
        return code;
    }

    public Execution execution() {
        return execution;
    }

    public boolean retryable() {
        return code.retryable() && !retryDisabled && execution == Execution.NOT_EXECUTED;
    }

    public boolean mayStillRun() {
        return code.retryable() && execution == Execution.UNKNOWN;
    }

    public AjmxException disableRetry() {
        retryDisabled = true;
        return this;
    }

    public AjmxException withExecution(Execution execution) {
        this.execution = execution;
        return this;
    }

    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    public AjmxException with(String key, @Nullable Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public AjmxException withContext(Map<String, ?> context) {
        Map<String, Object> own = new LinkedHashMap<>(details);
        details.clear();
        context.forEach(this::with);
        details.putAll(own);
        return this;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>(details);
        execution.toJson().ifPresent(executed -> json.put("executed", executed));
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", getMessage());
        error.put("retryable", retryable());
        error.put("details", json);
        return error;
    }
}
