# ajmx リリース前 手動テストケース

リリースする変更のうち、CI (`./gradlew -Pnative check` と JDK マトリクス) が見ていない「実環境に近い条件」を手で確かめるためのテストケース。0.2.0 のために作り、0.2.2 で #102 と #118 のケースを足した。リリースのたびに全件を流す。

## 対象と観点

| 変更 | 観点 |
|---|---|
| #102 `--debug` のスタックトレースの制御文字をエスケープ (0.2.1)、#118 JSON 出力の DEL と C1 制御文字をエスケープ (0.2.2) | ターゲットが返す例外メッセージ、戻り値、属性値に C0 / DEL / C1 制御文字があっても、stdout と stderr に生の制御文字が出ないか。JSON をパースすると元の文字列に戻るか。`--max-bytes` がエスケープ後の 6 バイトで数えるか。`--pid`、`--url`、`batch` のどれでも同じか |
| #88 `--tls` / `--tls-ca` / `TLS_FAILED` | docs の手順どおりに TLS の JVM を立てて繋がるか。信頼できない証明書、ホスト名違い、registry や接続が平文の JVM を拒否するか。平文の JVM に資格情報を送らないか。`--tls` なしの TLS 失敗が `CONNECTION_FAILED` (retryable) から `TLS_FAILED` に変わったこと |
| #90 デシリアライズ制限 (`maxdepth=500;maxarray=16777216`) | 境界値 (500/501 段、16,777,216/16,777,217 要素) で読める/`UNSUPPORTED_TYPE` になるか。`--pid`/`--url`/TLS/`batch` のどの経路でも効くか。v0.1.0 との差 |
| #101 別ネットワーク名前空間の JVM を `--pid` で拒否 | ホストや別コンテナからコンテナ内の JVM を指定すると、attach せずにすぐ `ATTACH_NOT_SUPPORTED` で拒否するか。同じ名前空間 (コンテナ内、`--network host`、ネットワーク共有、コンテナなしのホスト) では今までどおり繋がるか |
| #96〜#99 ドキュメント (glibc と zlib、TLS の reason、OpenJ9、`CAP_SYS_PTRACE` のない root) | docs に書いた挙動、メッセージ、対応ディストリビューションが実際と合うか |
| Maven から Gradle への移行 (#33) ほかビルドの変更 | `version` がリリースのバージョンを返すか。ネイティブバイナリが動く Linux ディストリビューションと、リンクするライブラリ |
| 既存機能 (回帰) | ターゲット JDK 8〜25 とベンダー違い、コンテナ、ユーザー違い、Tomcat 等の実アプリ、docs の例のコマンド |

## 実行方法

`run.sh` が下の ID のうち「自動」のものを Docker で実行する。ターゲットには IT と同じ `ajmxtest.TestTarget` を使う。

```sh
# リリース PR のブランチ (tagpr-from-vX.Y.Z) をビルドする。GraalVM 25 のコンテナでもよい
./gradlew :cli:nativeCompile :it:classes
# VERSION はバイナリが返すべきバージョン、PREV は比べる前のリリース (既定 v0.2.1)
VERSION=0.2.2 PREV=v0.2.1 manual-test/run.sh modules/cli/build/native/nativeCompile/ajmx modules/it/build/classes/java/main
# 一部だけ: SECTIONS=EF VERSION=... manual-test/run.sh ... 、ID で絞る: VERSION=... manual-test/run.sh ... ... '^F-1'
```

結果は `results.tsv` (ID・PASS/FAIL/INFO・終了コード・説明) と `details.log` (コマンドと出力) に出る。INFO は判定せず挙動を記録するだけのケース。

使うイメージ: `eclipse-temurin:{8,11,17,21,25}-jdk`, `eclipse-temurin:21-jre`, `amazoncorretto:21`, `azul/zulu-openjdk:17`, `ibm-semeru-runtimes:open-21-jdk`, `tomcat:10.1-jdk21-temurin`, `ubuntu:{20.04,22.04,24.04}`, `debian:{11,12}`, `rockylinux:{8,9}`, `amazonlinux:{2,2023}`, `alpine:3.20`, `gcr.io/distroless/{base,cc}-debian12`, `nicolaka/netshoot`。ホストの 127.0.0.1:7199 を使う。B-25 はホストの `java` で TestTarget を動かす (なければ飛ばす)。

## A. バイナリ

| ID | 観点 | 手順 | 期待結果 |
|---|---|---|---|
| A-01 | バージョン | `ajmx version` | `result.version` が `VERSION` |
| A-02 | `--version` | `ajmx --version` | 同上 |
| A-03 | help に新オプション | `ajmx help` | JSON に `--tls`, `--tls-ca` がある |
| A-04 | `--help` | `ajmx --help` | `ok: true` |
| A-05 | リンクするライブラリ | `ldd ajmx` | libc, libz, vdso, ld-linux のみ (INFO) |
| A-06 | 必要な glibc | `objdump -T` の `GLIBC_*` の最大 | 記録 (INFO) |
| A-07 | install.md が対応とする glibc 2.34 以上の環境 | ubuntu:22.04 / 24.04, debian:12, rockylinux:9, amazonlinux:2023 で `ajmx ps` | 起動して空の一覧を返す |
| A-07b | install.md が動かないとする環境 | ubuntu:20.04, debian:11, rockylinux:8, amazonlinux:2 で `ajmx ps` | install.md どおり ``version `GLIBC_2.34' not found`` で失敗する |
| A-07c | zlib のない distroless | distroless/base-debian12, cc-debian12 で `ajmx ps` | install.md どおり `libz.so.1` がなくて失敗する |
| A-08 | musl | alpine:3.20 で `ajmx version` | 記録 (INFO)。glibc 前提なので動かないのが想定 |

## B. `--pid` (ローカル attach)

コンテナ内で TestTarget を PID 1 で動かし、同じコンテナで `docker exec` から ajmx を実行する。

| ID | 観点 | 手順 | 期待結果 |
|---|---|---|---|
| B-01〜B-07 [jdk8/11/17/21/25] | ターゲット JDK ごとの基本操作 | `ps`、`--pid 1 ping` (初回 attach で agent 起動)、`read Runtime`、2 回目の `ping` (起動済み agent に接続)、`batch` (read/search/invoke)、`invoke nested [501]`、`write MaxConnections=42` | すべて成功。501 段は `UNSUPPORTED_TYPE` (exit 5, `maxDepth: 500`) |
| B-10 | Amazon Corretto 21 | `read Runtime VmVendor` | 成功 |
| B-11 | Azul Zulu 17 | 同上 | 成功 |
| B-12 | JRE イメージ (Temurin 21 JRE) | `ping` | 成功 |
| B-13 | OpenJ9 (Semeru 21) | `--timeout 5s ping`、その後プロセスの生存確認、作業ディレクトリのファイルの確認 | troubleshooting.md の `The process did not respond to attach ...` で `ATTACH_NOT_SUPPORTED` (exit 3, `timeoutMs: 5000`)、5 秒ほどかかる。JVM は生きている。docs の警告どおり `javacore.*.txt` が書かれる (個数は INFO) |
| B-14 | `-XX:-UsePerfData` | `ps`、`--pid 1 ping` | `ps` に出ない。`--pid` は繋がる |
| B-15 | `-XX:+DisableAttachMechanism` | `--pid 1 ping` | `ATTACH_NOT_SUPPORTED`、message `The JVM disables the attach mechanism` |
| B-16 | JVM でないプロセス (`sleep`) | `--pid <sleep>` | `ATTACH_NOT_SUPPORTED`、`sleep` は生きている (SIGQUIT を送っていない) |
| B-17 | 存在しない PID | `--pid 999999` | `PROCESS_NOT_FOUND` (exit 3) |
| B-18〜B-18d | JVM が uid 1000 | root / uid 1000 / uid 1001 から `ping`、uid 1000 から `ps` | root と uid 1000 は成功。uid 1001 は `ATTACH_PERMISSION_DENIED` (`processUser` あり) |
| B-18e〜B-18g | uid 1000 の JVM で `-XX:-UsePerfData` (`ps` に出ない) | `CAP_SYS_PTRACE` のない root、uid 1000、`--cap-add SYS_PTRACE` したコンテナの root から `ping` | docs (#98) どおり、`CAP_SYS_PTRACE` のない root だけ `ATTACH_NOT_SUPPORTED` |
| B-19〜B-19d | passwd にない UID (OpenShift 風、`-u 12345:0`) | その UID から `ps` と `ping`、root から `ping` (`CAP_SYS_PTRACE` なし / あり) | docs どおり `ps` に出ない。同じ UID と `CAP_SYS_PTRACE` のある root は繋がる。ない root は `ATTACH_NOT_SUPPORTED` |
| B-20 | jlink で `jdk.management.agent` を抜いたランタイム | `ping` | `LOCAL_JMX_UNAVAILABLE`、`reason` に `jdk.management.agent` |
| B-21 | PID 1 でない JVM | `--pid <子プロセス>` | 成功 |
| B-22〜B-22f | 別のネットワーク名前空間の JVM (#101) | 新しいコンテナの JVM に、ホストから `--pid <ホスト側 PID>` を 2 回、`jcmd 1 ManagementAgent.status`、ホストで `ps`、`--pid host` の別コンテナから、`--pid host` で JVM のネットワークを共有するコンテナから、コンテナ内から | ホストと別コンテナからは `ATTACH_NOT_SUPPORTED` (`another network namespace`、retryable でない、1 秒未満)。agent は disabled のまま。ネットワークを共有すれば繋がる。コンテナ内からは繋がる |
| B-24 | `--network host` のコンテナの JVM | ホストから `--pid <ホスト側 PID>` | 繋がる (同じネットワーク名前空間) |
| B-25 | コンテナを使わないホスト上の JVM | ホストで `ps` と `--pid` で `read` | 一覧に出て、読める (#101 の回帰確認) |
| B-23 | 実アプリ (Tomcat 10.1) | `ps`、`search 'Catalina:type=ThreadPool,*'`、`read maxThreads`、`write maxThreads=150` して読み戻し、`describe Catalina:type=Server` | すべて成功 |

## C. コマンド・値・出力の制限 (JDK 21, `--pid`)

| ID | 観点 | 期待結果 |
|---|---|---|
| C-01 | `search 'ajmxtest:*'` | 6 件、ソート済み |
| C-02 / C-02b | `,*` なしのパターン / ありのパターン | 0 件 / 1 件以上 |
| C-03 | `describe` | 属性 (writable) と操作が出る |
| C-04 | 複数属性の `read` | 両方返る |
| C-05 | 1 つだけ存在しない属性を含む `read` | exit 7、`errors.Nope.code == ATTRIBUTE_NOT_FOUND` |
| C-06 / C-07 | 単一の存在しない属性 / MBean | `ATTRIBUTE_NOT_FOUND` / `MBEAN_NOT_FOUND` (exit 4) |
| C-08 | スペースを含む名前をクォートしない (troubleshooting の例) | `details.mbean` がシェルで切られた名前 |
| C-09〜C-13 | `write` の型変換: 数値、真偽値、ObjectName、`String[]`、MXBean の enum | 変換後の値が返る |
| C-14 / C-15 | 型が合わない値 / 読み取り専用 | `TYPE_CONVERSION_FAILED` / `ATTRIBUTE_NOT_WRITABLE` (exit 2) |
| C-16〜C-19 | `invoke` の引数: String、オーバーロードの曖昧さ (`candidates` 2 件)、`--signature` で選ぶ、`int[]` | 期待どおりの戻り値。曖昧なときは `AMBIGUOUS_OPERATION` で何も実行しない |
| C-20 | `byte[]` の戻り値 | `{"$base64": "AQIDBAU="}` |
| C-21 / C-21b | JDK の例外 / アプリのクラスの例外 | `REMOTE_EXCEPTION` / `UNSUPPORTED_TYPE` (exit 5) |
| C-22 | アプリのクラスの値 | `UNSUPPORTED_TYPE`、`details.class` |
| C-23 | `--limit 5` で配列を切る | exit 7、`$total: 1000`、`$items` 5 件 |
| C-24 | 400 KB の文字列 | `OUTPUT_TRUNCATED`、`outputBytes` |
| C-25 / C-26 | `search --max-bytes 512` / `--max-bytes 100` | 末尾を落として `truncated` / `INVALID_ARGUMENT` (`minimum: 512`) |
| C-27 / C-28 | 遅い `read` / 遅い `invoke` に `--timeout 1s` | `TIMEOUT` (exit 6)。read は retryable、invoke は retryable でなく `executed: "unknown"` |
| C-29 | 存在しない操作 | `OPERATION_NOT_FOUND` |
| C-30〜C-33, C-36 | docs の例: `getVMOption`、`threadPrint --max-bytes 4096`、`gcClassHistogram`、`getThreadInfo [[1],1]`、`SystemProperties --limit 2`、`LastGcInfo` | docs どおり。`threadPrint` は `OUTPUT_TRUNCATED` で `executed: true` |
| C-34 | 自分自身を含むリスト | `truncated` |
| C-35 | MXBean の CompositeData と Map | オブジェクトと配列 |
| C-40 | 全 op の `batch` (空行あり、id は数値と文字列) | 6 件すべて ok、id がそのまま返る |
| C-41 / C-41b | 2 行目が不正な `batch` | `INVALID_ARGUMENT`、`details.line == 2`。1 行目の `clear` も実行されない |
| C-42 | `invoke` がタイムアウトした後の `write` と `read` | `TIMEOUT`, `SKIPPED`, ok |
| C-43 | `batch` が `--max-bytes` を超える | 大きい項目が `{"id","ok","truncated": true}` に置き換わる |
| C-50〜C-61 | オプションの誤り: 未知のコマンド (`allowed`)、未知のオプション、`--pid` と `--url`、ターゲットなし、不正な duration、`--limit=2` とコマンド後のオプション、`--` 以降の引数、`--debug` (stderr にスタックトレース、stdout は JSON)、同じオプション 2 回、`--credentials-stdin`/`--tls` を `--pid` と、`--tls-ca` だけ | 期待どおりの `INVALID_ARGUMENT` (exit 2) または成功 |
| C-70 / C-70b | 例外メッセージに C0 と C1 の制御文字 (`failWithControlCharacters`) | `REMOTE_EXCEPTION`、`exceptionMessage` が元の文字列と一致。stdout に生の制御文字がない |
| C-71〜C-71c | 同じ操作を `--debug` で | stderr のスタックトレースにメッセージが `\u001b...\u000d...\u009b` とエスケープされて出る。stderr にも stdout にも生の制御文字がない |
| C-72 / C-72b | 戻り値に C0 / DEL / C1 (`greet` の引数を `\u` エスケープした JSON で渡す) | 戻り値が元の文字列と一致。stdout に生の制御文字がない |
| C-73〜C-73c | 属性値に制御文字を `batch` で書いて読む、`read` で読む、`batch --debug` で失敗する要求 | 元の値と一致。stdout にも stderr にも生の制御文字がない。stderr に `ajmx: .result.items[0].error` |
| C-74 / C-74b | C1 制御文字 200 個の戻り値と `--max-bytes` | 自分のサイズ +5 では成功、-10 では `OUTPUT_TRUNCATED` で `outputBytes` が実際のサイズ (±3) と一致する (C1 1 文字を 6 バイトで数える) |

## D. デシリアライズ制限 (#90)

| ID | 観点 | 期待結果 |
|---|---|---|
| D-01 | 500 段の値 | 読める (出力は 32 段で切られて exit 7) |
| D-02 | 501 段の値 | `UNSUPPORTED_TYPE`、`maxDepth: 500`、`maxArrayLength: 16777216`、retryable でない |
| D-03 | 100,000 段の属性 (v0.1.0 でスタックが溢れた値) | `UNSUPPORTED_TYPE` (`INTERNAL_ERROR` ではない) |
| D-04 | 16,777,216 要素の `byte[]` (`--max-bytes 30000000`) | 読める。base64 が 22,369,624 文字 |
| D-05 | 16,777,217 要素の `byte[]` | `UNSUPPORTED_TYPE` |
| D-06 | `batch` の中の 501 段 | その項目だけ失敗、他は ok (exit 7) |
| D-07 / D-08 | 1 億文字の文字列 | `OUTPUT_TRUNCATED`。その後も普通に使える |
| B-06, E-17, E-18, F-22 | `--pid` の各 JDK、`--url`、TLS 経由 | どれも `UNSUPPORTED_TYPE` |

## E. `--url` (TLS なし)

| ID | 観点 | 期待結果 |
|---|---|---|
| E-01 / E-02 | docs の Compose の例 (`JAVA_TOOL_OPTIONS`、`127.0.0.1:7199:7199`、`hostname=localhost`) にホストから接続 | 成功 |
| E-03 / E-03b | コンテナ間 (`java.rmi.server.hostname` = サービス名) で search/invoke | 成功 |
| E-04 | JDK 8 の JVM | `SpecVersion == "1.8"` |
| E-05 | コンテナ内で `jmxremote.host=127.0.0.1` (docs の注意) | `CONNECTION_FAILED`、retryable |
| E-06 | 届かない `java.rmi.server.hostname` | `CONNECTION_TIMEOUT`、`timeoutMs: 3000` |
| E-07 / E-08 | 誰も listen していないポート / `--url localhost:7199` | `CONNECTION_FAILED` / `INVALID_ARGUMENT` |
| E-09〜E-12 | 認証: operator の ping と write、viewer の read と write と `findDeadlockedThreads`、誤ったパスワード、資格情報なし | operator は成功。viewer の write/invoke は `AUTH_FAILED` で `details.mbean`。誤り・なしは `AUTH_FAILED` で `details.url`、retryable でない |
| E-13〜E-16 | `--credentials-stdin`、不正な JSON、`batch` と `--credentials-stdin`、環境変数で `batch` | 成功 / `INVALID_ARGUMENT` / `INVALID_ARGUMENT` / 成功 |
| E-19 / E-19b | `--url` で例外メッセージに制御文字 | stdout にも `--debug` の stderr にも生の制御文字がない |

## F. `--tls` / `--tls-ca` (#88)

docs (connect/remote.md の TLS) の手順で証明書と `ssl.config.file` を作り、`registry.ssl=true`, `ssl=true`, 認証ありの JVM を立てる。

| ID | 観点 | 期待結果 |
|---|---|---|
| F-01〜F-04 | docs の手順で `--tls --tls-ca`: ping、read、write、invoke、batch、`--credentials-stdin` | 成功 |
| F-05 | `--tls-ca` なしで自己署名 | `TLS_FAILED`、reason が `(certificate_unknown) PKIX path building failed` で始まる、retryable でない |
| F-06 | 別の証明書を `--tls-ca` に | `TLS_FAILED` (PKIX) |
| F-07 / F-07b | 別ホスト名 (DNS 名の SAN) の証明書 | `TLS_FAILED`。reason が troubleshooting.md の表 (#97) の `(certificate_unknown) No subject alternative` か `(certificate_unknown) No name matching` で始まる |
| F-07c | IP アドレスの SAN だけの証明書にホスト名で接続 | `TLS_FAILED`、reason が `(certificate_unknown) No name matching` で始まる |
| F-08 | registry が平文 (`registry.ssl=false`) | `TLS_FAILED`、`Remote host terminated the handshake` |
| F-09 | 接続が平文 (`ssl=false`, `registry.ssl=true`) | `TLS_FAILED`、`Expecting a` で始まる |
| F-10 | TLS なしの JVM に `--tls` | `TLS_FAILED` |
| F-11〜F-11d | 平文で認証ありの JVM に `--tls` で接続し、JVM 側で tcpdump | `TLS_FAILED`、キャプチャにパスワードがない。対照として `--tls` なしではパスワードが平文で写る |
| F-12 | `--tls` なしで信頼できない証明書の接続 | `TLS_FAILED` (v0.1.0 は retryable な `CONNECTION_FAILED`、G-02b) |
| F-13 / F-14 | 私設 CA で署名した証明書: CA を `--tls-ca` に / 複数証明書の PEM バンドル | 成功 |
| F-15〜F-15d | `--tls-ca` が存在しない / 空 / PEM でない / 別形式のファイル | 接続前に `INVALID_ARGUMENT` (exit 2) |
| F-16 | `/jndi/rmi://` でない URL と `--tls` | `INVALID_ARGUMENT` |
| F-17 | JDK 8 の JVM に TLS | 成功 |
| F-18 | `need.client.auth=true` (未対応と docs にある) | 記録 (INFO) |
| F-19 | 証明書にない IP アドレスで接続 | `TLS_FAILED`、reason が troubleshooting.md の同じ行に合う |
| F-20 / F-21 | TLS 上で誤ったパスワード / viewer の write | `AUTH_FAILED` |
| F-22 | TLS 上で 501 段の値 | `UNSUPPORTED_TYPE` |

## G. インストールと前のリリース (`PREV`) との比較

| ID | 観点 | 期待結果 |
|---|---|---|
| G-01 | `install.sh -b <dir> $PREV` | インストールされる |
| G-01b / G-01c | (G-01 ができない環境の代わり) `PREV` のアセットと `.sha256` を直接取得して検証、実行 | チェックサム OK、`version` が `PREV` |
| G-02 | 対照: `PREV` で `failWithControlCharacters` を呼び、stdout を C-70b と同じ方法で調べる | 0.2.1 以前は生の U+009B を書くので検出される (exit 1)。C-70b の判定が実際に検出できることの確認 |
| G-03 | `PREV` (CI ビルド) が必要とする glibc | 記録 (INFO)。A-06 と比べる |
| G-04 / G-04b | `PREV` (CI ビルド) を ubuntu:22.04, debian:12, rockylinux:9, amazonlinux:2023 と ubuntu:20.04 で実行 | 前の 4 つで動き、ubuntu:20.04 は glibc のエラー。CI が作るバイナリが docs の要件どおりであることの確認 |

## H. リリース後に手で確認すること (Docker では実行しない)

| ID | 観点 | 手順 | 期待結果 |
|---|---|---|---|
| H-01 | install.sh (最新) | `curl -fsSL https://github.com/yagipass/ajmx/releases/latest/download/install.sh \| sh -s -- -b ~/.local/bin` を Linux amd64/arm64 と macOS で | 0.2.0 が入り、`ajmx version` が 0.2.0 |
| H-02 | install.sh のタグ指定と attestation | gh 2.93.0 以上がある環境とない環境で `... v0.2.0` | ある環境は attestation、ない環境は SHA-256 で検証される |
| H-03 | Homebrew | `brew install yagipass/tap/ajmx` (または `brew upgrade`) | 0.2.0 |
| H-04 | Nix | `nix profile install github:yagipass/ajmx` / `nix run github:yagipass/ajmx -- version` | 0.2.0 (`nix/ajmx.nix` の version が上がっていること) |
| H-05 | attestation | 3 つのバイナリと `install.sh` に `gh attestation verify <file> --repo yagipass/ajmx` | すべて verify される |
| H-06 | CI のバイナリで A-05〜A-07 | リリースされた `ajmx-linux-amd64` / `-arm64` で `ldd`、古いディストリビューションでの起動 | A と同じ結果 |
| H-07 | macOS (Apple silicon) | ローカルの JVM に `ps` / `--pid` / TLS の JVM に `--tls` | Linux と同じ |
| H-08 | docs サイト | ajmx.yagipass.com の TLS 節、Troubleshooting の TLS 表、Limitations の新しい節 | 0.2.0 の内容が出ている |
| H-09 | リリースノート | GitHub release の自動生成ノート | #88 と #90 が載る。`TLS_FAILED` で retryable でなくなる挙動変更が分かる |
