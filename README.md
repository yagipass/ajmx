# ajmx

A JMX CLI for AI agents. It lists local JVMs and searches, describes, reads, writes and invokes MBeans on local and remote JVMs.

## Why

To look inside a running JVM, such as its heap, threads, connection pools or log levels, an agent has to go through JMX. The usual JMX tools are made for people. JDK Mission Control, VisualVM and jconsole are GUIs, and jmxterm prints text meant to be read, not parsed.

ajmx reads or changes a JVM in one command and prints the result as one JSON document.

- **No setup.** `--pid` connects to a local JVM even without remote JMX enabled. No restart or JVM flags.
- **JSON only.** Errors come as `error.code` and `retryable`, not stack traces.
- **Bounded output.** `--limit` and `--max-bytes` cap the output, and `truncated` marks what was cut.
- **Single binary.** A native binary. No JVM or daemon required.

## Install

On macOS (Apple silicon) and Linux (x86_64, arm64):

```sh
curl -fsSL https://github.com/yagipass/ajmx/releases/latest/download/install.sh | sh -s -- -b ~/.local/bin
# or
brew install yagipass/tap/ajmx
# or
nix profile install github:yagipass/ajmx
```

### Agent skill

[`skills/ajmx`](skills/ajmx/SKILL.md) teaches an agent how to explore a JVM with ajmx, when to change it, and how to read partial results and errors. Install it with one of:

```sh
gh skill install yagipass/ajmx ajmx
npx skills add yagipass/ajmx --skill ajmx
apm install yagipass/ajmx/skills/ajmx
```

## Usage

Select the target with `--pid <pid>` for a local JVM, even without remote JMX enabled, or `--url <url>` for a remote one, such as `service:jmx:rmi:///jndi/rmi://host:9010/jmxrmi`. `ajmx help` prints the usage as JSON.

```sh
ajmx ps
ajmx --pid 12345 search 'java.lang:*'
ajmx --pid 12345 describe java.lang:type=Memory
ajmx --pid 12345 read java.lang:type=Memory HeapMemoryUsage
ajmx --pid 12345 write com.example:type=Config MaxConnections=20
ajmx --pid 12345 invoke com.example:type=Cache invalidate --args '["user:123"]'
```

## Commands

| Command | Result |
|---|---|
| `ps` | `items[]` of `{pid, mainClass, displayName}` sorted by PID, `returned`, `truncated` |
| `ping` | `{connected: true}` |
| `search [pattern]` | `items[]` of ObjectNames, sorted (default pattern `*:*`), `returned`, `truncated` |
| `describe <mbean>` | `mbean`, `className`, `attributes[]` `{name, type, readable, writable}`, `operations[]` `{name, returnType, signature[] {name, type}}` |
| `read <mbean> <attr>...` | `mbean`, `attributes{}`, and `errors{}` for the attributes that failed. Reading a single attribute fails with its error instead |
| `write <mbean> <attr>=<value>` | `mbean`, `attribute`, `value` |
| `invoke <mbean> <op>` | `mbean`, `operation`, `signature[]`, `returnValue` |
| `batch` | `items[]` of `{id, ok, result \| error}`, one per request on stdin |
| `help` | usage |
| `version` | `{version}` |

`write` converts the value to the attribute's type, so `MaxConnections=20` sets a number, `Enabled=true` a boolean and `Ref=java.lang:type=Memory` an ObjectName. Give an array as JSON, such as `Tags=["a","b"]`.

`invoke` takes its arguments as a JSON array in `--args` and picks the overload they fit. If several fit, nothing runs and `AMBIGUOUS_OPERATION` lists the candidates; pick one with `--signature java.lang.String,long`.

`batch` runs the requests on stdin, one JSON object per line, over one connection. Blank lines are skipped.

| `op` | Fields |
|---|---|
| `ping` | (none) |
| `search` | `pattern` (optional) |
| `describe` | `mbean` |
| `read` | `mbean`, `attributes` |
| `write` | `mbean`, `attribute`, `value` |
| `invoke` | `mbean`, `operation`, `args` (optional), `signature` (optional) |

```sh
echo '{"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage"]}
{"id": "gc", "op": "search", "pattern": "java.lang:type=GarbageCollector,*"}' | ajmx --pid 12345 batch
```

The whole batch is checked before any request runs, so a typo rejects it with `INVALID_ARGUMENT` and `details.line`. A failed request does not stop the rest, except that once a `write` or `invoke` times out or loses its connection, the later `write` and `invoke` requests fail with `SKIPPED`.

## Options

