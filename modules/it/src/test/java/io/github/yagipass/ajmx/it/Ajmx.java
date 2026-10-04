package io.github.yagipass.ajmx.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import io.github.yagipass.ajmx.cli.Cli;
import io.github.yagipass.ajmx.json.Json;

final class Ajmx {
    static final String NOT_A_JVM = "1";

    private Ajmx() {
    }

    record Result(int exitCode, String stdout, String stderr, Map<String, Object> json) {
        boolean ok() {
            return Objects.equals(json.get("ok"), true);
        }

        @SuppressWarnings("unchecked") // Json.parse builds every JSON object with String keys
        Map<String, Object> result() {
            assertTrue(ok(), () -> "expected success but got " + stdout);
            return map(json.get("result"));
        }

        @SuppressWarnings("unchecked") // Json.parse builds every JSON object with String keys
        Map<String, Object> error() {
            assertEquals(false, json.get("ok"), () -> "expected failure but got " + stdout);
            return map(json.get("error"));
        }

        @Nullable
        String errorCode() {
            return (String) error().get("code");
        }

        @SuppressWarnings("unchecked") // Json.parse builds every JSON object with String keys
        Map<String, Object> details() {
            return map(error().get("details"));
        }

        Result assertError(String code, int exit) {
            assertEquals(code, errorCode(), stdout);
            assertEquals(exit, exitCode, stdout);
            return this;
        }
    }

    @SuppressWarnings("unchecked") // callers pass parts of the JSON that Json.parse built
    static Map<String, Object> map(@Nullable Object o) {
        return (Map<String, Object>) Objects.requireNonNull(o);
    }

    @SuppressWarnings("unchecked") // callers pass parts of the JSON that Json.parse built
    static List<Object> list(@Nullable Object o) {
        return (List<Object>) Objects.requireNonNull(o);
    }

    static long number(@Nullable Object o) {
        return ((Number) Objects.requireNonNull(o)).longValue();
    }

    static boolean nativeMode() {
        String binary = System.getProperty("ajmx.binary");
        return binary != null && !binary.isBlank();
    }

    static Result run(String... args) throws Exception {
        return run(Map.of(), "", args);
    }

    static Result run(Map<String, String> env, String stdin, String... args) throws Exception {
        Result r = nativeMode() ? runNative(env, stdin, p -> {
        }, args) : runInProcess(env, stdin, args);
        assertSingleJsonDocument(r.stdout());
        return r;
    }

    @FunctionalInterface
    interface WhileRunning {
        void accept(Process ajmx) throws Exception;
    }

    static Result runNativeWhile(WhileRunning whileRunning, String... args) throws Exception {
        Result r = runNative(Map.of(), "", whileRunning, args);
        assertSingleJsonDocument(r.stdout());
        return r;
    }

    private static Result runInProcess(Map<String, String> env, String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out,
                new PrintStream(err, true, StandardCharsets.UTF_8), env);
        String stdout = out.toString(StandardCharsets.UTF_8);
        return new Result(exit, stdout, err.toString(StandardCharsets.UTF_8), parse(stdout));
    }

    static List<String> command(String... args) {
        List<String> command = new ArrayList<>();
        if (nativeMode()) {
            command.add(System.getProperty("ajmx.binary"));
        } else {
            command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), "io.github.yagipass.ajmx.Main"));
        }
        command.addAll(List.of(args));
        return command;
    }

    private static Result runNative(Map<String, String> env, String stdin, WhileRunning whileRunning, String... args)
            throws Exception {
        List<String> command = command(args);
        Path out = Files.createTempFile("ajmx-out", ".json");
        Path err = Files.createTempFile("ajmx-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
            Map<String, String> environment = pb.environment();
            environment.remove("JMX_USERNAME");
            environment.remove("JMX_PASSWORD");
            environment.putAll(new HashMap<>(env));
            Process p = pb.start();
            p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            whileRunning.accept(p);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("ajmx did not exit: " + command);
            }
            String stdout = Files.readString(out);
            return new Result(p.exitValue(), stdout, Files.readString(err), parse(stdout));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    @SuppressWarnings("unchecked") // Json.parse builds every JSON object with String keys
    private static Map<String, Object> parse(String stdout) {
        Object doc = Json.parse(stdout.strip());
        assertTrue(doc instanceof Map, () -> "stdout is not a JSON object: " + stdout);
        Map<String, Object> json = (Map<String, Object>) doc;
        assertEquals(1, ((Number) Objects.requireNonNull(json.get("schemaVersion"))).intValue(), stdout);
        return json;
    }

    private static void assertSingleJsonDocument(String stdout) {
        assertTrue(stdout.endsWith("\n"), () -> "stdout must end with a newline: " + stdout);
        assertEquals(stdout.length() - 1, stdout.indexOf('\n'), () -> "stdout must be a single line: " + stdout);
    }
}
