package io.github.yagipass.ajmx.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.WriteAbortedException;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.rmi.ConnectException;
import java.rmi.ConnectIOException;
import java.rmi.ServerException;
import java.rmi.UnmarshalException;
import java.util.List;
import java.util.Map;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.net.ssl.SSLHandshakeException;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;
import org.junit.jupiter.api.Test;

final class JmxErrorsTest {

  @Test
  void notFoundFailures() {
    assertEquals(ErrorCode.MBEAN_NOT_FOUND, map(new InstanceNotFoundException("d:k=v")).code());
    assertEquals(ErrorCode.ATTRIBUTE_NOT_FOUND, map(new AttributeNotFoundException("X")).code());
    assertEquals(
        ErrorCode.OPERATION_NOT_FOUND,
        map(new ReflectionException(new NoSuchMethodException("op()"))).code());
  }

  @Test
  void exceptionThrownByTheMBeanKeepsItsClassAndMessage() {
    AjmxException e = map(new RuntimeMBeanException(new IllegalStateException("bad state")));
    assertEquals(ErrorCode.REMOTE_EXCEPTION, e.code());
    assertEquals("java.lang.IllegalStateException", e.details().get("exceptionClass"));
    assertEquals("bad state", e.details().get("exceptionMessage"));
    assertEquals(ErrorCode.REMOTE_EXCEPTION, map(new MBeanException(new Exception("x"))).code());
  }

  @Test
  void typeOnlyKnownToTheTargetIsUnsupportedWithItsClassName() {
    UnmarshalException cnfe =
        new UnmarshalException(
            "error unmarshalling return",
            new ClassNotFoundException(
                "com.example.Foo (no security manager: RMI class loader disabled)"));
    AjmxException e = map(cnfe);
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code());
    assertEquals("com.example.Foo", e.details().get("class"));

