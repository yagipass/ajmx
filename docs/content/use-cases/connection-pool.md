---
description: Read a HikariCP connection pool's MBeans with ajmx, see how many connections are in use and how many threads wait for one, and tell when the pool is exhausted.
---

# Checking a connection pool

When requests slow down under load while the database stays idle, they may be waiting for a
connection from the pool. HikariCP's MBeans show how many connections are in use and how many
threads wait for one.

The output below comes from the
[`jdbc-hibernate`](https://github.com/yagipass/verbatime/tree/main/examples/jdbc-hibernate)
example of Verbatime: Spring Boot 4.1 with Spring Data JPA, HikariCP, and PostgreSQL, in Docker
with remote JMX on port 7091. See [A JVM in a container](../connect/remote.md#a-jvm-in-a-container).

::: terminal

```sh
URL=service:jmx:rmi:///jndi/rmi://localhost:7091/jmxrmi
```

:::

## Turn on HikariCP's MBeans

HikariCP registers its MBeans only when you ask it to. Without that, the search finds nothing.

::: terminal

```sh
ajmx --url "$URL" search 'com.zaxxer.hikari:*' | jq -c .
```

```json
{"schemaVersion":1,"ok":true,"result":{"items":[],"returned":0,"truncated":false},"durationMs":35}
```

:::

Turn it on, and restart the application.

| Application | Setting |
|---|---|
| Spring Boot | `spring.datasource.hikari.register-mbeans=true` |
| HikariCP alone | `registerMbeans` in `HikariConfig` |

## Find the pool

::: terminal

```sh
ajmx --url "$URL" search 'com.zaxxer.hikari:*' | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "items": [
      "com.zaxxer.hikari:type=Pool (HikariPool-1)",
      "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)"
    ],
    "returned": 2,
    "truncated": false
  },
  "durationMs": 43
}
```

:::

Each pool has two MBeans, named after the pool. `Pool` shows the connections, and `PoolConfig`
the settings. The names contain a space and parentheses, so quote them in the shell.

## See what the pool shows

::: terminal

```sh
ajmx --url "$URL" describe 'com.zaxxer.hikari:type=Pool (HikariPool-1)' \
  | jq -c '.result.attributes[], .result.operations[]'
```

```json
{"name":"ActiveConnections","type":"int","readable":true,"writable":false}
{"name":"IdleConnections","type":"int","readable":true,"writable":false}
{"name":"ThreadsAwaitingConnection","type":"int","readable":true,"writable":false}
{"name":"TotalConnections","type":"int","readable":true,"writable":false}
{"name":"resumePool","returnType":"void","signature":[]}
{"name":"softEvictConnections","returnType":"void","signature":[]}
{"name":"suspendPool","returnType":"void","signature":[]}
```

:::

The attributes only read. The operations change the pool. See
[Changing a setting](./change-setting.md).

## Read the pool and its limits

Read the connections from `Pool` and the limits from `PoolConfig` in one
[`batch`](../batch.md), over one connection.

::: terminal

```sh
cat > pool.jsonl <<'EOF'
{"id": "pool", "op": "read", "mbean": "com.zaxxer.hikari:type=Pool (HikariPool-1)", "attributes": ["ActiveConnections", "IdleConnections", "ThreadsAwaitingConnection", "TotalConnections"]}
{"id": "config", "op": "read", "mbean": "com.zaxxer.hikari:type=PoolConfig (HikariPool-1)", "attributes": ["MaximumPoolSize", "MinimumIdle", "ConnectionTimeout"]}
EOF
ajmx --url "$URL" batch < pool.jsonl | jq '.result.items[] | {id, attributes: .result.attributes}'
```

```json
{
  "id": "pool",
  "attributes": {
    "ActiveConnections": 0,
    "IdleConnections": 10,
    "ThreadsAwaitingConnection": 0,
    "TotalConnections": 10
  }
}
{
  "id": "config",
  "attributes": {
    "MaximumPoolSize": 10,
    "MinimumIdle": 10,
    "ConnectionTimeout": 30000
  }
}
```

:::

| Attribute | Meaning |
|---|---|
| `ActiveConnections` | Connections in use by the application |
| `IdleConnections` | Open connections that wait in the pool |
| `TotalConnections` | Both together |
| `ThreadsAwaitingConnection` | Threads that wait for a connection |
| `MaximumPoolSize` | The most connections the pool opens |
| `ConnectionTimeout` | How long a thread waits for a connection, in milliseconds, before it fails |

This pool is idle: 10 connections are open and none is in use.

## Watch it under load

Read the pool once a second while the application is under load. Press Ctrl+C to stop.

::: terminal

```sh
while sleep 1; do
  ajmx --url "$URL" read 'com.zaxxer.hikari:type=Pool (HikariPool-1)' \
      ActiveConnections IdleConnections ThreadsAwaitingConnection \
    | jq -c .result.attributes
done
```

:::

Here, 50 clients sent requests at the same time for 8 seconds.

```json
{"ActiveConnections":0,"IdleConnections":10,"ThreadsAwaitingConnection":0}
{"ActiveConnections":0,"IdleConnections":10,"ThreadsAwaitingConnection":0}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":39}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":40}
{"ActiveConnections":10,"IdleConnections":0,"ThreadsAwaitingConnection":20}
{"ActiveConnections":0,"IdleConnections":10,"ThreadsAwaitingConnection":0}
{"ActiveConnections":0,"IdleConnections":10,"ThreadsAwaitingConnection":0}
```

The pool is exhausted:

- `ActiveConnections` equals `MaximumPoolSize`, and `IdleConnections` is 0. Every connection is in
  use.
- `ThreadsAwaitingConnection` stays above 0. Forty request threads wait for a connection.

A request that waits longer than `ConnectionTimeout` fails. Here, none did, but requests took
68 ms on average, against 15 ms with 8 clients.

## What to do next

- If the database can take more connections, raise `MaximumPoolSize`. You can try it on the
  running pool first. See [Changing a setting](./change-setting.md).
- If each request holds its connection for long, for example during a slow call inside a
  transaction, find where the time goes with a profiler such as
  [Verbatime](https://verbatime-docs.yagipass.com/).

## Next

- [Changing a setting at runtime](./change-setting.md)
- [batch](../batch.md)
