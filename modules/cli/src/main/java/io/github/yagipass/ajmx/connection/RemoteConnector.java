package io.github.yagipass.ajmx.connection;

import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.jspecify.annotations.Nullable;

final class RemoteConnector {
  private RemoteConnector() {}

  @SuppressWarnings("BanJNDI")
  static JMXConnector connect(String url, @Nullable Credentials credentials, long timeoutMs) {
    Map<String, Object> context = Map.of("url", url);
    JMXServiceURL serviceUrl;
    try {
      serviceUrl = new JMXServiceURL(url);
    } catch (MalformedURLException e) {
      throw JmxErrors.translate(invalidUrl(e), context);
    }
    Map<String, Object> env = new HashMap<>();
    if (credentials != null) {
      env.put(
          JMXConnector.CREDENTIALS, new String[] {credentials.username(), credentials.password()});
    }
    return Timeouts.call(
        () -> JMXConnectorFactory.connect(serviceUrl, env),
        timeoutMs,
        () ->
            new AjmxException(
                    ErrorCode.CONNECTION_TIMEOUT, "Timed out connecting to the JMX server")
                .with("timeoutMs", timeoutMs),
        e ->
            JmxErrors.translate(e instanceof MalformedURLException m ? invalidUrl(m) : e, context));
  }

  private static AjmxException invalidUrl(MalformedURLException e) {
    return new AjmxException(
        ErrorCode.INVALID_ARGUMENT, "Invalid or unsupported JMX service URL", e);
  }
}
