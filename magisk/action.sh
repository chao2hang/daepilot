#!/system/bin/sh
# Magisk action button: toggle the data plane and print a short status.

BASE=/data/adb/daepilot
CTL="$BASE/bin/daepilotctl"

if [ ! -x "$CTL" ]; then
  echo "DaePilot 控制脚本缺失"
  exit 1
fi

if "$CTL" status | grep -q '^running=1'; then
  echo "正在停止 dae…"
  "$CTL" stop
else
  echo "正在启动 dae…"
  "$CTL" start
fi
echo
"$CTL" status
