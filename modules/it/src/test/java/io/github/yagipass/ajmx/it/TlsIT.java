package io.github.yagipass.ajmx.it;

import static io.github.yagipass.ajmx.it.Ajmx.map;
import static io.github.yagipass.ajmx.it.Ajmx.number;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TlsIT {
  private static final String PASSWORD = "s3cret-pw";
  private static final String STORE_PASSWORD = "store-pw";
  private static final Map<String, String> AGENT =
      Map.of("JMX_USERNAME", "agent", "JMX_PASSWORD", PASSWORD);

  @TempDir static Path dir;

  private static Path ca;
  private static Path sslConfig;
  private static Path password;
  private static Path access;
  private static TargetJvm tls;
  private static TargetJvm plaintextRegistry;
  private static TargetJvm plaintextConnection;
  private static int tlsPort;
  private static int plaintextRegistryPort;
  private static int plaintextConnectionPort;

  @BeforeAll
  static void start() throws Exception {
    Path keyStore = dir.resolve("server.p12");
    ca = dir.resolve("server.pem");
    keytool(
        "-genkeypair",
        "-alias",
        "jmx",
        "-keyalg",
        "RSA",
        "-dname",
        "CN=127.0.0.1",
        "-ext",
        "san=ip:127.0.0.1",
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        keyStore.toString(),
        "-storepass",
        STORE_PASSWORD,
        "-keypass",
        STORE_PASSWORD);
    keytool(
        "-exportcert",
        "-rfc",
        "-alias",
        "jmx",
        "-storetype",
        "PKCS12",
        "-keystore",
        keyStore.toString(),
        "-storepass",
        STORE_PASSWORD,
        "-file",
        ca.toString());
    sslConfig =
        secret(
            dir.resolve("jmx.ssl"),
            "javax.net.ssl.keyStore="
                + keyStore
                + "\njavax.net.ssl.keyStorePassword="
                + STORE_PASSWORD
                + "\n");
    password = secret(dir.resolve("jmx.password"), "agent " + PASSWORD + "\n");
    access = secret(dir.resolve("jmx.access"), "agent readwrite\n");

    tlsPort = freePort();
    tls = TargetJvm.start(serverArgs(tlsPort, tlsPort, true, true));
    plaintextRegistryPort = freePort();
    plaintextRegistry = TargetJvm.start(serverArgs(plaintextRegistryPort, freePort(), true, false));
    plaintextConnectionPort = freePort();
    plaintextConnection =
        TargetJvm.start(serverArgs(plaintextConnectionPort, freePort(), false, true));
  }

  @AfterAll
  static void stop() throws Exception {
    tls.close();
    plaintextRegistry.close();
    plaintextConnection.close();
  }

  @Test
  void trustedServerTakesTheCredentialsOverTls() throws Exception {
    Ajmx.Result r =
        Ajmx.runInOwnProcess(
            AGENT,
            "--url",
            url("127.0.0.1", tlsPort),
            "--tls",
            "--tls-ca",
            ca.toString(),
            "read",
            "ajmxtest:type=Cache",
            "Size");
    assertEquals(42L, number(map(r.result().get("attributes")).get("Size")));
  }

  @Test
  void plaintextRegistryIsRefusedBecauseItsStubCouldBeSwapped() throws Exception {
    Ajmx.Result r =
        Ajmx.runInOwnProcess(
                AGENT,
                "--url",
                url("127.0.0.1", plaintextRegistryPort),
                "--tls",
                "--tls-ca",
                ca.toString(),
                "ping")
            .assertError("TLS_FAILED", 3);
    assertEquals("TLS with the JMX server failed", r.error().get("message"));
    assertEquals(false, r.error().get("retryable"));
  }

  @Test
  void plaintextConnectionIsRefusedRatherThanSendingTheCredentials() throws Exception {
    Ajmx.Result r =
        Ajmx.runInOwnProcess(
                AGENT,
                "--url",
                url("127.0.0.1", plaintextConnectionPort),
                "--tls",
                "--tls-ca",
                ca.toString(),
                "ping")
            .assertError("TLS_FAILED", 3);
    assertEquals("The JMX server does not use TLS for the connection", r.error().get("message"));
    assertEquals(false, r.error().get("retryable"));
  }

  @Test
  void selfSignedCertificateIsNotTrustedWithoutTlsCa() throws Exception {
    Ajmx.Result r =
        Ajmx.runInOwnProcess(AGENT, "--url", url("127.0.0.1", tlsPort), "--tls", "ping")
            .assertError("TLS_FAILED", 3);
    assertEquals(false, r.error().get("retryable"));
  }

  @Test
  void trustedCertificateForAnotherHostIsRefused() throws Exception {
    Ajmx.Result r =
        Ajmx.runInOwnProcess(
                AGENT,
                "--url",
                url("localhost", tlsPort),
                "--tls",
                "--tls-ca",
                ca.toString(),
                "ping")
            .assertError("TLS_FAILED", 3);
    assertEquals(false, r.error().get("retryable"));
  }

  private static String[] serverArgs(
      int registryPort, int connectionPort, boolean tlsConnection, boolean tlsRegistry) {
    return new String[] {
      "-Dcom.sun.management.jmxremote.port=" + registryPort,
      "-Dcom.sun.management.jmxremote.rmi.port=" + connectionPort,
      "-Dcom.sun.management.jmxremote.ssl=" + tlsConnection,
      "-Dcom.sun.management.jmxremote.registry.ssl=" + tlsRegistry,
      "-Dcom.sun.management.jmxremote.ssl.config.file=" + sslConfig,
      "-Dcom.sun.management.jmxremote.authenticate=true",
      "-Dcom.sun.management.jmxremote.password.file=" + password,
      "-Dcom.sun.management.jmxremote.access.file=" + access,
      "-Djava.rmi.server.hostname=127.0.0.1"
    };
  }

  private static void keytool(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(TargetJvm.javaExecutable()).resolveSibling("keytool").toString());
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
  }

  private static String url(String host, int port) {
    return "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi";
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
