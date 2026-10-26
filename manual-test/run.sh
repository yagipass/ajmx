#!/usr/bin/env bash
# Runs the manual test cases of TESTCASES.md against an ajmx binary, with the JVMs in Docker.
#
#   manual-test/run.sh <ajmx-linux-amd64-binary> <modules/it/build/classes/java/main> [id-regex]
#
# The binary runs on the host and inside the containers, so it must be a Linux binary for the
# host's architecture. The second argument holds ajmxtest.TestTarget, built by
# `./gradlew :it:classes`. Results go to $OUT (default: a new temporary directory).
set -uo pipefail

BIN=$(realpath "$1")
CLASSES=$(realpath "$2")
ONLY=${3:-.}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$(mktemp -d)}
WORK=$OUT/work
RESULTS=$OUT/results.tsv
DETAILS=$OUT/details.log
mkdir -p "$WORK/conf"
: >"$RESULTS"
: >"$DETAILS"

# shellcheck source=lib.sh
. "$HERE/lib.sh"

T8=eclipse-temurin:8-jdk
T11=eclipse-temurin:11-jdk
T17=eclipse-temurin:17-jdk
T21=eclipse-temurin:21-jdk
T25=eclipse-temurin:25-jdk
NOAUTH="-Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
AUTH="-Dcom.sun.management.jmxremote.authenticate=true
  -Dcom.sun.management.jmxremote.password.file=/ajmx/conf/jmxremote.password
  -Dcom.sun.management.jmxremote.access.file=/ajmx/conf/jmxremote.access"
OPERATOR="-e JMX_USERNAME=operator -e JMX_PASSWORD=s3cret-pw"
VIEWER="-e JMX_USERNAME=viewer -e JMX_PASSWORD=v1ew-pw"
CACHE=ajmxtest:type=Cache
SETTINGS=ajmxtest:type=Settings

trap cleanup EXIT
cleanup
docker network rm "$NET" >/dev/null 2>&1
docker network create "$NET" >/dev/null

# --- Certificates, password and access files for the remote JVMs ---------------------------------
docker run --rm -v "$WORK/conf:/conf" -w /conf "$T21" sh -ec '
  P=changeit
  pair() { keytool -genkeypair -alias "$1" -keyalg RSA -validity 30 -dname "CN=$2" $3 \
             -storetype PKCS12 -keystore "$4" -storepass $P 2>/dev/null; }
  pem() { keytool -exportcert -rfc -alias "$1" -storetype PKCS12 -keystore "$2" -storepass $P -file "$3" 2>/dev/null; }
  pair jmx tls "-ext san=dns:tls,dns:tls8,dns:tls-noreg,dns:tls-plainconn,dns:tls-clientauth,dns:localhost" tls.p12
  pem jmx tls.p12 tls.pem
  pair jmx other.example "-ext san=dns:other.example" wrong.p12
  pem jmx wrong.p12 wrong.pem
  pair ca ajmx-test-ca "-ext bc:c" ca.p12
  pem ca ca.p12 ca.pem
  pair jmx tls-ca "" server-ca.p12
  keytool -certreq -alias jmx -keystore server-ca.p12 -storepass $P -file server-ca.csr 2>/dev/null
  keytool -gencert -alias ca -keystore ca.p12 -storepass $P -infile server-ca.csr -outfile server-ca.crt \
          -rfc -ext san=dns:tls-ca -validity 30 2>/dev/null
  keytool -importcert -noprompt -alias ca -file ca.pem -keystore server-ca.p12 -storepass $P 2>/dev/null
  keytool -importcert -noprompt -alias jmx -file server-ca.crt -keystore server-ca.p12 -storepass $P 2>/dev/null
  cat wrong.pem ca.pem > bundle.pem
  : > empty.pem
  echo "not a certificate" > notpem.pem
  for k in tls wrong server-ca; do
    printf "javax.net.ssl.keyStore=/ajmx/conf/%s.p12\njavax.net.ssl.keyStorePassword=%s\n" $k $P > $k.ssl
  done
  printf "operator s3cret-pw\nviewer v1ew-pw\n" > jmxremote.password
  printf "operator readwrite\nviewer readonly\n" > jmxremote.access
  printf "{\"username\": \"operator\", \"password\": \"s3cret-pw\"}" > credentials.json
  chmod 600 *.ssl jmxremote.password jmxremote.access
  chmod 644 *.pem credentials.json
' || { echo "!! could not make the certificates" >&2; exit 1; }

section() { [[ ${SECTIONS:-ABCDEFG} == *$1* ]]; }
c() { ax client "$@"; }

# === A: the binary ===============================================================================
if section A; then
  echo "== A: binary"
  check A-01 "version prints the release version" 0 '.result.version == "0.2.0"' -- "$BIN" version
  check A-02 "--version prints the same" 0 '.result.version == "0.2.0"' -- "$BIN" --version
  check A-03 "help is JSON and lists --tls and --tls-ca" 0 '(tostring | contains("--tls")) and (tostring | contains("--tls-ca"))' -- "$BIN" help
  check A-04 "--help prints the usage like help" 0 '.ok' -- "$BIN" --help
  info A-05 "dynamic libraries" -- ldd "$BIN"
  info A-06 "newest glibc symbol version required" -- sh -c "objdump -T '$BIN' | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1"
  for img in ubuntu:20.04 ubuntu:24.04 debian:11 rockylinux:8 amazonlinux:2 gcr.io/distroless/base-debian12; do
    check "A-07[$img]" "runs version and ps on $img" 0 '.ok and .result.returned == 0' -- \
      docker run --rm -v "$BIN:$BIN_IN:ro" --entrypoint "$BIN_IN" "$img" ps
  done
  info "A-08[alpine]" "runs on musl (alpine:3.20)" -- docker run --rm -v "$BIN:$BIN_IN:ro" alpine:3.20 "$BIN_IN" version
fi

