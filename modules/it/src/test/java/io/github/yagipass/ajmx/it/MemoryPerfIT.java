package io.github.yagipass.ajmx.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("perf")
final class MemoryPerfIT {
  private static final String CACHE = "ajmxtest:type=Cache";
  private static final int RUNS = 3;
  private static final double MIB = 1024 * 1024;
  private static final long HUGE_TEXT_CHARS = 100_000_000;
  private static final int LARGE_OUTPUT_BYTES = 7_500_000;
  private static final double MAX_BYTES_PER_HUGE_TEXT_CHAR = 2.6;
  private static final double MAX_SHARED_VALUE_MIB = 42;
  private static final double MAX_BYTES_PER_OUTPUT_BYTE = 7.6;

  private static TargetJvm target;

  @BeforeAll
  static void start() throws Exception {
    target = TargetJvm.start();
  }

  @AfterAll
  static void stop() {
    target.close();
  }

  private record Growth(Ajmx.Result result, long bytes) {}

  private static Growth growth(String... args) throws Exception {
    assumeTrue(Ajmx.nativeMode(), "measures the native binary; run :it:perfTest with -Pnative");
    List<String> all = new ArrayList<>(List.of("--pid", target.pidArg()));
    all.addAll(List.of(args));
    long baseline = medianPeakRss("--pid", target.pidArg(), "read", CACHE, "Size").peakRssBytes();
    Ajmx.Footprint measured = medianPeakRss(all.toArray(String[]::new));
    Growth g = new Growth(measured.result(), measured.peakRssBytes() - baseline);
    System.out.printf(
        Locale.ROOT,
        "%s: peak RSS %.1f MiB above `read %s Size` (%.1f MiB)%n",
        String.join(" ", args),
        g.bytes() / MIB,
        CACHE,
        baseline / MIB);
    return g;
  }

  private static Ajmx.Footprint medianPeakRss(String... args) throws Exception {
    List<Ajmx.Footprint> runs = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      runs.add(Ajmx.runWithPeakRss(args));
    }
    List<Long> peaks = runs.stream().map(Ajmx.Footprint::peakRssBytes).toList();
    long median = Ajmx.median(peaks);
    return runs.get(peaks.indexOf(median));
  }

  @Test
  void aHugeStringIsCutWithoutKeepingExtraCopiesOfIt() throws Exception {
    Growth g = growth("read", CACHE, "HugeText");
    g.result().assertError("OUTPUT_TRUNCATED", 7);
    double perChar = (double) g.bytes() / HUGE_TEXT_CHARS;
    assertTrue(
        perChar < MAX_BYTES_PER_HUGE_TEXT_CHAR,
        () ->
            String.format(
                Locale.ROOT,
                "reading a %d-character String took %.2f bytes per character, limit %.2f",
                HUGE_TEXT_CHARS,
                perChar,
                MAX_BYTES_PER_HUGE_TEXT_CHAR));
  }

  @Test
  void aValueSharingItsPartsStopsGrowingOnceItExceedsMaxBytes() throws Exception {
    Growth g = growth("read", CACHE, "Shared");
    g.result().assertError("OUTPUT_TRUNCATED", 7);
    double mib = g.bytes() / MIB;
    assertTrue(
        mib < MAX_SHARED_VALUE_MIB,
        () ->
            String.format(
                Locale.ROOT,
                "a value sharing its parts took %.1f MiB with the default --max-bytes, limit %.1f",
                mib,
                MAX_SHARED_VALUE_MIB));
  }

  @Test
  void aLargeOutputTakesOnlyAFewTimesItsSizeInMemory() throws Exception {
    Growth g =
        growth(
            "--max-bytes",
            "20000000",
            "invoke",
            CACHE,
            "bytes",
            "--args",
            "[" + LARGE_OUTPUT_BYTES + "]");
    assertEquals(0, g.result().exitCode(), g.result().stderr());
    long output = g.result().stdout().length();
    double perByte = (double) g.bytes() / output;
    assertTrue(
        perByte < MAX_BYTES_PER_OUTPUT_BYTE,
        () ->
            String.format(
                Locale.ROOT,
                "printing %d bytes took %.2f bytes of memory per output byte, limit %.2f",
                output,
                perByte,
                MAX_BYTES_PER_OUTPUT_BYTE));
  }
}
