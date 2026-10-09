---
description: The JSON document every ajmx command prints, how JMX values map to JSON, and how --limit and --max-bytes mark what they cut.
---

# Output

Every command prints one JSON document on one line to stdout, whether it succeeds or fails. An
agent reads what happened from it, with no text or stack trace to interpret.

## Result and error

```json
{"schemaVersion":1,"ok":true,"result":{...},"durationMs":12}
{"schemaVersion":1,"ok":false,"error":{"code":"MBEAN_NOT_FOUND","message":"MBean was not found","retryable":false,"details":{"mbean":"com.example:type=Nope"}},"durationMs":3}
```

| Field | Value |
|---|---|
| `schemaVersion` | `1` |
| `ok` | `true` with `result`, `false` with `error` |
| `result` | Depends on the command. See [Commands](./commands.md) |
| `error.code` | What went wrong. See [Errors and exit codes](./errors.md) |
| `error.message` | A message for people. Do not match on it |
| `error.retryable` | `true` if the same command may succeed later |
| `error.details` | What the error is about, and often how to fix it |
| `durationMs` | How long the command took |

`error.details` often names the fix. `allowed` lists the valid commands, ops or fields, and
`candidates` lists the overloads of an operation.

## JMX values in JSON

| JMX value | JSON |
|---|---|
| CompositeData | Object with sorted keys |
| TabularData | Array of rows, sorted |
| Enum | Its name |
| ObjectName | String |
| NaN, Infinity | String |
| `byte[]` | `{"$base64": "..."}`, standard base64 |
| Other types | `{"$type", "$string"}` |

The order is fixed. MBean names, map keys, set elements and the rows of a table are sorted, so the
same state prints the same output.

## Partial results

`ok` is `true`, but part of the result is missing, and ajmx exits with 7, when:

- `read` of several attributes could not read some of them. They are in `errors{}`.
- a request of a `batch` failed.
- something was cut, and the result has `"truncated": true`.

## Bounded output

`--limit` and `--max-bytes` cap the output, so no command fills an agent's context with more than
you allow. A cut is marked, so the agent can tell part of the data from all of it.

- A collection longer than `--limit` becomes `{"$truncated": true, "$total": n, "$items": [...]}`.
- A value that contains itself, or is nested more than 32 levels deep, is cut there as
  `{"$truncated": true, "$type": "<class>"}`.
- A `byte[]` is never cut by `--limit`. Its base64 takes 4⌈n/3⌉ bytes for n bytes.

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=Runtime SystemProperties --limit 2 | jq -c .result
```

```json
{"mbean":"java.lang:type=Runtime","attributes":{"SystemProperties":{"$truncated":true,"$total":55,"$items":[{"key":"apple.awt.application.name","value":"OrderService"},{"key":"file.encoding","value":"UTF-8"}]}},"truncated":true}
```

:::

When the output would be larger than `--max-bytes`:

- `ps` and `search` drop trailing items, and set `truncated`.
- `batch` replaces its largest items with `{"id", "ok", "truncated": true}`, and drops trailing
  items only if that is not enough. Their requests still ran.
- Other commands fail with `OUTPUT_TRUNCATED`. `details.outputBytes` is the size they needed.

```json
{"schemaVersion":1,"ok":false,"error":{"code":"OUTPUT_TRUNCATED","message":"Output exceeds --max-bytes","retryable":false,"details":{"maxBytes":512,"outputBytes":3822}},"durationMs":11}
```

::: warning
`invoke` has already run when its return value is cut or does not fit. Running it again with larger
limits runs the operation again. An operation that consumes what it returns, such as reading a
stream, does not return the lost part. Set the limits before such an invoke.
:::
