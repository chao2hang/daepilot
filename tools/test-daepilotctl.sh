#!/bin/sh
# Host-side regression tests for magisk/payload/bin/daepilotctl.
#
# The control script targets Android (/system/bin/sh, toybox, chroot as root),
# so the tests run it with plain `sh` under a fake PATH:
#   - fake chroot execs <root><guest-path> directly (no real chroot, no root)
#   - fake dae validates text configs, records which guest file was validated,
#     can be told to fail `reload`, and stays "running" for lifecycle tests
#   - fake ip / getprop provide deterministic routing and DNS-prop data
#
# Primary regression covered: ensure_geodata() used to leak the globals
# src/dest into apply_config(), so `cp "$src" "$CONF"` copied geoip.dat over
# config.dae (the 10/25-10/26 corruption). The harness re-derives the old
# buggy script by deleting the `local` guard and asserts the corruption is
# reproduced there and absent in the current script.
set -u

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CTL_SRC="$ROOT_DIR/magisk/payload/bin/daepilotctl"
WORK="$(mktemp -d)"
FAILED=0
DAEMON_PID=""

cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null
  for pf in "$WORK"/base/run/dae.host.pid "$WORK"/base/rootfs/run/dae.pid; do
    [ -f "$pf" ] || continue
    p="$(tr -dc '0-9' < "$pf" 2>/dev/null)"
    [ -n "$p" ] && kill "$p" 2>/dev/null
  done
  rm -rf "$WORK"
}
trap cleanup EXIT

pass() { printf 'PASS  %s\n' "$1"; }
fail_test() { printf 'FAIL  %s\n' "$1"; FAILED=1; }

assert_eq() {
  if [ "$1" = "$2" ]; then pass "$3"; else fail_test "$3 (got [$1] want [$2])"; fi
}

assert_contains() {
  if grep -qF -- "$2" "$1" 2>/dev/null; then pass "$3"; else fail_test "$3 (missing '$2' in $1)"; fi
}

assert_not_contains() {
  if grep -qF -- "$2" "$1" 2>/dev/null; then fail_test "$3 (unexpected '$2' in $1)"; else pass "$3"; fi
}

assert_file_absent() {
  if [ -e "$1" ]; then fail_test "$2 ($1 exists)"; else pass "$2"; fi
}

assert_file_present() {
  if [ -e "$1" ]; then pass "$2"; else fail_test "$2 ($1 missing)"; fi
}

# --- fake tools -----------------------------------------------------------

mkdir -p "$WORK/fakebin"

cat > "$WORK/fakebin/chroot" <<'EOF'
#!/bin/sh
# chroot ROOT GUEST args... -> exec ROOT/GUEST args... (no privilege needed)
root="$1"; guest="$2"; shift 2
DAEPILOT_TEST_ROOT="$root"
export DAEPILOT_TEST_ROOT
exec "$root$guest" "$@"
EOF

cat > "$WORK/fakebin/busybox" <<'EOF'
#!/bin/sh
# busybox stub: the ctl prefers busybox when present, so fake the subcommands
# the tests need (chroot/setsid/unshare/mount) and fail loudly on anything else.
cmd="$1"; shift
case "$cmd" in
  chroot)
    root="$1"; guest="$2"; shift 2
    DAEPILOT_TEST_ROOT="$root"
    export DAEPILOT_TEST_ROOT
    exec "$root$guest" "$@" ;;
  setsid)
    # The ctl's shebang is /system/bin/sh, which does not exist on the host,
    # so run scripts through the host sh instead of exec'ing them directly.
    exec sh "$@" ;;
  unshare)
    # Drop the only flag the ctl uses; run the re-executed ctl directly.
    [ "$1" = "-m" ] && shift
    prog="$1"; shift
    # The ctl's shebang is /system/bin/sh, which does not exist on the host.
    # Pretend the new namespace's root is private (see the awk stub).
    DAEPILOT_TEST_PRIVATE_NS=1
    export DAEPILOT_TEST_PRIVATE_NS
    exec sh "$prog" "$@" ;;
  awk)
    # ns_root_is_private() asks for the mountinfo line of "/". The test host's
    # root is shared, so synthesize a private line inside the fake namespace.
    if [ "${DAEPILOT_TEST_PRIVATE_NS:-0}" = "1" ] && [ "$1" = '$5 == "/" { print; exit }' ]; then
      echo "1 0 0:1 / / rw,relatime - ext4 /dev/root rw"
      exit 0
    fi
    exec awk "$@" ;;
  mount)
    # Tests are not privileged enough for real mounts; the namespace logic is
    # asserted structurally instead.
    exit 0 ;;
  *)
    echo "fake-busybox: unsupported subcommand: $cmd" >&2
    exit 1 ;;
esac
EOF

cat > "$WORK/fakebin/getprop" <<'EOF'
#!/bin/sh
# No DNS props -> ctl must fall back to its own nameserver list.
exit 0
EOF

cat > "$WORK/fakebin/ip" <<'EOF'
#!/bin/sh
family=""
case "$1" in
  -4|-6) family="$1"; shift ;;
esac
oneline=""
case "$1" in
  -o) oneline=1; shift ;;
esac
dev=""
prev=""
for a in "$@"; do
  [ "$prev" = "dev" ] && dev="$a"
  prev="$a"