# === B: --pid against JVMs in containers =========================================================
if section B; then
  echo "== B: --pid"
  for j in 8 11 17 21 25; do
    target "jdk$j" "eclipse-temurin:$j-jdk" -- || continue
    check "B-01[jdk$j]" "ps lists the JVM (PID 1) with mainClass and displayName" 0 \
      '.result.items | any(.pid == 1 and .mainClass == "ajmxtest.TestTarget" and .displayName == "TestTarget")' -- ax "jdk$j" ps
    check "B-02[jdk$j]" "ping attaches and starts the local agent" 0 '.result.connected' -- ax "jdk$j" --pid 1 ping
    check "B-03[jdk$j]" "read Runtime VmVendor SpecVersion" 0 '.result.attributes.SpecVersion != null' -- \
      ax "jdk$j" --pid 1 read java.lang:type=Runtime VmVendor SpecVersion
    check "B-04[jdk$j]" "second ping reuses the running agent" 0 '.result.connected' -- ax "jdk$j" --pid 1 ping
    check "B-05[jdk$j]" "batch of read, search and invoke" 0 '[.result.items[].ok] == [true, true, true]' -- \
      axi "jdk$j" --pid 1 batch <<'EOF'
{"id": "heap", "op": "read", "mbean": "java.lang:type=Memory", "attributes": ["HeapMemoryUsage"]}
{"id": "gc", "op": "search", "pattern": "java.lang:type=GarbageCollector,*"}
{"id": "dl", "op": "invoke", "mbean": "java.lang:type=Threading", "operation": "findDeadlockedThreads"}
EOF
    check "B-06[jdk$j]" "a value 501 levels deep fails with UNSUPPORTED_TYPE" 5 \
      '.error.code == "UNSUPPORTED_TYPE" and .error.details.maxDepth == 500' -- ax "jdk$j" --pid 1 invoke "$CACHE" nested --args '[501]'
    check "B-07[jdk$j]" "Standard MBean write and read back" 0 '.result.value == 42' -- \
      ax "jdk$j" --pid 1 write "$CACHE" MaxConnections=42
    [[ $j == 21 ]] || docker rm -f "${P}jdk$j" >/dev/null
  done

  target corretto amazoncorretto:21 -- &&
    check "B-10[corretto21]" "Amazon Corretto 21: ping and read VmVendor" 0 '.result.attributes.VmVendor | test("Amazon")' -- \
      ax corretto --pid 1 read java.lang:type=Runtime VmVendor
  target zulu azul/zulu-openjdk:17 -- &&
    check "B-11[zulu17]" "Azul Zulu 17: read VmVendor" 0 '.result.attributes.VmVendor | test("Azul")' -- \
      ax zulu --pid 1 read java.lang:type=Runtime VmVendor
  target jre eclipse-temurin:21-jre -- &&
    check "B-12[jre21]" "Temurin 21 JRE image: ping" 0 '.result.connected' -- ax jre --pid 1 ping
  docker rm -f "${P}corretto" "${P}zulu" "${P}jre" >/dev/null 2>&1

  if target openj9 ibm-semeru-runtimes:open-21-jdk --; then
    check "B-13[openj9]" "OpenJ9 is refused rather than attached" 3 '.error.code == "ATTACH_NOT_SUPPORTED"' -- \
      ax openj9 --pid 1 --timeout 5s ping
    check "B-13b[openj9]" "OpenJ9 JVM is still running after the attempt" 0 - -- alive openj9 1
    info "B-13c[openj9]" "files OpenJ9 wrote in reply to SIGQUIT" -- docker exec "${P}openj9" sh -c 'ls / /tmp | grep -i -E "javacore|core" || true'
    docker rm -f "${P}openj9" >/dev/null
  fi

  if target noperf "$T21" -- -XX:-UsePerfData; then
    check B-14 "-XX:-UsePerfData: ps does not list it" 0 '.result.returned == 0' -- ax noperf ps
    check B-14b "-XX:-UsePerfData: --pid still connects" 0 '.result.connected' -- ax noperf --pid 1 ping
    docker rm -f "${P}noperf" >/dev/null
  fi
  if target noattach "$T21" -- -XX:+DisableAttachMechanism; then
    check B-15 "-XX:+DisableAttachMechanism fails with ATTACH_NOT_SUPPORTED" 3 \
      '.error.code == "ATTACH_NOT_SUPPORTED" and .error.message == "The JVM disables the attach mechanism"' -- ax noattach --pid 1 ping
    docker rm -f "${P}noattach" >/dev/null
  fi

  docker exec -d "${P}jdk21" sh -c 'echo $$ > /tmp/sleep.pid; exec sleep 600'
  sleep 0.5
  SLEEP_PID=$(docker exec "${P}jdk21" cat /tmp/sleep.pid)
  check B-16 "a process that is not a JVM is refused" 3 '.error.code == "ATTACH_NOT_SUPPORTED"' -- ax jdk21 --pid "$SLEEP_PID" ping
  check B-16b "the process that is not a JVM is still alive" 0 - -- alive jdk21 "$SLEEP_PID"
  check B-17 "a PID that does not exist" 3 '.error.code == "PROCESS_NOT_FOUND" and .error.details.pid == 999999' -- ax jdk21 --pid 999999 ping

  if target user "$T21" -u 1000:1000 --; then
    check B-18 "root connects to a JVM of user 1000" 0 '.result.connected' -- axo user "-u 0" --pid 1 ping
    check B-18b "user 1000 connects to its own JVM" 0 '.result.connected' -- axo user "-u 1000:1000" --pid 1 ping
    check B-18c "another user is refused with processUser" 3 '.error.code == "ATTACH_PERMISSION_DENIED" and .error.details.processUser != null' -- \
      axo user "-u 1001:1001" --pid 1 ping
    check B-18d "user 1000 lists its JVM with ps" 0 '.result.items | any(.pid == 1)' -- axo user "-u 1000:1000" ps
    docker rm -f "${P}user" >/dev/null
  fi
  if target anyuid "$T21" -u 12345:0 --; then
    info B-19 "a JVM of a UID without a passwd entry (OpenShift style): ps as that UID" -- axo anyuid "-u 12345:0" ps
    check B-19b "a JVM of a UID without a passwd entry: --pid as that UID" 0 '.result.connected' -- axo anyuid "-u 12345:0" --pid 1 ping
    check B-19c "a JVM of a UID without a passwd entry: --pid as root" 0 '.result.connected' -- axo anyuid "-u 0" --pid 1 ping
    docker rm -f "${P}anyuid" >/dev/null
  fi

  if start jlink "$T21" -- sh -c "jlink --add-modules java.base,java.management,java.xml --output /tmp/rt >/dev/null &&
      exec /tmp/rt/bin/java -cp $CLASSES_IN $MAIN" && wait_ready jlink 120; then
    check B-20 "a jlink runtime without jdk.management.agent" 3 \
      '.error.code == "LOCAL_JMX_UNAVAILABLE" and (.error.details.reason | test("jdk.management.agent"))' -- ax jlink --pid 1 ping
    docker rm -f "${P}jlink" >/dev/null
  fi

  if start child "$T21" -- sh -c "java -cp $CLASSES_IN $MAIN & echo \$! > /tmp/java.pid; wait" && wait_ready child; then
    CHILD_PID=$(docker exec "${P}child" cat /tmp/java.pid)
    check B-21 "a JVM that is not PID 1 (pid $CHILD_PID)" 0 '.result.connected' -- ax child --pid "$CHILD_PID" ping
    docker rm -f "${P}child" >/dev/null
  fi

  HOST_PID=$(docker inspect -f '{{.State.Pid}}' "${P}jdk21")
  info B-22 "host ajmx --pid on a JVM inside a container (other PID namespace)" -- "$BIN" --pid "$HOST_PID" --timeout 5s ping
  check B-22b "the container JVM is still running after B-22" 0 '.result.connected' -- ax jdk21 --pid 1 ping
  check B-22c "host ps does not list JVMs inside containers" 0 '.result.items | all(.pid != '"$HOST_PID"')' -- "$BIN" ps

  docker rm -f "${P}tomcat" >/dev/null 2>&1
  docker run -d --name "${P}tomcat" --network "$NET" -v "$BIN:$BIN_IN:ro" tomcat:10.1-jdk21-temurin >/dev/null
  for _ in $(seq 1 150); do docker logs "${P}tomcat" 2>&1 | grep -q 'Server startup in' && break; sleep 0.2; done
  TP='Catalina:type=ThreadPool,name="http-nio-8080"'
  check B-23 "Tomcat: ps shows Bootstrap" 0 '.result.items | any(.mainClass == "org.apache.catalina.startup.Bootstrap")' -- ax tomcat ps
  check B-23b "Tomcat: search the thread pools" 0 '.result.items | any(test("ThreadPool"))' -- ax tomcat --pid 1 search 'Catalina:type=ThreadPool,*'
  check B-23c "Tomcat: read maxThreads" 0 '.result.attributes.maxThreads == 200' -- ax tomcat --pid 1 read "$TP" maxThreads currentThreadCount
  check B-23d "Tomcat: write maxThreads=150 and read it back" 0 '.result.attributes.maxThreads == 150' -- \
    sh -c "docker exec ${P}tomcat $BIN_IN --pid 1 write '$TP' maxThreads=150 >/dev/null && docker exec ${P}tomcat $BIN_IN --pid 1 read '$TP' maxThreads"
  check B-23e "Tomcat: describe Catalina:type=Server" 0 '.result.className != null and (.result.attributes | length > 0)' -- \
    ax tomcat --pid 1 describe Catalina:type=Server
  docker rm -f "${P}tomcat" >/dev/null
