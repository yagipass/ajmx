---
description: Read a running JVM's heap usage, garbage collection counts and times, and what is left after each collection with ajmx, to tell a memory leak from a heap that is too small.
---

# Checking heap and GC

Heap usage goes up and down with every garbage collection, so one reading says little. To tell a
leak from a heap that is too small, read how much is left after a collection, and compare
readings taken a while apart.

The output below comes from a small demo program. It runs with `-Xmx256m`, and keeps part of what
it allocates, so it leaks.

## Find the JVM

::: terminal

```sh
ajmx ps | jq '.result.items[] | select(.mainClass == "com.example.LeakDemo")'
```

```json
{
  "pid": 12345,
  "mainClass": "com.example.LeakDemo",
  "displayName": "LeakDemo"
}
```

:::

`--pid` connects to a local JVM without remote JMX. See [Local JVMs](../connect/local.md).

## Read the heap

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=Memory HeapMemoryUsage NonHeapMemoryUsage | jq .result.attributes
```

```json
{
  "HeapMemoryUsage": {
    "committed": 268435456,
    "init": 268435456,
    "max": 268435456,
    "used": 68164424
  },
  "NonHeapMemoryUsage": {
    "committed": 14614528,
    "init": 7667712,
    "max": -1,
    "used": 10906608
  }
}
```

:::

- The values are in bytes. `max` is the limit, 256 MiB from `-Xmx256m`, and `committed` is what
  the JVM has taken from the operating system.
- `used` includes garbage that has not been collected yet. It rises until the next collection and
  then drops.
- Non-heap memory holds class metadata and compiled code. A `max` of `-1` means no limit.

## Read GC counts and times

Each garbage collector has its own MBean.

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

The names depend on the collector and the JDK. These are the ones of G1, the default collector,
on JDK 25.

| MBean | Counts |
|---|---|
| `G1 Young Generation` | Young and mixed collections, which run all the time |
| `G1 Concurrent GC` | The pauses of G1's concurrent marking, which finds garbage in the old generation |
| `G1 Old Generation` | Full collections, which stop the application while the whole heap is collected |

Read them, together with what the old generation holds after a collection, in one
[`batch`](../batch.md).

::: terminal

```sh
cat > gc.jsonl <<'EOF'
{"id": "young", "op": "read", "mbean": "java.lang:type=GarbageCollector,name=G1 Young Generation", "attributes": ["CollectionCount", "CollectionTime"]}
{"id": "concurrent", "op": "read", "mbean": "java.lang:type=GarbageCollector,name=G1 Concurrent GC", "attributes": ["CollectionCount", "CollectionTime"]}
{"id": "full", "op": "read", "mbean": "java.lang:type=GarbageCollector,name=G1 Old Generation", "attributes": ["CollectionCount", "CollectionTime"]}
{"id": "old-gen", "op": "read", "mbean": "java.lang:type=MemoryPool,name=G1 Old Gen", "attributes": ["CollectionUsage"]}
EOF
ajmx --pid 12345 batch < gc.jsonl | jq -c '.result.items[] | {id} + .result.attributes'
```

```json
{"id":"young","CollectionCount":36,"CollectionTime":124}
{"id":"concurrent","CollectionCount":2,"CollectionTime":3}
{"id":"full","CollectionCount":0,"CollectionTime":0}
{"id":"old-gen","CollectionUsage":{"committed":140509184,"init":219152384,"max":268435456,"used":108199984}}
```

:::

- `CollectionCount` and `CollectionTime` count from the start of the JVM. `CollectionTime` is in
  milliseconds.
- `CollectionUsage` of a memory pool is its usage right after the JVM last collected it. For the
  old generation, that is what survived. It is 0 until the old generation has been collected
  once.

## Compare with a later reading

The same batch a minute later:

```json
{"id":"young","CollectionCount":54,"CollectionTime":170}
{"id":"concurrent","CollectionCount":14,"CollectionTime":13}
{"id":"full","CollectionCount":0,"CollectionTime":0}
{"id":"old-gen","CollectionUsage":{"committed":175112192,"init":219152384,"max":268435456,"used":160104624}}
```

- What survived in the old generation grew from 103 MiB to 153 MiB in one minute, although G1 kept
  marking and collecting it. The application keeps objects it no longer needs. That is a leak, and
  a larger heap only delays the end.
- If what survives stays flat but close to `max`, the live data barely fits. Then the heap is too
  small.
- Young collections took 46 ms of that minute. The pauses are not the problem here.

Shortly before the demo ran out of memory, the same batch showed full collections:

```json
{"id":"young","CollectionCount":181,"CollectionTime":357}
{"id":"concurrent","CollectionCount":96,"CollectionTime":52}
{"id":"full","CollectionCount":2,"CollectionTime":91}
{"id":"old-gen","CollectionUsage":{"committed":255852544,"init":219152384,"max":268435456,"used":239640280}}
```

A full collection means G1 could not free memory fast enough. In a healthy application on G1, the
count stays at 0. Less than half a minute later, the demo died with
`java.lang.OutOfMemoryError: Java heap space`.

## See what the last collection freed

`LastGcInfo` holds the memory of each pool before and after a collector's last run. Each is a
table, which ajmx prints as an array of `key` and `value` rows. See
[JMX values in JSON](../output.md#jmx-values-in-json).

::: terminal

```sh
ajmx --pid 12345 read 'java.lang:type=GarbageCollector,name=G1 Old Generation' LastGcInfo \
  | jq '.result.attributes.LastGcInfo | {duration, before: (.memoryUsageBeforeGc | from_entries)["G1 Old Gen"].used, after: (.memoryUsageAfterGc | from_entries)["G1 Old Gen"].used}'
```

```json
{
  "duration": 12,
  "before": 253992656,
  "after": 232039832
}
```

:::

The last full collection took 12 ms, and freed only 21 MiB of the old generation.

## Find what fills the heap

`DiagnosticCommand` runs the same commands as `jcmd`. `gcClassHistogram` is
`jcmd GC.class_histogram`, and counts the objects of each class.

::: warning
`gcClassHistogram` runs a full collection first, which stops the application. On a production
JVM, ask before you run it. With `--args '[["-all"]]'` it skips the collection, and counts garbage
too.
:::

::: terminal

```sh
ajmx --pid 12345 invoke com.sun.management:type=DiagnosticCommand gcClassHistogram --args '[[]]' \
  | jq -r .result.returnValue | head -5
```

```text
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         21411      225744360  [B (java.base@25.0.4.1.1)
   2:         17354         416496  java.lang.String (java.base@25.0.4.1.1)
   3:          2748         359000  java.lang.Class (java.base@25.0.4.1.1)
```

:::

`[B` is `byte[]`. Byte arrays take 215 MiB of the 256 MiB heap. To find what holds on to them,
take a heap dump with `com.sun.management:type=HotSpotDiagnostic` `dumpHeap`, and open it in a
heap analyzer. The dump is written on the JVM's machine, and is as large as the heap.

## Next

- [Finding deadlocked threads](./threads.md)
- [`batch`](../batch.md)
