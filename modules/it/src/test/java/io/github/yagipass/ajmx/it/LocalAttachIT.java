package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.list;
import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

final class LocalAttachIT {

    @Test
    void psListsJvmsSortedByPidWithMainClassAndDisplayName() throws Exception {
        try (TargetJvm a = TargetJvm.start(); TargetJvm b = TargetJvm.start()) {
            Ajmx.Result r = Ajmx.run("ps");
            List<Object> items = list(r.result().get("items"));
            List<Long> pids = items.stream().map(i -> number(map(i).get("pid"))).toList();
            assertEquals(pids.stream().sorted().toList(), pids, "ps must be deterministic");
            for (TargetJvm t : List.of(a, b)) {
                Map<String, Object> item = items.stream().map(Ajmx::map)
                        .filter(i -> number(i.get("pid")) == t.pid()).findFirst().orElseThrow();
                assertEquals(Map.of("pid", t.pid(), "mainClass", "ajmxtest.TestTarget", "displayName", "TestTarget"),
                        Map.of("pid", number(item.get("pid")), "mainClass", item.get("mainClass"), "displayName", item.get("displayName")));
            }
        }
    }

    @Test
    void attachWorksWithoutPerfData() throws Exception {
        try (TargetJvm t = TargetJvm.start("-XX:-UsePerfData")) {
            Ajmx.Result ps = Ajmx.run("ps");
            assertTrue(list(ps.result().get("items")).stream().noneMatch(i -> number(map(i).get("pid")) == t.pid()));
            assertEquals(0, Ajmx.run("--pid", t.pidArg(), "ping").exitCode());
        }
    }

    @Test
    void deadProcessIsNotFoundImmediately() throws Exception {
        Process p = new ProcessBuilder("true").start();
        p.waitFor(10, TimeUnit.SECONDS);
        long start = System.nanoTime();
        Ajmx.Result r = Ajmx.run("--pid", Long.toString(p.pid()), "ping").assertError("PROCESS_NOT_FOUND", 3);
        assertEquals(p.pid(), number(r.details().get("pid")));
        assertTrue(elapsedMs(start) < 2000, "must not wait for the Attach API");
    }

    @Test
    void jvmWithAttachDisabledIsReportedWithoutWaiting() throws Exception {
        try (TargetJvm t = TargetJvm.start("-XX:+DisableAttachMechanism")) {
            long start = System.nanoTime();
            Ajmx.run("--pid", t.pidArg(), "ping").assertError("ATTACH_NOT_SUPPORTED", 3);
            assertTrue(elapsedMs(start) < 2000, "must not wait for the Attach API");
        }
    }

    @Test
    void jvmThatPublishesItsLocalJmxAddressIsReachedThroughItWithoutAttaching() throws Exception {
        try (TargetJvm t = TargetJvm.start("-XX:+DisableAttachMechanism", "-Dcom.sun.management.jmxremote")) {
            Ajmx.Result r = Ajmx.run("--pid", t.pidArg(), "ping");
            assertEquals(0, r.exitCode(), r.stdout());
        }
    }

