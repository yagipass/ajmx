---
name: ajmx
description: Inspect and operate running JVMs over JMX with the ajmx CLI — list local JVMs, search and describe MBeans, read attributes, write attributes and invoke operations, on local processes (by PID) or remote JMX URLs. Use this whenever the user wants to look inside a running Java process — heap and GC, threads, class loading, connection pools, caches, Kafka/Tomcat/Hikari metrics, log levels or feature flags exposed as MBeans — or mentions JMX, MBeans, jconsole, VisualVM or a service:jmx URL, even if they do not name ajmx.
license: Apache-2.0
compatibility: Requires the ajmx binary on PATH (macOS arm64, Linux x86_64 and arm64).
---

# ajmx

ajmx is a JMX client built for agents. Every command prints one JSON document to stdout.

## Start with `ajmx help`

Run `ajmx help` before the first ajmx command of a task. It prints the commands, arguments and options of the installed version as JSON; take flags from there, not from memory. This skill covers what help does not: how to explore, when to change a JVM, and how to read results and errors.

## Reading the output

```json
{"schemaVersion":1,"ok":true,"result":{...},"durationMs":12}
{"schemaVersion":1,"ok":false,"error":{"code":"MBEAN_NOT_FOUND","message":"...","retryable":false,"details":{...}},"durationMs":3}
```

Decide on `ok` and `error.code`, not the message. `error.details` usually names the fix: `allowed` lists the valid commands, ops or fields, and `candidates` lists operation overloads.

A result can be partial while `ok` is `true` (exit code 7):

- `read` of several attributes puts the failed ones in `errors{}` next to `attributes{}`.
- `"truncated": true` means something was cut. A long collection becomes `{"$truncated": true, "$total": n, "$items": [...]}`. For `search` and `read`, a narrower pattern or fewer attributes is usually better than a higher `--limit` or `--max-bytes`.
- In a `batch`, the largest items lose their result first to fit `--max-bytes`.

Say so when you report a partial result, so the user does not take a truncated list for the whole one.

An `invoke` has already run when its return value is cut. Running it again with higher limits runs it a second time, and an operation that consumes what it returns, such as reading a stream or draining a queue, does not return the cut part again. So set the limits before such an invoke:

- When you pipe a stream to a file, its chunks never reach your context, so pass a large `--max-bytes` such as `67108864` rather than guessing the chunk size. A `byte[]` prints as base64, about 4/3 its size.
- If a chunk is lost anyway, `details.outputBytes` shows the size it needed. Do not read on: the file would have a gap, and some MBeans discard data once it has been read. Close the stream and tell the user. Read again from the saved size only if the MBean can open a stream at an offset.

A `byte[]` is `{"$base64": "..."}` in standard base64 and is never cut by `--limit`. Save it with `jq -r '.result.returnValue["$base64"] // empty' | base64 -d` under `set -o pipefail`: without `// empty`, a `null` return decodes to 3 bytes, and without `pipefail`, a failed ajmx looks like an empty chunk. In a stream loop, stop on `.result.returnValue == null`, not on empty output, because a chunk can be empty. As input, `write` and `--args` take a `byte[]` as numbers from -128 to 127, not as `{"$base64": ...}`.

## Exploring a JVM

1. Pick the target. For a local JVM, run `ajmx ps` and pass `--pid` with the PID whose `mainClass` or `displayName` matches. For a remote one, pass `--url service:jmx:rmi:///jndi/rmi://<host>:<port>/jmxrmi`. If several JVMs match and the user's words do not single one out, ask.
2. `search` with a narrow, quoted pattern such as `'java.lang:type=GarbageCollector,*'` or `'com.zaxxer.hikari:*'`. The default `*:*` is mostly noise.
3. `describe` the MBean before reading, writing or invoking. Names are case-sensitive, and it shows which attributes are writable and each operation's signature. Parameters named `p0`, `p1` and so on are undocumented; check the MBean's documentation or source instead of guessing, because arguments of the same type in the wrong order still run.
4. `read` several attributes of one MBean in one call.

Each command opens its own connection, so send more than a few calls as one `batch`.

Credentials for `--url` come from `JMX_USERNAME` and `JMX_PASSWORD`, or from stdin with `--credentials-stdin`, never from arguments. Ask the user to export the variables rather than paste a password into the conversation.

`--pid` starts a JMX agent in the target JVM if it has none, and the agent stays until the JVM restarts. Mention this when the target is a production process the user has not said you may touch.

## Changing a JVM

`write` and `invoke` act on a live process, and an operation can do anything its MBean implements: clear a cache, close connections, trigger a GC, shut down. So:

- Run `write` and `invoke` only for a change the user asked for or an operation that only reads (below).
- JMX cannot tell which operations change state, and `describe` shows only names and types, so judge by what you know of the operation. These only read and are cheap, so run them when the user asked for their result: `java.lang:type=Threading` `findDeadlockedThreads` and `findMonitorDeadlockedThreads`, and `com.sun.management:type=HotSpotDiagnostic` `getVMOption`.
- These only read but load the target, so confirm them first on a production JVM: `DiagnosticCommand` `gcClassHistogram` runs a full GC, `HotSpotDiagnostic` `dumpHeap` writes a file as large as the heap, and `dumpAllThreads` and `DiagnosticCommand` `threadPrint` slow down with more threads.
- Treat an operation you cannot judge as a change, and confirm it first.
- If the request does not name the exact attribute, value or operation, confirm the change before running it.
- `read` the attribute before a `write`, so you can report the old and new values and the user can revert.
- For `invoke`, check the signature in `describe` and pass the arguments as a JSON array in `--args`. If several overloads fit, nothing runs and `AMBIGUOUS_OPERATION` lists `details.candidates`; pick one with `--signature`.

## Errors and retries

- `retryable: true` means the same command may succeed later. A larger `--timeout` can help.
- `"executed": "unknown"` in the `details` of a failed `write` or `invoke` means the change may have been applied or may still be running. Do not retry. Read the state and tell the user what you know.
- `"executed": true` means the change was made although the command failed, for example because the result was too large to print. Running it again to see the result runs the operation again.
- `UNSUPPORTED_TYPE` means ajmx cannot deserialize the value's class. Retrying does not help; read a related attribute or operation instead.
- `ATTACH_PERMISSION_DENIED` means the JVM runs as another user. Tell the user instead of reaching for `sudo`.

## batch

Write one request per line, `{"id", "op", ...}`. A request may not span lines, and blank lines are skipped. The fields of each op:

| `op` | Fields |
|---|---|
| `ping` | none |
| `search` | `pattern` (optional) |
| `describe` | `mbean` |
| `read` | `mbean`, `attributes` (array) |
| `write` | `mbean`, `attribute`, `value` |
| `invoke` | `mbean`, `operation`, `args` (optional array), `signature` (optional array) |

```sh
echo '{"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage", "NonHeapMemoryUsage"]}
{"id": "gc", "op": "search", "pattern": "java.lang:type=GarbageCollector,*"}' | ajmx --pid 12345 batch
```

The whole batch is validated first, so a typo rejects it with `INVALID_ARGUMENT` and `details.line` instead of running half of it. Each of `result.items` is `{id, ok, result | error}`, and a failed request does not stop the others. But once a `write` or `invoke` times out or loses its connection, it may still be running, so the later `write` and `invoke` requests fail with `SKIPPED` without running. Reads still run.
