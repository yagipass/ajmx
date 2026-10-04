package io.github.yagipass.ajmx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import io.github.yagipass.ajmx.codec.ValueEncoder;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.error.Execution;

final class MBeanClientTest {

    @Test
    void runningOutOfMemoryWhileEncodingFailsLikeAnOutputTooLargeToPrint() {
        AjmxException e = assertThrows(AjmxException.class,
                () -> MBeanClient.encode(new ValueEncoder(100, 512), throwingOnToString(new OutOfMemoryError()), Execution.NOT_EXECUTED));
        assertEquals(ErrorCode.OUTPUT_TRUNCATED, e.code());
        assertEquals(512L, e.details().get("maxBytes"));
        assertFalse(details(e).containsKey("executed"), "a read changes nothing");
    }

    @Test
    void failingToEncodeTheResultOfAChangeSaysTheChangeWasMadeSoItIsNotRepeated() {
        AjmxException oom = assertThrows(AjmxException.class,
                () -> MBeanClient.encode(new ValueEncoder(100, 512), throwingOnToString(new OutOfMemoryError()), Execution.EXECUTED));
        assertEquals(ErrorCode.OUTPUT_TRUNCATED, oom.code());
        assertEquals(true, details(oom).get("executed"));

        AjmxException other = assertThrows(AjmxException.class,
                () -> MBeanClient.encode(new ValueEncoder(100, 512), throwingOnToString(new StackOverflowError()), Execution.EXECUTED));
        assertEquals(ErrorCode.INTERNAL_ERROR, other.code());
        assertEquals(true, details(other).get("executed"));
    }

    private static Map<?, ?> details(AjmxException e) {
        return (Map<?, ?>) Objects.requireNonNull(e.toJson().get("details"));
    }

    private static Object throwingOnToString(Error error) {
        return new Object() {
            @Override
            public String toString() {
                throw error;
            }
        };
    }
}