done
case "$1" in
  rule)
    case "$2" in
      add) exit 0 ;;
      del) exit 1 ;; # no rule present -> like real ip when the list is empty
      *)
        if [ "$family" != "-6" ]; then
          if [ "${FAKE_VPN:-0}" = "1" ]; then
            printf '0:\tfrom all lookup local\n'
            printf '11000:\tfrom all iif lo oif dummy0 lookup dummy0\n'
            printf '11100:\tfrom all lookup tun0\n'
            printf '11200:\tfrom all lookup rmnet_data3\n'
            printf '32000:\tfrom all unreachable\n'
          elif [ "${FAKE_MOBILE:-0}" = "1" ]; then
            printf '0:\tfrom all lookup local\n'
            printf '9900:\tfrom all fwmark 0x100/0x1ffff lookup rmnet_data3\n'
            printf '11000:\tfrom all iif lo oif rmnet_data3 uidrange 0-0 lookup rmnet_data3\n'
            printf '32000:\tfrom all unreachable\n'
          else
            printf '0:\tfrom all lookup local\n'
            printf '32766:\tfrom all lookup main\n'
            printf '32767:\tfrom all lookup default\n'
          fi
        fi
        ;;
    esac ;;
  route)
    if [ "$family" = "-6" ]; then
      [ "${FAKE_IPV6:-0}" = "1" ] && echo "default via fe80::1 dev eth0"
      exit 0
    fi
    case "$*" in
      "route get "*|*" route get "*)
        if [ "${FAKE_VPN:-0}" = "1" ]; then
          echo "1.1.1.1 via 10.8.0.1 dev tun0 src 10.8.0.2"
        elif [ "${FAKE_MOBILE:-0}" = "1" ]; then
          echo "1.1.1.1 via 10.0.0.1 dev rmnet_data3 src 10.0.0.2"
        else
          echo "1.1.1.1 via 192.0.2.1 dev eth0 src 192.0.2.2"
        fi
        exit 0 ;;
      *table\ dummy0*) echo "default via 10.255.0.1 dev dummy0" ;;
      *table\ tun0*) echo "default via 10.8.0.1 dev tun0" ;;
      *table\ rmnet_data3*) echo "default via 10.0.0.1 dev rmnet_data3" ;;
      *table\ main*) echo "default via 192.0.2.1 dev eth0" ;;
      *) : ;;
    esac ;;
  link)
    # "ip link show <dev>" passes the name positionally, without a "dev" keyword.
    [ -n "$dev" ] || dev="${3:-}"
    if [ "$oneline" = "1" ]; then
      printf '1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 state UNKNOWN\n'
      printf '2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n'
      printf '3: rmnet_data3: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UNKNOWN\n'
      if [ "${FAKE_STA_DOWN:-0}" = "1" ]; then
        printf '4: wlan0: <BROADCAST,MULTICAST> mtu 1500 state DOWN\n'
      else
        printf '4: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n'
      fi
      [ -n "${FAKE_AP:-}" ] && printf '43: %s: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n' "$FAKE_AP"
      [ -n "${FAKE_USB:-}" ] && printf '44: %s: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UNKNOWN\n' "$FAKE_USB"
      exit 0
    fi
    case "$*" in
      *rmnet_data3*) echo "3: rmnet_data3: <UP,LOWER_UP> mtu 1500 state UNKNOWN mode DEFAULT" ;;
      *tun0*) echo "4: tun0: <UP,LOWER_UP> mtu 1400 state UNKNOWN mode DEFAULT" ;;
      *eth0*) echo "2: eth0: <UP,LOWER_UP> mtu 1500 state UP mode DEFAULT" ;;
      *wlan0*)
        if [ "${FAKE_STA_DOWN:-0}" = "1" ]; then
          echo "4: wlan0: <BROADCAST,MULTICAST> mtu 1500 state DOWN mode DEFAULT"
        else
          echo "4: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP mode DEFAULT"
        fi ;;
      *)
        if [ -n "$dev" ] && [ "$dev" = "${FAKE_AP:-}" ]; then
          echo "43: $dev: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP mode DEFAULT"
        elif [ -n "$dev" ] && [ "$dev" = "${FAKE_USB:-}" ]; then
          echo "44: $dev: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UNKNOWN mode DEFAULT"
        fi ;;
    esac ;;
  addr)
    if [ "$family" = "-4" ]; then
      case "$*" in
        *rmnet_data3*) echo "    inet 10.0.0.2/30 scope global rmnet_data3" ;;
        *tun0*) echo "    inet 10.8.0.2/24 scope global tun0" ;;
        *eth0*) echo "    inet 192.0.2.2/24 scope global eth0" ;;
        *wlan0*) [ "${FAKE_STA_DOWN:-0}" = "1" ] || echo "    inet 192.0.2.2/24 scope global wlan0" ;;
        *)
          if [ -n "$dev" ] && [ "$dev" = "${FAKE_AP:-}" ]; then
            echo "    inet 192.168.43.1/24 scope global $dev"
          elif [ -n "$dev" ] && [ "$dev" = "${FAKE_USB:-}" ]; then
            echo "    inet 192.168.42.129/24 scope global $dev"
          fi ;;
      esac
    fi ;;
  neigh)
    n="${FAKE_LAN_CLIENTS:-0}"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    i=0
    while [ "$i" -lt "$n" ]; do
      i=$((i + 1))
      printf '192.168.43.%s lladdr aa:bb:cc:dd:ee:0%s REACHABLE\n' "$((100 + i))" "$i"
    done ;;
esac
exit 0
EOF

cat > "$WORK/fakebin/mount" <<'EOF'
#!/bin/sh
# Never let the host-side tests create real bind mounts under $WORK.
exit 0
EOF

cat > "$WORK/fakebin/mountpoint" <<'EOF'
#!/bin/sh
# Nothing is mounted in the hermetic test tree.
exit 1
EOF

cat > "$WORK/fakebin/ping" <<'EOF'
#!/bin/sh
# Only the final operand (the hostname) matters to write_node_hosts().
host=""
for arg in "$@"; do
  case "$arg" in -*) ;; *) host="$arg" ;; esac
done
echo "PING $host (203.0.113.9) 56(84) bytes of data."
EOF

cat > "$WORK/fakebin/dumpsys" <<'EOF'
#!/bin/sh
if [ "${FAKE_MOBILE:-0}" = "1" ]; then
  echo 'NetworkAgentInfo{network{101} lp{{InterfaceName: rmnet_data3 DnsAddresses: [ /10.2.164.1, /10.2.164.2 ]}}}'
fi
if [ "${FAKE_DUMPSYS:-0}" = "1" ]; then
  echo 'NetworkAgentInfo{network{99} lp{{InterfaceName: rmnet_data1 DnsAddresses: [ /114.114.114.114 ]}}}'
  echo 'NetworkAgentInfo{network{100} lp{{InterfaceName: eth0 DnsAddresses: [ /10.0.2.3, /2001:db8::54 ]}}}'
fi
exit 0
EOF

