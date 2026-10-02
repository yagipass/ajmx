package io.github.yagipass.ajmx.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.errorprone.annotations.Var;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.AttachOperationFailedException;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class LocalConnectorTest {

    private static final String NO_RESPONSE = "Unable to open socket file /tmp/.java_pid1: target process 1 doesn't respond within 4500ms or HotSpot VM not loaded";

    private static ErrorCode classify(Exception e) {
        return LocalConnector.classifyAttachFailure(5000, e, true).code();
    }

    @Test
    void linuxOtherUserIsPermissionDenied() {
        assertEquals(ErrorCode.ATTACH_PERMISSION_DENIED, classify(new IOException(
                "well-known file /tmp/.java_pid182 is not secure: file should be owned by the current user (which is 1001) but is owned by 1000")));
    }

    @Test
    void linuxMissingProcessIsNotFound() {
        assertEquals(ErrorCode.PROCESS_NOT_FOUND, classify(new IOException("non existent JVM pid: 999999")));
    }

    @Test
    void macOsTargetNotReadyForTheHandshakeIsNotAttachable() {
        assertEquals(ErrorCode.ATTACH_NOT_SUPPORTED, classify(new AttachNotSupportedException(
                "pid: 18535, state is not ready to participate in attach handshake!")));
    }

    @Test
    void jvmKnownFromItsPerfDataThatDoesNotRespondInTimeIsATimeoutWhicheverSideGaveUp() {
        AjmxException e = LocalConnector.classifyAttachFailure(5000, new AttachNotSupportedException(NO_RESPONSE), true);
        assertEquals(ErrorCode.CONNECTION_TIMEOUT, e.code());
        assertTrue(e.retryable());
        assertEquals(5000L, e.details().get("timeoutMs"));
    }

    @Test
    void processWithoutPerfDataThatDoesNotRespondIsNotRetryableBecauseEachRetryOnlySignalsItAgain() {
        AjmxException e = LocalConnector.classifyAttachFailure(5000, new AttachNotSupportedException(NO_RESPONSE), false);
        assertEquals(ErrorCode.ATTACH_NOT_SUPPORTED, e.code());
        assertFalse(e.retryable());
        assertEquals(5000L, e.details().get("timeoutMs"));
    }

    @Test
    void javaLauncherIsRecognizedEvenAfterItsFileWasReplacedUnderTheRunningJvm() {
        assertTrue(LocalConnector.isJavaLauncher("/usr/lib/jvm/java-21/bin/java"));
        assertTrue(LocalConnector.isJavaLauncher("/usr/lib/jvm/java-21/bin/java (deleted)"));
        assertFalse(LocalConnector.isJavaLauncher("/usr/bin/bash"));
        assertFalse(LocalConnector.isJavaLauncher("/usr/bin/bash (deleted)"));
        assertFalse(LocalConnector.isJavaLauncher("/opt/jdk/bin/javac"));
        assertFalse(LocalConnector.isJavaLauncher("/"));
    }

    @Test
    void agentTheJvmRefusesToStartIsNotRetryableAndSaysWhy() {
        String reason = "java.lang.module.FindException: Module jdk.management.agent not found";
        AjmxException e = LocalConnector.agentUnavailable(new AttachOperationFailedException(reason));
        assertEquals(ErrorCode.LOCAL_JMX_UNAVAILABLE, e.code());
        assertFalse(e.retryable());
        assertEquals(reason, e.details().get("reason"));
    }

    @Test
    void agentThatCouldNotBeReachedIsRetryable() {
        AjmxException e = LocalConnector.agentUnavailable(new IOException("Broken pipe"));
        assertEquals(ErrorCode.LOCAL_JMX_UNAVAILABLE, e.code());
        assertTrue(e.retryable());
        assertEquals("Broken pipe", e.details().get("reason"));
    }

    @Test
    void jdkGivesUpWithinTheBudgetSoItRemovesItsAttachFileItself() {
        for (long budget : new long[] { 100, 299, 300, 2900, 9900, 60_000 }) {
            long timeout = LocalConnector.attachTimeout(budget);
            @Var long spent = 0;
            @Var long delay = 0;
            do {
                delay += 100;
                spent += delay;
            } while (spent <= timeout);
            assertTrue(spent <= budget, budget + ": the JDK waits " + spent + "ms");
            assertTrue(spent + delay + 100 > budget, budget + ": the JDK could wait one more step");
        }
    }

    @Test
    void everyFailureToConnectNamesThePidSoACallerHandlingSeveralJvmsKnowsWhichOneFailed() {
        AjmxException e = assertThrows(AjmxException.class, () -> LocalConnector.connect(1, 5000));
        assertEquals(1L, e.details().get("pid"), e.details().toString());
    }
}