    @Test
    void unresponsiveJvmTimesOutRetryablyAndLeavesNothingThatBreaksTheRetry() throws Exception {
        try (TargetJvm t = TargetJvm.start()) {
            signal("STOP", t);
            try {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    long start = System.nanoTime();
                    Ajmx.Result r = Ajmx.run("--pid", t.pidArg(), "--timeout", "2s", "ping").assertError("CONNECTION_TIMEOUT", 3);
                    assertEquals(true, r.error().get("retryable"), "attempt " + attempt);
                    assertTrue(elapsedMs(start) < 4000, "attempt " + attempt + " must end with --timeout");
                    assertEquals(List.of(), attachFiles(t.pid()), "attempt " + attempt);
                }
            } finally {
                signal("CONT", t);
            }
            assertEquals(0, Ajmx.run("--pid", t.pidArg(), "ping").exitCode());
        }
    }

    @Test
    void jvmWithoutPerfDataThatIgnoresAttachIsNotRetryableSoAnAgentDoesNotKeepSignalingIt() throws Exception {
        try (TargetJvm t = TargetJvm.start("-XX:-UsePerfData", "-XX:+DisableAttachMechanism")) {
            long start = System.nanoTime();
            Ajmx.Result r = Ajmx.run("--pid", t.pidArg(), "--timeout", "2s", "ping").assertError("ATTACH_NOT_SUPPORTED", 3);
            assertEquals(false, r.error().get("retryable"));
            assertTrue(elapsedMs(start) < 4000, "must end with --timeout");
            assertEquals(List.of(), attachFiles(t.pid()));
        }
    }

    @Test
    void jvmWithoutPerfDataThatIgnoresAttachIsNotRetryableEvenWhenAjmxTimesOutBeforeTheJdkGivesUp() throws Exception {
        assumeTrue(Ajmx.nativeMode(), "only the native binary runs in a process of its own that can be stalled");
        try (TargetJvm t = TargetJvm.start("-XX:-UsePerfData", "-XX:+DisableAttachMechanism")) {
            Ajmx.Result r = Ajmx.runNativeWhile(ajmx -> {
                awaitHandshake(t, ajmx);
                signal("STOP", Long.toString(ajmx.pid()));
                Thread.sleep(2500);
                signal("CONT", Long.toString(ajmx.pid()));
            }, "--pid", t.pidArg(), "--timeout", "2s", "ping").assertError("ATTACH_NOT_SUPPORTED", 3);
            assertEquals(false, r.error().get("retryable"));
            assertEquals(List.of(), attachFiles(t.pid()));
        }
    }

    private static void awaitHandshake(TargetJvm t, Process ajmx) throws InterruptedException {
        long start = System.nanoTime();
        while (attachFiles(t.pid()).isEmpty()) {
            assertTrue(ajmx.isAlive() && elapsedMs(start) < 10_000, "ajmx must start the attach handshake");
            Thread.sleep(1);
        }
    }

    private static void signal(String name, TargetJvm t) throws Exception {
        signal(name, t.pidArg());
    }

    private static void signal(String name, String pid) throws Exception {
        assertEquals(0, new ProcessBuilder("kill", "-" + name, pid).start().waitFor());
    }

    private static List<Path> attachFiles(long pid) {
        String name = ".attach_pid" + pid;
        return Stream.of(Path.of(name), Path.of(System.getProperty("java.io.tmpdir"), name), Path.of("/tmp", name))
                .filter(Files::exists).toList();
    }

    @Test
    void nonJvmProcessThatHandlesSigquitIsNotSignaled() throws Exception {
        try (SigquitTrap daemon = SigquitTrap.start()) {
            long start = System.nanoTime();
            Ajmx.run("--pid", daemon.pidArg(), "ping").assertError("ATTACH_NOT_SUPPORTED", 3);
            assertTrue(elapsedMs(start) < 2000, "must not wait for the Attach API");
            daemon.assertNotSignaled();
        }
    }

    @Test
    void perfDataLeftByADeadJvmDoesNotMakeItsPidLookLikeAJvm() throws Exception {
        try (TargetJvm jvm = TargetJvm.start()) {
            Path perfData = perfDataOf(jvm.pid());
            Thread.sleep(2500);
            try (SigquitTrap daemon = SigquitTrap.start()) {
                assertCopyIsNotEvidence(perfData, daemon);
            }
        }
    }

    @Test
    void onLinuxPerfDataWhoseTimesAgreeWithAProcessIsNotEvidenceUnlessTheProcessMapsIt() throws Exception {
        assumeTrue(Files.isReadable(Path.of("/proc/self/maps")), "needs /proc/<pid>/maps");
        try (SigquitTrap daemon = SigquitTrap.start(); TargetJvm jvm = TargetJvm.start()) {
            assertCopyIsNotEvidence(perfDataOf(jvm.pid()), daemon);
        }
    }

    private static void assertCopyIsNotEvidence(Path perfData, SigquitTrap daemon) throws Exception {
        Path leftOver = Files.copy(perfData, perfData.resolveSibling(daemon.pidArg()));
        try {
            Ajmx.run("--pid", daemon.pidArg(), "ping").assertError("ATTACH_NOT_SUPPORTED", 3);
            daemon.assertNotSignaled();
            assertTrue(list(Ajmx.run("ps").result().get("items")).stream()
                    .noneMatch(i -> number(map(i).get("pid")) == daemon.pid()));
        } finally {
            Files.deleteIfExists(leftOver);
        }
    }

    private static Path perfDataOf(long pid) throws IOException {
        try (Stream<Path> dirs = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("hsperfdata_"))
                    .map(d -> d.resolve(Long.toString(pid))).filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private record SigquitTrap(Process process) implements AutoCloseable {
        static SigquitTrap start() throws IOException {
            return new SigquitTrap(new ProcessBuilder("bash", "-c", "trap 'exit 42' QUIT; sleep 120 & wait").start());
        }

        long pid() {
            return process.pid();
        }

        String pidArg() {
            return Long.toString(process.pid());
        }

        void assertNotSignaled() throws InterruptedException {
            assertFalse(process.waitFor(1, TimeUnit.SECONDS), "the process must not receive SIGQUIT");
        }

        @Override
        public void close() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void processOfAnotherUserIsPermissionDenied() throws Exception {
        String self = System.getProperty("user.name");
        String initOwner = ProcessHandle.of(1).flatMap(p -> p.info().user()).orElse(null);
        assumeTrue(!Objects.equals(self, "root") && Objects.equals(initOwner, "root"), "needs a non-root user and a root-owned PID 1");
        long start = System.nanoTime();
        Ajmx.Result r = Ajmx.run("--pid", "1", "ping").assertError("ATTACH_PERMISSION_DENIED", 3);
        assertEquals("root", r.details().get("processUser"));
        assertTrue(elapsedMs(start) < 2000, "must not wait for the Attach API");
    }

    @Test
    void pidAndUrlAreMutuallyExclusive() throws Exception {
        Ajmx.run("--pid", "1", "--url", "service:jmx:rmi:///jndi/rmi://localhost:1/jmxrmi", "ping")
                .assertError("INVALID_ARGUMENT", 2);
    }

    @Test
    void commandsThatNeedATargetSaySo() throws Exception {
        Ajmx.run("ping").assertError("INVALID_ARGUMENT", 2);
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