cat > "$WORK/fakebin/iptables" <<EOF
#!/bin/sh
printf 'iptables %s\n' "\$*" >> "$WORK/iptables.log"
case "\$*" in
  *"-S daepilot_bypass"*)
    printf '%s\n' '-A daepilot_bypass -m owner --uid-owner 10123 -j DSCP --set-dscp 0x2f'
    printf '%s\n' '-A daepilot_bypass -m owner --uid-owner 2000 -j DSCP --set-dscp 0x2f'
    exit 0 ;;
  *"-C OUTPUT"*) exit 1 ;;
esac
exit 0
EOF

cat > "$WORK/fakebin/curl" <<EOF
#!/bin/sh
# Warmup probe interceptor: record the call, succeed unless told to fail.
printf 'curl %s\n' "\$*" >> "$WORK/curl.log"
if [ "\${FAKE_CURL_FAIL:-0}" = "1" ]; then exit 28; fi
exit 0
EOF

cat > "$WORK/fakebin/iw" <<'EOF'
#!/bin/sh
# iw dev: a station plus an optional AP. HyperOS names the AP wlan2, so the
# ctl must read the type rather than guess from the interface name.
printf 'phy#0\n'
printf '\tInterface wlan0\n\t\tifindex 4\n\t\ttype managed\n'
if [ -n "${FAKE_AP:-}" ]; then
  printf '\tInterface %s\n\t\tifindex 43\n\t\ttype AP\n' "$FAKE_AP"
fi
exit 0
EOF

cat > "$WORK/fakebin/tc" <<EOF
#!/bin/sh
# Stateful clsact filter table: "filter show" reads \$FAKE_TC_STATE/<dev>.<hook>,
# "filter del ... pref N" drops that pref. Every call is logged.
printf 'tc %s\n' "\$*" >> "$WORK/tc.log"
state="\${FAKE_TC_STATE:?}"
[ "\$1" = "filter" ] || exit 0
action="\$2"
dev=""; hook=""; pref=""
shift 2
while [ \$# -gt 0 ]; do
  case "\$1" in
    dev) dev="\$2"; shift 2 ;;
    ingress|egress) hook="\$1"; shift ;;
    pref) pref="\$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "\$dev" ] && [ -n "\$hook" ] || exit 0
case "\$action" in
  show)
    [ -f "\$state/\$dev.\$hook" ] || exit 0
    while read -r p name; do
      [ -n "\$p" ] || continue
      printf 'filter protocol ip pref %s bpf chain 0 handle 0x1 %s direct-action not_in_hw\n' "\$p" "\$name"
    done < "\$state/\$dev.\$hook" ;;
  del)
    [ -f "\$state/\$dev.\$hook" ] || exit 0
    grep -v "^\$pref " "\$state/\$dev.\$hook" > "\$state/\$dev.\$hook.tmp" 2>/dev/null
    mv "\$state/\$dev.\$hook.tmp" "\$state/\$dev.\$hook" ;;
esac
exit 0
EOF

cat > "$WORK/fakebin/settings" <<'EOF'
#!/bin/sh
# diagnose asks for the tethering offload global; null = Android default.
echo "null"
EOF

chmod 755 "$WORK/fakebin/chroot" "$WORK/fakebin/busybox" "$WORK/fakebin/getprop" \
  "$WORK/fakebin/ip" "$WORK/fakebin/mount" "$WORK/fakebin/mountpoint" "$WORK/fakebin/ping" \
  "$WORK/fakebin/dumpsys" "$WORK/fakebin/iptables" "$WORK/fakebin/curl" \
  "$WORK/fakebin/iw" "$WORK/fakebin/tc" "$WORK/fakebin/settings"

# --- per-test fixtures ----------------------------------------------------

install_ctl() {
  cp "$1" "$BASE/bin/daepilotctl"
  chmod 755 "$BASE/bin/daepilotctl"
}

setup_base() { # $1 = ctl source to install
  BASE="$WORK/base"
  # A previous test may have left a fake daemon whose pid files are about to
  # be deleted; kill it first so is_running() does not leak across tests.
  for pf in "$BASE/run/dae.host.pid" "$BASE/rootfs/run/dae.pid"; do
    [ -s "$pf" ] || continue
    old_pid="$(tr -dc '0-9' < "$pf" 2>/dev/null)"
    [ -n "$old_pid" ] && kill -9 "$old_pid" 2>/dev/null
  done
  rm -rf "$BASE"
  mkdir -p "$BASE/geo" "$BASE/staging" "$BASE/bin" "$BASE/config"
  printf 'GEOSITE-BINARY\n' > "$BASE/geo/geosite.dat"
  printf 'GEOIP-BINARY-MARKER\n' > "$BASE/geo/geoip.dat"

  cat > "$BASE/bin/dae" <<'EOF'
#!/bin/sh
case "$1" in
  validate)
    file="$3"
    [ -n "$DAEPILOT_TEST_ROOT" ] && file="$DAEPILOT_TEST_ROOT$file"
    if [ ! -f "$file" ]; then echo "open $file: no such file"; exit 1; fi
    if ! grep -q 'global {' "$file" 2>/dev/null; then
      echo "token recognition error: invalid syntax"
      exit 1
    fi
    printf '%s\n' "$file" >> "${DAEPILOT_TEST_LOG:?}"
    exit 0 ;;
  reload)
    if [ -n "$DAEPILOT_TEST_FAIL_RELOAD" ]; then
      echo "reload failed (test)"
      exit 1
    fi
    exit 0 ;;
  run)
    # LAN bind failures: fatal (rejected TCX attach) or warn-only (kernel gate).
    if grep -q 'lan_interface' "${DAEPILOT_TEST_ROOT:?}/config/config.dae" 2>/dev/null; then
      if [ "${DAEPILOT_TEST_FAIL_LAN:-0}" = "1" ]; then
        echo 'FATAL commit TC HookSet transaction: attach TC hook dae_lan_ingress_l2 on wlan2: invalid argument'
        exit 1
      fi
      if [ "${DAEPILOT_TEST_WARN_LAN:-0}" = "1" ]; then
        echo ' WARN your kernel version 5.10 does not support bind to LAN; expect >=5.15; remove lan_interface in config file and try again'
      fi
    fi
    echo "$$" > "${DAEPILOT_TEST_ROOT:?}/run/dae.pid" 2>/dev/null
    # Keep "run -c /config/config.dae" in the process cmdline; is_our_dae()
    # deliberately checks it so a stale or unrelated dae is not adopted.
    trap 'exit 0' TERM
    while :; do sleep 5; done ;;
  *)
    echo "fake-dae: unsupported: $*"
    exit 1 ;;
