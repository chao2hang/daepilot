#!/system/bin/sh

BASE=/data/adb/daepilot
CTL="$BASE/bin/daepilotctl"

if [ -x "$CTL" ]; then
  "$CTL" stop >/dev/null 2>&1
else
  pid="$(cat "$BASE/run/watchdog.pid" 2>/dev/null | tr -dc '0-9')"
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
fi

rm -rf "$BASE"
echo "DaePilot 数据面已停止并移除（App 内保存的订阅仍在应用私有目录）"
