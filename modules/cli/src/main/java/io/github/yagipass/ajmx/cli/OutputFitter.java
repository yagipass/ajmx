package io.github.yagipass.ajmx.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.errorprone.annotations.Var;

import io.github.yagipass.ajmx.core.Batch;
import io.github.yagipass.ajmx.core.Outcome;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.json.Json;

final class OutputFitter {
    private static final int MIN_KEPT_CHARS = 16;
    private static final String ELLIPSIS = "…";
    private static final long ELLIPSIS_BYTES = escapedSize(ELLIPSIS);

    @SuppressWarnings("ArrayRecordComponent")
    record Output(byte[] bytes, int exitCode) {
    }

    private final long durationMs;
    private final long maxBytes;

    OutputFitter(long durationMs, long maxBytes) {
        this.durationMs = durationMs;
        this.maxBytes = maxBytes;
    }

    Output success(Outcome outcome) {
        Map<String, Object> envelope = Envelope.success(outcome.result(), durationMs);
        long size = Envelope.size(envelope);
        if (size <= maxBytes) {
            return new Output(Envelope.encode(envelope), outcome.partial() ? ErrorCode.PARTIAL_EXIT_CODE : 0);
        }

        byte[] shrunk = switch (outcome.shape()) {
            case ITEMS -> dropTrailingItems((List<?>) outcome.result().get("items"));
            case BATCH -> shrinkBatch((List<?>) outcome.result().get("items"));
            case PLAIN -> null;
        };
        if (shrunk != null) {
            return new Output(shrunk, ErrorCode.PARTIAL_EXIT_CODE);
        }
        return failure(new AjmxException(ErrorCode.OUTPUT_TRUNCATED, "Output exceeds --max-bytes")
                .with("maxBytes", maxBytes).with("outputBytes", size).withExecution(outcome.execution()));
    }

    Output failure(AjmxException error) {
        return new Output(fitFailure(error.toJson()), error.code().exitCode());
    }

    Output unfittedFailure(AjmxException error) {
        return new Output(Envelope.encode(Envelope.failure(error.toJson(), durationMs)), error.code().exitCode());
    }

    @SuppressWarnings("unchecked") // AjmxException.toJson() builds details with String keys
    private byte[] fitFailure(Map<String, Object> error) {
        Map<String, Object> envelope = Envelope.failure(error, durationMs);
        if (Envelope.size(envelope) <= maxBytes) {
            return Envelope.encode(envelope);
        }
        Map<String, Object> details = new LinkedHashMap<>((Map<String, Object>) error.get("details"));
        Map<String, Object> shrunk = new LinkedHashMap<>(error);
        shrunk.put("details", details);
        details.put("truncated", true);
        while (true) {
            long size = Envelope.size(Envelope.failure(shrunk, durationMs));
            String largest = details.keySet().stream().filter(k -> !k.equals("truncated"))
                    .max(Comparator.comparingLong((String k) -> Json.size(details.get(k)))).orElse(null);
            if (size <= maxBytes || largest == null) {
                return Envelope.encode(Envelope.failure(shrunk, durationMs));
            }
            String kept = details.get(largest) instanceof String s ? shorten(s, size - maxBytes) : null;
            if (kept != null) {
                details.put(largest, kept);
            } else {
                details.remove(largest);
            }
        }
    }

    private static String shorten(String s, long excess) {
        @Var long removed = 0;
        @Var int end = s.length();
        while (end > 0 && removed < excess + ELLIPSIS_BYTES) {
            int start = s.offsetByCodePoints(end, -1);
            removed += escapedSize(s.substring(start, end));
            end = start;
        }
        return end >= MIN_KEPT_CHARS ? s.substring(0, end) + ELLIPSIS : null;
    }

    private static long escapedSize(String s) {
        return Json.size(s) - 2;
    }

    private byte[] dropTrailingItems(List<?> items) {
        @Var Map<String, Object> best = null;
        @Var int lo = 0;
        @Var int hi = items.size() - 1;
        while (lo <= hi) {
            int k = (lo + hi) >>> 1;
            Map<String, Object> result = Outcome.itemsResult(items.subList(0, k), true);
            if (Envelope.size(Envelope.success(result, durationMs)) <= maxBytes) {
                best = result;
                lo = k + 1;
            } else {
                hi = k - 1;
            }
        }
        return best != null ? Envelope.encode(Envelope.success(best, durationMs)) : null;
    }

    private byte[] shrinkBatch(List<?> original) {
        List<Object> items = new ArrayList<>(original);
        int n = items.size();
        long[] sizes = new long[n];
        Object[] stubs = new Object[n];
        long[] stubSizes = new long[n];
        @Var long total = Envelope.size(Envelope.success(Batch.truncatedResult(List.of()), durationMs)) + Math.max(0, n - 1);
        List<Integer> bySaving = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sizes[i] = Json.size(items.get(i));
            stubs[i] = Batch.truncatedItem(items.get(i));
            stubSizes[i] = Json.size(stubs[i]);
            total += sizes[i];
            if (stubSizes[i] < sizes[i]) {
                bySaving.add(i);
            }
        }
        bySaving.sort(Comparator.comparingLong((Integer i) -> sizes[i] - stubSizes[i]).reversed());
        for (int i : bySaving) {
            if (total <= maxBytes) {
                break;
            }
            items.set(i, stubs[i]);
            total -= sizes[i] - stubSizes[i];
            sizes[i] = stubSizes[i];
        }
        for (int last = n - 1; total > maxBytes && last >= 0; last--) {
            items.remove(last);
            total -= sizes[last] + (last > 0 ? 1 : 0);
        }
        return total <= maxBytes ? Envelope.encode(Envelope.success(Batch.truncatedResult(items), durationMs)) : null;
    }
}
