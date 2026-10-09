---
description: Add the ajmx agent skill to an AI agent, so it explores a JVM with ajmx, asks before changing it, and reads partial results and errors correctly.
---

# Agent skill

[`skills/ajmx`](https://github.com/yagipass/ajmx/blob/main/skills/ajmx/SKILL.md) is an agent
skill that teaches an AI agent how to look inside a JVM with ajmx. Add it with one of these
commands, then ask about a running JVM in your own words. The agent finds the JVM and its MBeans,
reads them, and changes the JVM only when you ask.

::: code-group

```sh [GitHub CLI (2.90.0+)]
gh skill install yagipass/ajmx ajmx
```

```sh [npx skills]
npx skills add yagipass/ajmx --skill ajmx
```

```sh [apm]
apm install yagipass/ajmx/skills/ajmx
```

:::

The agent also needs `ajmx` on its `PATH`. See [Installation](./install.md).

## What to ask

- Why does the heap of `OrderService` keep growing?
- Are there deadlocked threads in the JVM listening on port 9010?
- Is the connection pool of the local Spring Boot app running out?
- Turn on a heap dump on `OutOfMemoryError` in the running app.

## What the skill teaches

`ajmx help` already lists the commands and options of the installed version, and the skill tells
the agent to run it first. The skill covers what help does not.

- **Exploring.** Pick the JVM with `ps`, `search` with a narrow pattern, `describe` before
  reading or changing, and send several calls as one `batch`.
- **Changing a JVM.** Run `write` and `invoke` only for a change the user asked for, `read` the
  old value first, and confirm an operation whose effect is unclear. Confirm the operations that
  load the JVM, such as a heap dump, before running them on a production JVM.
- **Partial results.** Report a cut or partial result as such. Set the limits before an `invoke`
  whose result cannot be read again.
- **Errors.** Retry only what is `retryable`, and never retry a `write` or `invoke` whose
  `executed` is `unknown`. Tell the user about `ATTACH_PERMISSION_DENIED` instead of reaching for
  `sudo`.
- **Credentials.** Ask the user to export `JMX_USERNAME` and `JMX_PASSWORD` instead of pasting a
  password into the conversation.

## Next

- [Use cases](./use-cases.md)
- [Errors and exit codes](./errors.md)
