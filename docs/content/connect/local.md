---
description: Connect ajmx to a JVM on the same machine with --pid, without remote JMX enabled, and what that leaves running in the JVM.
---

# Local JVMs

`--pid` connects to a JVM on the same machine. The JVM needs no JMX flags and no restart, so you
can look at a problem while it is still happening.

::: terminal

```sh
ajmx --pid 12345 ping
```

```json
{"schemaVersion":1,"ok":true,"result":{"connected":true},"durationMs":13}
```

:::

## Find the JVM

`ps` lists the local JVMs, sorted by PID. Take the `pid` of yours by its `mainClass` or
`displayName`.

::: terminal

```sh
ajmx ps | jq '.result.items[] | select(.displayName == "OrderService")'
```

```json
{
  "pid": 12345,
  "mainClass": "com.example.OrderService",
  "displayName": "OrderService"
}
```

:::

`mainClass` is the main class, or the jar for `java -jar`. `displayName` is its simple name.

## How it connects

1. ajmx checks that the process runs as the same user. Root may connect to any user's JVM on Linux.
2. It reads the JVM's performance data file, `hsperfdata_<user>/<pid>` under the temporary
   directory. If the JVM's local JMX agent is already running, ajmx connects to it.
3. Otherwise ajmx attaches with the Attach API, starts the local JMX agent, and connects to it.

The local JMX agent accepts connections only from the same machine. It stays until the JVM
restarts, so later commands connect directly.

## Requirements

- A HotSpot JVM. JDK 8 to 25 are tested.
- The same user, or root on Linux.
- The attach mechanism must be enabled. A JVM started with `-XX:+DisableAttachMechanism` fails
  with `ATTACH_NOT_SUPPORTED`.

Attaching sends `SIGQUIT` to the process, which kills many processes that are not JVMs. So ajmx
refuses a process that has no performance data file and does not run the `java` launcher, with
`ATTACH_NOT_SUPPORTED`.

## JVMs that ps does not list

`ps` finds JVMs by their performance data files. It does not list JVMs started with
`-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`. `--pid` still connects to them if they run the
`java` launcher.

## Next

- [Remote JVMs](./remote.md)
- [Troubleshooting](../troubleshooting.md)
