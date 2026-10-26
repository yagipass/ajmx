# Helpers for run.sh. Sourced, not run.

P=ajmx-mt-          # prefix of every container and network this harness creates
NET=${P}net
CLASSES_IN=/ajmx/classes
BIN_IN=/ajmx/ajmx
MAIN=ajmxtest.TestTarget

# check ID DESCRIPTION EXPECTED_EXIT FILTER -- COMMAND...
#   FILTER is a jq expression that must be true for the single-line JSON on stdout,
#   "re:<regex>" to grep stdout+stderr instead, or "-" to check the exit code only.
#   EXPECTED_EXIT "nz" accepts any non-zero exit code.
check() {
  local id=$1 desc=$2 want=$3 filter=$4
  shift 5
  [[ $id =~ $ONLY ]] || return 0
  local out err code status=PASS
  err=$(mktemp)
  out=$("$@" 2>"$err")
  code=$?
  if [[ $want == nz ]]; then
    [[ $code != 0 ]] || status=FAIL
  elif [[ $code != "$want" ]]; then
    status=FAIL
  fi
  case $filter in
    -) ;;
    re:*) grep -Eq -- "${filter#re:}" <(printf '%s\n' "$out"; cat "$err") || status=FAIL ;;
    *)
      if [[ $(printf '%s\n' "$out" | wc -l) != 1 ]] ||
        ! jq -e ".schemaVersion == 1 and ($filter)" <<<"$out" >/dev/null 2>&1; then
        status=FAIL
      fi
      ;;
  esac
  record "$id" "$status" "$code" "$desc" "$*" "$out" "$err"
  rm -f "$err"
}

# info ID DESCRIPTION -- COMMAND...   Runs the command and records what happened, without a verdict.
info() {
  local id=$1 desc=$2
  shift 3
  [[ $id =~ $ONLY ]] || return 0
  local out err code
  err=$(mktemp)
  out=$("$@" 2>"$err")
  code=$?
  record "$id" INFO "$code" "$desc" "$*" "$out" "$err"
  rm -f "$err"
}

record() {
  local id=$1 status=$2 code=$3 desc=$4 cmd=$5 out=$6 err=$7
  printf '%s\t%s\t%s\t%s\n' "$id" "$status" "$code" "$desc" >>"$RESULTS"
  {
    printf '### %s [%s] exit=%s %s\n$ %s\n' "$id" "$status" "$code" "$desc" "$cmd"
    printf '%s\n' "$out" | cut -c1-3000 | head -40
    if [[ -s $err ]]; then
      printf -- '--- stderr\n'
      head -c 3000 "$err" | head -40
    fi
    printf '\n'
  } >>"$DETAILS"
  printf '%-6s %-12s exit=%-3s %s\n' "$status" "$id" "$code" "$desc"
}

# start NAME IMAGE [docker-run options...] -- [command...]
# Starts a detached container on $NET with the binary and the test target mounted, and waits for
# the test target to print READY when the command runs it.
start() {
  local name=$1 image=$2
  shift 2
  local opts=()
  while [[ $# -gt 0 && $1 != -- ]]; do
    opts+=("$1")
    shift
  done
  shift
  docker rm -f "$P$name" >/dev/null 2>&1
  docker run -d --name "$P$name" --hostname "$name" --network "$NET" --network-alias "$name" \
    -v "$BIN:$BIN_IN:ro" -v "$CLASSES:$CLASSES_IN:ro" -v "$WORK/conf:/ajmx/conf:ro" \
    "${opts[@]}" "$image" "$@" >/dev/null || return 1
}

# wait_ready NAME [SECONDS]   Waits for the test target's READY line in the container's log.
wait_ready() {
  local name=$1 limit=${2:-60} i
  for ((i = 0; i < limit * 5; i++)); do
    if docker logs "$P$name" 2>&1 | grep -q '^READY'; then
      return 0
    fi
    sleep 0.2
  done
  echo "!! $name did not become ready" >&2
  docker logs "$P$name" 2>&1 | tail -20 >&2
  return 1
}

# target NAME IMAGE [docker-run options...] -- [java options...]
# Runs the test target as PID 1 with `java`, and waits for it.
target() {
  local name=$1 image=$2
  shift 2
  local opts=()
  while [[ $# -gt 0 && $1 != -- ]]; do
    opts+=("$1")
    shift
  done
  shift
  start "$name" "$image" "${opts[@]}" -- java "$@" -cp "$CLASSES_IN" "$MAIN" && wait_ready "$name"
}

# remote_flags HOST [PORT]   The JVM flags of docs/content/connect/remote.md, without auth or TLS.
remote_flags() {
  local host=$1 port=${2:-7199}
  echo "-Dcom.sun.management.jmxremote.port=$port -Dcom.sun.management.jmxremote.rmi.port=$port" \
    "-Djava.rmi.server.hostname=$host"
}

url() { echo "service:jmx:rmi:///jndi/rmi://$1:${2:-7199}/jmxrmi"; }

# ax CONTAINER ARGS...   Runs ajmx inside a container.
ax() {
  local c=$1
  shift
  docker exec "$P$c" "$BIN_IN" "$@"
}

# axi CONTAINER ARGS...   Like ax, with stdin.
axi() {
  local c=$1
  shift
  docker exec -i "$P$c" "$BIN_IN" "$@"
}

# axo CONTAINER "DOCKER EXEC OPTIONS" ARGS...   Like ax, with options for docker exec such as -u or -e.
axo() {
  local c=$1 o=$2
  shift 2
  # shellcheck disable=SC2086
  docker exec $o "$P$c" "$BIN_IN" "$@"
}

alive() { docker exec "$P$1" sh -c "kill -0 $2"; }

cleanup() {
  docker ps -aq --filter "name=^$P" | xargs -r docker rm -f >/dev/null 2>&1
}