esac
EOF

  cat > "$BASE/bin/dae-hold" <<'EOF'
#!/bin/sh
trap 'exit 0' TERM
while :; do sleep 5; done
EOF

  chmod 755 "$BASE/bin/dae" "$BASE/bin/dae-hold"
  install_ctl "$1"
  : > "$WORK/validated.log"
  : > "$WORK/stderr.txt"
  mkdir -p "$WORK/tcstate"
  rm -f "$WORK/tcstate/"* 2>/dev/null
  : > "$WORK/tc.log"
}

launch_daemon() {
  mkdir -p "$BASE/rootfs/bin" "$BASE/rootfs/run" "$BASE/run"
  cp "$BASE/bin/dae" "$BASE/rootfs/bin/dae"
  cp "$BASE/bin/dae-hold" "$BASE/rootfs/bin/dae-hold"
  chmod 755 "$BASE/rootfs/bin/dae" "$BASE/rootfs/bin/dae-hold"
  # The fake dae writes its pid under DAEPILOT_TEST_ROOT, which the fake
  # chroot normally provides; launch_daemon runs it directly, so export it.
  DAEPILOT_TEST_ROOT="$BASE/rootfs" sh "$BASE/rootfs/bin/dae" run -c /config/config.dae >/dev/null 2>&1 &
  DAEMON_PID=$!
  echo "$DAEMON_PID" > "$BASE/run/dae.host.pid"
  i=0
  while [ ! -s "$BASE/rootfs/run/dae.pid" ] && [ "$i" -lt 50 ]; do
    sleep 0.1
    i=$((i + 1))
  done
}

run_ctl() { # $@ = ctl args; stdout is the ctl stdout, stderr -> $WORK/stderr.txt
  ( export PATH="$WORK/fakebin:$PATH"
    export DAEPILOT_BASE="$BASE"
    export DAEPILOT_TEST_ROOT="$BASE/rootfs"
    export DAEPILOT_TEST_LOG="$WORK/validated.log"
    export DAEPILOT_TEST_FAIL_RELOAD="${FAIL_RELOAD:-}"
    export FAKE_MOBILE="${FAKE_MOBILE:-}"
    export FAKE_VPN="${FAKE_VPN:-}"
    export FAKE_IPV6="${FAKE_IPV6:-}"
    export FAKE_DUMPSYS="${FAKE_DUMPSYS:-}"
    export FAKE_CURL_FAIL="${FAKE_CURL_FAIL:-}"
    export FAKE_AP="${FAKE_AP:-}"
    export FAKE_USB="${FAKE_USB:-}"
    export FAKE_LAN_CLIENTS="${FAKE_LAN_CLIENTS:-}"
    export FAKE_STA_DOWN="${FAKE_STA_DOWN:-}"
    export FAKE_TC_STATE="$WORK/tcstate"
    export DAEPILOT_TEST_FAIL_LAN="${FAIL_LAN:-}"
    export DAEPILOT_TEST_WARN_LAN="${WARN_LAN:-}"
    sh "$CTL_UNDER_TEST" "$@" 2>"$WORK/stderr.txt"
  )
}

CONF="$WORK/base/config/config.dae"
BAK="$WORK/base/config/config.dae.bak"

# ==========================================================================
# 1. apply installs the staged text config, validates via <name>.dae guest
#    path, and writes the DNS fallback list (no 1.1.1.1).
# ==========================================================================
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  log_level: info\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply succeeds"
assert_contains "$CONF" "log_level" "config.dae contains staged config"
assert_not_contains "$CONF" "GEOIP-BINARY-MARKER" "config.dae is not geoip data"
validated_file="$(tail -n 1 "$WORK/validated.log")"
case "$validated_file" in
  "$BASE/rootfs/config/config.validate."*.dae) pass "dae validated the .dae-suffixed guest file" ;;
  *) fail_test "dae validated the .dae-suffixed guest file (got [$validated_file])" ;;
esac
leftover=""
for f in "$BASE"/rootfs/config/config.validate.*; do
  [ -e "$f" ] && leftover="$leftover $f"
done
assert_eq "$leftover" "" "validate temp file removed"
assert_contains "$BASE/rootfs/etc/resolv.conf" "119.29.29.29" "DNS fallback 119.29.29.29 present"
assert_contains "$BASE/rootfs/etc/resolv.conf" "223.5.5.5" "DNS fallback 223.5.5.5 present"
assert_not_contains "$BASE/rootfs/etc/resolv.conf" "1.1.1.1" "no unreachable 1.1.1.1 fallback"
assert_file_absent "$BASE/run/last_error" "no last_error after successful apply"

# 2. second apply backs up the previous good config (not polluted data).
printf 'global {\n  log_level: debug\n  second: v2\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "second apply succeeds"
assert_contains "$CONF" "second: v2" "config.dae updated to v2"
assert_contains "$BAK" "log_level: info" "config.dae.bak holds previous good config"
assert_not_contains "$BAK" "GEOIP-BINARY-MARKER" "config.dae.bak is not geoip data"

# 3. validation failure surfaces dae's own output (app shows the reason).
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'this is not a dae config\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$([ "$rc" -eq 0 ] && echo ok || echo fail)" "fail" "bad config rejected"
assert_contains "$WORK/stderr.txt" "配置校验失败：token recognition error" \
  "stderr carries dae's real validation error"
assert_contains "$BASE/run/last_error" "token recognition error" "last_error records dae's reason"
assert_file_absent "$CONF" "bad config never reaches config.dae"

# 4. hot-reload failure rolls back to a backup that still validates.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  version: v1\n}\n' > "$CONF"
launch_daemon
FAIL_RELOAD=1
printf 'global {\n  version: v2-broken\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae" --reload)"; rc=$?
assert_eq "$([ "$rc" -eq 0 ] && echo ok || echo fail)" "fail" "failed hot reload rejected"
assert_contains "$WORK/stderr.txt" "已回滚到上一版" "rollback message reported"
assert_contains "$CONF" "version: v1" "config.dae rolled back to previous version"
assert_not_contains "$CONF" "v2-broken" "broken config not kept"
assert_contains "$BASE/rootfs/config/config.dae" "version: v1" "chroot config also rolled back"
FAIL_RELOAD=""
kill "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null; DAEMON_PID=""

