---
description: Run many ajmx requests over one JMX connection with batch, one JSON object per line on stdin, and read the result of each.
---

# batch

`batch` runs requests from stdin, one JSON object per line, over one connection. Use it instead of
several commands, which each open their own connection.

::: terminal

```sh
echo '{"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage"]}
{"id": "gc", "op": "search", "pattern": "java.lang:type=GarbageCollector,*"}' | ajmx --pid 12345 batch
```

:::

## Requests

Each line is `{"id", "op", ...}`. A request may not span lines, and blank lines are skipped.

| `op` | Fields |
|---|---|
| `ping` | none |
| `search` | `pattern` (optional) |
| `describe` | `mbean` |
| `read` | `mbean`, `attributes` (array) |
| `write` | `mbean`, `attribute`, `value` |
| `invoke` | `mbean`, `operation`, `args` (optional array), `signature` (optional array) |

`id` is any JSON value you choose. ajmx copies it to the result, so you can match each result to
its request.

## Results

`result.items` has one item per request, in order. Each is `{id, ok, result}` or
`{id, ok, error}`, where `result` and `error` are the same as for the command alone.

::: terminal

```sh
echo '{"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage"]}
{"id": "gc", "op": "search", "pattern": "java.lang:type=GarbageCollector,*"}
{"id": "bad", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["heapMemoryUsage"]}' | ajmx --pid 12345 batch | jq .result
```

```json
{
  "items": [
    {
      "id": "heap",
      "ok": true,
      "result": {
        "mbean": "java.lang:type=Memory",
        "attributes": {
          "HeapMemoryUsage": {
            "committed": 268435456,
            "init": 268435456,
            "max": 268435456,
            "used": 33915416
          }
        }
      }
    },
    {
      "id": "gc",
      "ok": true,
      "result": {
        "items": [
          "java.lang:name=G1 Concurrent GC,type=GarbageCollector",
          "java.lang:name=G1 Old Generation,type=GarbageCollector",
          "java.lang:name=G1 Young Generation,type=GarbageCollector"
        ],
        "returned": 3,
        "truncated": false
      }
    },
    {
      "id": "bad",
      "ok": false,
      "error": {
        "code": "ATTRIBUTE_NOT_FOUND",
        "message": "Attribute was not found",
        "retryable": false,
        "details": {
          "mbean": "java.lang:type=Memory",
          "attribute": "heapMemoryUsage"
        }
      }
    }
  ]
}
```

:::

A failed request does not stop the rest. The batch itself still has `"ok": true`, and ajmx exits
with 7 because the result is partial.

When the result is larger than `--max-bytes`, ajmx replaces the largest items with
`{"id", "ok", "truncated": true}`, and drops trailing items only if that is not enough. Their
requests still ran.

## Validation

ajmx checks the whole batch before it runs any request. A mistake in any line rejects the batch
with `INVALID_ARGUMENT`, and `details.line` points at the line. Here line 2 has an `op` that does
not exist.

```json
{"schemaVersion":1,"ok":false,"error":{"code":"INVALID_ARGUMENT","message":"Unknown op","retryable":false,"details":{"line":2,"id":"gc","op":"lookup","allowed":["ping","search","describe","read","write","invoke"]}},"durationMs":10}
```

## When a write or invoke fails

JMX cannot cancel a call. Once a `write` or `invoke` times out or loses its connection, it may
still be running in the JVM. So the later `write` and `invoke` requests fail with `SKIPPED`
without running. The other requests still run.

## Credentials

`batch` reads its requests from stdin, so it cannot take `--credentials-stdin`. Use
`JMX_USERNAME` and `JMX_PASSWORD`. See [Remote JVMs](./connect/remote.md#credentials).
