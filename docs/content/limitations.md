---
description: Known limitations of ajmx, such as classes that exist only in the target application, the types write and invoke accept, and the JVMs --pid can attach to.
---

# Limitations

## Classes that exist only in the application

ajmx cannot deserialize values and exceptions of classes that exist only in the target
application, and cannot send values that are not serializable. Both fail with `UNSUPPORTED_TYPE`,
as do JDK classes the native binary leaves out, such as those of `java.desktop`.

MXBeans and most JDK MBeans use only open types, such as CompositeData, so ajmx reads them. For an
MBean that returns its own classes, read a related attribute or operation that returns open types
instead.

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
- `--pid` starts the local JMX agent in the target JVM. It stays until the JVM restarts.

See [Local JVMs](./connect/local.md).

## No TLS settings for --url

ajmx has no TLS settings, such as a trust store or a client certificate. It is tested against
JVMs started with `com.sun.management.jmxremote.ssl=false`. See
[Remote JVMs](./connect/remote.md#turn-on-remote-jmx).

## JVMs that ps does not list

`ps` does not list JVMs started with `-XX:-UsePerfData` or `-XX:+PerfDisableSharedMem`. `--pid`
still connects to them if they run the `java` launcher.
