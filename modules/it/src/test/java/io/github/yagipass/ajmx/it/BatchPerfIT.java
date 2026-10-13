package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("perf")
final class BatchPerfIT {
  private static final int SMALL = 100;
  private static final int MEDIUM = 1000;
  private static final int LARGE = 5000;
  private static final int ROUNDS = 5;
  private static final double MAX_SLOWDOWN_PER_REQUEST = 2;

  private static TargetJvm target;

  @BeforeAll
  static void start() throws Exception {
    target = TargetJvm.start();
  }

  @AfterAll
  static void stop() {
    target.close();
  }

  @Test
  void eachRequestOfALargeBatchCostsAboutAsMuchAsInASmallOne() throws Exception {
    assumeTrue(Ajmx.nativeMode(), "times the native binary; run :it:perfTest with -Pnative");
    batch(LARGE);
    List<Duration> small = new ArrayList<>();
    List<Duration> medium = new ArrayList<>();
    List<Duration> large = new ArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      small.add(batch(SMALL));
      medium.add(batch(MEDIUM));
      large.add(batch(LARGE));
    }
    double smallMs = Ajmx.median(small).toNanos() / 1e6;
    double mediumMs = Ajmx.median(medium).toNanos() / 1e6;
    double largeMs = Ajmx.median(large).toNanos() / 1e6;
    double earlier = (mediumMs - smallMs) / (MEDIUM - SMALL);
    double later = (largeMs - mediumMs) / (LARGE - MEDIUM);
    String summary =
        String.format(
            Locale.ROOT,
            "batch medians: %d requests %.1f ms, %d requests %.1f ms, %d requests %.1f ms;"
                + " each request adds %.3f ms from %d to %d and %.3f ms from %d to %d",
            SMALL,
            smallMs,
            MEDIUM,
            mediumMs,
            LARGE,
            largeMs,
            earlier,
            SMALL,
            MEDIUM,
            later,
            MEDIUM,
            LARGE);
    System.out.println(summary);
    assertTrue(later <= MAX_SLOWDOWN_PER_REQUEST * earlier, summary);
  }

  private static String requests(int n) {
    return IntStream.rangeClosed(1, n)
        .mapToObj(
            i ->
                "{\"id\": \"r"
                    + i
                    + "\", \"op\": \"read\", \"mbean\": \"java.lang:type=Memory\","
                    + " \"attributes\": [\"HeapMemoryUsage\"]}")
        .collect(Collectors.joining("\n", "", "\n"));
  }

  private static Duration batch(int n) throws Exception {
    Ajmx.Timed t =
        Ajmx.runTimed(requests(n), "--pid", target.pidArg(), "--max-bytes", "10000000", "batch");
    assertEquals(0, t.result().exitCode(), t.result().stderr());
    assertEquals(n, list(t.result().result().get("items")).size());
    return t.elapsed();
  }
}
