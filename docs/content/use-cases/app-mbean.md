---
description: Call an application's own MBean with ajmx instead of its dedicated client, from reading its state and starting and stopping work to pulling a binary file out as a stream.
---

# Operating an application's own MBean

Applications and agents often register their own MBean to show their state and take commands.
Usually a dedicated client calls it. ajmx can call it directly, from a shell script or an AI agent.

The example here is the Verbatime profiler's agent. Its `verbatime:type=Control` MBean starts and
stops recordings, and the Verbatime plugin for JDK Mission Control is its usual client. The output
below comes from the
[`jdbc-hibernate`](https://github.com/yagipass/verbatime/tree/main/examples/jdbc-hibernate)
example of Verbatime, a Spring Boot application in Docker with remote JMX on port 7091, started
with the agent and `roots=org.springframework.web.servlet.DispatcherServlet::doDispatch`.

::: terminal

```sh
URL=service:jmx:rmi:///jndi/rmi://localhost:7091/jmxrmi
```

:::

## Find the MBean

::: terminal

```sh
ajmx --url "$URL" search 'verbatime:*' | jq -c .result.items
```

```json
["verbatime:type=Control"]
```

:::

## Learn its operations

::: terminal

```sh
ajmx --url "$URL" describe verbatime:type=Control \
  | jq -r '.result.operations[] | "\(.returnType) \(.name)(\([.signature[] | "\(.type) \(.name)"] | join(", ")))"'
```

```text
void closeStream(long p1)
long openStream(long p1, long p2)
[B readStream(long p1)
void replaceRoots([Ljava.lang.String; p1)
[Ljava.lang.String; searchMethods(java.lang.String p1, int p2)
long startRecording(java.lang.String p1)
[Ljava.lang.String; status()
void stopRecording()
```

:::

`[B` is the JVM's name for `byte[]`, and `[Ljava.lang.String;` for `String[]`.

The parameter names `p1` and `p2` say nothing about the parameters. JMX knows only their types,
so take their meaning from the MBean's documentation or source. Here, it is
[`VerbatimeControlMBean`](https://github.com/yagipass/verbatime/blob/main/modules/agent/src/main/java/io/github/yagipass/verbatime/agent/jmx/VerbatimeControlMBean.java).

| Operation | Parameters | Returns |
|---|---|---|
| `status` | | The agent's state, as `key=value` lines |
| `startRecording` | Recording name | Recording ID |
| `stopRecording` | | |
| `replaceRoots` | Methods to record | |
| `openStream` | Recording ID, offset to start at | Stream ID |
| `readStream` | Stream ID | The next chunk of the recording, or `null` at the end |
| `closeStream` | Stream ID | |

Do not guess. Two arguments of the same type in the wrong order still run.

## Check its state

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control status | jq .result.returnValue
```

```json
[
  "v=4",
  "pid=1",
  "state=idle",
  "roots=1",
  "root.0=ok org.springframework.web.servlet.DispatcherServlet::doDispatch",
  "include=*",
  "exclude=",
  "instrumentedClasses=6964",
  "instrumentedMethods=67110",
  "failedClasses=0",
  "idLimitSkippedClasses=0"
]
```

:::

## Start and stop

Start a recording named `orders`. `--args` takes the arguments as a JSON array.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control startRecording --args '["orders"]' | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "mbean": "verbatime:type=Control",
    "operation": "startRecording",
    "signature": [
      "java.lang.String"
    ],
    "returnValue": 1
  },
  "durationMs": 176
}
```

:::

`returnValue` is the recording ID. Send some requests to the application, then stop the recording
and read the state in one [`batch`](../batch.md).

::: terminal

```sh
cat > stop.jsonl <<'EOF'
{"id": "stop", "op": "invoke", "mbean": "verbatime:type=Control", "operation": "stopRecording"}
{"id": "status", "op": "invoke", "mbean": "verbatime:type=Control", "operation": "status"}
EOF
ajmx --url "$URL" batch < stop.jsonl | jq '.result.items[] | {id, ok, returnValue: .result.returnValue}'
```

```json
{
  "id": "stop",
  "ok": true,
  "returnValue": null
}
{
  "id": "status",
  "ok": true,
  "returnValue": [
    "v=4",
    "pid=1",
    "state=idle",
    "spoolDir=/tmp/vbtm-1-14294213987009918055",
    "roots=1",
    "root.0=ok org.springframework.web.servlet.DispatcherServlet::doDispatch",
    "include=*",
    "exclude=",
    "instrumentedClasses=7100",
    "instrumentedMethods=68153",
    "failedClasses=0",
    "idLimitSkippedClasses=0",
    "lastRecording.id=1",
    "lastRecording.name=orders",
    "lastRecording.file=rec-1-20261006-054610.vbtm",
    "lastRecording.startEpochMs=1791265570452",
    "lastRecording.bytes=6935236"
  ]
}
```

:::

## When the MBean refuses

An operation that does not fit the MBean's state fails with `REMOTE_EXCEPTION`. The MBean's own
message is in `details.exceptionMessage`. Here, `startRecording` was called while recording `1`
was running.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control startRecording --args '["again"]' | jq .error
```

```json
{
  "code": "REMOTE_EXCEPTION",
  "message": "The MBean threw an exception",
  "retryable": false,
  "details": {
    "mbean": "verbatime:type=Control",
    "operation": "startRecording",
    "exceptionClass": "java.lang.IllegalStateException",
    "exceptionMessage": "already recording #1"
  }
}
```

:::

## Pull out a file

The recording stays in the application. `openStream` opens it, each `readStream` returns the next
chunk as a `byte[]`, and `closeStream` closes it. ajmx prints a `byte[]` as
`{"$base64": "..."}`. See [JMX values in JSON](../output.md#jmx-values-in-json).

A chunk here is 1 MiB, which is about 1.4 MB as base64, more than the default `--max-bytes` of
256 KiB. The call then fails, but it has already run.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control openStream --args '[1, 0]' | jq -c .result.returnValue
```

```json
1
```

:::

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control readStream --args '[1]' | jq .error
```

```json
{
  "code": "OUTPUT_TRUNCATED",
  "message": "Output exceeds --max-bytes",
  "retryable": false,
  "details": {
    "maxBytes": 262144,
    "outputBytes": 1398269,
    "executed": true
  }
}
```

:::

`"executed": true` means `readStream` ran and returned the chunk, which ajmx could not print. The
stream has moved past it, so the next `readStream` returns the chunk after it, and the file would
have a gap. Close this stream and open a new one at offset 0.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control closeStream --args '[1]' | jq -c .ok
```

```json
true
```

:::

Set a large `--max-bytes` before you read. Save this script as `pull-recording.sh`. It reads the
recording with the ID in its first argument into the file in its second.

```bash
#!/usr/bin/env bash
set -euo pipefail
url=service:jmx:rmi:///jndi/rmi://localhost:7091/jmxrmi
mbean=verbatime:type=Control

stream=$(ajmx --url "$url" invoke "$mbean" openStream --args "[$1, 0]" | jq -e '.result.returnValue')
trap 'ajmx --url "$url" invoke "$mbean" closeStream --args "[$stream]" > /dev/null' EXIT

: > "$2"
while true; do
  if ! chunk=$(ajmx --url "$url" --max-bytes 67108864 invoke "$mbean" readStream --args "[$stream]"); then
    echo "$chunk" >&2
    exit 1
  fi
  if [ "$(jq '.result.returnValue == null' <<< "$chunk")" = true ]; then
    break
  fi
  jq -r '.result.returnValue["$base64"]' <<< "$chunk" | base64 -d >> "$2"
done
```

- `--max-bytes 67108864` allows 64 MiB per call, far above one chunk. The chunks go to the file,
  not to your terminal or an agent's context, so a large limit costs nothing.
- The loop ends when `returnValue` is `null`. Do not stop on empty output: a chunk can be empty.
- If any call fails, `set -e` and `pipefail` stop the script, and it prints the error. The file is
  then incomplete, so do not use it.
- `trap` closes the stream when the script ends, also after a failure.

::: terminal

```sh
bash pull-recording.sh 1 orders.vbtm
wc -c < orders.vbtm
```

```text
6935236
```

:::

The size matches `lastRecording.bytes` in the status. The file is a complete recording.

::: terminal

```sh
vbtm sessions orders.vbtm --sort dur --limit 3
```

```text
file: orders.vbtm  status: complete  recorded: 2026-10-06T05:46:10.452+00:00
length: 792.0544 ms  threads: 5  sessions: 20  calls: 404,203  methods: 68,153  gc: 0 pauses, 0.0000 ms
units: ms, 0.0001 ms = 1 tick of 100 ns
20 sessions, sorted by dur, showing 3

id     start       dur    calls  depth  throws   gc_ms  thread                root
 1  138.9062  135.7749  269,736     84   2,406  0.0000  http-nio-8080-exec-3  DispatcherServlet.doDispatch
11  527.9470   28.0213    6,704     84       0  0.0000  http-nio-8080-exec-3  DispatcherServlet.doDispatch
 2  284.6450   18.9978    8,555     84      11  0.0000  http-nio-8080-exec-5  DispatcherServlet.doDispatch
# 17 more sessions. next: vbtm sessions orders.vbtm --sort dur --limit 9
```

:::

Some MBeans discard data once it has been read. Verbatime deletes a recording once it has been
read to the end and its streams are closed, so a second pull fails.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control openStream --args '[1, 0]' | jq .error.details
```

```json
{
  "mbean": "verbatime:type=Control",
  "operation": "openStream",
  "exceptionClass": "java.lang.IllegalArgumentException",
  "exceptionMessage": "unknown recording id 1"
}
```

:::

## Pass an array

`replaceRoots` takes one `String[]` parameter. In `--args`, that is an array inside the array of
arguments.

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control replaceRoots \
  --args '[["io.github.yagipass.verbatime.examples.jpa.PersistentOrderService::place"]]' | jq -c .ok
```

```json
true
```

:::

::: terminal

```sh
ajmx --url "$URL" invoke verbatime:type=Control status | jq -c '.result.returnValue | map(select(startswith("root")))'
```

```json
["roots=1","root.0=ok io.github.yagipass.verbatime.examples.jpa.PersistentOrderService::place"]
```

:::

Without the inner array, ajmx reads the string as the `String[]` itself and fails with
`TYPE_CONVERSION_FAILED`, `"expected": "[Ljava.lang.String;"`.

## Next

- [Changing a setting at runtime](./change-setting.md)
- [invoke](../commands.md#invoke)