# 5. hot-reload failure with a polluted backup must NOT restore it
#    (the historical state: config.dae and .bak both held geoip data).
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'GEOIP-BINARY-MARKER\n' > "$CONF"   # polluted main config, no backup yet
launch_daemon
FAIL_RELOAD=1
printf 'global {\n  version: v2\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae" --reload)"; rc=$?
assert_eq "$([ "$rc" -eq 0 ] && echo ok || echo fail)" "fail" "reload with polluted backup fails"
assert_contains "$WORK/stderr.txt" "备份配置校验未通过" "polluted backup is refused"
assert_contains "$CONF" "version: v2" "new config kept instead of restoring geoip"
assert_not_contains "$CONF" "GEOIP-BINARY-MARKER" "geoip backup was not restored"
FAIL_RELOAD=""
kill "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null; DAEMON_PID=""

# 6. harness reproduction of the historical bug: removing the `local` guard
#    in ensure_geodata must make apply corrupt config.dae with geoip data.
sed '/local name src dest a b/d' "$CTL_SRC" > "$WORK/ctl-buggy"
setup_base "$WORK/ctl-buggy"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  log_level: info\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "buggy ctl still exits 0 (as it did on device)"
assert_contains "$CONF" "GEOIP-BINARY-MARKER" \
  "harness reproduces the geoip-overwrites-config.dae bug"

# 7. "wan_interface: auto" is resolved against Android policy routing before
#    the chroot copy is written, while the app-owned config keeps "auto".
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  wan_interface: auto\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with auto WAN succeeds"
assert_contains "$CONF" "wan_interface: auto" "source config keeps auto"
assert_contains "$BASE/rootfs/config/config.dae" "wan_interface: eth0" \
  "chroot config gets the detected WAN"

# 9. Node server hostnames are resolved through the host resolver and pinned
#    in the chroot hosts file, so dae does not need a public DNS answer to
#    connect to the node.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  log_level: info
}
node {
  qxy: 'hysteria2://pw@qxyfoods.com:8443/?insecure=1&sni=qxyfoods.com#qxy'
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with a node hostname succeeds"
assert_contains "$BASE/rootfs/etc/hosts" "203.0.113.9 qxyfoods.com" \
  "node hostname is pinned in the chroot hosts file"

# 10. Android's dumpsys formats resolvers as "/10.0.2.3"; a leading slash in
#     resolv.conf makes dae's Go resolver fall back to [::1]:53.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
mkdir -p "$BASE/run"
printf '/10.0.2.3\n' > "$BASE/run/dns.servers"
printf 'global {\n  log_level: info\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with dumpsys-style resolver succeeds"
assert_contains "$BASE/rootfs/etc/resolv.conf" "nameserver 10.0.2.3" \
  "leading slash is stripped from the resolver"
assert_not_contains "$BASE/rootfs/etc/resolv.conf" "nameserver /" \
  "no invalid slash-prefixed resolver is written"

# 11. Cellular policy routing exposes rmnet_data3 instead of the main table;
#     auto WAN must resolve it too.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_MOBILE=1
printf 'global {\n  wan_interface: auto\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with cellular policy routing succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "wan_interface: rmnet_data3" \
  "auto WAN resolves the cellular interface"
FAKE_MOBILE=""

# 12. Live dumpsys DNS must win over a stale app-published list, and IPv6
#     nameservers must be dropped when there is no IPv6 default route.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
mkdir -p "$BASE/run"
printf '1.1.1.1\n2001:db8::53\n' > "$BASE/run/dns.servers"
FAKE_DUMPSYS=1
FAKE_IPV6=0
printf 'global {\n  log_level: info\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with live and stale resolvers succeeds"
assert_eq "$(head -n 1 "$BASE/rootfs/etc/resolv.conf")" "nameserver 10.0.2.3" \
  "live dumpsys resolver wins over the stale app list"
assert_contains "$BASE/rootfs/etc/resolv.conf" "nameserver 1.1.1.1" \
  "app resolver remains as a fallback"
assert_not_contains "$BASE/rootfs/etc/resolv.conf" "2001:db8:" \
  "IPv6 resolver is dropped without an IPv6 default"
FAKE_IPV6=1
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with IPv6 default succeeds"
assert_contains "$BASE/rootfs/etc/resolv.conf" "2001:db8::54" \
  "IPv6 resolver is kept when IPv6 is available"
FAKE_DUMPSYS=""
FAKE_IPV6=""

# 13. Windows CRLF config must still get the auto WAN replaced.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  wan_interface: auto\r\n}\r\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with CRLF config succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "wan_interface: eth0" \
  "CRLF config still gets the detected WAN"

# 14. A VPN tun default must not become dae's WAN.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_VPN=1
printf 'global {\n  wan_interface: auto\n}\n' > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with a VPN default route succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "wan_interface: rmnet_data3" \
  "VPN tun/dummy defaults are skipped in favor of the physical WAN"
FAKE_VPN=""

# 15. Switching the proxy node must restart dae and re-resolve the new host
#     instead of hot-reloading with the old pin.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
cat > "$CONF" <<'EOF'
global {
  log_level: info
}
node {
  a: 'trojan://pw@a.example:443#a'
}
group {
  proxy {
    filter: name('a')
    policy: fixed(0)
  }
}
EOF
launch_daemon
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  log_level: info
}
node {
  b: 'trojan://pw@b.example:443#b'
}
group {
  proxy {
    filter: name('b')
    policy: fixed(0)
  }
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae" --reload)"; rc=$?
assert_eq "$rc" "0" "node switch apply succeeds"
printf '%s
' "$out" > "$WORK/node-switch.out"
assert_contains "$WORK/node-switch.out" "节点服务器已变化" "node switch triggers a restart"
assert_contains "$BASE/rootfs/config/config.dae" "@203.0.113.9:443" \
  "new node URL is pinned after the restart"
assert_contains "$CTL_SRC" 'chmod 600 "$file"' \
  "ctl restores the private config mode after the node URL rewrite"
kill "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null; DAEMON_PID=""

# 16. The built-in domestic resolver is dropped by some carriers. It must be
#     replaced with the active WAN's live resolver, while a user-customized
#     upstream is preserved.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'udp://119.29.29.29:53'
    googledns: 'tcp+udp://8.8.8.8:53'
  }
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with default domestic resolver succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "alidns: 'udp://10.0.2.3:53'" \
  "built-in domestic resolver follows the WAN's live DNS"
