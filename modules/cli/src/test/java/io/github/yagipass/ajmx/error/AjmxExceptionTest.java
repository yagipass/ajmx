package io.github.yagipass.ajmx.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class AjmxExceptionTest {

  @Test
  void changeThatMayHaveBeenAppliedIsNeverRetryableEvenWhenItsCodeUsuallyIs() {
    AjmxException timedOut = new AjmxException(ErrorCode.TIMEOUT, "timed out");
    assertTrue(timedOut.retryable());

    timedOut.withExecution(Execution.UNKNOWN);
    assertFalse(timedOut.retryable(), "retrying could apply the change twice");
    Map<String, Object> json = timedOut.toJson();
    assertEquals(false, json.get("retryable"));
    assertEquals(
        "unknown", ((Map<?, ?>) Objects.requireNonNull(json.get("details"))).get("executed"));
  }

  @Test
  void onlyAChangeThatWasCutOffMayStillBeRunningInTheTarget() {
    assertTrue(
        new AjmxException(ErrorCode.TIMEOUT, "timed out")
            .withExecution(Execution.UNKNOWN)
            .mayStillRun());
    assertFalse(
        new AjmxException(ErrorCode.UNSUPPORTED_TYPE, "unreadable reply")
            .withExecution(Execution.UNKNOWN)
            .mayStillRun(),
        "the reply arrived, so the call has returned");
    assertFalse(
        new AjmxException(ErrorCode.TIMEOUT, "lookup timed out").mayStillRun(), "nothing was sent");
  }
}