fi

# === C: commands, values and output limits (JDK 21, --pid) =======================================
if section C; then
  echo "== C: commands"
  docker inspect "${P}jdk21" >/dev/null 2>&1 || target jdk21 "$T21" --
  a() { ax jdk21 --pid 1 "$@"; }
  ai() { axi jdk21 --pid 1 "$@"; }
  check C-01 "search ajmxtest:* is sorted" 0 '.result.items == (.result.items | sort) and .result.returned == 6' -- a search 'ajmxtest:*'
  check C-02 "a pattern without ,* matches only exact keys" 0 '.result.returned == 0' -- a search 'java.lang:type=GarbageCollector'
  check C-02b "with ,* it matches" 0 '.result.returned > 0' -- a search 'java.lang:type=GarbageCollector,*'
  check C-03 "describe lists attributes and operations" 0 \
    '(.result.attributes | any(.name == "MaxConnections" and .writable)) and (.result.operations | any(.name == "greet"))' -- a describe "$CACHE"
  check C-04 "read two attributes" 0 '.result.attributes | has("MaxConnections") and has("Name")' -- a read "$CACHE" MaxConnections Name
  check C-05 "read with one bad attribute is partial (exit 7)" 7 '.ok and .result.errors.Nope.code == "ATTRIBUTE_NOT_FOUND"' -- a read "$CACHE" MaxConnections Nope
  check C-06 "read a single missing attribute" 4 '.error.code == "ATTRIBUTE_NOT_FOUND"' -- a read java.lang:type=Memory heapMemoryUsage
  check C-07 "read a missing MBean" 4 '.error.code == "MBEAN_NOT_FOUND"' -- a read com.example:type=Nope X
  check C-08 "an unquoted name with spaces is split by the shell" 4 '.error.details.mbean == "java.lang:type=GarbageCollector,name=G1"' -- \
    a read java.lang:type=GarbageCollector,name=G1 Young Generation CollectionCount
  check C-09 "write a number" 0 '.result.value == 20' -- a write "$CACHE" MaxConnections=20
  check C-10 "write a boolean" 0 '.result.value == true' -- a write "$CACHE" Enabled=true
  check C-11 "write an ObjectName" 0 '.result.value == "java.lang:type=Memory"' -- a write "$CACHE" Ref=java.lang:type=Memory
  check C-12 "write a String[]" 0 '.result.value == ["a","b"]' -- a write "$CACHE" 'Tags=["a","b"]'
  check C-13 "write an MXBean enum" 0 '.result.value == "SAFE"' -- a write "$SETTINGS" Mode=SAFE
  check C-14 "write a value of the wrong type" 2 '.error.code == "TYPE_CONVERSION_FAILED"' -- a write "$CACHE" MaxConnections=abc
  check C-15 "write a read-only attribute" 2 '.error.code == "ATTRIBUTE_NOT_WRITABLE"' -- a write "$CACHE" Size=1
  check C-16 "invoke with a String argument" 0 '.result.returnValue == "hello x"' -- a invoke "$CACHE" greet --args '["x"]'
  check C-17 "an argument that fits two overloads is ambiguous" 4 '.error.code == "AMBIGUOUS_OPERATION" and (.error.details.candidates | length == 2)' -- \
    a invoke "$CACHE" invalidate --args '["k"]'
  check C-17b "--signature picks the overload" 0 '.result.returnValue == "String:k"' -- a invoke "$CACHE" invalidate --args '["k"]' --signature java.lang.String
  check C-18 "--signature with two types" 0 '.result.returnValue == "long:k=1"' -- a invoke "$CACHE" put --args '["k", 1]' --signature java.lang.String,long
  check C-19 "invoke with an int[] argument" 0 '.result.returnValue == 6' -- a invoke "$CACHE" sum --args '[[1,2,3]]'
  check C-20 "byte[] is base64" 0 '.result.returnValue == {"$base64": "AQIDBAU="}' -- a invoke "$CACHE" bytes --args '[5]'
  check C-21 "an exception of a JDK class is REMOTE_EXCEPTION" 5 '.error.code == "REMOTE_EXCEPTION"' -- a invoke "$CACHE" failRuntime
  check C-21b "an exception of an application class is UNSUPPORTED_TYPE" 5 '.error.code == "UNSUPPORTED_TYPE"' -- a invoke "$CACHE" fail
  check C-22 "a value of an application class" 5 '.error.code == "UNSUPPORTED_TYPE" and (.error.details.class | test("CustomValue"))' -- a read "$CACHE" Custom
  check C-23 "--limit cuts an array (exit 7)" 7 '.result.truncated and .result.attributes.LargeArray["$total"] == 1000 and (.result.attributes.LargeArray["$items"] | length == 5)' -- \
    a read "$CACHE" LargeArray --limit 5
  check C-24 "a 400 KB string does not fit the default --max-bytes" 7 '.error.code == "OUTPUT_TRUNCATED" and .error.details.outputBytes > 262144' -- a read "$CACHE" LargeText
  check C-25 "search over --max-bytes drops trailing items" 7 '.result.truncated and .result.returned > 0' -- a --max-bytes 512 search
  check C-26 "--max-bytes below 512" 2 '.error.code == "INVALID_ARGUMENT" and .error.details.minimum == 512' -- a --max-bytes 100 search
  check C-27 "a slow read times out and may be retried" 6 '.error.code == "TIMEOUT" and .error.retryable' -- a --timeout 1s read "$CACHE" SlowValue
  check C-28 "a slow invoke times out, not retryable, executed unknown" 6 \
    '.error.code == "TIMEOUT" and (.error.retryable | not) and .error.details.executed == "unknown"' -- a --timeout 1s invoke "$CACHE" sleep --args '[3000]'
  check C-29 "an operation that does not exist" 4 '.error.code == "OPERATION_NOT_FOUND"' -- a invoke "$CACHE" nope
  check C-30 "HotSpotDiagnostic getVMOption (docs example)" 0 '.result.returnValue.name == "MaxHeapSize"' -- \
    a invoke com.sun.management:type=HotSpotDiagnostic getVMOption --args '["MaxHeapSize"]'
  check C-31 "DiagnosticCommand threadPrint over --max-bytes (docs example)" 7 \
    '.error.code == "OUTPUT_TRUNCATED" and .error.details.executed == true' -- \
    a --max-bytes 4096 invoke com.sun.management:type=DiagnosticCommand threadPrint --args '[[]]'
  check C-31b "DiagnosticCommand gcClassHistogram (docs example)" 0 '.result.returnValue | type == "string"' -- \
    a --max-bytes 4194304 invoke com.sun.management:type=DiagnosticCommand gcClassHistogram --args '[[]]'
  check C-32 "Threading getThreadInfo with long[] (docs example)" 0 '.result.returnValue | type == "array"' -- \
    a invoke java.lang:type=Threading getThreadInfo --args '[[1], 1]'
  check C-33 "SystemProperties --limit 2 (docs example)" 7 '.result.attributes.SystemProperties["$truncated"] and (.result.attributes.SystemProperties["$items"] | length == 2)' -- \
    a read java.lang:type=Runtime SystemProperties --limit 2
  check C-34 "a list that contains itself is cut" 7 '.result.truncated' -- a read "$CACHE" SelfReference
  check C-35 "MXBean CompositeData and Map" 0 '(.result.attributes.Stats | has("hits") and has("misses")) and (.result.attributes.Limits | length > 0)' -- \
    a read "$SETTINGS" Stats Limits
  check C-36 "LastGcInfo after a GC (docs example)" 0 '.result.attributes | has("LastGcInfo")' -- \
    sh -c "docker exec ${P}jdk21 $BIN_IN --pid 1 invoke java.lang:type=Memory gc >/dev/null && docker exec ${P}jdk21 $BIN_IN --pid 1 read 'java.lang:type=GarbageCollector,name=G1 Young Generation' LastGcInfo"

  check C-40 "batch with every op" 0 '[.result.items[].ok] == [true,true,true,true,true,true] and [.result.items[].id] == [1,"s","d","r","w","i"]' -- ai batch <<EOF
{"id": 1, "op": "ping"}
{"id": "s", "op": "search", "pattern": "ajmxtest:*"}