assert_contains "$BASE/rootfs/config/config.dae" "googledns: 'tcp+udp://8.8.8.8:53'" \
  "foreign resolver is left unchanged"

setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  log_level: info
}
dns {
  upstream {
    alidns: 'udp://192.0.2.53:53'
  }
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with custom domestic resolver succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "alidns: 'udp://192.0.2.53:53'" \
  "custom domestic resolver is preserved"
assert_not_contains "$BASE/rootfs/config/config.dae" "udp://10.0.2.3:53" \
  "live resolver does not overwrite a custom upstream"

setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_MOBILE=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'udp://119.29.29.29:53'
  }
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply on cellular with default resolver succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "alidns: 'udp://10.2.164.1:53'" \
  "cellular resolver is taken from the active rmnet interface"
FAKE_MOBILE=""
FAKE_DUMPSYS=""

# 17. On cellular the datapath captures dae's own public-DNS queries, so the
#     domestic lookup rule must fall back to the proxy resolver. Wi-Fi keeps
#     the direct resolver.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_MOBILE=1
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'udp://119.29.29.29:53'
    googledns: 'tcp+udp://8.8.8.8:53'
  }
  routing {
    request {
      qname(geosite:cn) -> alidns
      fallback: googledns
    }
  }
}
node {
  a: 'trojan://pw@a.example:443#a'
}
group {
  proxy {
    filter: name('a')
    policy: fixed(0)
  }
}
routing {
  fallback: proxy
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply on cellular with proxy DNS succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "qname(geosite:cn) -> googledns" \
  "cellular domestic DNS is routed through the proxy"
assert_contains "$BASE/rootfs/config/config.dae" "alidns: 'udp://10.2.164.1:53'" \
  "cellular resolver is still injected for other lookups"
assert_contains "$BASE/rootfs/config/config.dae" "qtype(AAAA) && !qname(geosite:cn) -> reject" \
  "IPv4-only proxy blocks foreign AAAA lookups"
FAKE_MOBILE=""

# 17b. A domestic DoH upstream does not use port 53, so the cellular rewrite
#      must leave CN names on it instead of forcing the foreign DoH.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_MOBILE=1
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'https://223.5.5.5/dns-query'
    googledns: 'https://1.1.1.1/dns-query'
  }
  routing {
    request {
      qtype(AAAA) && !qname(geosite:cn) -> reject
      qname(geosite:cn) -> alidns
      fallback: googledns
    }
  }
}
node {
  a: 'trojan://pw@a.example:443#a'
}
group {
  proxy {
    filter: name('a')
    policy: fixed(0)
  }
}
routing {
  fallback: proxy
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with domestic DoH on cellular succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "qname(geosite:cn) -> alidns" \
  "domestic DoH keeps CN names on the domestic resolver"
assert_contains "$BASE/rootfs/config/config.dae" "alidns: 'https://223.5.5.5/dns-query'" \
  "domestic DoH upstream is preserved on cellular"
assert_not_contains "$BASE/rootfs/config/config.dae" "qname(geosite:cn) -> googledns" \
  "domestic DoH is not rewritten to the foreign resolver"
FAKE_MOBILE=""
FAKE_DUMPSYS=""

setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'udp://119.29.29.29:53'
    googledns: 'tcp+udp://8.8.8.8:53'
  }
  routing {
    request {
      qname(geosite:cn) -> alidns
      fallback: googledns
    }
  }
}
node {
  a: 'trojan://pw@a.example:443#a'
}
group {
  proxy {
    filter: name('a')
    policy: fixed(0)
  }
}
routing {
  fallback: proxy
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply on Wi-Fi with direct DNS succeeds"
assert_contains "$BASE/rootfs/config/config.dae" "qname(geosite:cn) -> alidns" \
  "Wi-Fi domestic DNS keeps the direct resolver"
assert_contains "$BASE/rootfs/config/config.dae" "qtype(AAAA) && !qname(geosite:cn) -> reject" \
  "foreign AAAA lookups are blocked on Wi-Fi too"
FAKE_MOBILE=""
FAKE_DUMPSYS=""

# 17d. The app can explicitly allow IPv6; the legacy AAAA injection must not
#      override that choice and the marker must survive staging.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_DUMPSYS=1
cat > "$BASE/staging/config.dae" <<'EOF'
# daepilot:ipv6=allow
global {
  wan_interface: auto
}
dns {
  upstream {
    alidns: 'https://223.5.5.5/dns-query'
    googledns: 'https://1.1.1.1/dns-query'
  }
  routing {
    request {
      qname(geosite:cn) -> alidns
      fallback: googledns
    }
  }
}
node {
  a: 'trojan://pw@a.example:443#a'
}
group {
  proxy {
    filter: name('a')
    policy: fixed(0)
  }
}
routing {
  fallback: proxy
}
EOF
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "apply with explicit IPv6 allow succeeds"
assert_not_contains "$BASE/rootfs/config/config.dae" "qtype(AAAA)" \
  "explicit IPv6 allow is not overwritten by the legacy AAAA block"
assert_contains "$BASE/rootfs/config/config.dae" "# daepilot:ipv6=allow" \
  "IPv6 allow marker survives staging"
FAKE_MOBILE=""
FAKE_DUMPSYS=""

# 17e. UID bypass: the app writes bypass_uids; ctl builds a DSCP mark chain.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
mkdir -p "$BASE/run"
printf '10123\n2000\n' > "$BASE/run/bypass_uids"
: > "$WORK/iptables.log"
out="$(run_ctl bypass)"; rc=$?
assert_eq "$rc" "0" "bypass command succeeds"
assert_contains "$WORK/iptables.log" "uid-owner 10123 -j DSCP --set-dscp 0x2f" \
  "first bypass UID is DSCP-marked"
assert_contains "$WORK/iptables.log" "uid-owner 2000 -j DSCP --set-dscp 0x2f" \
  "second bypass UID is DSCP-marked"
assert_contains "$WORK/iptables.log" "OUTPUT 1 -j daepilot_bypass" \
  "bypass chain is inserted into mangle OUTPUT"

# 8. The chroot must live in a private mount namespace and never unmount
#    host-visible mounts (the /dev/binderfs regression).
assert_contains "$CTL_SRC" "exec_in_private_ns" "ctl has the private-mount-namespace entry point"
assert_contains "$CTL_SRC" "unshare -m" "ctl launches the chroot under unshare -m"
assert_not_contains "$CTL_SRC" "cleanup_mounts" "ctl no longer unmounts host-visible chroot mounts"
assert_not_contains "$CTL_SRC" "umount -l" "ctl does not lazy-unmount anything"
assert_not_contains "$CTL_SRC" "grep -l -a" "ctl avoids toybox-incompatible grep flags"
assert_contains "$CTL_SRC" "ps -A -o PID,ARGS" "ctl discovers processes through ps"
assert_contains "$CTL_SRC" 'validate_file_ready "$CONF"' \
  "start validates without re-preparing the chroot"
assert_contains "$CTL_SRC" "config.validated" "unchanged config skips dae validate"
assert_contains "$CTL_SRC" "warmup_proxy_async" "start warms the proxy path in the background"
assert_contains "$CTL_SRC" 'mkdir "$WATCH_LOCK"' "watchdog start is serialized by an atomic lock"

# ==========================================================================
# 18. Cold start warms the proxy DNS path so the first app lookups do not race
#     the cold QUIC session (the startup SERVFAIL storm). A dead node must not
#     block boot: the warmup is bounded and non-fatal.
WARMUP_CONF='global {
  wan_interface: auto
}
node {
  a: '\''trojan://pw@203.0.113.7:443#a'\''
}
group {
  proxy {
    filter: name('\''a'\'')
    policy: fixed(0)
  }
}
routing {
  fallback: proxy
}'
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf '%s\n' "$WARMUP_CONF" > "$BASE/staging/config.dae"
: > "$WORK/curl.log"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "warmup: apply config succeeds"
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "warmup: start succeeds"
# The probe runs detached because it may block for seconds on a slow node;
# start must return first, then the probe must still happen.
i=0
while [ "$i" -lt 50 ] && ! grep -q 'generate_204' "$WORK/curl.log" 2>/dev/null; do
  sleep 0.1
  i=$((i + 1))
