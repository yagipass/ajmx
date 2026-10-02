package io.github.yagipass.ajmx.cli;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yagipass.ajmx.json.Json;

final class Envelope {
    private static final int SCHEMA_VERSION = 1;

    private Envelope() {
    }

    static Map<String, Object> success(Map<String, Object> result, long durationMs) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("ok", true);
        envelope.put("result", result);
        envelope.put("durationMs", durationMs);
        return envelope;
    }

    static Map<String, Object> failure(Map<String, Object> error, long durationMs) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("ok", false);
        envelope.put("error", error);
        envelope.put("durationMs", durationMs);
        return envelope;
    }

    static byte[] encode(Map<String, Object> envelope) {
        return (Json.write(envelope) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static long size(Map<String, Object> envelope) {
        return Json.size(envelope) + 1;
    }
}
