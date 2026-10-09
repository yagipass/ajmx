---
description: Change a running JVM's settings with ajmx write and invoke, reading the old value first so you can change it back, with HikariCP's pool size and HotSpot's heap dump option as examples.
---

# Changing a setting at runtime

Some MBeans let you change a setting of a running JVM, such as a pool size or a VM option, without
a restart. `write` sets an attribute, and `invoke` calls an operation.

::: warning
`write` and `invoke` act on a live process, and an operation can do anything its MBean
implements. Try a change on your own machine first.
:::

The output below comes from the
[`jdbc-hibernate`](https://github.com/yagipass/verbatime/tree/main/examples/jdbc-hibernate)
example of Verbatime, with HikariCP's MBeans turned on as in
[Checking a connection pool](./connection-pool.md).

::: terminal

```sh
URL=service:jmx:rmi:///jndi/rmi://localhost:7091/jmxrmi
```

:::

## The steps

:::: steps

1. `describe` the MBean to see which attributes are writable and which operations it has.
2. `read` the current value, so you can change it back.
3. Change it with `write` or `invoke`.
4. `read` it again to check the change.
5. Change it back when you are done.

::::

## Find what you can change

::: terminal

```sh
ajmx --url "$URL" describe 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' \
  | jq -c '.result.attributes[] | select(.writable)'
```

```json
{"name":"Catalog","type":"java.lang.String","readable":true,"writable":true}
{"name":"ConnectionTimeout","type":"long","readable":true,"writable":true}
{"name":"Credentials","type":"javax.management.openmbean.CompositeData","readable":false,"writable":true}
{"name":"IdleTimeout","type":"long","readable":true,"writable":true}
{"name":"LeakDetectionThreshold","type":"long","readable":true,"writable":true}
{"name":"MaxLifetime","type":"long","readable":true,"writable":true}
{"name":"MaximumPoolSize","type":"int","readable":true,"writable":true}
{"name":"MinimumIdle","type":"int","readable":true,"writable":true}
{"name":"Password","type":"java.lang.String","readable":false,"writable":true}
{"name":"Username","type":"java.lang.String","readable":false,"writable":true}
{"name":"ValidationTimeout","type":"long","readable":true,"writable":true}
```

:::

`Password` and `Username` can be written but not read. Reading them fails with
`ATTRIBUTE_NOT_FOUND`.

## Change an attribute

The pool in [Checking a connection pool](./connection-pool.md) ran out of connections under load.
Raise its size from 10 to 20.

::: terminal

```sh
ajmx --url "$URL" read 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' MaximumPoolSize | jq -c .result.attributes
```

```json
{"MaximumPoolSize":10}
```

:::

::: terminal

```sh
ajmx --url "$URL" write 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' MaximumPoolSize=20 | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)",
    "attribute": "MaximumPoolSize",
    "value": 20
  },
  "durationMs": 39
}
```

:::

`write` converts `20` to the attribute's type, here `int`. See [`write`](../commands.md#write).

::: terminal

```sh
ajmx --url "$URL" read 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' MaximumPoolSize | jq -c .result.attributes
```

```json
{"MaximumPoolSize":20}
```

:::

You can also read, write, and read again in one [`batch`](../batch.md). The requests run in order,
over one connection.

::: terminal

```sh
cat > resize.jsonl <<'EOF'
{"id": "before", "op": "read", "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)", "attributes": ["MaximumPoolSize"]}
{"id": "write", "op": "write", "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)", "attribute": "MaximumPoolSize", "value": 20}
{"id": "after", "op": "read", "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)", "attributes": ["MaximumPoolSize"]}
EOF
ajmx --url "$URL" batch < resize.jsonl | jq -c '.result.items[]'
```

```json
{"id":"before","ok":true,"result":{"mbean":"com.zaxxer.hikari:type=PoolConfig (HikariPool-1)","attributes":{"MaximumPoolSize":10}}}
{"id":"write","ok":true,"result":{"mbean":"com.zaxxer.hikari:type=PoolConfig (HikariPool-1)","attribute":"MaximumPoolSize","value":20}}
{"id":"after","ok":true,"result":{"mbean":"com.zaxxer.hikari:type=PoolConfig (HikariPool-1)","attributes":{"MaximumPoolSize":20}}}
```

:::

## Check the effect

Under the same load as before, 50 clients at the same time, the pool now opens 20 connections.

::: terminal

```sh
while sleep 1; do
  ajmx --url "$URL" read 'com.zaxxer.hikari:type=Pool (HikariPool-1)' \
      ActiveConnections IdleConnections ThreadsAwaitingConnection TotalConnections \
    | jq -c .result.attributes
done
```

```json
{"ActiveConnections":0,"IdleConnections":10,"ThreadsAwaitingConnection":0,"TotalConnections":10}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":30,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":30,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":30,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":29,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":29,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":30,"TotalConnections":20}
{"ActiveConnections":20,"IdleConnections":0,"ThreadsAwaitingConnection":28,"TotalConnections":20}
{"ActiveConnections":0,"IdleConnections":20,"ThreadsAwaitingConnection":0,"TotalConnections":20}
```

:::

Fewer threads wait, and requests took 32 ms on average instead of 68 ms. Whether the database can
serve more connections in production is a separate question. Once you know the size you want, set
it in the application's configuration.

## Change it back

::: terminal

```sh
ajmx --url "$URL" write 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' MaximumPoolSize=10 | jq -c .
```

```json
{"schemaVersion":1,"ok":true,"result":{"mbean":"com.zaxxer.hikari:type=PoolConfig (HikariPool-1)","attribute":"MaximumPoolSize","value":10}}
```

:::

The setting is back, but the pool keeps the connections it has opened.

::: terminal

```sh
ajmx --url "$URL" read 'com.zaxxer.hikari:type=Pool (HikariPool-1)' IdleConnections TotalConnections | jq -c .result.attributes
```

```json
{"IdleConnections":20,"TotalConnections":20}
```

:::

The `Pool` MBean's `softEvictConnections` operation closes the idle connections at once, and the
others when they come back to the pool. HikariCP then opens new ones up to the pool size.

::: terminal

```sh
ajmx --url "$URL" invoke 'com.zaxxer.hikari:type=Pool (HikariPool-1)' softEvictConnections | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "mbean": "com.zaxxer.hikari:type=Pool (HikariPool-1)",
    "operation": "softEvictConnections",
    "signature": [],
    "returnValue": null
  },
  "durationMs": 79
}
```

:::

Read twice, two seconds apart:

```json
{"IdleConnections":1,"TotalConnections":1}
{"IdleConnections":10,"TotalConnections":10}
```

## Call an operation

`com.sun.management:type=HotSpotDiagnostic` reads and sets VM options in any HotSpot JVM. Turn on
`HeapDumpOnOutOfMemoryError`, so the JVM writes a heap dump if it runs out of memory, without a
restart.

::: terminal

```sh
ajmx --url "$URL" describe com.sun.management:type=HotSpotDiagnostic | jq -c '.result.operations[]'
```

```json
{"name":"dumpHeap","returnType":"void","signature":[{"name":"p0","type":"java.lang.String"},{"name":"p1","type":"boolean"}]}
{"name":"dumpThreads","returnType":"void","signature":[{"name":"p0","type":"java.lang.String"},{"name":"p1","type":"java.lang.String"}]}
{"name":"getVMOption","returnType":"javax.management.openmbean.CompositeData","signature":[{"name":"p0","type":"java.lang.String"}]}
{"name":"setVMOption","returnType":"void","signature":[{"name":"p0","type":"java.lang.String"},{"name":"p1","type":"java.lang.String"}]}
```

:::

Only some VM options can be changed at runtime. The `DiagnosticOptions` attribute lists them.

::: terminal

```sh
ajmx --url "$URL" read com.sun.management:type=HotSpotDiagnostic DiagnosticOptions \
  | jq -c '[.result.attributes.DiagnosticOptions[] | select(.writeable) | .name]'
```

```json
["HeapDumpBeforeFullGC","HeapDumpAfterFullGC","FullGCHeapDumpLimit","HeapDumpOnOutOfMemoryError","HeapDumpPath","HeapDumpGzipLevel","ShowCodeDetailsInExceptionMessages","PrintClassHistogram","MinHeapFreeRatio","MaxHeapFreeRatio","PrintConcurrentLocks","G1PeriodicGCInterval","G1PeriodicGCSystemLoadThreshold","SoftMaxHeapSize"]
```

:::

Read the option first. `--args` takes the arguments as a JSON array.

::: terminal

```sh
ajmx --url "$URL" invoke com.sun.management:type=HotSpotDiagnostic getVMOption --args '["HeapDumpOnOutOfMemoryError"]' | jq .result.returnValue
```

```json
{
  "name": "HeapDumpOnOutOfMemoryError",
  "origin": "DEFAULT",
  "value": "false",
  "writeable": true
}
```

:::

Set it. `setVMOption` takes the value as a string, so pass `"true"`, not `true`.

::: terminal

```sh
ajmx --url "$URL" invoke com.sun.management:type=HotSpotDiagnostic setVMOption --args '["HeapDumpOnOutOfMemoryError", "true"]' | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "mbean": "com.sun.management:type=HotSpotDiagnostic",
    "operation": "setVMOption",
    "signature": [
      "java.lang.String",
      "java.lang.String"
    ],
    "returnValue": null
  },
  "durationMs": 66
}
```

:::

Then read it again. `origin` is now `MANAGEMENT`.

```json
{
  "name": "HeapDumpOnOutOfMemoryError",
  "origin": "MANAGEMENT",
  "value": "true",
  "writeable": true
}
```

To change it back, set it to `"false"` the same way.

## When a change is refused

Before it sends a change, ajmx checks that the attribute is writable and converts the value to the
attribute's or parameter's type. The MBean then checks the value itself.

| Command | Error | Exit |
|---|---|---|
| `write ... PoolName=main` | `ATTRIBUTE_NOT_WRITABLE` | 2 |
| `write ... MaximumPoolSize=twenty` | `TYPE_CONVERSION_FAILED`, with `expected: "int"` | 2 |
| `invoke ... setVMOption --args '["HeapDumpOnOutOfMemoryError", true]'` | `TYPE_CONVERSION_FAILED`, with `expected: "java.lang.String"` | 2 |
| `write ... MaximumPoolSize=0` | `REMOTE_EXCEPTION`, with `maxPoolSize cannot be less than 1` | 5 |
| `invoke ... setVMOption --args '["MaxHeapSize", "1g"]'` | `REMOTE_EXCEPTION`, with `VM Option "MaxHeapSize" is not writeable` | 5 |

`REMOTE_EXCEPTION` means the MBean threw an exception. `details` has its class and message.

::: terminal

```sh
ajmx --url "$URL" write 'com.zaxxer.hikari:type=PoolConfig (HikariPool-1)' MaximumPoolSize=0 | jq .error
```

```json
{
  "code": "REMOTE_EXCEPTION",
  "message": "The MBean threw an exception",
  "retryable": false,
  "details": {
    "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)",
    "attribute": "MaximumPoolSize",
    "exceptionClass": "java.lang.IllegalArgumentException",
    "exceptionMessage": "maxPoolSize cannot be less than 1"
  }
}
```

:::

If a `write` or `invoke` times out or loses its connection, the change may or may not have been
made. Read the value before you try again. See
[When a write or invoke fails](../errors.md#when-a-write-or-invoke-fails).

## After a restart

Both changes on this page were gone after the application restarted: `MaximumPoolSize` was 10
again, and `HeapDumpOnOutOfMemoryError` was `false` with `origin` `DEFAULT`. To keep a setting, put
it in the application's configuration or its JVM flags.

## Next

- [Operating an application's own MBean](./app-mbean.md)
- [Errors and exit codes](../errors.md)
