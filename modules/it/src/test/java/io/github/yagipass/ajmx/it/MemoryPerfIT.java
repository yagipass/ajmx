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
  private static final int LARGE_OUTPUT_BYTES = 7_500_000;

  private record Limits(double hugeTextMib, double sharedMib, double largeOutputMib) {}

  private static final Limits LIMITS =
      Ajmx.macOs()
          ? new Limits(1.2 * 245.9, 1.5 * 66.7, 1.05 * 87.9)
          : new Limits(1.2 * 265.7, 1.5 * 86.6, 1.05 * 107.4);

  private static TargetJvm target;

  @BeforeAll
  static void start() throws Exception {
    target = TargetJvm.start();
  }

  @AfterAll
  static void stop() {
    target.close();
  }

  private static Ajmx.Footprint peakRss(String... args) throws Exception {
    assumeTrue(Ajmx.nativeMode(), "measures the native binary; run :it:perfTest with -Pnative");
    List<String> all = new ArrayList<>(List.of("--pid", target.pidArg()));
    all.addAll(List.of(args));
    List<Ajmx.Footprint> runs = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      runs.add(Ajmx.runWithPeakRss(all.toArray(String[]::new)));
    }
    List<Long> peaks = runs.stream().map(Ajmx.Footprint::peakRssBytes).toList();
    return runs.get(peaks.indexOf(Ajmx.median(peaks)));
  }

  private static void assertPeakUnder(double limitMib, Ajmx.Footprint f, String what) {
    double mib = f.peakRssBytes() / MIB;
    String summary =
        String.format(Locale.ROOT, "%s: peak RSS %.1f MiB, limit %.1f MiB", what, mib, limitMib);
    System.out.println(summary);
    assertTrue(mib < limitMib, summary);
  }

  @Test
  void aHugeStringIsCutWithoutKeepingExtraCopiesOfIt() throws Exception {
    Ajmx.Footprint f = peakRss("read", CACHE, "HugeText");
    f.result().assertError("OUTPUT_TRUNCATED", 7);
    assertPeakUnder(LIMITS.hugeTextMib(), f, "read of a 100,000,000-character String");
  }

  @Test
  void aValueSharingItsPartsStopsGrowingOnceItExceedsMaxBytes() throws Exception {
    Ajmx.Footprint f = peakRss("read", CACHE, "Shared");
    f.result().assertError("OUTPUT_TRUNCATED", 7);
    assertPeakUnder(LIMITS.sharedMib(), f, "read of a value sharing its parts");
  }

  @Test
  void aLargeOutputTakesOnlyAFewTimesItsSizeInMemory() throws Exception {
    Ajmx.Footprint f =
        peakRss(
            "--max-bytes",
            "20000000",
            "invoke",
            CACHE,
            "bytes",
            "--args",
            "[" + LARGE_OUTPUT_BYTES + "]");
    assertEquals(0, f.result().exitCode(), f.result().stderr());
    assertPeakUnder(
        LIMITS.largeOutputMib(), f, "printing " + f.result().stdout().length() + " bytes");
  }
}
