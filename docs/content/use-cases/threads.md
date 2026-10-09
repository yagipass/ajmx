---
description: Find deadlocked threads in a running JVM with ajmx, see which thread holds the lock each one waits for, and take a full thread dump, without restarting the JVM.
---

# Finding deadlocked threads

When requests hang while the CPU stays idle, threads may be waiting for each other's locks. The
JVM's `Threading` MBean finds such a deadlock and shows which threads and locks are involved.

The output below comes from a small demo program. Its two threads, `order-worker` and
`refund-worker`, each hold one lock and wait for the other's.

## Find the JVM

::: terminal

```sh
ajmx ps | jq '.result.items[] | select(.mainClass == "com.example.DeadlockDemo")'
```

```json
{
  "pid": 12345,
  "mainClass": "com.example.DeadlockDemo",
  "displayName": "DeadlockDemo"
}
```

:::

`--pid` connects to a local JVM without remote JMX. See [Local JVMs](../connect/local.md).

## Find the deadlocked threads

::: terminal

```sh
ajmx --pid 12345 invoke java.lang:type=Threading findDeadlockedThreads | jq .result
```

```json
{
  "mbean": "java.lang:type=Threading",
  "operation": "findDeadlockedThreads",
  "signature": [],
  "returnValue": [
    25,
    26
  ]
}
```

:::

`returnValue` holds the IDs of the deadlocked threads. It is `null` when there is no deadlock.

`findDeadlockedThreads` covers both `synchronized` blocks and locks such as `ReentrantLock`.
`findMonitorDeadlockedThreads` covers `synchronized` blocks only.

## See who holds which lock

`getThreadInfo` returns the details of threads by ID. It has several overloads. `describe` lists
their parameter types, where `[J` is the JVM's name for `long[]`.

::: terminal

```sh
ajmx --pid 12345 describe java.lang:type=Threading \
  | jq -c '.result.operations[] | select(.name == "getThreadInfo") | [.signature[].type]'
```

```text
["[J"]
["[J","boolean","boolean"]
["[J","boolean","boolean","int"]
["[J","int"]
["long"]
["long","int"]
```

:::

The parameters show up as `p0`, `p1` and so on, without their meaning. The
[`ThreadMXBean` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/ThreadMXBean.html)
says that `getThreadInfo(long[] ids, int maxDepth)` returns the threads with up to `maxDepth`
frames of each stack.

Pass the IDs as a JSON array, and `1` to keep the top frame only.

::: terminal

```sh
ajmx --pid 12345 invoke java.lang:type=Threading getThreadInfo --args '[[25, 26], 1]' \
  | jq '.result.signature, (.result.returnValue[] | {threadName, threadState, lockName, lockOwnerName, frame: .stackTrace[0] | "\(.className).\(.methodName)(\(.fileName):\(.lineNumber))"})'
```

```json
[
  "[J",
  "int"
]
{
  "threadName": "order-worker",
  "threadState": "BLOCKED",
  "lockName": "java.lang.Object@3502ab08",
  "lockOwnerName": "refund-worker",
  "frame": "com.example.DeadlockDemo.lambda$main$0(DeadlockDemo.java:12)"
}
{
  "threadName": "refund-worker",
  "threadState": "BLOCKED",
  "lockName": "java.lang.Object@21b37293",
  "lockOwnerName": "order-worker",
  "frame": "com.example.DeadlockDemo.lambda$main$1(DeadlockDemo.java:19)"
}
```

:::

- ajmx picked the overload whose parameter types fit the arguments, and `signature` shows which
  one ran. If several fit, see [`invoke`](../commands.md#invoke).
- `lockName` is the lock the thread waits for, and `lockOwnerName` the thread that holds it. Here
  each thread waits for a lock the other holds.
- `frame` is the line where the thread waits.

## Take a thread dump

`DiagnosticCommand` runs the same commands as `jcmd`. `threadPrint` is `jcmd Thread.print`. It
takes its options as a `String[]`, so pass an empty array for none, or `[["-l"]]` to also list the
`java.util.concurrent` locks each thread holds.

::: terminal

```sh
ajmx --pid 12345 invoke com.sun.management:type=DiagnosticCommand threadPrint --args '[[]]' \
  | jq -r .result.returnValue > threads.txt
grep -A 8 'Found one Java-level deadlock' threads.txt
```

```text
Found one Java-level deadlock:
=============================
"order-worker":
  waiting to lock monitor 0x00000008f2ccfb80 (object 0x00000003cfd52a50, a java.lang.Object),
  which is held by "refund-worker"

"refund-worker":
  waiting to lock monitor 0x00000008f2ccfaa0 (object 0x00000003cfd52a40, a java.lang.Object),
  which is held by "order-worker"
```

:::

The dump has the stack of every thread, and ends with this deadlock report and the stacks of the
deadlocked threads.

A JVM with many threads takes longer to dump, and its dump can be larger than `--max-bytes`. Then
ajmx prints no dump and fails with `OUTPUT_TRUNCATED`. `details.outputBytes` is the size it needed.

::: terminal

```sh
ajmx --pid 12345 --max-bytes 4096 invoke com.sun.management:type=DiagnosticCommand threadPrint --args '[[]]' \
  | jq .error
```

```json
{
  "code": "OUTPUT_TRUNCATED",
  "message": "Output exceeds --max-bytes",
  "retryable": false,
  "details": {
    "maxBytes": 4096,
    "outputBytes": 15368,
    "executed": true
  }
}
```

:::

`threadPrint` only reads, so run it again with a larger `--max-bytes`. See
[Bounded output](../output.md#bounded-output).

## These calls only read

`findDeadlockedThreads`, `getThreadInfo` and `threadPrint` do not change the JVM. A thread dump
stops every thread while it is taken, though, so on a busy production JVM with many threads,
expect a short pause.

## Next

- [Checking heap and GC](./heap-gc.md)
- [`invoke`](../commands.md#invoke)
