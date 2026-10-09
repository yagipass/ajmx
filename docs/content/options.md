---
description: Every ajmx option, from choosing the target JVM to timeouts and the limits on output size.
---

# Options

| Option | Default | Description |
|---|---|---|
| `--pid <pid>` | | Local target JVM. See [Local JVMs](./connect/local.md) |
| `--url <url>` | | Remote target JVM. See [Remote JVMs](./connect/remote.md) |
| `--timeout <duration>` | `10s` | For `ps`, attach, connect and each operation |
| `--limit <n>` | `100` | Max items in a list, and max elements per array, collection, map or table |
| `--max-bytes <n>` | `262144` | Max stdout size, at least `512` |
| `--args <array>` | `[]` | `invoke` arguments, as a JSON array |
| `--signature <type,...>` | | `invoke` parameter types, to pick an overload |
| `--credentials-stdin` | | Read `{"username", "password"}` from stdin. Needs `--url` |
| `--debug` | | Print stack traces to stderr |
| `--help` | | Print the usage, like `help` |
| `--version` | | Print the version, like `version` |

Options may come before or after the command, as `--limit 10` or `--limit=10`. `--` ends the
options, so an argument after it may start with `--`.

## Target

Give `--pid` or `--url`, not both. `ps`, `help` and `version` need neither.

## Durations

`--timeout` takes a number with `ms`, `s` or `m`, such as `500ms`, `5s` or `1m`. A number alone
is seconds.

A `write` or `invoke` that times out may still be running in the JVM, because JMX cannot cancel a
call. See [Errors and exit codes](./errors.md#when-a-write-or-invoke-fails).

## Output limits

`--limit` and `--max-bytes` keep the output small enough for an agent to read. What they cut is
marked with `truncated`. See [Output](./output.md#bounded-output).

Set them before an `invoke` whose return value may be large. The operation has already run when
its return value is cut.

## Credentials

Credentials for `--url` come from the `JMX_USERNAME` and `JMX_PASSWORD` environment variables, or
from stdin with `--credentials-stdin`. They cannot be given as arguments. See
[Remote JVMs](./connect/remote.md#credentials).
