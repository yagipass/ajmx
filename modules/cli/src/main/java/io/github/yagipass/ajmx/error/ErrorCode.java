package io.github.yagipass.ajmx.error;

public enum ErrorCode {
    INVALID_ARGUMENT(2, false), TYPE_CONVERSION_FAILED(2, false), ATTRIBUTE_NOT_WRITABLE(2, false),

    CONNECTION_FAILED(3, true), CONNECTION_TIMEOUT(3, true), AUTH_FAILED(3, false), PROCESS_NOT_FOUND(3, false), ATTACH_NOT_SUPPORTED(3, false), ATTACH_PERMISSION_DENIED(3, false), LOCAL_JMX_UNAVAILABLE(3, true),

    MBEAN_NOT_FOUND(4, false), ATTRIBUTE_NOT_FOUND(4, false), OPERATION_NOT_FOUND(4, false), AMBIGUOUS_OPERATION(4, false),

    REMOTE_EXCEPTION(5, false), UNSUPPORTED_TYPE(5, false),

    TIMEOUT(6, true), SKIPPED(6, true),

    OUTPUT_TRUNCATED(7, false),

    INTERNAL_ERROR(1, false);

    public static final int PARTIAL_EXIT_CODE = OUTPUT_TRUNCATED.exitCode();

    private final int exitCode;
    private final boolean retryable;

    ErrorCode(int exitCode, boolean retryable) {
        this.exitCode = exitCode;
        this.retryable = retryable;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
