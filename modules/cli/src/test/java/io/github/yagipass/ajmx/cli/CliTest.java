package io.github.yagipass.ajmx.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.json.Json;

final class CliTest {

    private record Run(int exit, Map<?, ?> json, int bytes) {
        @Nullable
        Object code() {
            return error().get("code");
        }

        Map<?, ?> error() {
            return (Map<?, ?>) Objects.requireNonNull(json.get("error"));
        }
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = Cli.run(args, new ByteArrayInputStream(new byte[0]), out,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), Map.of());
        return new Run(exit, (Map<?, ?>) Objects.requireNonNull(Json.parse(out.toString(StandardCharsets.UTF_8))), out.size());
    }

    @Test
    void usageErrorsExitWithTwo() {
        for (String[] args : new String[][] { {}, { "nope" }, { "--pid", "1", "read", "d:k=v" }, { "--pid", "1", "describe", "d:*" },
                { "--pid", "1", "write", "d:k=v", "novalue" }, { "--pid", "1", "read", "d:k=v", "A", "--args", "[]" },
                { "--pid", "1", "invoke", "d:k=v", "op", "--args", "[1e99999999999]" } }) {
            Run r = run(args);
            assertEquals("INVALID_ARGUMENT", r.code(), String.join(" ", args));
            assertEquals(2, r.exit());
        }
    }

    @Test
    void envelopeFollowsTheContract() {
        Run r = run("nope");
        assertEquals(1L, ((Number) Objects.requireNonNull(r.json().get("schemaVersion"))).longValue());
        assertEquals(false, r.json().get("ok"));
        Map<?, ?> error = (Map<?, ?>) Objects.requireNonNull(r.json().get("error"));
        assertEquals(Set.of("code", "message", "retryable", "details"), error.keySet());
        assertTrue(r.json().get("durationMs") instanceof Number);
    }

    @Test
    void errorEchoingAHugeArgumentStaysWithinMaxBytesAndKeepsItsCode() {
        String huge = "x".repeat(100_000);
        Run r = run("--max-bytes", "512", "--pid", "1", "write", "d:k=v", huge);
        assertTrue(r.bytes() <= 512, () -> r.bytes() + " bytes");
        assertEquals("INVALID_ARGUMENT", r.code());
        assertEquals(2, r.exit());
        Map<?, ?> details = (Map<?, ?>) Objects.requireNonNull(r.error().get("details"));
        assertEquals(true, details.get("truncated"));
        assertTrue(((String) Objects.requireNonNull(details.get("argument"))).startsWith("xxxxxxxx"), details.toString());
    }

    @Test
    void errorCutToMaxBytesNeverSplitsACharacterSoStrictParsersStillReadIt() {
        for (int n = 100; n <= 140; n++) {
            Run r = run("--max-bytes", "512", "describe", "a" + "😀".repeat(n));
            assertTrue(r.bytes() <= 512, r.bytes() + " bytes");
            Map<?, ?> details = (Map<?, ?>) Objects.requireNonNull(r.error().get("details"));
            assertEquals(true, details.get("truncated"), "n=" + n);
            String mbean = (String) Objects.requireNonNull(details.get("mbean"));
            assertTrue(mbean.startsWith("a😀") && mbean.endsWith("…"), "n=" + n + ": " + mbean);
            assertTrue(mbean.codePoints().noneMatch(c -> Character.getType(c) == Character.SURROGATE),
                    "n=" + n + " leaves half a surrogate pair: " + mbean);
        }
    }

    @Test
    void errorCutToMaxBytesKeepsAsMuchOfANonAsciiDetailAsFits() {
        Run r = run("--max-bytes", "512", "describe", "a" + "あ".repeat(500));
        Map<?, ?> details = (Map<?, ?>) Objects.requireNonNull(r.error().get("details"));
        assertTrue(details.get("mbean") instanceof String mbean && mbean.startsWith("aあ") && mbean.endsWith("…"),
                details.toString());
        assertTrue(r.bytes() <= 512 && r.bytes() > 512 - "あ".getBytes(StandardCharsets.UTF_8).length,
                r.bytes() + " bytes");
    }

    @Test
    void helpIsJsonToo() {
        Run r = run("--help");
        assertEquals(0, r.exit());
        assertTrue(((Map<?, ?>) Objects.requireNonNull(r.json().get("result"))).containsKey("commands"));
    }

    @Test
    void versionIsThePomVersionSoABumpedReleaseReportsItsOwnVersion() {
        for (String[] args : new String[][] { { "version" }, { "--version" }, { "--pid", "1", "--version", "read" } }) {
            Run r = run(args);
            assertEquals(0, r.exit(), String.join(" ", args));
            assertEquals(Map.of("version", System.getProperty("ajmx.version")), r.json().get("result"),
                    String.join(" ", args));
        }
    }

    @Test
    void helpListsEveryCommandAndOptionInTheFormTheParserAccepts() {
        Map<?, ?> usage = (Map<?, ?>) Objects.requireNonNull(run("help").json().get("result"));
        List<String> commands = new ArrayList<>();
        for (Object syntax : ((Map<?, ?>) Objects.requireNonNull(usage.get("commands"))).keySet()) {
            String token = ((String) syntax).split(" ", -1)[0];
            commands.add(token);
            assertEquals(token, Objects.requireNonNull(Options.parse(new String[] { token }).command()).token());
        }
        assertEquals(Command.tokens(), commands);

        List<String> options = new ArrayList<>();
        for (Object syntax : ((Map<?, ?>) Objects.requireNonNull(usage.get("options"))).keySet()) {
            String[] parts = ((String) syntax).split(" ", -1);
            options.add(parts[0]);
            if (parts.length > 1) {
                AjmxException e = assertThrows(AjmxException.class, () -> Options.parse(new String[] { parts[0] }));
                assertEquals("Option requires a value", e.getMessage(), parts[0]);
            } else {
                Options.parse(new String[] { parts[0] });
            }
        }
        assertEquals(Arrays.stream(Option.values()).map(Option::flag).toList(), options);
    }
}