{"id": "d", "op": "describe", "mbean": "$CACHE"}
{"id": "r", "op": "read", "mbean": "$CACHE", "attributes": ["MaxConnections"]}
{"id": "w", "op": "write", "mbean": "$CACHE", "attribute": "MaxConnections", "value": 7}
{"id": "i", "op": "invoke", "mbean": "$CACHE", "operation": "greet", "args": ["b"], "signature": ["java.lang.String"]}
EOF
  BEFORE=$(a read "$CACHE" ClearCount | jq .result.attributes.ClearCount)
  check C-41 "a bad line rejects the whole batch with details.line" 2 '.error.code == "INVALID_ARGUMENT" and .error.details.line == 2' -- ai batch <<EOF
{"id": "c", "op": "invoke", "mbean": "$CACHE", "operation": "clear"}
{"id": "gc", "op": "lookup"}
EOF
  check C-41b "no request of the rejected batch ran" 0 ".result.attributes.ClearCount == $BEFORE" -- a read "$CACHE" ClearCount
  check C-42 "after an invoke times out, later writes are SKIPPED and reads still run" 7 \
    '[.result.items[] | (.error.code // "ok")] == ["TIMEOUT","SKIPPED","ok"]' -- ai --timeout 1s batch <<EOF
{"id": "slow", "op": "invoke", "mbean": "$CACHE", "operation": "sleep", "args": [3000]}
{"id": "w", "op": "write", "mbean": "$CACHE", "attribute": "MaxConnections", "value": 9}
{"id": "r", "op": "read", "mbean": "$CACHE", "attributes": ["Name"]}
EOF
  check C-43 "batch over --max-bytes replaces the largest items" 7 \
    '.result.items[0] == {"id": "big", "ok": true, "truncated": true} and .result.items[1].result.connected' -- ai --max-bytes 4096 batch <<EOF
{"id": "big", "op": "read", "mbean": "$CACHE", "attributes": ["LargeText"]}
{"id": "small", "op": "ping"}
EOF

  check C-50 "unknown command lists the allowed ones" 2 '.error.code == "INVALID_ARGUMENT" and (.error.details.allowed | index("invoke"))' -- a frobnicate
  check C-51 "unknown option" 2 '.error.code == "INVALID_ARGUMENT"' -- a --nope ping
  check C-52 "--pid and --url together" 2 '.error.code == "INVALID_ARGUMENT"' -- a --url "$(url localhost)" ping
  check C-53 "a command that needs a target without one" 2 '.error.code == "INVALID_ARGUMENT"' -- ax jdk21 ping
  check C-54 "a bad duration" 2 '.error.code == "INVALID_ARGUMENT"' -- a --timeout 5x ping
  check C-55 "--limit=2 and options after the command" 7 '.result.returned == 2' -- a search 'java.lang:*' --limit=2
  check C-56 "-- ends the options" 7 '.result.errors["--weird"].code == "ATTRIBUTE_NOT_FOUND"' -- a read "$CACHE" Name -- --weird
  check C-57 "--debug prints a stack trace to stderr, stdout stays JSON" 4 'true' -- a --debug read com.example:type=Nope X
  check C-57b "the stack trace is on stderr" 4 're:at [a-z]' -- a --debug read com.example:type=Nope X
  check C-58 "the same option twice" 2 '.error.code == "INVALID_ARGUMENT"' -- a --limit 1 --limit 2 ps
  check C-59 "--credentials-stdin with --pid" 2 '.error.code == "INVALID_ARGUMENT"' -- a --credentials-stdin ping
  check C-60 "--tls with --pid" 2 '.error.code == "INVALID_ARGUMENT"' -- a --tls ping
  check C-61 "--tls-ca without --tls" 2 '.error.code == "INVALID_ARGUMENT"' -- ax jdk21 --url "$(url localhost)" --tls-ca /ajmx/conf/tls.pem ping