    UnmarshalException incompatible =
        new UnmarshalException(
            "x", new InvalidClassException("java.lang.Character", "local class incompatible"));
    assertEquals("java.lang.Character", map(incompatible).details().get("class"));
  }

  @Test
  void valueTheTargetCannotSerializeIsUnsupportedNotARetryableConnectionFailure() {
    AjmxException e =
        map(
            new UnmarshalException(
                "error unmarshalling return",
                new WriteAbortedException(
                    "writing aborted", new NotSerializableException("com.example.Handle"))));
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code());
    assertEquals("com.example.Handle", e.details().get("class"));
    assertEquals(false, e.code().retryable());

    IOException aborted =
        new WriteAbortedException("writing aborted", new IOException("custom writeObject failed"));
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, map(new UnmarshalException("x", aborted)).code());
  }

  @Test
  void argumentTheTargetCannotDeserializeIsUnsupported() {
    ServerException rejected =
        new ServerException(
            "RemoteException occurred in server thread",
            new UnmarshalException(
                "error unmarshalling arguments", new ClassNotFoundException("com.example.Arg")));
    AjmxException e = map(rejected);
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code());
    assertEquals("com.example.Arg", e.details().get("class"));
  }

  @Test
  void valueBeyondTheDeserializationLimitsIsUnsupportedAndNamesTheLimits() {
    AjmxException e =
        map(
            new UnmarshalException(
                "error unmarshalling return",
                new InvalidClassException("filter status: REJECTED")));
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code());
    assertEquals(false, e.retryable());
    assertTrue(e.details().containsKey("maxDepth"), e.details().toString());
    assertTrue(e.details().containsKey("maxArrayLength"), e.details().toString());
  }

  @Test
  void argumentRejectedByTheTargetsOwnFilterIsNotBlamedOnTheLimitsOfAjmx() {
    AjmxException e =
        map(
            new ServerException(
                "RemoteException occurred in server thread",
                new UnmarshalException(
                    "error unmarshalling arguments",
                    new InvalidClassException("filter status: REJECTED"))));
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, e.code());
    assertFalse(e.details().containsKey("maxDepth"), e.details().toString());
  }

  @Test
  void metadataMissingFromTheNativeBinaryIsUnsupportedNotAnInternalError() {
    MissingReflectionRegistrationError missing =
        new MissingReflectionRegistrationError(
            "Cannot reflectively invoke method",
            Method.class,
            Map.Entry.class,
            "$deserializeLambda$",
            new Class<?>[] {SerializedLambda.class});
    assertEquals(ErrorCode.UNSUPPORTED_TYPE, map(missing).code());
    assertEquals(
        ErrorCode.UNSUPPORTED_TYPE,
        map(new UnmarshalException("error unmarshalling return", new IOException(missing))).code());
  }

  @Test
  void circularCauseChainDoesNotHang() {
    IOException a = new IOException("a");
    IOException b = new IOException("b", a);
    a.initCause(b);
    assertEquals(ErrorCode.CONNECTION_FAILED, map(a).code());
  }

  @Test
  void rejectedValueAndConnectionLoss() {
    assertEquals(
        ErrorCode.TYPE_CONVERSION_FAILED, map(new InvalidAttributeValueException("bad")).code());
    AjmxException lost = map(new ConnectException("refused"));
    assertEquals(ErrorCode.CONNECTION_FAILED, lost.code());
    assertEquals(true, lost.code().retryable());
    assertEquals(
        ErrorCode.CONNECTION_FAILED,
        map(new UnmarshalException("x", new IOException("reset"))).code());
    assertEquals(ErrorCode.AUTH_FAILED, map(new SecurityException("Access denied!")).code());
    assertEquals(
        ErrorCode.AUTH_FAILED,
        map(new IOException("x", new SecurityException("Authentication failed!"))).code());
  }

  @Test
  void tlsFailureIsNotRetryableAndNotMistakenForWrongCredentials() {
    AjmxException untrusted =
        map(
            new IOException(
                "Failed to retrieve RMIServer stub",
                new ConnectIOException(
                    "error during JRMP connection establishment",
                    new SSLHandshakeException("PKIX path building failed"))));
    assertEquals(ErrorCode.TLS_FAILED, untrusted.code());
    assertEquals(false, untrusted.retryable());
    assertEquals("PKIX path building failed", untrusted.details().get("reason"));

    AjmxException plaintextStub =
        map(
            new SecurityException(
                "Expecting a javax.rmi.ssl.SslRMIClientSocketFactory RMI client socket factory in"
                    + " stub!"));
    assertEquals(ErrorCode.TLS_FAILED, plaintextStub.code());
    assertEquals(false, plaintextStub.retryable());
  }

  @Test
  void contextIsCopiedIntoDetails() {
    assertEquals("d:k=v", map(new InstanceNotFoundException()).details().get("mbean"));
  }

  @Test
  void errorAjmxRaisedItselfDuringTheCallStillNamesWhatTheCallWasAbout() {
    AjmxException raised =
        new AjmxException(ErrorCode.PROCESS_NOT_FOUND, "Process was not found")
            .with("reason", "gone");
    AjmxException e = JmxErrors.translate(raised, Map.of("pid", 42L));
    assertEquals(
        List.of("pid", "reason"),
        List.copyOf(e.details().keySet()),
        "the context comes first, as for JMX failures");
    assertEquals(42L, e.details().get("pid"));
    assertEquals("gone", e.details().get("reason"));
  }

  @Test
  void ourOwnBugsAreNotDisguisedAsRemoteFailures() {
    assertEquals(ErrorCode.INTERNAL_ERROR, map(new NullPointerException()).code());
  }

  @Test
  void unexpectedFailureIsNotRetryableBecauseRetryingGivesTheSameResult() {
    AjmxException e =
        JmxErrors.translate(
            new ClassCastException("RegistryImpl_Stub cannot be cast to RMIServer"),
            Map.of("url", "u"));
    assertEquals(ErrorCode.INTERNAL_ERROR, e.code());
    assertEquals(false, e.retryable());
    assertEquals("u", e.details().get("url"));
  }

  private static AjmxException map(Throwable t) {
    return JmxErrors.translate(t, Map.of("mbean", "d:k=v"));
  }
}
