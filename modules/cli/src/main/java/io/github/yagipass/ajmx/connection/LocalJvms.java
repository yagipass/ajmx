package io.github.yagipass.ajmx.connection;

import com.google.errorprone.annotations.Var;
import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class LocalJvms {
  public record Jvm(long pid, @Nullable String mainClass, @Nullable String displayName) {}

  private LocalJvms() {}

  public static List<Jvm> list(long timeoutMs) {
    return Timeouts.call(
        LocalJvms::scan,
        timeoutMs,
        () ->
            new AjmxException(ErrorCode.TIMEOUT, "Timed out reading the perf data of local JVMs")
                .with("timeoutMs", timeoutMs),
        AjmxException::wrap);
  }

  private static List<Jvm> scan() {
    List<Jvm> jvms = new ArrayList<>();
    for (long pid : PerfDataFiles.pids()) {
      Optional<PerfData> perf =
          ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(PerfDataFiles::load);
      if (perf.isEmpty()) {
        continue;
      }
      String mainClass = mainClass(perf.get().javaCommand());
      jvms.add(new Jvm(pid, mainClass, displayName(mainClass)));
    }
    return jvms;
  }

  static @Nullable String mainClass(@Nullable String javaCommand) {
    if (javaCommand == null || javaCommand.isBlank()) {
      return null;
    }
    String command = javaCommand.strip();
    int space = command.indexOf(' ');
    return space < 0 ? command : command.substring(0, space);
  }

  static @Nullable String displayName(@Nullable String mainClass) {
    if (mainClass == null) {
      return null;
    }
    @Var String name = mainClass;
    int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (sep >= 0) {
      name = name.substring(sep + 1);
    }
    if (name.endsWith(".jar")) {
      return name;
    }
    int dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot + 1) : name;
  }
}
