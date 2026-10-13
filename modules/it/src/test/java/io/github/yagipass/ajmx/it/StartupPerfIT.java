package io.github.yagipass.ajmx.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("perf")
final class StartupPerfIT {
  private static final int WARMUPS = 3;
  private static final int RUNS = 20;
  private static final int NEW_JVMS = 3;

  @BeforeEach
  void nativeOnly() {
    assumeTrue(Ajmx.nativeMode(), "times the native binary; run :it:perfTest with -Pnative");
  }

  private static Duration timed(String... args) throws Exception {
    Ajmx.Timed t = Ajmx.runTimed("", args);
    assertEquals(0, t.result().exitCode(), t.result().stdout());
    return t.elapsed();
  }

  private static Duration median(String label, List<Duration> times) {
    Duration median = Ajmx.median(times);
    System.out.printf(
        Locale.ROOT,
        "%s: median %.1f ms of %d runs, max %.1f ms%n",
        label,
        median.toNanos() / 1e6,
        times.size(),
        times.stream().max(Duration::compareTo).orElseThrow().toNanos() / 1e6);
    return median;
  }

  private static Duration medianAfterWarmup(String label, String... args) throws Exception {
    for (int i = 0; i < WARMUPS; i++) {
      timed(args);
    }
    List<Duration> times = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      times.add(timed(args));
    }
    return median(label, times);
  }

  private static void assertUnder(Duration limit, Duration median, String what) {
    assertTrue(
        median.compareTo(limit) < 0,
        () -> what + " took " + median.toMillis() + " ms, limit " + limit.toMillis() + " ms");
  }

  @Test
  void psIsFastEnoughToRunBeforeEveryCommand() throws Exception {
    assertUnder(Duration.ofMillis(50), medianAfterWarmup("ps", "ps"), "ps");
  }

  @Test
  void laterCommandsReuseTheAgentOfTheFirstAndStayFast() throws Exception {
    try (TargetJvm target = TargetJvm.start()) {
      timed("--pid", target.pidArg(), "ping");
      Duration median = medianAfterWarmup("later --pid ping", "--pid", target.pidArg(), "ping");
      assertUnder(Duration.ofMillis(100), median, "--pid ping with the agent running");
    }
  }

  @Test
  void attachingToAJvmForTheFirstTimeTakesUnderASecond() throws Exception {
    List<Duration> times = new ArrayList<>();
    for (int i = 0; i < NEW_JVMS; i++) {
      try (TargetJvm target = TargetJvm.start()) {
        times.add(timed("--pid", target.pidArg(), "ping"));
      }
    }
    assertUnder(Duration.ofSeconds(1), median("first --pid ping", times), "first --pid ping");
  }
}