fi

# === D: limits on deserialization (#90) ===========================================================
if section D; then
  echo "== D: deserialization limits"
  docker inspect "${P}jdk21" >/dev/null 2>&1 || target jdk21 "$T21" --
  a() { ax jdk21 --pid 1 "$@"; }
  check D-01 "a value 500 levels deep is read (and cut at 32 for output)" 7 '.ok and .result.truncated' -- a invoke "$CACHE" nested --args '[500]'
  check D-02 "501 levels: UNSUPPORTED_TYPE with the limits, not retryable" 5 \
    '.error.code == "UNSUPPORTED_TYPE" and .error.details.maxDepth == 500 and .error.details.maxArrayLength == 16777216 and (.error.retryable | not)' -- \
    a invoke "$CACHE" nested --args '[501]'
  check D-03 "an attribute 100,000 levels deep: UNSUPPORTED_TYPE, not INTERNAL_ERROR" 5 '.error.code == "UNSUPPORTED_TYPE" and .error.details.maxDepth == 500' -- \
    a read "$CACHE" DeepValue
  check D-04 "a byte[] of 16,777,216 elements is read" 0 '.ok and (.result.returnValue["$base64"] | length) == 22369624' -- \
    a --timeout 60s --max-bytes 30000000 invoke "$CACHE" bytes --args '[16777216]'
  check D-05 "a byte[] of 16,777,217 elements: UNSUPPORTED_TYPE" 5 '.error.code == "UNSUPPORTED_TYPE" and .error.details.maxArrayLength == 16777216' -- \
    a --timeout 60s invoke "$CACHE" bytes --args '[16777217]'
  check D-06 "in a batch, the deep value fails alone" 7 '[.result.items[].ok] == [false, true] and .result.items[0].error.code == "UNSUPPORTED_TYPE"' -- \
    axi jdk21 --pid 1 batch <<EOF
{"id": "deep", "op": "invoke", "mbean": "$CACHE", "operation": "nested", "args": [501]}
{"id": "ok", "op": "ping"}
EOF
  check D-07 "a 100,000,000-character string does not fit --max-bytes" 7 '.error.code == "OUTPUT_TRUNCATED"' -- a --timeout 60s read "$CACHE" HugeText
  check D-08 "ajmx still works after D-07" 0 '.result.connected' -- a ping
fi

