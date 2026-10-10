package io.github.yagipass.ajmx.connection;

import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import org.jspecify.annotations.Nullable;

public final class JmxSession implements AutoCloseable {
  private static final long CLOSE_TIMEOUT_MS = 1000;

  @FunctionalInterface
  public interface JmxCall<T extends @Nullable Object> {
    T run(MBeanServerConnection connection) throws Exception;
  }

  private final JMXConnector connector;
  private final MBeanServerConnection connection;
  private final long timeoutMs;

  JmxSession(JMXConnector connector, long timeoutMs) throws IOException {
    this.connector = connector;
    this.connection = connector.getMBeanServerConnection();
    this.timeoutMs = timeoutMs;
  }

  public <T extends @Nullable Object> T call(Map<String, ?> context, JmxCall<T> task) {
    return start(context, task).get();
  }

  public <T extends @Nullable Object> Supplier<T> start(Map<String, ?> context, JmxCall<T> task) {
    return Timeouts.start(
        () -> task.run(connection),
        timeoutMs,
        () ->
            new AjmxException(ErrorCode.TIMEOUT, "JMX operation timed out")
                .with("timeoutMs", timeoutMs),
        e -> JmxErrors.translate(e, context));
  }

  @Override
  public void close() {
    Timeouts.runQuietly(connector::close, CLOSE_TIMEOUT_MS);
  }
}
