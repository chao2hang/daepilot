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

  # Do not probe the main-table default route here: Android keeps it in a
  # policy table, so that check usually waited the full 60 s before starting.
  # Wait for a global IPv4 instead (the cellular interface may appear after
  # bootanim); then ctl refresh_wan_config can bind the right rmnet_dataN.
  i=0
  while [ "$i" -lt 12 ]; do
    if ip -4 addr show scope global 2>/dev/null | grep -q 'inet '; then break; fi
    sleep 5
    i=$((i + 1))
  done

  if [ -x "$BASE/bin/daepilotctl" ] && [ -f "$BASE/boot_enabled" ]; then
    attempt=1
    while [ "$attempt" -le 3 ]; do
      printf '[%s] boot start attempt %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" "$attempt" >> "$BASE/logs/boot.log" 2>&1
      "$BASE/bin/daepilotctl" start >> "$BASE/logs/boot.log" 2>&1
      if "$BASE/bin/daepilotctl" status 2>/dev/null | grep -q '^running=1$'; then
        # ctl start only re-arms the watchdog when the pre-stop enabled flag
        # is already present; after a clean stop it is not. Start it here so a
        # crashed daemon is still resurrected after a cold boot.
        if ! "$BASE/bin/daepilotctl" status 2>/dev/null | grep -q '^watchdog=1$'; then
          nohup "$BASE/bin/daepilotctl" watch >> "$BASE/logs/watchdog.log" 2>&1 &
        fi
        printf '[%s] boot start ok\n' \
          "$(date '+%Y-%m-%d %H:%M:%S')" >> "$BASE/logs/boot.log" 2>&1
        break
      fi
      sleep 10
      attempt=$((attempt + 1))
    done
  fi
) &