# === E: --url without TLS ===========================================================================
if section E; then
  echo "== E: --url"
  start client ubuntu:24.04 -- sleep infinity
  # shellcheck disable=SC2086
  {
    target plain "$T21" -- $(remote_flags plain) $NOAUTH
    target plain8 "$T8" -- $(remote_flags plain8) $NOAUTH
    target auth "$T21" -- $(remote_flags auth) $AUTH -Dcom.sun.management.jmxremote.ssl=false
    target loopback "$T21" -- $(remote_flags loopback) $NOAUTH -Dcom.sun.management.jmxremote.host=127.0.0.1
    target badhost "$T21" -- $(remote_flags 10.255.255.1) $NOAUTH
  }
  # The compose recipe of docs/content/connect/remote.md, verbatim except for the image.
  docker rm -f "${P}docs" >/dev/null 2>&1
  docker run -d --name "${P}docs" -p 127.0.0.1:7199:7199 -v "$CLASSES:$CLASSES_IN:ro" \
    -e JAVA_TOOL_OPTIONS="-Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.rmi.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=localhost" \
    "$T21" java -cp "$CLASSES_IN" "$MAIN" >/dev/null && wait_ready docs

  check E-01 "host to a published container port (docs recipe)" 0 '.result.connected' -- "$BIN" --url "$(url localhost)" ping
  check E-02 "host: read VmName VmVersion (docs example)" 0 '.result.attributes.VmName != null' -- "$BIN" --url "$(url localhost)" read java.lang:type=Runtime VmName VmVersion
  check E-03 "container to container: search, describe, read" 0 '.result.returned == 6' -- c --url "$(url plain)" search 'ajmxtest:*'
  check E-03b "container to container: write and invoke" 0 '.result.returnValue == "hello r"' -- c --url "$(url plain)" invoke "$CACHE" greet --args '["r"]'
  check E-04 "a JDK 8 JVM over --url" 0 '.result.attributes.SpecVersion == "1.8"' -- c --url "$(url plain8)" read java.lang:type=Runtime SpecVersion
  check E-05 "jmxremote.host=127.0.0.1 in the container: CONNECTION_FAILED (docs)" 3 '.error.code == "CONNECTION_FAILED" and .error.retryable' -- \
    c --url "$(url loopback)" ping
  check E-06 "an unreachable java.rmi.server.hostname: CONNECTION_TIMEOUT" 3 '.error.code == "CONNECTION_TIMEOUT" and .error.details.timeoutMs == 3000' -- \
    c --timeout 3s --url "$(url badhost)" ping
  check E-07 "nothing listens on the port" 3 '.error.code == "CONNECTION_FAILED"' -- c --url "$(url plain 7299)" ping
  check E-08 "--url host:port is not a URL" 2 '.error.code == "INVALID_ARGUMENT"' -- c --url localhost:7199 ping
  check E-09 "operator: ping" 0 '.result.connected' -- axo client "$OPERATOR" --url "$(url auth)" ping
  check E-09b "operator: write Verbose (docs example)" 0 '.result.value == true' -- axo client "$OPERATOR" --url "$(url auth)" write java.lang:type=Memory Verbose=true
  check E-10 "viewer: read" 0 '.ok' -- axo client "$VIEWER" --url "$(url auth)" read java.lang:type=Memory Verbose
  check E-10b "viewer: write fails with AUTH_FAILED and mbean in details" 3 '.error.code == "AUTH_FAILED" and .error.details.mbean == "java.lang:type=Memory"' -- \
    axo client "$VIEWER" --url "$(url auth)" write java.lang:type=Memory Verbose=false
  check E-10c "viewer: invoke findDeadlockedThreads fails (docs)" 3 '.error.code == "AUTH_FAILED"' -- \
    axo client "$VIEWER" --url "$(url auth)" invoke java.lang:type=Threading findDeadlockedThreads
  check E-11 "a wrong password" 3 '.error.code == "AUTH_FAILED" and (.error.retryable | not) and .error.details.url != null' -- \
    axo client "-e JMX_USERNAME=operator -e JMX_PASSWORD=wrong" --url "$(url auth)" ping
  check E-12 "no credentials" 3 '.error.code == "AUTH_FAILED"' -- c --url "$(url auth)" ping
  check E-13 "--credentials-stdin" 0 '.result.connected' -- axi client --url "$(url auth)" --credentials-stdin ping <<<'{"username": "operator", "password": "s3cret-pw"}'
  check E-14 "--credentials-stdin with a malformed document" 2 '.error.code == "INVALID_ARGUMENT"' -- axi client --url "$(url auth)" --credentials-stdin ping <<<'operator:s3cret-pw'
  check E-15 "batch with --credentials-stdin is refused" 2 '.error.code == "INVALID_ARGUMENT"' -- axi client --url "$(url auth)" --credentials-stdin batch <<<'{"id":1,"op":"ping"}'
  check E-16 "batch with credentials from the environment" 0 '[.result.items[].ok] == [true, true]' -- \
    docker exec -i -e JMX_USERNAME=operator -e JMX_PASSWORD=s3cret-pw "${P}client" "$BIN_IN" --url "$(url auth)" batch <<EOF
{"id": 1, "op": "ping"}
{"id": 2, "op": "read", "mbean": "java.lang:type=Runtime", "attributes": ["Uptime"]}
EOF
  check E-17 "501 levels over --url: UNSUPPORTED_TYPE" 5 '.error.code == "UNSUPPORTED_TYPE" and .error.details.maxDepth == 500' -- \
    c --url "$(url plain)" invoke "$CACHE" nested --args '[501]'
  check E-18 "100,000 levels over --url: UNSUPPORTED_TYPE" 5 '.error.code == "UNSUPPORTED_TYPE"' -- c --url "$(url plain)" read "$CACHE" DeepValue
  docker rm -f "${P}docs" "${P}loopback" "${P}badhost" "${P}plain8" >/dev/null 2>&1
fi

