---
description: Guides to look inside a running JVM with ajmx, from heap, GC and threads to connection pools, changing a setting, and an application's own MBean, each with real output.
---

# Use cases

Pick the case closest to yours. Each page shows which MBeans to look at and how to read them, with
real output.

| Case | MBeans | Changes the JVM |
|---|---|---|
| [Heap and GC](./use-cases/heap-gc.md) | `java.lang:type=Memory`, `GarbageCollector`, `MemoryPool`, `DiagnosticCommand` | No |
| [Deadlocks and threads](./use-cases/threads.md) | `java.lang:type=Threading`, `DiagnosticCommand` | No |
| [Connection pools](./use-cases/connection-pool.md) | `com.zaxxer.hikari` | No |
| [Changing a setting](./use-cases/change-setting.md) | `com.zaxxer.hikari`, `HotSpotDiagnostic` | Yes |
| [An application's own MBean](./use-cases/app-mbean.md) | The application's own, here `verbatime:type=Control` | Yes |

## Let an AI agent do it

[Add the ajmx skill](./agent-skill.md) to your agent, and ask it about the JVM in your own words.
