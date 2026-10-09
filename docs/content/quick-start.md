---
description: Install ajmx and its agent skill, then ask your AI agent about a JVM running on your machine with /ajmx.
---

# Quick start

Install ajmx and its agent skill, then ask your agent about a JVM running on your machine. The JVM
needs no flags and no restart.

## Requirements

| Part | Needs |
|---|---|
| ajmx | macOS on Apple silicon, or Linux on x86_64 or arm64 |
| AI agent | One that supports agent skills |
| Target JVM | A HotSpot JVM, JDK 8 to 25, running as your user |

## Ask about a JVM

:::: steps

1. Install ajmx. The agent runs it from your `PATH`.

   ::: code-group

   ```sh [Install script]
   curl -fsSL https://github.com/yagipass/ajmx/releases/latest/download/install.sh | sh -s -- -b ~/.local/bin
   ```

   ```sh [Homebrew]
   brew install yagipass/tap/ajmx
   ```

   ```sh [Nix]
   nix profile install github:yagipass/ajmx
   ```

   :::

2. Add the agent skill.

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

3. Ask your agent with `/ajmx`.

   ```text
   /ajmx Why does the heap of OrderService keep growing?
   ```

   The agent finds the JVM and its MBeans, reads them, and answers. It changes the JVM only when you
   ask.

::::

## Next

- [Agent skill](./agent-skill.md), for more to ask and what the skill teaches
- [Use cases](./use-cases.md), from heap and threads to changing a setting
- [Commands](./commands.md), to run ajmx yourself
- [Remote JVMs](./connect/remote.md), to connect with `--url`