done
assert_contains "$WORK/curl.log" "https://www.google.com/generate_204" \
  "warmup: start probes the proxy path in the background"
case "$out" in
  *"proxy path warmed up"*) fail_test "warmup: start must not wait for the probe" ;;
  *) pass "warmup: start returns before the probe finishes" ;;
esac
run_ctl stop >/dev/null 2>&1

setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf '%s\n' "$WARMUP_CONF" > "$BASE/staging/config.dae"
: > "$WORK/curl.log"
run_ctl apply "$BASE/staging/config.dae" >/dev/null 2>&1
FAKE_CURL_FAIL=1
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "warmup: a dead node does not block boot"
i=0
while [ "$i" -lt 80 ]; do
  curl_calls="$(grep -c 'generate_204' "$WORK/curl.log" 2>/dev/null)"
  [ "$curl_calls" -ge 2 ] && break
  sleep 0.1
  i=$((i + 1))
done
curl_calls="$(grep -c 'generate_204' "$WORK/curl.log" 2>/dev/null)"
assert_eq "$curl_calls" "2" "warmup: retries are bounded to two attempts"
out="$(run_ctl __warmup)"
FAKE_CURL_FAIL=""
case "$out" in
  *"proxy path warmed up"*) fail_test "warmup: failed probe is not reported as warmed" ;;
  *) pass "warmup: failed probe is not reported as warmed" ;;
esac
run_ctl stop >/dev/null 2>&1

# ==========================================================================
# 19. Hotspot / USB tethering sharing. Tethered clients are FORWARD-path
#     traffic that dae's socket and WAN hooks never see, so only lan_interface
#     captures them: resolve the downstream interfaces, keep Android's
#     tethering offload filters from blocking the TCX attach, and degrade to
#     phone-only proxying when the LAN hook cannot be attached.
LAN_CONF='global {
  wan_interface: auto
  lan_interface: auto
}
routing {
  fallback: proxy
}'

lan_value() {
  sed -n 's/^[[:space:]]*lan_interface:[[:space:]]*\([^[:space:]]*\).*/\1/p' \
    "$BASE/rootfs/config/config.dae" 2>/dev/null | head -n 1
}

# 19a. The AP is found by its iw type, not by name (HyperOS: wlan2 AP, wlan0
#      station), and the station must not be picked up with it.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_AP=wlan2
FAKE_LAN_CLIENTS=2
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "lan: apply with auto LAN succeeds"
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "lan: start with an AP succeeds"
assert_contains "$CONF" "lan_interface: auto" "lan: source config keeps the app intent"
assert_eq "$(lan_value)" "wlan2" "lan: only the AP interface is bound"
assert_eq "$(head -n 1 "$BASE/run/lan.interfaces")" "wlan2" "lan: binding recorded"
assert_file_present "$BASE/run/lan.auto" "lan: auto intent recorded"
run_ctl status > "$WORK/status.out"
assert_contains "$WORK/status.out" "lan=wlan2" "lan: status reports the bound interface"
assert_contains "$WORK/status.out" "lan_hook=ok" "lan: status reports the hook as attached"
assert_contains "$WORK/status.out" "lan_clients=2" "lan: status counts tethered clients"
run_ctl diagnose > "$WORK/diag.out"
assert_contains "$WORK/diag.out" "lan_detected=wlan2" "lan: diagnose reports the detected AP"
run_ctl stop >/dev/null 2>&1
FAKE_LAN_CLIENTS=""

# 19b. Nothing shared -> the line is dropped so dae never binds a stale name.
FAKE_AP=""
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "lan: apply without sharing succeeds"
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "lan: start without sharing succeeds"
assert_eq "$(lan_value)" "" "lan: no downstream interface -> line dropped"
assert_file_absent "$BASE/run/lan.interfaces" "lan: binding cleared"
run_ctl status > "$WORK/status.out"
assert_contains "$WORK/status.out" "lan_hook=absent" "lan: status reports no LAN hook"
assert_contains "$WORK/status.out" "lan_clients=0" "lan: no clients without sharing"
run_ctl stop >/dev/null 2>&1

