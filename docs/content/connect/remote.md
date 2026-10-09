---
description: Connect ajmx to a JVM on another host or in a container with --url, the JVM flags that turn on remote JMX, and how to pass a username and password.
---

# Remote JVMs

`--url` connects to a JVM that has remote JMX turned on: on another host, in a container, or on
your machine. For a local JVM without remote JMX, use [`--pid`](./local.md).

## The URL

```text
service:jmx:rmi:///jndi/rmi://<host>:<port>/jmxrmi
```

`<host>` and `<port>` are where the JVM's JMX agent listens.

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi read java.lang:type=Runtime VmName VmVersion | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "mbean": "java.lang:type=Runtime",
    "attributes": {
      "VmName": "OpenJDK 64-Bit Server VM",
      "VmVersion": "25.0.4.1.1+1-jvmci-25.4-b23"
    }
  },
  "durationMs": 41
}
```

:::

ajmx needs the whole URL. `--url localhost:7199` fails with `INVALID_ARGUMENT`.

Each command opens its own connection. To send several requests over one connection, use
[`batch`](../batch.md).

## Turn on remote JMX

Start the target JVM with these system properties.

::: terminal

```sh
java -Dcom.sun.management.jmxremote.port=7199 \
     -Dcom.sun.management.jmxremote.rmi.port=7199 \
     -Dcom.sun.management.jmxremote.host=127.0.0.1 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Djava.rmi.server.hostname=localhost \
     -jar app.jar
```

:::

::: warning
These flags turn off authentication, so anyone who can reach the port can read and change the
JVM. Use them on your own machine for development. Elsewhere, turn on [credentials](#credentials).
:::

| Property | Value |
|---|---|
| `com.sun.management.jmxremote.port` | The port in the URL |
| `com.sun.management.jmxremote.rmi.port` | The same port. Without it, the JVM also listens on a random port, which a firewall or a container does not let through |
| `java.rmi.server.hostname` | The host name that ajmx uses to reach the JVM. The JVM hands it to ajmx, and ajmx connects to it for every call |
| `com.sun.management.jmxremote.host` | `127.0.0.1` to accept connections from the same machine only. Leave it out to accept them from other hosts |
| `com.sun.management.jmxremote.authenticate` | `true` to require a username and password. See [Credentials](#credentials) |
| `com.sun.management.jmxremote.ssl` | `false`. ajmx has no TLS settings, such as a trust store |

## A JVM in a container

Publish the JMX port on `127.0.0.1` only, and set `java.rmi.server.hostname` to `localhost`, the
name that ajmx uses to reach the published port. With Docker Compose:

```yaml
services:
  app:
    # image, build, and the rest of the service
    ports:
      - "127.0.0.1:7199:7199"
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Dcom.sun.management.jmxremote.port=7199
        -Dcom.sun.management.jmxremote.rmi.port=7199
        -Dcom.sun.management.jmxremote.authenticate=false
        -Dcom.sun.management.jmxremote.ssl=false
        -Djava.rmi.server.hostname=localhost
```

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi ping | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": true,
  "result": {
    "connected": true
  },
  "durationMs": 48
}
```

:::

- Do not set `com.sun.management.jmxremote.host=127.0.0.1` in the container. Docker forwards the
  port to the container's own address, where the JVM then does not listen, and ajmx fails with
  `CONNECTION_FAILED`.
- Without `java.rmi.server.hostname`, the JVM hands out the container's own address. When your
  machine cannot reach it, as with Docker Desktop, ajmx waits until `--timeout` and fails with
  `CONNECTION_TIMEOUT`.

## Credentials

:::: steps

1. Write a password file and an access file, and make them readable by their owner only. The JVM
   refuses to start if other users can read the password file.

   ::: terminal

   ```sh
   printf 'operator s3cret-pw\nviewer v1ew-pw\n' > jmxremote.password
   printf 'operator readwrite\nviewer readonly\n' > jmxremote.access
   chmod 600 jmxremote.password jmxremote.access
   ```

   :::

2. Start the JVM with authentication turned on.

   ::: terminal

   ```sh
   java -Dcom.sun.management.jmxremote.port=7199 \
        -Dcom.sun.management.jmxremote.rmi.port=7199 \
        -Dcom.sun.management.jmxremote.authenticate=true \
        -Dcom.sun.management.jmxremote.password.file=jmxremote.password \
        -Dcom.sun.management.jmxremote.access.file=jmxremote.access \
        -Dcom.sun.management.jmxremote.ssl=false \
        -Djava.rmi.server.hostname=localhost \
        -jar app.jar
   ```

   :::

3. Give ajmx the username and password in `JMX_USERNAME` and `JMX_PASSWORD`.

   ::: terminal

   ```sh
   export JMX_USERNAME=operator
   read -rs JMX_PASSWORD && export JMX_PASSWORD
   ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi ping
   ```

   :::

   Or pass them as JSON on stdin with `--credentials-stdin`.

   ::: terminal

   ```sh
   ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi --credentials-stdin ping < credentials.json
   ```

   :::

   ```json
   {"username": "operator", "password": "s3cret-pw"}
   ```

::::

ajmx has no option that takes a password, so it never shows up in your shell history or the
process list. `batch` reads its requests from stdin, so it takes credentials from the environment
variables only.

When an AI agent runs ajmx, export the variables yourself rather than paste the password into the
conversation. See [Agent skill](../agent-skill.md).

A wrong or missing password fails with `AUTH_FAILED`. Retrying does not help.

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi ping | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": false,
  "error": {
    "code": "AUTH_FAILED",
    "message": "Access denied by the JMX server",
    "retryable": false,
    "details": {
      "url": "service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi"
    }
  },
  "durationMs": 19
}
```

:::

A `readonly` user, such as `viewer` above, can `search`, `describe` and `read`. A `write` or
`invoke` fails with `AUTH_FAILED` and does not run. This includes operations that only read, such
as `findDeadlockedThreads`. Here `JMX_USERNAME` is `viewer`:

::: terminal

```sh
ajmx --url service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi write java.lang:type=Memory Verbose=true | jq .
```

```json
{
  "schemaVersion": 1,
  "ok": false,
  "error": {
    "code": "AUTH_FAILED",
    "message": "Access denied by the JMX server",
    "retryable": false,
    "details": {
      "mbean": "java.lang:type=Memory",
      "attribute": "Verbose"
    }
  },
  "durationMs": 18
}
```

:::

## Next

- [Local JVMs](./local.md)
- [Troubleshooting](../troubleshooting.md)