# === F: --tls and --tls-ca (#88) ==================================================================
if section F; then
  echo "== F: TLS"
  docker inspect "${P}client" >/dev/null 2>&1 || start client ubuntu:24.04 -- sleep infinity
  tls() { # tls NAME IMAGE SSL REGISTRY_SSL SSL_FILE [extra java options...]
    local name=$1 image=$2 ssl=$3 reg=$4 file=$5
    shift 5
    # shellcheck disable=SC2086
    target "$name" "$image" -- $(remote_flags "$name") $AUTH \
      -Dcom.sun.management.jmxremote.ssl="$ssl" -Dcom.sun.management.jmxremote.registry.ssl="$reg" \
      -Dcom.sun.management.jmxremote.ssl.config.file="/ajmx/conf/$file.ssl" "$@"
  }
  tls tls "$T21" true true tls
  tls tls8 "$T8" true true tls
  tls tls-wrong "$T21" true true wrong
  # With TLS for only one of them, the registry and the connection cannot share a port.
  tls tls-noreg "$T21" true false tls -Dcom.sun.management.jmxremote.rmi.port=7200
  tls tls-plainconn "$T21" false true tls -Dcom.sun.management.jmxremote.rmi.port=7200
  tls tls-ca "$T21" true true server-ca
  tls tls-clientauth "$T21" true true tls -Dcom.sun.management.jmxremote.ssl.need.client.auth=true
  docker inspect "${P}auth" >/dev/null 2>&1 ||
    target auth "$T21" -- $(remote_flags auth) $AUTH -Dcom.sun.management.jmxremote.ssl=false
  docker inspect "${P}plain" >/dev/null 2>&1 || target plain "$T21" -- $(remote_flags plain) $NOAUTH
  op() { axo client "$OPERATOR" "$@"; }
  CA=/ajmx/conf

  check F-01 "--tls --tls-ca: ping (docs recipe)" 0 '.result.connected' -- op --url "$(url tls)" --tls --tls-ca $CA/tls.pem ping
  check F-02 "read over TLS" 0 '.result.attributes.VmName != null' -- op --url "$(url tls)" --tls --tls-ca $CA/tls.pem read java.lang:type=Runtime VmName
  check F-02b "write over TLS" 0 '.result.value == 33' -- op --url "$(url tls)" --tls --tls-ca $CA/tls.pem write "$CACHE" MaxConnections=33
  check F-02c "invoke over TLS" 0 '.result.returnValue == "hello tls"' -- op --url "$(url tls)" --tls --tls-ca $CA/tls.pem invoke "$CACHE" greet --args '["tls"]'
  check F-03 "batch over TLS" 0 '[.result.items[].ok] == [true, true]' -- \
    docker exec -i -e JMX_USERNAME=operator -e JMX_PASSWORD=s3cret-pw "${P}client" "$BIN_IN" --url "$(url tls)" --tls --tls-ca $CA/tls.pem batch <<EOF
{"id": 1, "op": "ping"}
{"id": 2, "op": "search", "pattern": "ajmxtest:*"}
EOF
  check F-04 "--credentials-stdin over TLS" 0 '.result.connected' -- \
    axi client --url "$(url tls)" --tls --tls-ca $CA/tls.pem --credentials-stdin ping <<<'{"username": "operator", "password": "s3cret-pw"}'
  check F-05 "without --tls-ca a self-signed certificate is not trusted" 3 \
    '.error.code == "TLS_FAILED" and (.error.retryable | not) and (.error.details.reason | startswith("(certificate_unknown) PKIX path building failed"))' -- \
    op --url "$(url tls)" --tls ping
  check F-06 "--tls-ca with another certificate" 3 '.error.code == "TLS_FAILED" and (.error.details.reason | test("PKIX"))' -- \
    op --url "$(url tls)" --tls --tls-ca $CA/wrong.pem ping
  check F-07 "a certificate for another host name" 3 \
    '.error.code == "TLS_FAILED" and (.error.details.reason | test("No subject alternative"))' -- \
    op --url "$(url tls-wrong)" --tls --tls-ca $CA/wrong.pem ping
  check F-07b "...with the reason troubleshooting.md lists for it" 3 \
    '.error.details.reason | startswith("(certificate_unknown) No subject alternative names matching")' -- \
    op --url "$(url tls-wrong)" --tls --tls-ca $CA/wrong.pem ping
  check F-08 "a registry without TLS (registry.ssl=false)" 3 '.error.code == "TLS_FAILED" and .error.details.reason == "Remote host terminated the handshake"' -- \
    op --url "$(url tls-noreg)" --tls --tls-ca $CA/tls.pem ping
  check F-09 "a connection without TLS (ssl=false, registry.ssl=true)" 3 '.error.code == "TLS_FAILED" and (.error.details.reason | startswith("Expecting a"))' -- \
    op --url "$(url tls-plainconn)" --tls --tls-ca $CA/tls.pem ping
  check F-10 "--tls against a JVM without any TLS" 3 '.error.code == "TLS_FAILED"' -- c --url "$(url plain)" --tls ping

  docker rm -f "${P}cap" >/dev/null 2>&1
  docker run -d --name "${P}cap" --network "container:${P}auth" nicolaka/netshoot tcpdump -i any -U -w /tmp/cap.pcap port 7199 >/dev/null
  sleep 3
  check F-11 "--tls against a plain-text JVM with auth fails..." 3 '.error.code == "TLS_FAILED"' -- \
    axo client "-e JMX_USERNAME=operator -e JMX_PASSWORD=Marker-7f3a9c" --url "$(url auth)" --tls ping
  sleep 1
  # Copy the capture out: busybox grep in the capture container stops at NUL bytes.
  check F-11b "...and the password never crossed the network" 0 - -- \
    sh -c "docker cp ${P}cap:/tmp/cap.pcap '$WORK/cap1.pcap' && test -s '$WORK/cap1.pcap' && ! grep -aq Marker-7f3a9c '$WORK/cap1.pcap'"
  check F-11c "control: without --tls the same password is sent in plain text" 3 '.error.code == "AUTH_FAILED"' -- \
    axo client "-e JMX_USERNAME=operator -e JMX_PASSWORD=Marker-7f3a9c" --url "$(url auth)" ping
  sleep 1
  check F-11d "control: the capture shows the password" 0 - -- \
    sh -c "docker cp ${P}cap:/tmp/cap.pcap '$WORK/cap2.pcap' && grep -aq Marker-7f3a9c '$WORK/cap2.pcap'"
  docker rm -f "${P}cap" >/dev/null

  check F-12 "without --tls, an untrusted certificate is TLS_FAILED, not a retryable CONNECTION_FAILED" 3 \
    '.error.code == "TLS_FAILED" and (.error.retryable | not)' -- op --url "$(url tls-noreg)" ping
  check F-13 "--tls-ca with the CA that signed the certificate" 0 '.result.connected' -- op --url "$(url tls-ca)" --tls --tls-ca $CA/ca.pem ping
  check F-14 "--tls-ca with a PEM bundle of several certificates" 0 '.result.connected' -- op --url "$(url tls-ca)" --tls --tls-ca $CA/bundle.pem ping
  check F-15 "--tls-ca: a missing file, before connecting" 2 '.error.code == "INVALID_ARGUMENT"' -- op --url "$(url tls)" --tls --tls-ca $CA/nope.pem ping
  check F-15b "--tls-ca: an empty file" 2 '.error.code == "INVALID_ARGUMENT"' -- op --url "$(url tls)" --tls --tls-ca $CA/empty.pem ping
  check F-15c "--tls-ca: a file that is not PEM" 2 '.error.code == "INVALID_ARGUMENT"' -- op --url "$(url tls)" --tls --tls-ca $CA/notpem.pem ping
  check F-15d "--tls-ca: a key store instead of PEM" 2 '.error.code == "INVALID_ARGUMENT"' -- op --url "$(url tls)" --tls --tls-ca $CA/jmxremote.access ping
  check F-16 "--tls with a URL that is not /jndi/rmi://" 2 '.error.code == "INVALID_ARGUMENT"' -- \
    op --url "service:jmx:rmi://tls:7199/stub/rO0ABQ==" --tls ping
  check F-17 "--tls on a JDK 8 JVM" 0 '.result.attributes.SpecVersion == "1.8"' -- op --url "$(url tls8)" --tls --tls-ca $CA/tls.pem read java.lang:type=Runtime SpecVersion
  info F-18 "need.client.auth=true (documented as unsupported)" -- op --url "$(url tls-clientauth)" --tls --tls-ca $CA/tls.pem ping
  TLS_IP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "${P}tls")
  check F-19 "an IP address in the URL that the certificate does not name" 3 '.error.code == "TLS_FAILED"' -- \
    op --url "$(url "$TLS_IP")" --tls --tls-ca $CA/tls.pem ping
  check F-20 "a wrong password over TLS is AUTH_FAILED" 3 '.error.code == "AUTH_FAILED"' -- \
    axo client "-e JMX_USERNAME=operator -e JMX_PASSWORD=wrong" --url "$(url tls)" --tls --tls-ca $CA/tls.pem ping
  check F-21 "viewer write over TLS is AUTH_FAILED" 3 '.error.code == "AUTH_FAILED" and .error.details.mbean != null' -- \
    axo client "$VIEWER" --url "$(url tls)" --tls --tls-ca $CA/tls.pem write "$CACHE" MaxConnections=1
  check F-22 "501 levels over TLS: UNSUPPORTED_TYPE" 5 '.error.code == "UNSUPPORTED_TYPE"' -- \
    op --url "$(url tls)" --tls --tls-ca $CA/tls.pem invoke "$CACHE" nested --args '[501]'