# 19c. Wi-Fi and USB tethering at once bind both, comma-separated without
#      spaces (dae splits the value on ",").
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_AP=wlan2
FAKE_USB=usb0
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "lan: apply with AP and USB succeeds"
assert_eq "$(lan_value)" "wlan2,usb0" "lan: both downstream interfaces are bound"
FAKE_USB=""
FAKE_AP=""

# 19d. Without iw the fallback is a live Wi-Fi interface that is not the WAN;
#      a disconnected station must not be mistaken for a hotspot.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
mv "$WORK/fakebin/iw" "$WORK/iw.hidden"
FAKE_AP=wlan2
FAKE_STA_DOWN=1
FAKE_MOBILE=1
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae")"; rc=$?
assert_eq "$rc" "0" "lan: apply without iw succeeds"
assert_eq "$(lan_value)" "wlan2" "lan: AP detected without iw, station skipped"
mv "$WORK/iw.hidden" "$WORK/fakebin/iw"
FAKE_STA_DOWN=""
FAKE_MOBILE=""
FAKE_AP=""

# 19e. Android's tethering offload filters make the TCX attach fail with
#      EINVAL. They must be gone before launch; unrelated filters must stay.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_AP=wlan2
printf '3 prog_offload_schedcls_tether_upstream4_ether\n2 prog_offload_schedcls_tether_upstream6_ether\n5 some_other_prog\n' \
  > "$WORK/tcstate/wlan2.ingress"
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
run_ctl apply "$BASE/staging/config.dae" >/dev/null 2>&1
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "lan: start with offload filters present succeeds"
assert_contains "$WORK/tc.log" "filter del dev wlan2 ingress pref 3" \
  "lan: IPv4 tethering offload filter removed"
assert_contains "$WORK/tc.log" "filter del dev wlan2 ingress pref 2" \
  "lan: IPv6 tethering offload filter removed"
assert_not_contains "$WORK/tc.log" "pref 5" "lan: unrelated filters are left alone"
assert_contains "$WORK/tcstate/wlan2.ingress" "some_other_prog" \
  "lan: unrelated filter still installed"
run_ctl stop >/dev/null 2>&1
FAKE_AP=""

# 19f. A rejected LAN attach must not take the whole proxy down: retry once
#      with the filters cleared, then start without LAN and report it.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_AP=wlan2
FAIL_LAN=1
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
run_ctl apply "$BASE/staging/config.dae" >/dev/null 2>&1
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "lan: fatal LAN attach still starts the proxy"
case "$out" in
  *降级*) pass "lan: degrade is reported to the caller" ;;
  *) fail_test "lan: degrade is reported to the caller (got [$out])" ;;
esac
assert_eq "$(lan_value)" "" "lan: degraded config drops the LAN bind"
assert_contains "$CONF" "lan_interface: auto" "lan: app intent kept for the next retry"
assert_eq "$(head -n 1 "$BASE/run/lan.hook")" "failed" "lan: hook state records the failure"
run_ctl status > "$WORK/status.out"
assert_contains "$WORK/status.out" "running=1" "lan: daemon is up after degrading"
assert_contains "$WORK/status.out" "lan_hook=failed" "lan: status reports the degraded hook"
FAIL_LAN=""
run_ctl stop >/dev/null 2>&1
FAKE_AP=""

# 19g. The kernel-version gate only warns: dae stays up without LAN capture,
#      so the log must be checked, not just the process state.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
FAKE_AP=wlan2
WARN_LAN=1
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
run_ctl apply "$BASE/staging/config.dae" >/dev/null 2>&1
out="$(run_ctl start)"; rc=$?
assert_eq "$rc" "0" "lan: warn-only LAN failure still starts the proxy"
assert_eq "$(lan_value)" "" "lan: warn-only failure also drops the LAN bind"
assert_eq "$(head -n 1 "$BASE/run/lan.hook")" "failed" "lan: warn-only failure is recorded"
run_ctl status > "$WORK/status.out"
assert_contains "$WORK/status.out" "running=1" "lan: daemon is up after a warn-only failure"
WARN_LAN=""
run_ctl stop >/dev/null 2>&1
FAKE_AP=""

# 19h. Toggling sharing while dae runs goes through hot reload: the offload
#      filters must be cleared there too, and the binding recorded.
setup_base "$CTL_SRC"
CTL_UNDER_TEST="$BASE/bin/daepilotctl"
printf 'global {\n  wan_interface: auto\n}\n' > "$BASE/staging/config.dae"
run_ctl apply "$BASE/staging/config.dae" >/dev/null 2>&1
run_ctl start >/dev/null 2>&1
FAKE_AP=wlan2
printf '3 prog_offload_schedcls_tether_upstream4_ether\n' > "$WORK/tcstate/wlan2.ingress"
printf '%s\n' "$LAN_CONF" > "$BASE/staging/config.dae"
out="$(run_ctl apply "$BASE/staging/config.dae" --reload)"; rc=$?
assert_eq "$rc" "0" "lan: hot reload with sharing succeeds"
assert_contains "$WORK/tc.log" "filter del dev wlan2 ingress pref 3" \
  "lan: hot reload clears the offload filter"
assert_eq "$(head -n 1 "$BASE/run/lan.interfaces")" "wlan2" "lan: hot reload records the binding"
assert_eq "$(head -n 1 "$BASE/run/lan.hook")" "ok" "lan: hot reload reports the hook attached"
run_ctl stop >/dev/null 2>&1
FAKE_AP=""

# 19i. The watchdog must follow hotspot changes (dae binds LAN only at startup)
#      and keep clearing the filters netd re-installs.
assert_contains "$CTL_SRC" "LAN changed (" "lan: watchdog restarts on a hotspot change"
assert_contains "$CTL_SRC" 'LAN_CANDIDATE_COUNT' "lan: watchdog debounces the LAN change"
assert_contains "$CTL_SRC" "prepare_lan_hooks" "lan: offload filters are re-cleared periodically"

# ==========================================================================
if [ "$FAILED" -eq 0 ]; then
  echo "ALL TESTS PASSED"
else
  echo "SOME TESTS FAILED"
fi
exit "$FAILED"
