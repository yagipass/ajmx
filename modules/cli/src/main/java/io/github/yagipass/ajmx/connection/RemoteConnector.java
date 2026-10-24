package io.github.yagipass.ajmx.connection;

import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import org.jspecify.annotations.Nullable;

final class RemoteConnector {
  private static final String REGISTRY_SOCKET_FACTORY = "com.sun.jndi.rmi.factory.socket";
  private static final String CHECK_STUB = "jmx.remote.x.check.stub";
  private static final String REGISTRY_PATH = "/jndi/rmi://";

  private RemoteConnector() {}

  @SuppressWarnings("BanJNDI")
  static JMXConnector connect(
      String url, @Nullable Credentials credentials, @Nullable Tls tls, long timeoutMs) {
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
    if (tls != null) {
      if (!serviceUrl.getProtocol().equals("rmi")
          || !serviceUrl.getURLPath().startsWith(REGISTRY_PATH)) {
        throw JmxErrors.translate(
            new AjmxException(
                ErrorCode.INVALID_ARGUMENT,
                "--tls needs a service:jmx:rmi:///jndi/rmi://<host>:<port>/<name> URL"),
            context);
      }
      List<Certificate> trusted = tls.trustedCertificates();
      if (trusted != null) {
        SSLContext.setDefault(trusting(trusted));
      }
      env.put(REGISTRY_SOCKET_FACTORY, new SslRMIClientSocketFactory());
      env.put(CHECK_STUB, "true");
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

  private static SSLContext trusting(List<Certificate> certificates) {
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      for (int i = 0; i < certificates.size(); i++) {
        store.setCertificateEntry("ca-" + i, certificates.get(i));
      }
      TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX");
      trust.init(store);
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(null, trust.getTrustManagers(), null);
      return ssl;
    } catch (GeneralSecurityException | IOException e) {
      throw AjmxException.wrap(e);
    }
  }
}