fi

# === G: install script ============================================================================
if section G; then
  echo "== G: install.sh"
  mkdir -p "$WORK/install"
  docker inspect "${P}jdk21" >/dev/null 2>&1 || target jdk21 "$T21" --
  check G-01 "install.sh installs the released v0.1.0 into -b" 0 - -- sh "$HERE/../install.sh" -b "$WORK/install" v0.1.0
  if [[ ! -x $WORK/install/ajmx ]]; then
    # Where install.sh cannot look up the tag, fetch the asset and its checksum the same way it does.
    check G-01b "download the v0.1.0 asset and check its SHA-256" 0 - -- sh -c "cd '$WORK/install' &&
      curl -fsSLO https://github.com/yagipass/ajmx/releases/download/v0.1.0/ajmx-linux-amd64 &&
      curl -fsSLO https://github.com/yagipass/ajmx/releases/download/v0.1.0/ajmx-linux-amd64.sha256 &&
      sha256sum -c ajmx-linux-amd64.sha256 && chmod +x ajmx-linux-amd64 && mv ajmx-linux-amd64 ajmx"
  fi
  check G-01c "the released v0.1.0 binary runs" 0 '.result.version == "0.1.0"' -- "$WORK/install/ajmx" version
  if [[ -x $WORK/install/ajmx ]] && docker inspect "${P}jdk21" >/dev/null 2>&1; then
    docker cp "$WORK/install/ajmx" "${P}jdk21:/tmp/ajmx-0.1.0" >/dev/null
    info G-02a "for comparison: v0.1.0 ping" -- docker exec "${P}jdk21" /tmp/ajmx-0.1.0 --pid 1 ping
    info G-02 "for comparison: v0.1.0 reads the 100,000-level value" -- docker exec "${P}jdk21" /tmp/ajmx-0.1.0 --pid 1 read "$CACHE" DeepValue
    info G-02b "for comparison: v0.1.0 without --tls against an untrusted TLS connection" -- \
      docker exec -e JMX_USERNAME=operator -e JMX_PASSWORD=s3cret-pw "${P}jdk21" /tmp/ajmx-0.1.0 --url "$(url tls-noreg)" ping
  fi
  if [[ -x $WORK/install/ajmx ]]; then
    info G-03 "newest glibc symbol version the released v0.1.0 requires" -- \
      sh -c "objdump -T '$WORK/install/ajmx' | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1"
    for img in ubuntu:20.04 debian:11 rockylinux:8 amazonlinux:2; do
      info "G-04[$img]" "the released v0.1.0 binary on $img" -- \
        docker run --rm -v "$WORK/install/ajmx:$BIN_IN:ro" --entrypoint "$BIN_IN" "$img" version
    done
  fi
fi

echo
echo "Results: $RESULTS"
echo "Details: $DETAILS"
awk -F'\t' '{n[$2]++} END {for (s in n) printf "%s=%d ", s, n[s]; print ""}' "$RESULTS"
