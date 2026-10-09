---
description: Why ajmx exists. An AI agent needs JMX to look inside a running JVM, and ajmx gives it one command per question and one JSON document per answer.
---

# What is ajmx?

ajmx is a JMX CLI for AI agents, to inspect and control a running JVM. It lists local JVMs and
searches, describes, reads, writes and invokes MBeans on local and remote JVMs.

## Why

To look inside a running JVM, such as its heap, threads, connection pools or log levels, an agent
has to go through JMX. The usual JMX tools are made for people. JDK Mission Control, VisualVM and
jconsole are GUIs, and jmxterm prints text meant to be read, not parsed.

ajmx reads or changes a JVM in one command and prints the result as one JSON document.

**Zero setup.**

`--pid` connects to a local JVM even without remote JMX enabled. No JVM flags and no restart, so you
can look at a problem while it is still happening.

**Only JSON.**

Every result is one JSON document, and so is every error. `error.code` and `retryable` tell the
agent what happened and whether to try again, with no stack trace to interpret.

**Always bounded.**

`--limit` and `--max-bytes` cap the output, so no command fills the agent's context with more than
you allow. `truncated` marks what was cut.

**Find, read, change.**

An agent needs no MBean names up front. `search` and `describe` show what a JVM has, so the agent
can work on an application it has never seen.

**Single binary.**

A native binary. No JVM or daemon required.

## Just ask

Add the [agent skill](./agent-skill.md) and ask about a running JVM in your own words. The agent
finds the JVM and its MBeans, reads them, and changes the JVM only when you ask.

## What it looks like

::: terminal

```sh
ajmx --pid 12345 read java.lang:type=Memory HeapMemoryUsage
```

```json
{"schemaVersion":1,"ok":true,"result":{"mbean":"java.lang:type=Memory","attributes":{"HeapMemoryUsage":{"committed":268435456,"init":268435456,"max":268435456,"used":41491160}}},"durationMs":19}
```

:::

An agent decides on `ok` and `error.code`, and reads the values from `result`. See
[Output](./output.md) and [Errors and exit codes](./errors.md).

## Next

- [Quick start](./quick-start.md)
- [Use cases](./use-cases.md), from heap and threads to changing a setting
