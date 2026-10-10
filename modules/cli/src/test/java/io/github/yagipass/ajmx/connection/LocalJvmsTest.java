package io.github.yagipass.ajmx.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class LocalJvmsTest {

  @Test
  void mainClassLaunch() {
    String main = LocalJvms.mainClass("org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.2.0");
    assertEquals("org.gradle.launcher.daemon.bootstrap.GradleDaemon", main);
    assertEquals("GradleDaemon", LocalJvms.displayName(main));
  }

  @Test
  void jarLaunch() {
    String main = LocalJvms.mainClass("/opt/app/service.jar --port 8080");
    assertEquals("/opt/app/service.jar", main);
    assertEquals("service.jar", LocalJvms.displayName(main));
  }

  @Test
  void moduleLaunch() {
    assertEquals(
        "Main", LocalJvms.displayName(LocalJvms.mainClass("com.example.app/com.example.Main")));
  }

  @Test
  void unknownCommandIsNull() {
    assertNull(LocalJvms.mainClass(""));
    assertNull(LocalJvms.mainClass(null));
    assertNull(LocalJvms.displayName(null));
  }
}