| Option | Default | Description |
|---|---|---|
| `--pid <pid>` | | local target JVM |
| `--url <url>` | | remote target JVM |
| `--timeout <duration>` | `10s` | for `ps`, attach, connect and each operation (`500ms`, `5s`, `1m`) |
| `--limit <n>` | `100` | max items in a list, and max elements per array, collection, map or table |
| `--max-bytes <n>` | `262144` | max stdout size |
| `--args <array>` | `[]` | `invoke` arguments |
| `--signature <type,...>` | | `invoke` parameter types |
| `--credentials-stdin` | | read `{"username", "password"}` from stdin (with `--url`) |
| `--debug` | | print stack traces to stderr |
| `--help` | | print the usage, like `help` |
| `--version` | | print the version, like `version` |

Credentials for `--url` come from `JMX_USERNAME` and `JMX_PASSWORD`, or from stdin with `--credentials-stdin`. They cannot be passed as arguments.

## Output

```json
{"schemaVersion":1,"ok":true,"result":{...},"durationMs":12}
{"schemaVersion":1,"ok":false,"error":{"code":"MBEAN_NOT_FOUND","message":"MBean was not found","retryable":false,"details":{"mbean":"com.example:type=Nope"}},"durationMs":3}
```

| JMX value | JSON |
|---|---|
| CompositeData | object with sorted keys |
| TabularData | array of rows, sorted |
| Enum | its name |
| ObjectName | string |
| NaN, Infinity | string |
| `byte[]` | `{"$base64": "..."}`, standard base64 |
| other types | `{"$type", "$string"}` |

Output is bounded. A cut result has `"truncated": true`, and ajmx exits with 7:

- A collection longer than `--limit` becomes `{"$truncated": true, "$total": n, "$items": [...]}`. A value that contains itself or is nested more than 32 levels deep is cut there as `{"$truncated": true, "$type": "<class>"}`.
- A `byte[]` is never cut. Its base64 takes 4⌈n/3⌉ bytes for n bytes.
- Over `--max-bytes`, `ps` and `search` drop trailing items. `batch` replaces its largest items with `{"id", "ok", "truncated": true}`, and drops trailing items only if that is not enough; their requests still ran. Other commands fail with `OUTPUT_TRUNCATED`.
- `invoke` has already run when its return value is cut or does not fit. Running it again with larger limits runs the operation again, and an operation that consumes what it returns, such as reading a stream, does not return the lost part. Set the limits before such an invoke.

## Errors

Use `error.code` to handle errors. Exit codes group them for shell scripts.

| Exit | Codes |
|---|---|
| 0 | success |
| 1 | `INTERNAL_ERROR`, or stdout could not be written |
| 2 | `INVALID_ARGUMENT`, `TYPE_CONVERSION_FAILED`, `ATTRIBUTE_NOT_WRITABLE` |
| 3 | `CONNECTION_FAILED`, `CONNECTION_TIMEOUT`, `AUTH_FAILED`, `PROCESS_NOT_FOUND`, `ATTACH_NOT_SUPPORTED`, `ATTACH_PERMISSION_DENIED`, `LOCAL_JMX_UNAVAILABLE` |
| 4 | `MBEAN_NOT_FOUND`, `ATTRIBUTE_NOT_FOUND`, `OPERATION_NOT_FOUND`, `AMBIGUOUS_OPERATION` |
| 5 | `REMOTE_EXCEPTION`, `UNSUPPORTED_TYPE` |
| 6 | `TIMEOUT`, `SKIPPED` |
| 7 | `OUTPUT_TRUNCATED`, or a partial result (truncated, or some attributes or batch requests failed) |

`retryable` is `true` for `CONNECTION_FAILED`, `CONNECTION_TIMEOUT`, `LOCAL_JMX_UNAVAILABLE`, `TIMEOUT` and `SKIPPED`, but not for a `write` or `invoke` that fails after it was sent. JMX cannot cancel a call, so such an error has `"executed": "unknown"` in `details`, or `"executed": true` if the change was made but its result could not be printed. Check the state before trying again.

## Limitations

- Values and exceptions of classes that exist only in the target application cannot be deserialized, and values that are not serializable cannot be sent. Both fail with `UNSUPPORTED_TYPE`, as do JDK classes the native binary leaves out, such as those of `java.desktop`.
- A Standard MBean attribute of an application-defined enum can be neither read nor written. MXBean enums work as strings.
- `write` and `invoke` accept primitives and their wrappers, `String`, `ObjectName`, `BigInteger`, `BigDecimal`, and one-dimensional arrays of these. A `byte[]` is given as an array of numbers from -128 to 127, not as `{"$base64": ...}`.
- `--pid` supports HotSpot JVMs of the same user, or any user for root on Linux. JDK 8 to 25 are tested. It refuses a process that does not look like a HotSpot JVM with `ATTACH_NOT_SUPPORTED`, because attaching sends SIGQUIT, which kills many non-JVM processes.
- `--pid` starts the local JMX agent in the target JVM. It stays until the JVM restarts.
- `ps` does not list JVMs started with `-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`. `--pid` still connects to them if they run the `java` launcher.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

ajmx is licensed under the [Apache License, Version 2.0](LICENSE).
