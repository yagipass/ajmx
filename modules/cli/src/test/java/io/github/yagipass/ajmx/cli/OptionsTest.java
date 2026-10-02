package io.github.yagipass.ajmx.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class OptionsTest {

    @Test
    void optionsMayComeBeforeOrAfterTheCommand() {
        Options o = Options.parse(new String[] { "--pid", "12", "read", "java.lang:type=Memory", "--timeout=5s", "HeapMemoryUsage" });
        assertEquals(12L, o.pid());
        assertEquals(Command.READ, o.command());
        assertEquals(List.of("java.lang:type=Memory", "HeapMemoryUsage"), o.args());
        assertEquals(5000, o.timeoutMs());
    }

    @Test
    void doubleDashLetsAValueStartWithDashes() {
        Options o = Options.parse(new String[] { "write", "--", "d:type=x", "Name=--x" });
        assertEquals(List.of("d:type=x", "Name=--x"), o.args());
    }

    @Test
    void safeDefaults() {
        Options o = Options.parse(new String[] { "ps" });
        assertEquals(10_000, o.timeoutMs());
        assertEquals(100, o.limit());
        assertEquals(262_144, o.maxBytes());
        assertNull(o.signature());
    }

    @Test
    void durations() {
        assertEquals(500, Options.duration("500ms"));
        assertEquals(5000, Options.duration("5s"));
        assertEquals(5000, Options.duration("5"));
        assertEquals(60_000, Options.duration("1m"));
        for (String bad : List.of("0s", "-1s", "1h", "5 s", "")) {
            assertInvalid(() -> Options.duration(bad));
        }
    }

    @Test
    void signatureIsRepeatableAndCommaSeparated() {
        Options o = Options.parse(new String[] { "invoke", "--signature", "java.lang.String,int", "--signature", "long" });
        assertEquals(List.of("java.lang.String", "int", "long"), o.signature());
        assertEquals(List.of(), Options.parse(new String[] { "invoke", "--signature", "" }).signature());
    }

    @Test
    void mistakesAreInvalidArgumentsNotSilentlyIgnored() {
        assertInvalid(() -> Options.parse(new String[] { "--pid", "1", "--pid", "2", "ping" }));
        assertInvalid(() -> Options.parse(new String[] { "--pid", "abc", "ping" }));
        assertInvalid(() -> Options.parse(new String[] { "--pid", "0", "ping" }));
        assertInvalid(() -> Options.parse(new String[] { "--bogus", "ping" }));
        assertInvalid(() -> Options.parse(new String[] { "--pid", "1", "pign" }));
        assertInvalid(() -> Options.parse(new String[] { "ping", "--timeout" }));
        assertInvalid(() -> Options.parse(new String[] { "--debug=yes", "ping" }));
        assertInvalid(() -> Options.parse(new String[] { "--max-bytes", "100", "ping" }));
    }

    @Test
    void combinationsThatCannotWorkAreRejectedBeforeAnythingIsConnectedOrRead() {
        for (String[] args : new String[][] { { "--pid", "1", "--url", "service:jmx:x", "ping" }, { "--pid", "1" },
                { "--pid", "1", "read", "d:k=v", "A", "--args", "[]" }, { "--pid", "1", "read", "d:k=v", "A", "--signature", "int" },
                { "--pid", "1", "--credentials-stdin", "ping" }, { "--url", "service:jmx:x", "--credentials-stdin", "batch" } }) {
            Options o = Options.parse(args);
            assertInvalid(o::validate);
        }
        Options.parse(new String[] { "--url", "service:jmx:x", "--credentials-stdin", "invoke", "d:k=v", "op", "--args", "[]" })
                .validate();
    }

    private static void assertInvalid(Runnable r) {
        AjmxException e = assertThrows(AjmxException.class, r::run);
        assertEquals(ErrorCode.INVALID_ARGUMENT, e.code());
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
    }
}
