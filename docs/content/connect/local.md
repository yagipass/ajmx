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

1. ajmx checks that the process runs as the same user, or that ajmx runs as root on Linux. On
   Linux, it also checks that the process runs in the same network namespace, if it can read both
   in `/proc`.
2. It reads the JVM's performance data file, `hsperfdata_<user>/<pid>` under the temporary
   directory. If the JVM's local JMX agent is already running, ajmx connects to it.
3. Otherwise ajmx attaches with the Attach API, starts the local JMX agent, and connects to it.

The local JMX agent accepts connections only from its own network namespace. For a JVM in a
container with its own network, that means only from inside the container. It stays until the JVM
restarts, so later commands connect directly.

## Requirements

- A HotSpot JVM. JDK 8 to 25 are tested.
- The same user, or root on Linux. Root without `CAP_SYS_PTRACE`, as in a Docker container by
  default, connects to another user's JVM only if `ps` lists it.
- The attach mechanism must be enabled. A JVM started with `-XX:+DisableAttachMechanism` fails
  with `ATTACH_NOT_SUPPORTED`.
- On Linux, the same network namespace. A container started with `--network host` shares the
  host's. If ajmx can read both namespaces in `/proc`, it refuses a JVM in another one with
  `ATTACH_NOT_SUPPORTED` before it attaches. Run ajmx inside the JVM's container, such as with
  `docker exec`, or [use `--url`](./remote.md).

Attaching sends `SIGQUIT` to the process, which kills many processes that are not JVMs. So ajmx
refuses a process that has no performance data file and does not run the `java` launcher, with
`ATTACH_NOT_SUPPORTED`.

An OpenJ9 JVM, such as IBM Semeru, also runs the `java` launcher, so ajmx attaches. OpenJ9 does
not answer. With its default dump settings, it writes a `javacore` file into its working directory
for each `SIGQUIT` instead. ajmx fails with `ATTACH_NOT_SUPPORTED` after about `--timeout`.
Connect to it with [`--url`](./remote.md).

## JVMs that ps does not list

`ps` finds JVMs by their performance data files. It does not list JVMs started with
`-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`, or JVMs running as a UID with no user name,
such as one missing from `/etc/passwd`, which write no such file. `--pid` still connects to them if
they run the `java` launcher, except as root without `CAP_SYS_PTRACE` for another user's JVM.

## Next

- [Remote JVMs](./remote.md)
- [Troubleshooting](../troubleshooting.md)
