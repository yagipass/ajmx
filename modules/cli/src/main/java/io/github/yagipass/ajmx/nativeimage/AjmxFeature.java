package io.github.yagipass.ajmx.nativeimage;

import com.google.errorprone.annotations.Var;
import com.sun.tools.attach.AttachNotSupportedException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.rmi.RMIConnection;
import javax.management.remote.rmi.RMIServer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeJNIAccess;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeSerialization;

public final class AjmxFeature implements Feature {
  private static final Set<String> FULLY_REGISTERED_MODULES =
      Set.of(
          "java.base",
          "java.management",
          "java.management.rmi",
          "java.rmi",
          "java.naming",
          "java.sql",
          "java.logging",
          "jdk.management");
  private static final Set<String> API_REGISTERED_JDK_MODULES =
      Set.of("jdk.management.jfr", "jdk.jfr");
  private static final Set<String> NATIVE_LIBRARY_MODULES =
      Set.of("java.desktop", "java.datatransfer", "java.sql.rowset", "java.security.jgss");
  private static final int ARRAY_DIMENSIONS = 3;

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
    for (String module : modules(jrt)) {
      Path root = jrt.getPath("/modules/" + module);
      boolean apiOnly = !FULLY_REGISTERED_MODULES.contains(module);
      try (Stream<Path> files = Files.walk(root)) {
        files.forEach(file -> register(root, file, apiOnly));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot scan module " + module, e);
      }
    }
    for (Class<?> c :
        new Class<?>[] {
          boolean.class,
          byte.class,
          char.class,
          short.class,
          int.class,
          long.class,
          float.class,
          double.class,
          Object.class,
          CompositeData.class,
          TabularData.class
        }) {
      registerArrays(c);
    }

    for (Class<?> remote : new Class<?>[] {RMIServer.class, RMIConnection.class}) {
      RuntimeProxyCreation.register(remote);
      RuntimeSerialization.registerProxyClass(remote);
    }

    try {
      RuntimeJNIAccess.register(AttachNotSupportedException.class);
      RuntimeJNIAccess.register(AttachNotSupportedException.class.getConstructor(String.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> modules(FileSystem jrt) {
    try (Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
      return modules
          .map(m -> m.getFileName().toString())
          .filter(
              m ->
                  FULLY_REGISTERED_MODULES.contains(m)
                      || API_REGISTERED_JDK_MODULES.contains(m)
                      || (m.startsWith("java.") && !NATIVE_LIBRARY_MODULES.contains(m)))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot list the JDK modules", e);
    }
  }

  private static void register(Path root, Path file, boolean apiOnly) {
    String path = root.relativize(file).toString();
    if (!path.endsWith(".class")
        || path.endsWith("module-info.class")
        || path.endsWith("package-info.class")) {
      return;
    }
    String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
    Class<?> c;
    try {
      c = Class.forName(className, false, ClassLoader.getSystemClassLoader());
    } catch (Throwable t) {
      return;
    }
    if (c.isAnonymousClass()
        || c.isHidden()
        || (apiOnly && !c.getModule().isExported(c.getPackageName()))) {
      return;
    }
    if (!c.isInterface() && Serializable.class.isAssignableFrom(c)) {
      RuntimeSerialization.register(c);
      registerArrays(c);
    }
    if (declaresLambdaDeserializer(c)) {
      RuntimeSerialization.registerLambdaCapturingClass(c);
    }
  }

  private static void registerArrays(Class<?> component) {
    @Var Class<?> array = component;
    for (int i = 0; i < ARRAY_DIMENSIONS; i++) {
      array = array.arrayType();
      RuntimeSerialization.register(array);
    }
  }

  private static boolean declaresLambdaDeserializer(Class<?> c) {
    try {
      return c.getDeclaredMethod("$deserializeLambda$", SerializedLambda.class) != null;
    } catch (NoSuchMethodException | LinkageError e) {
      return false;
    }
  }
}
