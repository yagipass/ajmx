---
description: Fixes for common ajmx errors, such as a JVM missing from ps, attach failures with --pid, remote connections that fail or time out, names that are not found, and timeouts.
---

# Troubleshooting

Every failure has an `error.code`. Find it below, or in [Error codes](./errors.md#error-codes).
`--debug` prints the stack trace to stderr. The examples print only the error, with `jq .error`.

## ps does not list the JVM

`ps` finds JVMs by their performance data files. A JVM started with `-XX:-UsePerfData` or
`-XX:+PerfDisableSharedMem` writes none. Here two `ShopApplication` JVMs run, and `ps` lists only
the one without `-XX:-UsePerfData`.

::: terminal

```sh
ajmx ps | jq '.result.items[] | select(.mainClass == "com.example.shop.ShopApplication")'
```

```json
{
  "pid": 12344,
  "mainClass": "com.example.shop.ShopApplication",
  "displayName": "ShopApplication"
}
```

:::

Find the other PID with your operating system's tools, such as `pgrep -f ShopApplication`.
`--pid` still connects if the JVM runs the `java` launcher.

::: terminal

```sh
ajmx --pid 12345 ping | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "connected": true
  },
  "durationMs": 31
}
```

:::

## --pid fails with PROCESS_NOT_FOUND

::: terminal

```sh
ajmx --pid 12345 ping | jq .error
```

```json
{
  "code": "PROCESS_NOT_FOUND",
  "message": "Process was not found",
  "retryable": false,
  "details": {
    "pid": 12345
  }
}
```

:::

No process has that PID. The JVM may have exited, or restarted with a new PID. Run `ajmx ps`
again.

## --pid fails with ATTACH_NOT_SUPPORTED

::: terminal

```sh
ajmx --pid 12345 ping | jq .error
```

```json
{
  "code": "ATTACH_NOT_SUPPORTED",
  "message": "The process is not recognizable as a HotSpot JVM (no perf data and not the java launcher)",
  "retryable": false,
  "details": {
    "pid": 12345
  }
}
```

:::

The process is not a JVM, or ajmx cannot tell that it is one: it has no performance data file and
does not run the `java` launcher. Attaching sends `SIGQUIT`, which kills most processes that are
not JVMs, so ajmx does not try. Check the PID with `ajmx ps`. For a JVM started by its own
launcher, remove `-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`, or
[use `--url`](./connect/remote.md).

A JVM that turns attach off fails with another message:

```json
{
  "code": "ATTACH_NOT_SUPPORTED",
  "message": "The JVM disables the attach mechanism",
  "retryable": false,
  "details": {
    "pid": 12345
  }
}
```

The JVM runs with `-XX:+DisableAttachMechanism`. Remove the flag, or
[turn on remote JMX](./connect/remote.md#turn-on-remote-jmx) and use `--url`.

## --pid fails with ATTACH_PERMISSION_DENIED

::: terminal

```sh
ajmx --pid 12345 ping | jq .error
```

```json
{
  "code": "ATTACH_PERMISSION_DENIED",
  "message": "The process belongs to another user",
  "retryable": false,
  "details": {
    "pid": 12345,
    "processUser": "root"
  }
}
```

:::

`processUser` runs the JVM, and you do not. Run ajmx as that user, or as root on Linux. When the
operating system refuses the attach itself, the message is `Not permitted to attach to the process`
instead.

## --pid fails with LOCAL_JMX_UNAVAILABLE

ajmx attached, but the JVM could not start its local JMX agent. `reason` is the JVM's message.

::: terminal

```sh
ajmx --pid 12345 ping | jq .error
```

```json
{
  "code": "LOCAL_JMX_UNAVAILABLE",
  "message": "Unable to start the local JMX agent in the JVM",
  "retryable": false,
  "details": {
    "pid": 12345,
    "reason": "java.lang.module.FindException: Module jdk.management.agent not found"
  }
}
```

:::

This JVM runs on a runtime image made with `jlink` without the `jdk.management.agent` module. Add
it to the image:

::: terminal

```sh
jlink --add-modules java.base,java.management,jdk.management.agent --output runtime
```

:::

When `retryable` is `true`, the connection to the JVM broke while the agent was starting. Try
again.

## --url fails with CONNECTION_FAILED

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi ping | jq .error
```

```json
{
  "code": "CONNECTION_FAILED",
  "message": "Connection to the JVM failed",
  "retryable": true,
  "details": {
    "url": "service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi"
  }
}
```

:::

Run the command again with `--debug` and read the first `Caused by:` line on stderr.

- `Failed to retrieve RMIServer stub` means nothing answered on the port in the URL. Check that the
  JVM runs with the [remote JMX flags](./connect/remote.md#turn-on-remote-jmx) and that the port
  matches. In a container, also check that `com.sun.management.jmxremote.host` is not set.
- Otherwise the JVM answered, but ajmx could not reach its second port. Set
  `com.sun.management.jmxremote.rmi.port` to the same port as `com.sun.management.jmxremote.port`.

## --url fails with CONNECTION_TIMEOUT

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi ping | jq .error
```

```json
{
  "code": "CONNECTION_TIMEOUT",
  "message": "Timed out connecting to the JMX server",
  "retryable": true,
  "details": {
    "url": "service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi",
    "timeoutMs": 10000
  }
}
```

:::

The JVM answered with an address that ajmx cannot reach. Set `java.rmi.server.hostname` in the
target JVM to the host name in the URL. For a container, see
[A JVM in a container](./connect/remote.md#a-jvm-in-a-container).

## --url fails with AUTH_FAILED

- With `url` in `details`, the username or password is wrong or missing. See
  [Credentials](./connect/remote.md#credentials).
- With `mbean` in `details`, the user is `readonly`, and the command was a `write` or `invoke`. It
  did not run.

## search returns no items

A pattern without `*` matches only a name with exactly those keys.

::: terminal

```sh
ajmx --pid 12345 search 'java.lang:type=GarbageCollector' | jq .result
```

```json
{
  "items": [],
  "returned": 0,
  "truncated": false
}
```

:::

Add `,*` to match names with more keys.

::: terminal

```sh
ajmx --pid 12345 search 'java.lang:type=GarbageCollector,*' | jq .result
```

```json
{
  "items": [
    "java.lang:name=G1 Concurrent GC,type=GarbageCollector",
    "java.lang:name=G1 Old Generation,type=GarbageCollector",
    "java.lang:name=G1 Young Generation,type=GarbageCollector"
  ],
  "returned": 3,
  "truncated": false
}
```

:::

Names are case-sensitive, so `java.lang:type=memory` matches nothing. Quote every pattern: unquoted,
zsh stops with `no matches found`, and bash may replace it with file names.

## MBEAN_NOT_FOUND, ATTRIBUTE_NOT_FOUND or OPERATION_NOT_FOUND

Names of MBeans, attributes and operations are case-sensitive.

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=Memory heapMemoryUsage | jq .error
```

```json
{
  "code": "ATTRIBUTE_NOT_FOUND",
  "message": "Attribute was not found",
  "retryable": false,
  "details": {
    "mbean": "java.lang:type=Memory",
    "attribute": "heapMemoryUsage"
  }
}
```

:::

Copy names from [`search`](./commands.md#search) and [`describe`](./commands.md#describe). The
order of the keys in an MBean name does not matter.

`details` shows the name that ajmx received. A name with spaces needs quotes, or the shell splits
it:

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=GarbageCollector,name=G1 Young Generation CollectionCount | jq .error
```

```json
{
  "code": "MBEAN_NOT_FOUND",
  "message": "MBean was not found",
  "retryable": false,
  "details": {
    "mbean": "java.lang:type=GarbageCollector,name=G1"
  }
}
```

:::

## UNSUPPORTED_TYPE

::: terminal

```sh
ajmx --pid 12345 read com.example.shop:type=Inventory LastItem | jq .error
```

```json
{
  "code": "UNSUPPORTED_TYPE",
  "message": "A value or exception has a type that is not available to ajmx",
  "retryable": false,
  "details": {
    "mbean": "com.example.shop:type=Inventory",
    "attribute": "LastItem",
    "class": "com.example.shop.Item"
  }
}
```

:::

The value is an instance of `class`, which exists only in the application, so ajmx cannot read it.
Retrying does not help. See [Limitations](./limitations.md#classes-that-exist-only-in-the-application).

- Read the other attributes. A `read` of several attributes returns the ones that work, with this
  error under `errors`. See [Partial results](./output.md#partial-results).
- If you own the MBean, make it an MXBean. Its values reach ajmx as open types, here as an object:

::: terminal

```sh
ajmx --pid 12345 read com.example.shop:type=InventoryStats LastItem | jq .result
```

```json
{
  "mbean": "com.example.shop:type=InventoryStats",
  "attributes": {
    "LastItem": {
      "quantity": 3,
      "sku": "widget"
    }
  }
}
```

:::

## TIMEOUT

Each operation must finish within `--timeout`, 10 seconds by default.

::: terminal

```sh
ajmx --pid 12345 --timeout 2s invoke com.example.shop:type=Inventory rebuildIndex | jq .error
```

```json
{
  "code": "TIMEOUT",
  "message": "JMX operation timed out",
  "retryable": false,
  "details": {
    "mbean": "com.example.shop:type=Inventory",
    "operation": "rebuildIndex",
    "timeoutMs": 2000,
    "executed": "unknown"
  }
}
```

:::

- For `search`, `describe` and `read`, `retryable` is `true`. Try again with a larger `--timeout`.
- For `write` and `invoke`, JMX cannot cancel the call, so it may still be running in the JVM.
  Check the state before you run it again. See
  [When a write or invoke fails](./errors.md#when-a-write-or-invoke-fails).
