package io.github.yagipass.ajmx.core;

import com.google.errorprone.annotations.Var;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.error.Execution;
import io.github.yagipass.ajmx.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class Batch {
  record Entry(@Nullable Object id, Request request) {}

  private final List<Entry> entries;

  Batch(List<Entry> entries) {
    this.entries = entries;
  }

  public static Batch parse(String jsonl) {
    List<String> lines = jsonl.lines().toList();
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).isBlank()) {
        continue;
      }
      Object document;
      try {
        document = Json.parse(lines.get(i));
      } catch (Json.ParseException e) {
        throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Invalid JSON")
            .with("line", i + 1)
            .with("reason", e.getMessage());
      }
      if (!(document instanceof Map<?, ?> m)) {
        throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Each line must be a JSON object")
            .with("line", i + 1);
      }
      // Json.parse builds every JSON object with String keys.
      @SuppressWarnings("unchecked")
      Map<String, Object> request = (Map<String, Object>) m;
      try {
        entries.add(new Entry(request.get("id"), RequestParser.parse(request)));
      } catch (AjmxException e) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("line", i + 1);
        context.put("id", request.get("id"));
        throw e.withContext(context);
      }
    }
    return new Batch(entries);
  }

  public Outcome run(Function<Request, Outcome> executor) {
    List<Object> items = new ArrayList<>();
    Map<String, AjmxException> failuresByPath = new LinkedHashMap<>();
    @Var boolean partial = false;
    @Var boolean executed = false;
    @Var Integer stillRunning = null;
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", entry.id());
      try {
        if (stillRunning != null && entry.request().op().mutating()) {
          throw new AjmxException(
                  ErrorCode.SKIPPED,
                  "Not executed because an earlier write or invoke may still be running")
              .with("stillRunningIndex", stillRunning);
        }
        Outcome outcome = executor.apply(entry.request());
        item.put("ok", true);
        item.put("result", outcome.result());
        partial |= outcome.partial();
        executed |= outcome.execution() == Execution.EXECUTED;
        String path = ".items[" + i + "].result";
        outcome.failuresByPath().forEach((p, e) -> failuresByPath.put(path + p, e));
      } catch (RuntimeException | Error e) {
        AjmxException error = AjmxException.wrap(e);
        if (stillRunning == null && error.mayStillRun()) {
          stillRunning = i;
        }
        executed |= error.execution() == Execution.EXECUTED;
        item.put("ok", false);
        item.put("error", error.toJson());
        failuresByPath.put(".items[" + i + "].error", error);
        partial = true;
      }
      items.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    return Outcome.batch(
        result, partial, failuresByPath, executed ? Execution.EXECUTED : Execution.NOT_EXECUTED);
  }

  public static Map<String, Object> truncatedResult(List<Object> items) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("truncated", true);
    return result;
  }

  @SuppressWarnings(
      "unchecked") // run() builds each item, and AjmxException.toJson() its error, with String keys
  public static Map<String, Object> truncatedItem(Object item) {
    Map<String, Object> original = (Map<String, Object>) item;
    Map<String, Object> truncated = new LinkedHashMap<>();
    truncated.put("id", original.get("id"));
    truncated.put("ok", original.get("ok"));
    if (original.get("error") instanceof Map<?, ?> error) {
      Map<String, Object> reduced = new LinkedHashMap<>((Map<String, Object>) error);
      Map<String, Object> details = new LinkedHashMap<>();
      Object executed = ((Map<?, ?>) Objects.requireNonNull(error.get("details"))).get("executed");
      if (executed != null) {
        details.put("executed", executed);
      }
      details.put("truncated", true);
      reduced.put("details", details);
      truncated.put("error", reduced);
    } else {
      truncated.put("truncated", true);
    }
    return truncated;
  }
}
