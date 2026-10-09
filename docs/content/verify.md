---
description: Check that an ajmx binary you downloaded is the one ajmx released, unchanged, with the GitHub CLI or its SHA-256 checksum.
---

# Verifying downloads

The install script and Homebrew check the binary for you. To check a binary you downloaded from
the [GitHub releases page](https://github.com/yagipass/ajmx/releases) yourself, verify it with
the [GitHub CLI](https://cli.github.com/).

::: terminal

```sh
gh attestation verify ajmx-darwin-arm64 --repo yagipass/ajmx
```

:::

This checks that GitHub Actions in `yagipass/ajmx` built the file. The binaries are
`ajmx-darwin-arm64`, `ajmx-linux-amd64` and `ajmx-linux-arm64`, and `install.sh` is attested too.

The install script verifies the attestation when GitHub CLI 2.93.0 or later is installed, and
falls back to the SHA-256 checksum otherwise.

## With the checksum

Each binary has a `.sha256` file next to it on the releases page. Download both into one
directory and check the binary against it.

::: terminal

```sh
shasum -a 256 -c ajmx-darwin-arm64.sha256
```

```text
ajmx-darwin-arm64: OK
```

:::

A checksum shows that the download is complete and unchanged, but not who built it. Prefer the
attestation when you can.
