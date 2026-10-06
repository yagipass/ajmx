package io.github.yagipass.ajmx.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class TargetJvm implements AutoCloseable {
    private final Process process;
    private final long pid;

    private TargetJvm(Process process, long pid) {
        this.process = process;
        this.pid = pid;
    }

    static TargetJvm start(String... jvmArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(List.of(jvmArgs));
        command.addAll(List.of("-cp", classpath(), "ajmxtest.TestTarget"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder log = new StringBuilder();
                for (String line; (line = out.readLine()) != null;) {
                    if (line.startsWith("READY ")) {
                        return line.substring(6).trim();
                    }
                    log.append(line).append('\n');
                }
                throw new IllegalStateException("Target JVM exited before READY:\n" + log);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        String pid;
        try {
            pid = ready.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        }
        Thread drain = new Thread(new FutureTask<>(() -> out.transferTo(Writer.nullWriter())));
        drain.setDaemon(true);
        drain.start();
        return new TargetJvm(process, Long.parseLong(pid));
    }

    long pid() {
        return pid;
    }

    String pidArg() {
        return Long.toString(pid);
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Path socket = Paths.get(System.getProperty("java.io.tmpdir"), ".java_pid" + pid);
        if (Files.exists(socket)) {
            throw new IllegalStateException("Target JVM left its attach socket " + socket + ", which breaks attach to a later process with its PID");
        }
    }

    static String javaExecutable() {
        String java = System.getProperty("ajmx.test.java");
        return java != null && !java.isBlank() ? java : Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        String classpath = System.getProperty("ajmx.test.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("ajmx.test.classpath is not set; run the integration tests with Gradle");
        }
        return classpath;
    }
}
