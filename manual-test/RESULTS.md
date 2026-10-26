# ajmx 0.2.0 手動テスト結果 (2026-10-26, 2 回目)

[TESTCASES.md](TESTCASES.md) のケースを `run.sh` で Docker 上で実行した結果。生の出力は [results/results.tsv](results/results.tsv) と [results/details.log](results/details.log)。

**221 件: PASS 211 / FAIL 1 / INFO 9。** 製品の不具合による FAIL はない。FAIL の 1 件 (G-01 install.sh) はこの環境のプロキシ制限で実行できなかったもの。

## 判定

0.2.0 はリリースしてよい。

- TLS (#88)、デシリアライズ制限 (#90)、別ネットワーク名前空間の拒否 (#101) は期待どおりに動き、既存機能の回帰もない。
- 1 回目で見つかった点は、#96〜#99 で docs に書いた挙動、メッセージ、対応ディストリビューションが実際と一致し、#101 で修正された。
- 残っているのは、リリースノートに `TLS_FAILED` の挙動変更を書くことと、リリース後の確認 (TESTCASES.md の H)。

## 環境

| 項目 | 値 |
|---|---|
| テスト対象 | リリース PR (yagipass/ajmx#23) の head `a5c8348` (main `dbc9d76` + `version=0.2.0`) を `./gradlew :cli:nativeCompile :it:classes` でビルドしたもの |
| ビルド環境 | `ghcr.io/graalvm/native-image-community:25` (GraalVM CE 25.0.2, Oracle Linux 9)。CI は ubuntu-latest と setup-graalvm |
| ホスト | Linux 6.18 x86_64, Docker 29.9.2, ホストの JVM は OpenJDK 21.0.12 (B-25) |
| ターゲット JVM | Temurin 1.8.0_504 / 11.0.32.1 / 17.0.20.1 / 21.0.12.1 / 25.0.4.1, Temurin 21 JRE, Corretto 21.0.13, Zulu 17.0.20.1, Semeru (OpenJ9) 21.0.12, Tomcat 10.1.61 (Temurin 21) |

CI の成果物はこの環境のプロキシが取得を拒否するため、手元でビルドしたバイナリを使った。glibc の要件 (`GLIBC_2.34`) は、リリース済みの v0.1.0 (CI ビルド) と同じ (A-06, G-03)。

## 1 回目 (main `12ded42`) からの変化

| 1 回目の指摘 | 対応 | 今回の確認 |
|---|---|---|
| glibc 2.34 未満と distroless で起動しない | #96 で docs に要件と環境を記載 | install.md が対応とする ubuntu:22.04 / 24.04, debian:12, rockylinux:9, amazonlinux:2023 で起動する (A-07)。動かないとする 4 つは docs が引用する ``version `GLIBC_2.34' not found`` で失敗し (A-07b)、distroless base / cc は `libz.so.1` がなく失敗する (A-07c) |
| troubleshooting.md のホスト名不一致の reason が DNS 名の場合と合わない | #97 で表の行を拡張 | DNS 名の SAN (`No subject alternative DNS name matching`)、IP の SAN だけの証明書にホスト名で接続 (`No name matching`)、IP アドレスで接続 (`No subject alternative names matching IP address`) のどれも表の行に合う (F-07b, F-07c, F-19) |
| OpenJ9 で javacore が書かれる | #99 で docs に記載 | troubleshooting.md のメッセージどおり、`--timeout 5s` に対して 4.5 秒で `ATTACH_NOT_SUPPORTED`。JVM は生きていて、javacore が 4 つ書かれた (B-13〜B-13d) |
| `CAP_SYS_PTRACE` のない root が passwd にない UID の JVM に繋がらない | #98 で docs に記載 | docs どおり、`ps` に出ない他ユーザーの JVM (passwd にない UID、`-XX:-UsePerfData`) には `CAP_SYS_PTRACE` のない root だけが `ATTACH_NOT_SUPPORTED` になり、`--cap-add SYS_PTRACE` した root と同じ UID は繋がる (B-18e〜B-18g, B-19〜B-19d) |
| ホストからコンテナ内 JVM に `--pid` すると agent を起動したうえで retryable な `CONNECTION_FAILED` | #101 で attach 前に拒否 | 下記 |

### #101 (別ネットワーク名前空間の拒否) の確認

| ajmx | JVM | 結果 |
|---|---|---|
| ホスト | 自分のネットワークを持つコンテナ | `ATTACH_NOT_SUPPORTED` (`The JVM runs in another network namespace ...`)、retryable でない、1〜2 ms。2 回目も同じ。`jcmd 1 ManagementAgent.status` は `Agent: disabled` のまま (B-22〜B-22b) |
| 別コンテナ (`--pid host`、別ネットワーク) | 同上 | 同じく拒否 (B-22d) |
| 別コンテナ (`--pid host`、JVM のネットワークを共有) | 同上 | 繋がる (B-22e) |
| 同じコンテナ (`docker exec`) | 同上 | 繋がる (B-22f, B 全般) |
| ホスト | `--network host` のコンテナ | 繋がる (B-24) |
| ホスト | ホスト上の JVM (コンテナなし) | `ps` に出て、`--pid` で読める (B-25) |

## INFO の内容

| ID | 内容 |
|---|---|
| A-05 / A-06 | `libz.so.1` と `libc.so.6` にリンクし、`GLIBC_2.34` まで要求する。v0.1.0 も同じ (G-03) |
| A-08 | alpine (musl) では起動しない (`exec ... no such file or directory`)。glibc 前提なので想定どおり |
| B-13d | OpenJ9 に 1 回試すと javacore が 4 つ書かれた |
| F-18 | `need.client.auth=true` は `TLS_FAILED` (`(certificate_required) Received fatal alert: certificate_required`)。Limitations どおり |
| G-02a / G-02 | v0.1.0 で 100,000 段の値を読むと `CONNECTION_FAILED` (`retryable: true`)。0.2.0 は `UNSUPPORTED_TYPE` (D-03) |
| G-02b | v0.1.0 で `--tls` なしの信頼できない TLS 接続は `CONNECTION_FAILED` (`retryable: true`)。0.2.0 は `TLS_FAILED` (`retryable: false`、F-12)。リリースノートに書く挙動変更 |

## この環境で実行できなかったもの

- **G-01 install.sh**: プロキシが `https://github.com/yagipass/ajmx/releases/v0.1.0` (Accept: application/json) を 403 にするため、タグの解決で止まる。代わりにアセットと `.sha256` を直接取得し、チェックサムと実行を確かめた (G-01b, G-01c)。install.sh は v0.1.0 から変わっていない。リリース後に H-01, H-02 で確認する。
- CI がビルドしたバイナリ。リリース後に配布バイナリで A の項目を確認する (H-06)。
- TESTCASES.md の H (Homebrew, Nix, attestation, macOS, arm64, docs サイト, リリースノート)。

## 全結果

| ID | 結果 | exit | ケース |
|---|---|---|---|
| A-01 | PASS | 0 | version prints the release version |
| A-02 | PASS | 0 | --version prints the same |
| A-03 | PASS | 0 | help is JSON and lists --tls and --tls-ca |
| A-04 | PASS | 0 | --help prints the usage like help |
| A-05 | INFO | 0 | dynamic libraries |
| A-06 | INFO | 0 | newest glibc symbol version required |
| A-07[ubuntu:22.04] | PASS | 0 | runs ps on ubuntu:22.04, which install.md lists as supported |
| A-07[ubuntu:24.04] | PASS | 0 | runs ps on ubuntu:24.04, which install.md lists as supported |
| A-07[debian:12] | PASS | 0 | runs ps on debian:12, which install.md lists as supported |
| A-07[rockylinux:9] | PASS | 0 | runs ps on rockylinux:9, which install.md lists as supported |
| A-07[amazonlinux:2023] | PASS | 0 | runs ps on amazonlinux:2023, which install.md lists as supported |
| A-07b[ubuntu:20.04] | PASS | 1 | fails on ubuntu:20.04 with the glibc error install.md quotes |
| A-07b[debian:11] | PASS | 1 | fails on debian:11 with the glibc error install.md quotes |
| A-07b[rockylinux:8] | PASS | 1 | fails on rockylinux:8 with the glibc error install.md quotes |
| A-07b[amazonlinux:2] | PASS | 1 | fails on amazonlinux:2 with the glibc error install.md quotes |
| A-07c[gcr.io/distroless/base-debian12] | PASS | 127 | fails on gcr.io/distroless/base-debian12 for want of zlib, as install.md says |
| A-07c[gcr.io/distroless/cc-debian12] | PASS | 127 | fails on gcr.io/distroless/cc-debian12 for want of zlib, as install.md says |
| A-08[alpine] | INFO | 255 | runs on musl (alpine:3.20) |
| B-01[jdk8] | PASS | 0 | ps lists the JVM (PID 1) with mainClass and displayName |
| B-02[jdk8] | PASS | 0 | ping attaches and starts the local agent |
| B-03[jdk8] | PASS | 0 | read Runtime VmVendor SpecVersion |
| B-04[jdk8] | PASS | 0 | second ping reuses the running agent |
| B-05[jdk8] | PASS | 0 | batch of read, search and invoke |
| B-06[jdk8] | PASS | 5 | a value 501 levels deep fails with UNSUPPORTED_TYPE |
| B-07[jdk8] | PASS | 0 | Standard MBean write and read back |
| B-01[jdk11] | PASS | 0 | ps lists the JVM (PID 1) with mainClass and displayName |
| B-02[jdk11] | PASS | 0 | ping attaches and starts the local agent |
| B-03[jdk11] | PASS | 0 | read Runtime VmVendor SpecVersion |
| B-04[jdk11] | PASS | 0 | second ping reuses the running agent |
| B-05[jdk11] | PASS | 0 | batch of read, search and invoke |
| B-06[jdk11] | PASS | 5 | a value 501 levels deep fails with UNSUPPORTED_TYPE |
| B-07[jdk11] | PASS | 0 | Standard MBean write and read back |
| B-01[jdk17] | PASS | 0 | ps lists the JVM (PID 1) with mainClass and displayName |
| B-02[jdk17] | PASS | 0 | ping attaches and starts the local agent |
| B-03[jdk17] | PASS | 0 | read Runtime VmVendor SpecVersion |
| B-04[jdk17] | PASS | 0 | second ping reuses the running agent |
| B-05[jdk17] | PASS | 0 | batch of read, search and invoke |
| B-06[jdk17] | PASS | 5 | a value 501 levels deep fails with UNSUPPORTED_TYPE |
| B-07[jdk17] | PASS | 0 | Standard MBean write and read back |
| B-01[jdk21] | PASS | 0 | ps lists the JVM (PID 1) with mainClass and displayName |
| B-02[jdk21] | PASS | 0 | ping attaches and starts the local agent |
| B-03[jdk21] | PASS | 0 | read Runtime VmVendor SpecVersion |
| B-04[jdk21] | PASS | 0 | second ping reuses the running agent |
| B-05[jdk21] | PASS | 0 | batch of read, search and invoke |
| B-06[jdk21] | PASS | 5 | a value 501 levels deep fails with UNSUPPORTED_TYPE |
| B-07[jdk21] | PASS | 0 | Standard MBean write and read back |
| B-01[jdk25] | PASS | 0 | ps lists the JVM (PID 1) with mainClass and displayName |
| B-02[jdk25] | PASS | 0 | ping attaches and starts the local agent |
| B-03[jdk25] | PASS | 0 | read Runtime VmVendor SpecVersion |
| B-04[jdk25] | PASS | 0 | second ping reuses the running agent |
| B-05[jdk25] | PASS | 0 | batch of read, search and invoke |
| B-06[jdk25] | PASS | 5 | a value 501 levels deep fails with UNSUPPORTED_TYPE |
| B-07[jdk25] | PASS | 0 | Standard MBean write and read back |
| B-10[corretto21] | PASS | 0 | Amazon Corretto 21: ping and read VmVendor |
| B-11[zulu17] | PASS | 0 | Azul Zulu 17: read VmVendor |
| B-12[jre21] | PASS | 0 | Temurin 21 JRE image: ping |
| B-13[openj9] | PASS | 3 | OpenJ9 fails after about --timeout with the message in troubleshooting.md |
| B-13b[openj9] | PASS | 0 | OpenJ9 JVM is still running after the attempt |
| B-13c[openj9] | PASS | 0 | OpenJ9 wrote javacore files into its working directory, as the docs warn |
| B-13d[openj9] | INFO | 0 | number of javacore files from one try |
| B-14 | PASS | 0 | -XX:-UsePerfData: ps does not list it |
| B-14b | PASS | 0 | -XX:-UsePerfData: --pid still connects |
| B-15 | PASS | 3 | -XX:+DisableAttachMechanism fails with ATTACH_NOT_SUPPORTED |
| B-16 | PASS | 3 | a process that is not a JVM is refused |
| B-16b | PASS | 0 | the process that is not a JVM is still alive |
| B-17 | PASS | 3 | a PID that does not exist |
| B-18 | PASS | 0 | root connects to a JVM of user 1000 |
| B-18b | PASS | 0 | user 1000 connects to its own JVM |
| B-18c | PASS | 3 | another user is refused with processUser |
| B-18d | PASS | 0 | user 1000 lists its JVM with ps |
| B-18e | PASS | 3 | root without CAP_SYS_PTRACE, user 1000's JVM that ps does not list: refused |
| B-18f | PASS | 0 | user 1000 still connects to it |
| B-18g | PASS | 0 | root with CAP_SYS_PTRACE connects to it |
| B-19 | PASS | 0 | a JVM of a UID without a passwd entry is not listed by ps (docs) |
| B-19b | PASS | 0 | a JVM of a UID without a passwd entry: --pid as that UID |
| B-19c | PASS | 3 | a JVM of a UID without a passwd entry: root without CAP_SYS_PTRACE is refused (docs) |
| B-19d | PASS | 0 | a JVM of a UID without a passwd entry: root with CAP_SYS_PTRACE connects (docs) |
| B-20 | PASS | 3 | a jlink runtime without jdk.management.agent |
| B-21 | PASS | 0 | a JVM that is not PID 1 (pid 7) |
| B-22 | PASS | 3 | host --pid on a JVM in a container with its own network: refused at once |
| B-22a | PASS | 3 | the same again |
| B-22b | PASS | 0 | the JVM's local agent was not started |
| B-22c | PASS | 0 | host ps does not list JVMs inside containers |
| B-22d | PASS | 3 | ajmx in another container sharing the host's PID namespace: refused |
| B-22e | PASS | 0 | ajmx in a container sharing the JVM's network and the host's PID namespace connects |
| B-22f | PASS | 0 | ajmx inside the JVM's container still connects |
| B-24 | PASS | 0 | host --pid on a JVM in a container on the host network (--network host) connects |
| B-25 | PASS | 0 | host: ps lists a JVM on the host |
| B-25b | PASS | 0 | host: --pid on a JVM on the host connects |
| B-23 | PASS | 0 | Tomcat: ps shows Bootstrap |
| B-23b | PASS | 0 | Tomcat: search the thread pools |
| B-23c | PASS | 0 | Tomcat: read maxThreads |
| B-23d | PASS | 0 | Tomcat: write maxThreads=150 and read it back |
| B-23e | PASS | 0 | Tomcat: describe Catalina:type=Server |
| C-01 | PASS | 0 | search ajmxtest:* is sorted |
| C-02 | PASS | 0 | a pattern without ,* matches only exact keys |
| C-02b | PASS | 0 | with ,* it matches |
| C-03 | PASS | 0 | describe lists attributes and operations |
| C-04 | PASS | 0 | read two attributes |
| C-05 | PASS | 7 | read with one bad attribute is partial (exit 7) |
| C-06 | PASS | 4 | read a single missing attribute |
| C-07 | PASS | 4 | read a missing MBean |
| C-08 | PASS | 4 | an unquoted name with spaces is split by the shell |
| C-09 | PASS | 0 | write a number |
| C-10 | PASS | 0 | write a boolean |
| C-11 | PASS | 0 | write an ObjectName |
| C-12 | PASS | 0 | write a String[] |
| C-13 | PASS | 0 | write an MXBean enum |
| C-14 | PASS | 2 | write a value of the wrong type |
| C-15 | PASS | 2 | write a read-only attribute |
| C-16 | PASS | 0 | invoke with a String argument |
| C-17 | PASS | 4 | an argument that fits two overloads is ambiguous |
| C-17b | PASS | 0 | --signature picks the overload |
| C-18 | PASS | 0 | --signature with two types |
| C-19 | PASS | 0 | invoke with an int[] argument |
| C-20 | PASS | 0 | byte[] is base64 |
| C-21 | PASS | 5 | an exception of a JDK class is REMOTE_EXCEPTION |
| C-21b | PASS | 5 | an exception of an application class is UNSUPPORTED_TYPE |
| C-22 | PASS | 5 | a value of an application class |
| C-23 | PASS | 7 | --limit cuts an array (exit 7) |
| C-24 | PASS | 7 | a 400 KB string does not fit the default --max-bytes |
| C-25 | PASS | 7 | search over --max-bytes drops trailing items |
| C-26 | PASS | 2 | --max-bytes below 512 |
| C-27 | PASS | 6 | a slow read times out and may be retried |
| C-28 | PASS | 6 | a slow invoke times out, not retryable, executed unknown |
| C-29 | PASS | 4 | an operation that does not exist |
| C-30 | PASS | 0 | HotSpotDiagnostic getVMOption (docs example) |
| C-31 | PASS | 7 | DiagnosticCommand threadPrint over --max-bytes (docs example) |
| C-31b | PASS | 0 | DiagnosticCommand gcClassHistogram (docs example) |
| C-32 | PASS | 0 | Threading getThreadInfo with long[] (docs example) |
| C-33 | PASS | 7 | SystemProperties --limit 2 (docs example) |
| C-34 | PASS | 7 | a list that contains itself is cut |
| C-35 | PASS | 0 | MXBean CompositeData and Map |
| C-36 | PASS | 0 | LastGcInfo after a GC (docs example) |
| C-40 | PASS | 0 | batch with every op |
| C-41 | PASS | 2 | a bad line rejects the whole batch with details.line |
| C-41b | PASS | 0 | no request of the rejected batch ran |
| C-42 | PASS | 7 | after an invoke times out, later writes are SKIPPED and reads still run |
| C-43 | PASS | 7 | batch over --max-bytes replaces the largest items |
| C-50 | PASS | 2 | unknown command lists the allowed ones |
| C-51 | PASS | 2 | unknown option |
| C-52 | PASS | 2 | --pid and --url together |
| C-53 | PASS | 2 | a command that needs a target without one |
| C-54 | PASS | 2 | a bad duration |
| C-55 | PASS | 7 | --limit=2 and options after the command |
| C-56 | PASS | 7 | -- ends the options |
| C-57 | PASS | 4 | --debug prints a stack trace to stderr, stdout stays JSON |
| C-57b | PASS | 4 | the stack trace is on stderr |
| C-58 | PASS | 2 | the same option twice |
| C-59 | PASS | 2 | --credentials-stdin with --pid |
| C-60 | PASS | 2 | --tls with --pid |
| C-61 | PASS | 2 | --tls-ca without --tls |
| D-01 | PASS | 7 | a value 500 levels deep is read (and cut at 32 for output) |
| D-02 | PASS | 5 | 501 levels: UNSUPPORTED_TYPE with the limits, not retryable |
| D-03 | PASS | 5 | an attribute 100,000 levels deep: UNSUPPORTED_TYPE, not INTERNAL_ERROR |
| D-04 | PASS | 0 | a byte[] of 16,777,216 elements is read |
| D-05 | PASS | 5 | a byte[] of 16,777,217 elements: UNSUPPORTED_TYPE |
| D-06 | PASS | 7 | in a batch, the deep value fails alone |
| D-07 | PASS | 7 | a 100,000,000-character string does not fit --max-bytes |
| D-08 | PASS | 0 | ajmx still works after D-07 |
| E-01 | PASS | 0 | host to a published container port (docs recipe) |
| E-02 | PASS | 0 | host: read VmName VmVersion (docs example) |
| E-03 | PASS | 0 | container to container: search, describe, read |
| E-03b | PASS | 0 | container to container: write and invoke |
| E-04 | PASS | 0 | a JDK 8 JVM over --url |
| E-05 | PASS | 3 | jmxremote.host=127.0.0.1 in the container: CONNECTION_FAILED (docs) |
| E-06 | PASS | 3 | an unreachable java.rmi.server.hostname: CONNECTION_TIMEOUT |
| E-07 | PASS | 3 | nothing listens on the port |
| E-08 | PASS | 2 | --url host:port is not a URL |
| E-09 | PASS | 0 | operator: ping |
| E-09b | PASS | 0 | operator: write Verbose (docs example) |
| E-10 | PASS | 0 | viewer: read |
| E-10b | PASS | 3 | viewer: write fails with AUTH_FAILED and mbean in details |
| E-10c | PASS | 3 | viewer: invoke findDeadlockedThreads fails (docs) |
| E-11 | PASS | 3 | a wrong password |
| E-12 | PASS | 3 | no credentials |
| E-13 | PASS | 0 | --credentials-stdin |
| E-14 | PASS | 2 | --credentials-stdin with a malformed document |
| E-15 | PASS | 2 | batch with --credentials-stdin is refused |
| E-16 | PASS | 0 | batch with credentials from the environment |
| E-17 | PASS | 5 | 501 levels over --url: UNSUPPORTED_TYPE |
| E-18 | PASS | 5 | 100,000 levels over --url: UNSUPPORTED_TYPE |
| F-01 | PASS | 0 | --tls --tls-ca: ping (docs recipe) |
| F-02 | PASS | 0 | read over TLS |
| F-02b | PASS | 0 | write over TLS |
| F-02c | PASS | 0 | invoke over TLS |
| F-03 | PASS | 0 | batch over TLS |
| F-04 | PASS | 0 | --credentials-stdin over TLS |
| F-05 | PASS | 3 | without --tls-ca a self-signed certificate is not trusted |
| F-06 | PASS | 3 | --tls-ca with another certificate |
| F-07 | PASS | 3 | a certificate for another host name |
| F-07b | PASS | 3 | ...with a reason the troubleshooting.md row matches (DNS name) |
| F-07c | PASS | 3 | a certificate with only an IP address, by host name: the row matches 'No name matching' |
| F-08 | PASS | 3 | a registry without TLS (registry.ssl=false) |
| F-09 | PASS | 3 | a connection without TLS (ssl=false, registry.ssl=true) |
| F-10 | PASS | 3 | --tls against a JVM without any TLS |
| F-11 | PASS | 3 | --tls against a plain-text JVM with auth fails... |
| F-11b | PASS | 0 | ...and the password never crossed the network |
| F-11c | PASS | 3 | control: without --tls the same password is sent in plain text |
| F-11d | PASS | 0 | control: the capture shows the password |
| F-12 | PASS | 3 | without --tls, an untrusted certificate is TLS_FAILED, not a retryable CONNECTION_FAILED |
| F-13 | PASS | 0 | --tls-ca with the CA that signed the certificate |
| F-14 | PASS | 0 | --tls-ca with a PEM bundle of several certificates |
| F-15 | PASS | 2 | --tls-ca: a missing file, before connecting |
| F-15b | PASS | 2 | --tls-ca: an empty file |
| F-15c | PASS | 2 | --tls-ca: a file that is not PEM |
| F-15d | PASS | 2 | --tls-ca: a key store instead of PEM |
| F-16 | PASS | 2 | --tls with a URL that is not /jndi/rmi:// |
| F-17 | PASS | 0 | --tls on a JDK 8 JVM |
| F-18 | INFO | 3 | need.client.auth=true (documented as unsupported) |
| F-19 | PASS | 3 | an IP address in the URL that the certificate does not name |
| F-20 | PASS | 3 | a wrong password over TLS is AUTH_FAILED |
| F-21 | PASS | 3 | viewer write over TLS is AUTH_FAILED |
| F-22 | PASS | 5 | 501 levels over TLS: UNSUPPORTED_TYPE |
| G-01 | FAIL | 1 | install.sh installs the released v0.1.0 into -b |
| G-01b | PASS | 0 | download the v0.1.0 asset and check its SHA-256 |
| G-01c | PASS | 0 | the released v0.1.0 binary runs |
| G-02a | INFO | 0 | for comparison: v0.1.0 ping |
| G-02 | INFO | 3 | for comparison: v0.1.0 reads the 100,000-level value |
| G-02b | INFO | 3 | for comparison: v0.1.0 without --tls against an untrusted TLS connection |
| G-03 | INFO | 0 | newest glibc symbol version the released v0.1.0 requires |
