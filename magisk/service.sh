#!/system/bin/sh
# Magisk late_start service. The data plane is independent from the app:
# after boot this script starts dae (and its watchdog) from the last applied
# configuration, even if DaePilot has never been opened since boot.

BASE=/data/adb/daepilot

(
  until [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; do
    sleep 5
  done
  until [ "$(getprop init.svc.bootanim 2>/dev/null)" = "stopped" ]; do
    sleep 3
  done

  # Give the network a chance to come up; dae can still start without it, but
  # resolving node hostnames is faster once a default route exists.
  i=0
  while [ "$i" -lt 12 ]; do
    if ip route 2>/dev/null | grep -q default; then break; fi
    sleep 5
    i=$((i + 1))
  done

  if [ -x "$BASE/bin/daepilotctl" ] && [ -f "$BASE/boot_enabled" ]; then
    "$BASE/bin/daepilotctl" start >> "$BASE/logs/boot.log" 2>&1
  fi
) &
