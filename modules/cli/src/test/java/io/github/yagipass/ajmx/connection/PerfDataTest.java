package io.github.yagipass.ajmx.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class PerfDataTest {
    private static final ProcessHandle SELF = ProcessHandle.current();
    private static final String USER = SELF.info().user().orElseThrow();

    @TempDir
    Path tmp;

    @Test
    void readsTheFileTheJvmWritesForItself() throws Exception {
        write(own(), jvm("app.Main --port 1"));
        MappedByteBuffer mapping = map(own());
        try {
            PerfData perf = PerfDataFiles.load(SELF, tmp).orElseThrow();
            assertEquals("app.Main --port 1", perf.javaCommand());
            assertTrue(perf.attachable());
            assertEquals(Set.of(SELF.pid()), PerfDataFiles.pids(tmp));
        } finally {
            Reference.reachabilityFence(mapping);
        }
    }

    @Test
    void onLinuxTheFileTheProcessMapsIsItsOwnEvenAfterTheClockSteps() throws Exception {
        assumeTrue(Files.isReadable(Path.of("/proc/self/maps")), "needs /proc/<pid>/maps");
        long beforeStart = SELF.info().startInstant().orElseThrow().toEpochMilli() - TimeUnit.HOURS.toMillis(1);
        write(own(), perfData(Map.of("sun.rt.javaCommand", "app.Main", "sun.rt.createVmBeginTime", beforeStart)));
        MappedByteBuffer mapping = map(own());
        try {
            assertTrue(PerfDataFiles.load(SELF, tmp).isPresent());
        } finally {
            Reference.reachabilityFence(mapping);
        }
    }

    @Test
    void onLinuxAFileTheProcessDoesNotMapIsNotItsOwnEvenWhenTheTimesAgree() throws Exception {
        assumeTrue(Files.isReadable(Path.of("/proc/self/maps")), "needs /proc/<pid>/maps");
        write(own(), jvm("app.Main"));
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void mappingIsEvidenceOnlyForTheFileItselfNotForAnInodeNumberReusedOnAnotherFilesystem() {
        String name = "/" + SELF.pid();
        assertTrue(PerfDataFiles.isMappingOf("7f00-7f08 r--s 00000000 00:2a 1234   /tmp/hsperfdata_a b" + name, "1234",
                name));
        assertFalse(PerfDataFiles.isMappingOf("7f00-7f08 r-xp 00000000 fd:01 1234   /usr/lib/libc.so.6", "1234", name));
        assertFalse(PerfDataFiles.isMappingOf("7f00-7f08 r--s 00000000 00:2a 1234   /tmp/hsperfdata_a" + name + " (deleted)",
                "1234", name));
        assertFalse(PerfDataFiles.isMappingOf("7f00-7f08 rw-p 00000000 00:00 0 ", "0", name));
    }

    @Test
    void fileLeftByAJvmThatStartedBeforeTheProcessIsIgnored() throws Exception {
        long beforeStart = SELF.info().startInstant().orElseThrow().toEpochMilli() - TimeUnit.HOURS.toMillis(1);
        write(own(), perfData(Map.of("sun.rt.javaCommand", "dead.Jvm", "sun.rt.createVmBeginTime", beforeStart)));
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void fileWithoutTheJvmCreationTimeCannotBeTiedToTheProcess() throws Exception {
        write(own(), perfData(Map.of("sun.rt.javaCommand", "app.Main")));
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void fileInAnotherUsersDirectoryIsNotEvidenceForThisProcess() throws Exception {
        write(tmp.resolve("hsperfdata_someone-else").resolve(Long.toString(SELF.pid())), jvm("forged"));
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void fileNotOwnedByTheProcessOwnerIsNotEvidence() throws Exception {
        ProcessHandle init = ProcessHandle.of(1).orElseThrow();
        String initOwner = init.info().user().orElse(null);
        assumeTrue(initOwner != null && !initOwner.equals(USER), "needs a PID 1 owned by another user");
        write(tmp.resolve("hsperfdata_" + initOwner).resolve("1"), jvm("forged"));
        assertTrue(PerfDataFiles.load(init, tmp).isEmpty());
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fifoIsNotOpenedSoReadingCannotBlock() throws Exception {
        Path fifo = own();
        Files.createDirectories(fifo.getParent());
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor());
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void symlinkIsNotFollowed() throws Exception {
        Path real = tmp.resolve("elsewhere");
        Files.write(real, jvm("app.Main"));
        Files.createDirectories(own().getParent());
        Files.createSymbolicLink(own(), real);
        assertTrue(PerfDataFiles.load(SELF, tmp).isEmpty());
    }

    @Test
    void offsetsPointingOutsideTheFileAreIgnoredInsteadOfThrowing() throws Exception {
        byte[] valid = perfData(Map.of("sun.rt.javaCommand", "app.Main", "sun.rt.createVmBeginTime", 1L));
        for (int[] patch : new int[][] { { 24, 0x7FFFFFF0 }, { 24, -8 }, { 32, 0x7FFFFFFF }, { 32 + 4, -1000 },
                { 32 + 4, 0x7FFFFFF0 }, { 32 + 8, 0x7FFFFFFF }, { 32 + 16, -1000 }, { 32 + 16, 0x7FFFFFF0 } }) {
            byte[] crafted = valid.clone();
            ByteBuffer.wrap(crafted).putInt(patch[0], patch[1]);
            assertNotNull(PerfData.parse(crafted), () -> "offset " + patch[0] + " = " + patch[1]);
        }
    }

    private static byte[] jvm(String javaCommand) {
        return perfData(Map.of("sun.rt.javaCommand", javaCommand, "sun.rt.jvmCapabilities", "1000",
                "sun.rt.createVmBeginTime", System.currentTimeMillis()));
    }

    private Path own() {
        return tmp.resolve("hsperfdata_" + USER).resolve(Long.toString(SELF.pid()));
    }

    private static MappedByteBuffer map(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
    }

    private static void write(Path file, byte[] content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static byte[] perfData(Map<String, Object> values) {
        Map<String, Object> ordered = new LinkedHashMap<>(values);
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> e : ordered.entrySet()) {
            byte[] name = pad((e.getKey() + "\0").getBytes(StandardCharsets.UTF_8));
            byte[] data = e.getValue() instanceof Long l ? ByteBuffer.allocate(8).putLong(l).array()
                    : pad((e.getValue() + "\0").getBytes(StandardCharsets.UTF_8));
            int vectorLength = e.getValue() instanceof Long ? 0 : data.length;
            ByteBuffer entry = ByteBuffer.allocate(20 + name.length + data.length);
            entry.putInt(entry.capacity()).putInt(20).putInt(vectorLength)
                    .put((byte) (e.getValue() instanceof Long ? 'J' : 'B')).put((byte) 0).put((byte) 0).put((byte) 0)
                    .putInt(20 + name.length).put(name).put(data);
            entries.writeBytes(entry.array());
        }
        ByteBuffer file = ByteBuffer.allocate(32 + entries.size());
        file.putInt(0xcafec0c0).put((byte) 0).put((byte) 2).put((byte) 0).put((byte) 1)
                .putInt(file.capacity()).putInt(0).putLong(0).putInt(32).putInt(ordered.size())
                .put(entries.toByteArray());
        return file.array();
    }

    private static byte[] pad(byte[] b) {
        return Arrays.copyOf(b, (b.length + 7) / 8 * 8);
    }
}
