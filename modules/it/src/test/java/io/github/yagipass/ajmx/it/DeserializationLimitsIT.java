package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class DeserializationLimitsIT {
  private static final String CACHE = "ajmxtest:type=Cache";
  private static final int MAX_DEPTH = 500;
  private static final int MAX_ARRAY_LENGTH = 16_777_216;

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
  void valueNestedDeeperThanTheLimitIsUnsupportedRatherThanExhaustingTheStack() throws Exception {
    Ajmx.Result atLimit = invoke("nested", MAX_DEPTH);
    assertTrue(atLimit.ok(), atLimit.stdout());

    Ajmx.Result beyond = invoke("nested", MAX_DEPTH + 1).assertError("UNSUPPORTED_TYPE", 5);
    assertEquals(false, beyond.error().get("retryable"));
    assertEquals(MAX_DEPTH, number(beyond.details().get("maxDepth")));
  }

  @Test
  void arrayLongerThanTheLimitIsRefusedRatherThanAllocated() throws Exception {
    invoke("bytes", MAX_ARRAY_LENGTH).assertError("OUTPUT_TRUNCATED", 7);

    Ajmx.Result beyond = invoke("bytes", MAX_ARRAY_LENGTH + 1).assertError("UNSUPPORTED_TYPE", 5);
    assertEquals(false, beyond.error().get("retryable"));
    assertEquals(MAX_ARRAY_LENGTH, number(beyond.details().get("maxArrayLength")));
  }

  private static Ajmx.Result invoke(String operation, int argument) throws Exception {
    return Ajmx.runInOwnProcess(
        Map.of(),
        "--pid",
        target.pidArg(),
        "invoke",
        CACHE,
        operation,
        "--args",
        "[" + argument + "]");
  }
}
