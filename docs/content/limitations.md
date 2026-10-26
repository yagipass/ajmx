---
description: Known limitations of ajmx, such as classes that exist only in the target application, how deep and long a value it reads, the types write and invoke accept, and the JVMs --pid can attach to.
---

# Limitations

## Classes that exist only in the application

ajmx cannot deserialize values and exceptions of classes that exist only in the target
application, and cannot send values that are not serializable. Both fail with `UNSUPPORTED_TYPE`,
as do JDK classes the native binary leaves out, such as those of `java.desktop`.

MXBeans and most JDK MBeans use only open types, such as CompositeData, so ajmx reads them. For an
MBean that returns its own classes, read a related attribute or operation that returns open types
instead.

## Values nested too deeply or holding long arrays

ajmx does not read a value nested more than 500 levels deep, or holding an array of more than
16,777,216 elements. Some lists, sets and maps of about that size, such as `ArrayList`, `HashMap`
and `TabularData`, are refused too. `LinkedList`, `TreeMap` and `ConcurrentHashMap` are not. It
fails with `UNSUPPORTED_TYPE`, and `details` gives the limits as `maxDepth` and `maxArrayLength`.
The limits keep a JVM from making ajmx run out of stack with a deep value, or out of memory with an
array it declares but does not send. A JVM that does send more data than ajmx has memory for can
still make it run out of memory.

## Enums of Standard MBeans

An attribute of a Standard MBean whose type is an application-defined enum can be neither read nor
written. MXBean enums work, as strings.

## Types that write and invoke accept

`write` and `invoke` accept:

- primitives and their wrappers
- `String`, `ObjectName`, `BigInteger` and `BigDecimal`
- one-dimensional arrays of these

A `byte[]` is given as an array of numbers from -128 to 127, not as `{"$base64": ...}`.

## JVMs that --pid can attach to

- `--pid` supports HotSpot JVMs of the same user, or of any user for root on Linux. JDK 8 to 25
  are tested.
- It refuses a process that does not look like a HotSpot JVM with `ATTACH_NOT_SUPPORTED`, because
  attaching sends `SIGQUIT`, which kills many processes that are not JVMs.
- `--pid` does not work with OpenJ9 JVMs, such as IBM Semeru. ajmx does not refuse them up front.
  It fails with `ATTACH_NOT_SUPPORTED` after about `--timeout`. With OpenJ9's default dump
  settings, the JVM writes a `javacore` file into its working directory for each `SIGQUIT`, so a
  try can leave several. `--url` works.
- `--pid` starts the local JMX agent in the target JVM. It stays until the JVM restarts.

See [Local JVMs](./connect/local.md).

## TLS for --url

- `--tls` needs TLS for both the registry and the connection, as
  `com.sun.management.jmxremote.registry.ssl=true` and `com.sun.management.jmxremote.ssl=true`.
- `--tls` takes only a `service:jmx:rmi:///jndi/rmi://` URL.
- `--tls-ca` takes PEM certificates, not a key store.
- ajmx cannot present a client certificate, so it cannot connect to a JVM started with
  `com.sun.management.jmxremote.ssl.need.client.auth=true`.

See [TLS](./connect/remote.md#tls).

## JVMs that ps does not list

`ps` does not list JVMs started with `-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`. `--pid`
still connects to them if they run the `java` launcher.
