package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RemoteIT {
  private static final String CACHE = "ajmxtest:type=Cache";
  private static final String PASSWORD = "s3cret-pw";

  @TempDir static Path jmxremote;

  private static TargetJvm open;
  private static TargetJvm secured;
  private static String openUrl;
  private static String securedUrl;

  @BeforeAll
  static void start() throws Exception {
    int openPort = freePort();
    open =
        TargetJvm.start(remoteArgs(openPort, "-Dcom.sun.management.jmxremote.authenticate=false"));
    openUrl = url(openPort);

    Path password = secret(jmxremote.resolve("jmx.password"), "agent " + PASSWORD + "\n");
    Path access = secret(jmxremote.resolve("jmx.access"), "agent readwrite\n");
    int securedPort = freePort();
    secured =
        TargetJvm.start(
            remoteArgs(
                securedPort,
                "-Dcom.sun.management.jmxremote.authenticate=true",
                "-Dcom.sun.management.jmxremote.password.file=" + password,
                "-Dcom.sun.management.jmxremote.access.file=" + access));
    securedUrl = url(securedPort);
  }

  @AfterAll
  static void stop() throws Exception {
    open.close();
    secured.close();
  }

  @Test
  void remoteAndLocalGiveTheSameResults() throws Exception {
    assertEquals(Map.of("connected", true), Ajmx.run("--url", openUrl, "ping").result());
    assertEquals(
        Ajmx.run("--pid", open.pidArg(), "describe", CACHE).result(),
        Ajmx.run("--url", openUrl, "describe", CACHE).result());
    assertEquals(
        42L,
        number(
            map(Ajmx.run("--url", openUrl, "read", CACHE, "Size").result().get("attributes"))
                .get("Size")));
  }

  @Test
  void credentialsFromEnvironment() throws Exception {
    Ajmx.Result r =
        Ajmx.run(
            Map.of("JMX_USERNAME", "agent", "JMX_PASSWORD", PASSWORD),
            "",
            "--url",
            securedUrl,
            "ping");
    assertEquals(0, r.exitCode(), r.stdout());
  }

  @Test
  void credentialsFromStdin() throws Exception {
    String stdin = "{\"username\":\"agent\",\"password\":\"" + PASSWORD + "\"}";
    Ajmx.Result r = Ajmx.run(Map.of(), stdin, "--url", securedUrl, "--credentials-stdin", "ping");
    assertEquals(0, r.exitCode(), r.stdout());
  }

  @Test
  void wrongPasswordIsAuthFailedAndNotRetryable() throws Exception {
    Ajmx.Result r =
        Ajmx.run(
                Map.of("JMX_USERNAME", "agent", "JMX_PASSWORD", "wrong"),
                "",
                "--url",
                securedUrl,
                "ping")
            .assertError("AUTH_FAILED", 3);
    assertEquals(false, r.error().get("retryable"));
  }

  @Test
  void missingCredentialsIsAuthFailed() throws Exception {
    Ajmx.run("--url", securedUrl, "ping").assertError("AUTH_FAILED", 3);
  }

  @Test
  void passwordNeverAppearsInOutput() throws Exception {
    Ajmx.Result r =
        Ajmx.run(
                Map.of("JMX_USERNAME", "agent", "JMX_PASSWORD", PASSWORD),
                "",
                "--debug",
                "--url",
                securedUrl,
                "read",
                "ajmxtest:type=Nope",
                "X")
            .assertError("MBEAN_NOT_FOUND", 4);
    assertFalse(r.stdout().contains(PASSWORD));
    assertFalse(r.stderr().contains(PASSWORD));
  }

  @Test
  void refusedConnectionIsRetryableConnectionFailure() throws Exception {
    Ajmx.Result r = Ajmx.run("--url", url(freePort()), "ping").assertError("CONNECTION_FAILED", 3);
    assertEquals(true, r.error().get("retryable"));
  }

  @Test
  void urlNamingSomethingOtherThanAJmxServerIsNotRetryable() throws Exception {
    int port = freePort();
    Registry registry = LocateRegistry.createRegistry(port);
    try {
      registry.bind("jmxrmi", registry);
      Ajmx.Result r = Ajmx.run("--url", url(port), "ping").assertError("INTERNAL_ERROR", 1);
      assertEquals(false, r.error().get("retryable"));
      assertEquals(url(port), r.details().get("url"));
      assertEquals("java.lang.ClassCastException", r.details().get("exceptionClass"));
    } finally {
      UnicastRemoteObject.unexportObject(registry, true);
    }
  }

  @Test
  void unresponsiveServerTimesOut() throws Exception {
    try (ServerSocket blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      @SuppressWarnings("ModifiedButNotUsed")
      List<Socket> held = new ArrayList<>();
      Thread acceptor =
          new Thread(
              new FutureTask<>(
                  () -> {
                    while (true) {
                      held.add(blackHole.accept());
                    }
                  }));
      acceptor.setDaemon(true);
      acceptor.start();
      long start = System.nanoTime();
      Ajmx.Result r =
          Ajmx.run("--timeout", "1s", "--url", url(blackHole.getLocalPort()), "ping")
              .assertError("CONNECTION_TIMEOUT", 3);
      assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
      assertEquals(1000L, number(r.details().get("timeoutMs")));
    }
  }

  @Test
  void malformedOrUnsupportedUrlIsInvalidArgument() throws Exception {
    Ajmx.run("--url", "not-a-jmx-url", "ping").assertError("INVALID_ARGUMENT", 2);
    Ajmx.run("--url", "service:jmx:nope://localhost:1", "ping").assertError("INVALID_ARGUMENT", 2);
  }

  @Test
  void credentialsStdinIsOnlyForUrls() throws Exception {
    Ajmx.run(Map.of(), "{}", "--pid", open.pidArg(), "--credentials-stdin", "ping")
        .assertError("INVALID_ARGUMENT", 2);
  }

  private static String[] remoteArgs(int port, String... extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port,
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Djava.rmi.server.hostname=127.0.0.1"));
    args.addAll(List.of(extra));
    return args.toArray(String[]::new);
  }

  private static String url(int port) {
    return "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi";
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private static Path secret(Path file, String content) throws IOException {
    Files.writeString(file, content);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    return file;
  }
}
