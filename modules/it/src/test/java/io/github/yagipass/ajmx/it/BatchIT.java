package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.list;
import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class BatchIT {
  private static TargetJvm target;

  @BeforeAll
  static void start() throws Exception {
    target = TargetJvm.start("-Xss256m");
  }

  @AfterAll
  static void stop() throws Exception {
    target.close();
  }

  private static Ajmx.Result batch(String stdin, String... options) throws Exception {
    List<String> args = new ArrayList<>(List.of("--pid", target.pidArg()));
    args.addAll(List.of(options));
    args.add("batch");
    return Ajmx.run(Map.of(), stdin, args.toArray(String[]::new));
  }

  @Test
  void failedRequestsDoNotStopTheOthers() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage"]}
                {"id": "missing", "op": "read", "mbean": "ajmxtest:type=Nope", "attributes": ["X"]}
                {"id": "nope", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "nope"}
                {"id": "size", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Size"]}
                """);
    assertEquals(7, r.exitCode(), "partial failure");
    List<Object> items = list(r.result().get("items"));
    assertEquals(
        List.of("heap", "missing", "nope", "size"),
        items.stream().map(i -> map(i).get("id")).toList());
    assertEquals(true, map(items.get(0)).get("ok"));
    assertEquals("MBEAN_NOT_FOUND", code(items.get(1)));
    assertEquals("OPERATION_NOT_FOUND", code(items.get(2)));
    assertEquals(
        42L, number(map(map(map(items.get(3)).get("result")).get("attributes")).get("Size")));
  }

  @Test
  void debugPrintsTheStackTraceOfEachPartialFailureAfterItsPathInTheOutput() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "partial", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Size", "Nope"]}
                {"id": "missing", "op": "describe", "mbean": "ajmxtest:type=Nope"}
                """,
            "--debug");
    assertEquals(7, r.exitCode(), r.stdout());
    String err = r.stderr();
    int attribute = err.indexOf("ajmx: .result.items[0].result.errors[\"Nope\"]\n");
    int request = err.indexOf("ajmx: .result.items[1].error\n");
    assertTrue(attribute >= 0 && request > attribute, err);
    assertTrue(err.substring(attribute, request).contains("AttributeNotFoundException"), err);
    assertTrue(err.substring(request).contains("InstanceNotFoundException"), err);
  }

  @Test
  void misspelledFieldRejectsTheWholeBatchSoNothingRuns() throws Exception {
    long before = readCache("ClearCount");
    Ajmx.Result r =
        batch(
                """
                {"id": "clear", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "clear"}
                {"id": "typo", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "invalidate", "arguments": ["k"]}
                """)
            .assertError("INVALID_ARGUMENT", 2);
    assertEquals("arguments", r.details().get("field"));
    assertEquals(2L, number(r.details().get("line")));
    assertEquals(before, readCache("ClearCount"), "no request may run when the batch is invalid");
  }

  @Test
  void errorWhileReceivingOneValueKeepsTheResultsOfExecutedMutations() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "before", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                {"id": "clear", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "clear"}
                {"id": "deep", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["DeepValue"]}
                {"id": "after", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                """);
    assertEquals(7, r.exitCode(), r.stdout());
    List<Object> items = list(r.result().get("items"));
    assertEquals(true, map(items.get(1)).get("ok"), r.stdout());
    assertEquals(false, map(items.get(2)).get("ok"), r.stdout());
    assertEquals(clearCount(items.get(0)) + 1, clearCount(items.get(3)));
  }

  @Test
  @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void valueSharingItsPartsIsReplacedLikeAnyOversizedItemAndTheOtherResultsAreKept()
      throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "shared", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Shared"]}
                {"id": "size", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Size"]}
                """,
            "--max-bytes",
            "10000");
    assertEquals(7, r.exitCode(), r.stdout());
    List<Object> items = list(r.result().get("items"));
    assertEquals(Map.of("id", "shared", "ok", true, "truncated", true), items.get(0));
    assertEquals(
        42L, number(map(map(map(items.get(1)).get("result")).get("attributes")).get("Size")));
  }

  private static long clearCount(Object item) {
    return number(map(map(map(item).get("result")).get("attributes")).get("ClearCount"));
  }

  @Test
  void writesAndInvokesInABatchChangeTheTargetSoALaterReadSeesTheChange() throws Exception {
    long connections = readCache("MaxConnections");
    long clears = readCache("ClearCount");
    String stdin =
        String.format(
            Locale.ROOT,
            """
                {"id": "w", "op": "write", "mbean": "ajmxtest:type=Cache", "attribute": "MaxConnections", "value": %d}
                {"id": "c", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "clear"}
                {"id": "i", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "put", "args": ["k", 1], "signature": ["java.lang.String", "int"]}
                {"id": "r", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["MaxConnections", "ClearCount"]}
                """,
            connections + 1);

    Ajmx.Result r = batch(stdin);
    assertEquals(0, r.exitCode(), r.stdout());
    List<Object> items = list(r.result().get("items"));
    assertEquals("int:k=1", map(map(items.get(2)).get("result")).get("returnValue"));
    Map<String, Object> changed = map(map(map(items.get(3)).get("result")).get("attributes"));
    assertEquals(connections + 1, number(changed.get("MaxConnections")));
    assertEquals(clears + 1, number(changed.get("ClearCount")));
  }

  @Test
  void anImmutableMBeanInfoIsFetchedOnceForAllTheRequestsOnItsMBean() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "before", "op": "read", "mbean": "ajmxtest:type=ArrayEcho", "attributes": ["InfoCalls"]}
                {"id": "d", "op": "describe", "mbean": "ajmxtest:type=ArrayEcho"}
                {"id": "i1", "op": "invoke", "mbean": "ajmxtest:type=ArrayEcho", "operation": "classOf", "args": [[1]], "signature": ["[I"]}
                {"id": "i2", "op": "invoke", "mbean": "ajmxtest:type=ArrayEcho", "operation": "classOf", "args": [["s"]], "signature": ["[Ljava.lang.String;"]}
                {"id": "after", "op": "read", "mbean": "ajmxtest:type=ArrayEcho", "attributes": ["InfoCalls"]}
                """);
    assertEquals(0, r.exitCode(), r.stdout());
    List<Object> items = list(r.result().get("items"));
    assertEquals(1, infoCalls(items.get(4)) - infoCalls(items.get(0)), r.stdout());
  }

  private static long infoCalls(Object item) {
    return number(map(map(map(item).get("result")).get("attributes")).get("InfoCalls"));
  }

  @Test
  void anMBeanInfoThatMayChangeIsFetchedAgainSoARequestSeesWhatAnEarlierOneChanged()
      throws Exception {
    long generation =
        number(
            map(Ajmx.run("--pid", target.pidArg(), "read", "ajmxtest:type=Evolving", "Generation")
                    .result()
                    .get("attributes"))
                .get("Generation"));
    Ajmx.Result r =
        batch(
            String.format(
                Locale.ROOT,
                """
                {"id": "evolve", "op": "invoke", "mbean": "ajmxtest:type=Evolving", "operation": "evolve"}
                {"id": "next", "op": "invoke", "mbean": "ajmxtest:type=Evolving", "operation": "generation%d"}
                """,
                generation + 1));
    assertEquals(0, r.exitCode(), r.stdout());
    assertEquals(
        generation + 1,
        number(map(map(list(r.result().get("items")).get(1)).get("result")).get("returnValue")));
  }

  @Test
  void timeoutOfOneRequestLeavesTheConnectionUsable() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "slow", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "sleep", "args": [20000]}
                {"id": "after", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Size"]}
                """,
            "--timeout",
            "1s");
    List<Object> items = list(r.result().get("items"));
    assertEquals("TIMEOUT", code(items.get(0)));
    assertEquals(true, map(items.get(1)).get("ok"), r.stdout());
  }

  @Test
  void mutationsAfterATimedOutMutationAreNotExecutedWhileItMayStillRun() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "before", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                {"id": "slow", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "sleep", "args": [20000]}
                {"id": "clear", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "clear"}
                {"id": "after", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                """,
            "--timeout",
            "1s");
    List<Object> items = list(r.result().get("items"));
    assertEquals(false, map(map(items.get(1)).get("error")).get("retryable"), r.stdout());
    Map<String, Object> skipped = map(map(items.get(2)).get("error"));
    assertEquals("SKIPPED", skipped.get("code"));
    assertEquals(true, skipped.get("retryable"));
    assertEquals(1L, number(map(skipped.get("details")).get("stillRunningIndex")));
    assertEquals(clearCount(items.get(0)), clearCount(items.get(3)), "clear must not run");
  }

  @Test
  void mutationsAfterAMutationThatLostItsConnectionAreNotExecutedWhileItMayStillRun()
      throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "before", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                {"id": "lost", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "touch", "args": ["unsendable"]}
                {"id": "clear", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "clear"}
                {"id": "after", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["ClearCount"]}
                """);
    List<Object> items = list(r.result().get("items"));
    Map<String, Object> lost = map(map(items.get(1)).get("error"));
    assertEquals("CONNECTION_FAILED", lost.get("code"), r.stdout());
    assertEquals(false, lost.get("retryable"));
    assertEquals("unknown", map(lost.get("details")).get("executed"));
    Map<String, Object> skipped = map(map(items.get(2)).get("error"));
    assertEquals("SKIPPED", skipped.get("code"), r.stdout());
    assertEquals(1L, number(map(skipped.get("details")).get("stillRunningIndex")));
    assertEquals(
        clearCount(items.get(0)) + 1, clearCount(items.get(3)), "only touch may run, not clear");
  }

  @Test
  void oversizedResultsAreOmittedButEveryRequestKeepsItsOutcome() throws Exception {
    Ajmx.Result r =
        batch(
            """
                {"id": "big", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["LargeText"]}
                {"id": "verbose", "op": "invoke", "mbean": "ajmxtest:type=Cache", "operation": "failVerbose"}
                {"id": "small", "op": "read", "mbean": "ajmxtest:type=Cache", "attributes": ["Size"]}
                """,
            "--max-bytes",
            "1024");
    assertEquals(7, r.exitCode(), r.stdout());
    assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 1024);
    assertEquals(true, r.result().get("truncated"));
    List<Object> items = list(r.result().get("items"));
    assertEquals(Map.of("id", "big", "ok", true, "truncated", true), map(items.get(0)));
    Map<String, Object> verbose = map(map(items.get(1)).get("error"));
    assertEquals("REMOTE_EXCEPTION", verbose.get("code"));
    assertEquals(Map.of("truncated", true), verbose.get("details"));
    assertEquals(
        42L, number(map(map(map(items.get(2)).get("result")).get("attributes")).get("Size")));
  }

  @Test
  void aFailedMutationShrunkToFitMaxBytesStillSaysItMayHaveRun() throws Exception {
    List<String> requests =
        new ArrayList<>(
            List.of(
                "{\"id\": \"lost\", \"op\": \"invoke\", \"mbean\": \"ajmxtest:type=Cache\", \"operation\": \"touch\", \"args\": [\"unsendable\"]}"));
    for (int i = 0; i < 10; i++) {
      requests.add(
          "{\"id\": \"r"
              + i
              + "\", \"op\": \"read\", \"mbean\": \"ajmxtest:type=Cache\", \"attributes\": [\"Size\"]}");
    }
    Ajmx.Result r = batch(String.join("\n", requests), "--max-bytes", "512");
    assertEquals(7, r.exitCode(), r.stdout());
    assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 512);
    Map<String, Object> lost = map(map(list(r.result().get("items")).get(0)).get("error"));
    assertEquals("CONNECTION_FAILED", lost.get("code"), r.stdout());
    assertEquals(Map.of("executed", "unknown", "truncated", true), lost.get("details"));
  }

  @Test
  void manySmallMutationsUnderATinyMaxBytesStillShowThatTheyRan() throws Exception {
    List<String> requests = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      requests.add(
          "{\"id\": \"c"
              + i
              + "\", \"op\": \"invoke\", \"mbean\": \"ajmxtest:type=Cache\", \"operation\": \"clear\"}");
    }
    long before = readCache("ClearCount");
    Ajmx.Result r = batch(String.join("\n", requests), "--max-bytes", "512");
    assertEquals(7, r.exitCode(), r.stdout());
    assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 512);
    assertEquals(true, r.result().get("truncated"));
    List<Object> items = list(r.result().get("items"));
    assertFalse(items.isEmpty(), r.stdout());
    for (int i = 0; i < items.size(); i++) {
      assertEquals(
          "c" + i,
          map(items.get(i)).get("id"),
          "items keep their order and only trailing ones are dropped");
      assertEquals(true, map(items.get(i)).get("ok"), r.stdout());
    }
    assertEquals(
        before + 12, readCache("ClearCount"), "every request ran even if its result was omitted");
  }

  private static long readCache(String attribute) throws Exception {
    return number(
        map(Ajmx.run("--pid", target.pidArg(), "read", "ajmxtest:type=Cache", attribute)
                .result()
                .get("attributes"))
            .get(attribute));
  }

  @Test
  void malformedBatchIsRejectedBeforeConnecting() throws Exception {
    for (String stdin :
        List.of(
            "not json",
            "[{\"op\": \"ping\"}]",
            "{\"requests\": [{\"op\": \"ping\"}]}",
            "{\"op\": \"frobnicate\"}")) {
      Ajmx.run(Map.of(), stdin, "--pid", Ajmx.NOT_A_JVM, "batch")
          .assertError("INVALID_ARGUMENT", 2);
    }
  }

  @Test
  void emptyBatchSucceedsWithNoItems() throws Exception {
    Ajmx.Result empty = batch("");
    assertEquals(0, empty.exitCode());
    assertEquals(List.of(), empty.result().get("items"));
  }

  private static @Nullable String code(Object item) {
    assertEquals(false, map(item).get("ok"), () -> "expected failure but got " + item);
    return (String) map(map(item).get("error")).get("code");
  }
}
