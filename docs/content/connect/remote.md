---
description: Connect ajmx to a JVM on another host or in a container with --url, the JVM flags that turn on remote JMX, how to pass a username and password, and how to require TLS.
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
JVM. Use them on your own machine for development. Elsewhere, turn on [credentials](#credentials)
and [TLS](#tls).
:::

| Property | Value |
|---|---|
| `com.sun.management.jmxremote.port` | The port in the URL |
| `com.sun.management.jmxremote.rmi.port` | The same port. Without it, the JVM also listens on a random port, which a firewall or a container does not let through |
| `java.rmi.server.hostname` | The host name that ajmx uses to reach the JVM. The JVM hands it to ajmx, and ajmx connects to it for every call |
| `com.sun.management.jmxremote.host` | `127.0.0.1` to accept connections from the same machine only. Leave it out to accept them from other hosts |
| `com.sun.management.jmxremote.authenticate` | `true` to require a username and password. See [Credentials](#credentials) |
| `com.sun.management.jmxremote.ssl` | `false` to turn off TLS. To use TLS, see [TLS](#tls) |

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

Without [TLS](#tls), the username and password cross the network in plain text. Beyond your own
machine, turn on TLS too.

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

## TLS

`--tls` makes ajmx use TLS for both the RMI registry and the connection, and refuse a JVM that
does not. ajmx checks this before it sends the credentials.

:::: steps

1. Make a key pair for the JVM, and export its certificate. The subject alternative names must
   include the host in the URL and the value of `java.rmi.server.hostname`.

   ::: terminal

   ```sh
   read -rs STORE_PASSWORD && export STORE_PASSWORD
   keytool -genkeypair -alias jmx -keyalg RSA -validity 365 \
           -dname CN=jmx.example.com -ext san=dns:jmx.example.com \
           -storetype PKCS12 -keystore jmx.p12 -storepass:env STORE_PASSWORD
   keytool -exportcert -rfc -alias jmx -storetype PKCS12 -keystore jmx.p12 \
           -storepass:env STORE_PASSWORD -file jmx.pem
   ```

   :::

2. Write the key store and its password to a file readable by its owner only. The JVM refuses to
   start if other users can read it.

   ::: terminal

   ```sh
   printf 'javax.net.ssl.keyStore=jmx.p12\njavax.net.ssl.keyStorePassword=%s\n' "$STORE_PASSWORD" > jmxremote.ssl
   chmod 600 jmxremote.ssl
   ```

   :::

3. Start the JVM with TLS for both the registry and the connection.

   ::: terminal

   ```sh
   java -Dcom.sun.management.jmxremote.port=7199 \
        -Dcom.sun.management.jmxremote.rmi.port=7199 \
        -Dcom.sun.management.jmxremote.ssl=true \
        -Dcom.sun.management.jmxremote.registry.ssl=true \
        -Dcom.sun.management.jmxremote.ssl.config.file=jmxremote.ssl \
        -Dcom.sun.management.jmxremote.authenticate=true \
        -Dcom.sun.management.jmxremote.password.file=jmxremote.password \
        -Dcom.sun.management.jmxremote.access.file=jmxremote.access \
        -Djava.rmi.server.hostname=jmx.example.com \
        -jar app.jar
   ```

   :::

4. Give ajmx `--tls`, and the certificate with `--tls-ca`.

   ::: terminal

   ```sh
   ajmx --url service:jmx:rmi:///jndi/rmi://jmx.example.com:7199/jmxrmi --tls --tls-ca jmx.pem ping
   ```

   :::

::::

`--tls-ca` takes one or more PEM certificates, such as the JVM's own or the CA that signed it.
Without it, ajmx trusts the CAs built into it.

The JDK sets `com.sun.management.jmxremote.registry.ssl` to `false` by default. Then the registry
hands out the connection's address in plain text, which anyone on the network can replace, so
`--tls` fails with `TLS_FAILED`. Set it to `true`.

`--tls` takes only a `service:jmx:rmi:///jndi/rmi://` URL, and cannot present a client certificate.
See [Limitations](../limitations.md).

## Next

- [Local JVMs](./local.md)
- [Troubleshooting](../troubleshooting.md)
