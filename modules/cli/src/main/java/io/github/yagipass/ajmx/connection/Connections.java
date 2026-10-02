package io.github.yagipass.ajmx.connection;

import java.io.IOException;
import java.util.Map;

import javax.management.remote.JMXConnector;

public final class Connections {
    private Connections() {
    }

    public static JmxSession open(Target target, long timeoutMs) {
        JMXConnector connector = switch (target) {
            case Target.Local(long pid) -> LocalConnector.connect(pid, timeoutMs);
            case Target.Remote(String url, Credentials credentials) -> RemoteConnector.connect(url, credentials, timeoutMs);
        };
        try {
            return new JmxSession(connector, timeoutMs);
        } catch (IOException e) {
            throw JmxErrors.translate(e, context(target));
        }
    }

    private static Map<String, Object> context(Target target) {
        return switch (target) {
            case Target.Local local -> Map.of("pid", local.pid());
            case Target.Remote remote -> Map.of("url", remote.url());
        };
    }
}
