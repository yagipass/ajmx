---
description: Every ajmx command, from ps and search to read, write and invoke, with its arguments and the fields of its result.
---

# Commands

```text
ajmx [--pid <pid> | --url <url>] [options] <command> [arguments]
```

Every command except `ps`, `help` and `version` needs a target, `--pid` for a
[local JVM](./connect/local.md) or `--url` for a [remote one](./connect/remote.md). Each command
opens its own connection. To send several requests over one connection, use [`batch`](./batch.md).

The tables below list the fields of `result`. See [Output](./output.md) for the rest of the
document.

## ps

Lists the local JVMs, sorted by PID. It needs no target.

| Field | Value |
|---|---|
| `items[]` | `{pid, mainClass, displayName}` |
| `returned` | Number of items |
| `truncated` | `true` if `--limit` or `--max-bytes` cut the list |

## ping

Checks that ajmx can connect. The result is `{"connected": true}`.

## search

```text
ajmx search [pattern]
```

Lists the ObjectNames that match an [ObjectName pattern](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/javax/management/ObjectName.html),
sorted. The default pattern, `*:*`, matches every MBean. Quote the pattern, so the shell leaves `*`
alone.

::: terminal

```sh
ajmx --pid 12345 search 'java.lang:type=GarbageCollector,*'
```

:::

| Field | Value |
|---|---|
| `items[]` | ObjectNames |
| `returned` | Number of items |
| `truncated` | `true` if `--limit` or `--max-bytes` cut the list |

## describe

```text
ajmx describe <mbean>
```

Shows the attributes and operations of an MBean, sorted by name.

| Field | Value |
|---|---|
| `mbean` | The ObjectName |
| `className` | The MBean's class |
| `attributes[]` | `{name, type, readable, writable}` |
| `operations[]` | `{name, returnType, signature[]}`, where `signature[]` is `{name, type}` per parameter |

Parameter names such as `p0` and `p1` come from MBeans that do not document them. Check the MBean's
documentation for what they mean.

## read

```text
ajmx read <mbean> <attribute>...
```

Reads one or more attributes of an MBean.

| Field | Value |
|---|---|
| `mbean` | The ObjectName |
| `attributes{}` | Value per attribute that was read |
| `errors{}` | Error per attribute that failed, only when some failed |

When some of several attributes fail, the others are still read, and ajmx exits with 7. When the
only attribute fails, the command fails with its error.

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=Memory HeapMemoryUsage NonHeapMemoryUsage
```

:::

## write

```text
ajmx write <mbean> <attribute>=<value>
```

Sets an attribute. ajmx converts the value to the attribute's type, so `MaxConnections=20` sets a
number, `Enabled=true` a boolean and `Ref=java.lang:type=Memory` an ObjectName. Give an array as
JSON, such as `Tags=["a","b"]`.

::: terminal

```sh
ajmx --pid 12345 write java.lang:type=Memory Verbose=true
```

```json
{"schemaVersion":1,"ok":true,"result":{"mbean":"java.lang:type=Memory","attribute":"Verbose","value":true},"durationMs":64}
```

:::

| Field | Value |
|---|---|
| `mbean` | The ObjectName |
| `attribute` | The attribute |
| `value` | The value that was set, after conversion |

`write` fails with `ATTRIBUTE_NOT_WRITABLE` for a read-only attribute and with
`TYPE_CONVERSION_FAILED` for a value that does not fit its type. See
[Limitations](./limitations.md) for the types it accepts.

## invoke

```text
ajmx invoke <mbean> <operation> [--args <json-array>] [--signature <type,...>]
```

Invokes an operation. `--args` takes the arguments as a JSON array, and ajmx picks the overload
they fit.

::: terminal

```sh
ajmx --pid 12345 invoke com.sun.management:type=HotSpotDiagnostic getVMOption --args '["MaxHeapSize"]'
```

```json
{"schemaVersion":1,"ok":true,"result":{"mbean":"com.sun.management:type=HotSpotDiagnostic","operation":"getVMOption","signature":["java.lang.String"],"returnValue":{"name":"MaxHeapSize","origin":"VM_CREATION","value":"268435456","writeable":false}},"durationMs":20}
```

:::

| Field | Value |
|---|---|
| `mbean` | The ObjectName |
| `operation` | The operation |
| `signature[]` | Parameter types of the overload that ran |
| `returnValue` | What the operation returned, or `null` |

Unlike `write`, `--args` does not parse strings, so give a number as `20`, not `"20"`.

If several overloads fit the arguments, nothing runs and `AMBIGUOUS_OPERATION` lists them in
`details.candidates`. Pick one with `--signature`, such as `--signature java.lang.String,long`.
Write the types as `describe` shows them. Arrays use the JVM's names, such as `[J` for `long[]`
and `[Ljava.lang.String;` for `String[]`.

::: warning
An operation can do anything its MBean implements, such as clearing a cache or shutting down the
JVM. Check what it does before you invoke it.
:::

## batch

Runs requests from stdin, one JSON object per line, over one connection. See [batch](./batch.md).

## help and version

`help`, or `--help`, prints the commands and options of the installed version as JSON. `version`,
or `--version`, prints `{"version": "..."}`. Neither needs a target.
