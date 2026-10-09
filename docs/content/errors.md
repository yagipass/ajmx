---
description: The error codes and exit codes of ajmx, which errors are worth retrying, and what to do when a write or invoke fails after it was sent.
---

# Errors and exit codes

Handle errors by `error.code`, and try again only what is `retryable`. Exit codes group the codes
for shell scripts.

```json
{"schemaVersion":1,"ok":false,"error":{"code":"MBEAN_NOT_FOUND","message":"MBean was not found","retryable":false,"details":{"mbean":"com.example:type=Nope"}},"durationMs":12}
```

## Error codes

| Code | Meaning |
|---|---|
| `INVALID_ARGUMENT` | A command, option, argument or batch line is wrong. `details` names it |
| `TYPE_CONVERSION_FAILED` | A value for `write` or `--args` does not fit the type |
| `ATTRIBUTE_NOT_WRITABLE` | `write` to a read-only attribute |
| `CONNECTION_FAILED` | Could not connect, or the connection was lost |
| `CONNECTION_TIMEOUT` | Connecting took longer than `--timeout` |
| `AUTH_FAILED` | The JVM rejected the credentials |
| `PROCESS_NOT_FOUND` | No process has the PID given to `--pid` |
| `ATTACH_NOT_SUPPORTED` | The process is not a HotSpot JVM, or it disables attach |
| `ATTACH_PERMISSION_DENIED` | The JVM runs as another user |
| `LOCAL_JMX_UNAVAILABLE` | The JVM could not start its local JMX agent |
| `MBEAN_NOT_FOUND` | No MBean has this name |
| `ATTRIBUTE_NOT_FOUND` | The MBean has no attribute of this name |
| `OPERATION_NOT_FOUND` | The MBean has no operation of this name, or none that takes these arguments |
| `AMBIGUOUS_OPERATION` | Several overloads fit the arguments. `details.candidates` lists them |
| `REMOTE_EXCEPTION` | The MBean threw an exception |
| `UNSUPPORTED_TYPE` | ajmx cannot read or send a value of this class. See [Limitations](./limitations.md) |
| `TIMEOUT` | An operation took longer than `--timeout` |
| `SKIPPED` | A `batch` request did not run, because an earlier `write` or `invoke` may still be running |
| `OUTPUT_TRUNCATED` | The result does not fit `--max-bytes` |
| `INTERNAL_ERROR` | A bug in ajmx. Run again with `--debug` and report it |

Names of MBeans, attributes and operations are case-sensitive. Check them with `describe`.
[Troubleshooting](./troubleshooting.md) has fixes for the connection errors.

## Exit codes

| Exit | Codes |
|---|---|
| 0 | Success |
| 1 | `INTERNAL_ERROR`, or stdout could not be written |
| 2 | `INVALID_ARGUMENT`, `TYPE_CONVERSION_FAILED`, `ATTRIBUTE_NOT_WRITABLE` |
| 3 | `CONNECTION_FAILED`, `CONNECTION_TIMEOUT`, `AUTH_FAILED`, `PROCESS_NOT_FOUND`, `ATTACH_NOT_SUPPORTED`, `ATTACH_PERMISSION_DENIED`, `LOCAL_JMX_UNAVAILABLE` |
| 4 | `MBEAN_NOT_FOUND`, `ATTRIBUTE_NOT_FOUND`, `OPERATION_NOT_FOUND`, `AMBIGUOUS_OPERATION` |
| 5 | `REMOTE_EXCEPTION`, `UNSUPPORTED_TYPE` |
| 6 | `TIMEOUT`, `SKIPPED` |
| 7 | `OUTPUT_TRUNCATED`, or a [partial result](./output.md#partial-results) |

## Retrying

`retryable` is `true` when the same command may succeed later. Only `CONNECTION_FAILED`,
`CONNECTION_TIMEOUT`, `LOCAL_JMX_UNAVAILABLE`, `TIMEOUT` and `SKIPPED` can be retryable, and not
always, so decide on the field rather than the code. A larger `--timeout` can help. The other
errors fail the same way again until something changes.

## When a write or invoke fails

JMX cannot cancel a call. A `write` or `invoke` that fails after it was sent may have changed the
JVM anyway, so it is never `retryable`, and `details.executed` says what is known.

| `details.executed` | Meaning |
|---|---|
| `"unknown"` | The change may have been made, or may still be running |
| `true` | The change was made, but its result could not be printed, for example because it did not fit `--max-bytes` |

Check the state before trying again. Running it again runs the operation again.
