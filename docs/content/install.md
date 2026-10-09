---
description: Install the ajmx native binary on macOS or Linux with the install script, Homebrew or Nix.
---

# Installation

ajmx is a single native binary for macOS on Apple silicon and Linux on x86_64 or arm64. It needs
no JVM.

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

The install script downloads the binary for your platform from the
[GitHub releases page](https://github.com/yagipass/ajmx/releases) into the directory given with
`-b`. It checks the binary before installing it. See [Verifying downloads](./verify.md).

To install a specific version, give its tag after the options.

::: terminal

```sh
curl -fsSL https://github.com/yagipass/ajmx/releases/latest/download/install.sh | sh -s -- -b ~/.local/bin v0.1.0
```

:::

## Check the install

::: terminal

```sh
ajmx version
```

```json
{"schemaVersion":1,"ok":true,"result":{"version":"0.1.0"},"durationMs":1}
```

:::

## Next

- [Quick start](./quick-start.md)
- [Agent skill](./agent-skill.md)
