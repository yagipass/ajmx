package io.github.yagipass.ajmx.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

final class PerfDataFiles {
    private static final String DIR_PREFIX = "hsperfdata_";
    private static final int MAX_SIZE = 1 << 20;
    private static final long START_TIME_TOLERANCE_MS = 1000;

    private PerfDataFiles() {
    }

    static Set<Long> pids() {
        return pids(tmpdir());
    }

    static Set<Long> pids(Path tmp) {
        Set<Long> pids = new TreeSet<>();
        for (Path dir : list(tmp, DIR_PREFIX + "*")) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            for (Path file : list(dir, "*")) {
                Long pid = pidOf(file);
                if (pid != null && Files.isReadable(file)) {
                    pids.add(pid);
                }
            }
        }
        return pids;
    }

    static Optional<PerfData> load(ProcessHandle process) {
        return load(process, tmpdir());
    }

    static Optional<PerfData> load(ProcessHandle process, Path tmp) {
        String user = process.info().user().orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        Path file = tmp.resolve(DIR_PREFIX + user).resolve(Long.toString(process.pid()));
        try {
            PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !attributes.owner().getName().equals(user)) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_SIZE);
            }
            PerfData perf = PerfData.parse(bytes);
            return belongsTo(perf, process, file) ? Optional.of(perf) : Optional.empty();
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    private static boolean belongsTo(PerfData perf, ProcessHandle process, Path file) {
        return mappedBy(process, file).orElseGet(() -> createdSinceStartOf(perf, process));
    }

    private static boolean createdSinceStartOf(PerfData perf, ProcessHandle process) {
        OptionalLong created = perf.vmCreationTimeMs();
        return created.isPresent() && process.info().startInstant()
                .map(start -> created.getAsLong() >= start.toEpochMilli() - START_TIME_TOLERANCE_MS)
                .orElse(false);
    }

    private static Optional<Boolean> mappedBy(ProcessHandle process, Path file) {
        try (Stream<String> maps = Files.lines(Path.of("/proc", Long.toString(process.pid()), "maps"), StandardCharsets.ISO_8859_1)) {
            String inode = Long.toUnsignedString((Long) Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS));
            String name = "/" + file.getFileName();
            return Optional.of(maps.anyMatch(line -> isMappingOf(line, inode, name)));
        } catch (IOException | UncheckedIOException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    static boolean isMappingOf(String mapsLine, String inode, String name) {
        String[] fields = mapsLine.strip().split("\\s+", 6);
        return fields.length == 6 && fields[4].equals(inode) && fields[5].endsWith(name);
    }

    private static Long pidOf(Path file) {
        try {
            return Long.parseLong(file.getFileName().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> list(Path dir, String glob) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, glob)) {
            List<Path> paths = new ArrayList<>();
            entries.forEach(paths::add);
            return paths;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path tmpdir() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }
}
