package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.list;
import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class SmokeIT {
    private static TargetJvm target;

    @BeforeAll
    static void start() throws Exception {
        target = TargetJvm.start();
    }

    @AfterAll
    static void stop() throws Exception {
        target.close();
    }

    @Test
    void psFindsTheTargetJvm() throws Exception {
        Ajmx.Result r = Ajmx.run("ps");
        assertEquals(0, r.exitCode(), r.stdout());
        boolean found = list(r.result().get("items")).stream()
                .anyMatch(i -> number(map(i).get("pid")) == target.pid()
                        && Objects.equals(map(i).get("mainClass"), "ajmxtest.TestTarget"));
        assertTrue(found, r.stdout());
    }

    @Test
    void pingConnectsByPid() throws Exception {
        Ajmx.Result r = Ajmx.run("--pid", target.pidArg(), "ping");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(Map.of("connected", true), r.result());
    }

    @Test
    void versionIsEmbeddedInTheNativeBinaryToo() throws Exception {
        Ajmx.Result r = Ajmx.run("version");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(Map.of("version", System.getProperty("ajmx.version")), r.result());
    }

    @Test
    void outputThatCannotBeWrittenIsAFailureNotASilentSuccess() throws Exception {
        Process p = new ProcessBuilder(Ajmx.command("--url", "service:jmx:rmi:///jndi/rmi://127.0.0.1:1/jmxrmi",
                "--credentials-stdin", "ping")).start();
        p.getInputStream().close();
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write("{\"username\": \"u\", \"password\": \"p\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, p.exitValue(), stderr);
        assertTrue(stderr.contains("failed to write output"), stderr);
    }

    @Test
    void argumentsThatLookLikeVmOptionsAreAjmxArgumentsInTheNativeBinaryToo() throws Exception {
        for (String[] args : new String[][] { { "ps", "-Dfoo=bar" }, { "ps", "--", "-Xmx64m" }, { "ps", "-XX:+NoSuchOption" } }) {
            Ajmx.Result r = Ajmx.run(args).assertError("INVALID_ARGUMENT", 2);
            assertEquals(1L, number(r.details().get("argumentCount")), String.join(" ", args));
        }
    }

    @Test
    void readReturnsAnAttributeByPid() throws Exception {
        Ajmx.Result r = Ajmx.run("--pid", target.pidArg(), "read", "ajmxtest:type=Cache", "Size");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals(42L, number(map(r.result().get("attributes")).get("Size")));
    }
}
