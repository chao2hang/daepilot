#!/system/bin/sh

SKIPUNZIP=1
SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

BASE=/data/adb/daepilot

ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print " DaePilot 翼航 · dae eBPF 数据面"
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ "$BOOTMODE" != true ]; then
  abort "! 请在 Magisk / KernelSU / APatch 管理器中安装，不支持 recovery 刷入"
fi

abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
case "$abi" in
  arm64*) : ;;
  *) abort "! 仅支持 arm64 设备，当前 ABI：$abi" ;;
esac

unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2

if [ ! -f "$MODPATH/payload/bin/dae" ]; then
  abort "! 模块包内缺少 dae 二进制（payload/bin/dae）"
fi
for geo in geosite.dat geoip.dat; do
  if [ ! -f "$MODPATH/payload/geo/$geo" ]; then
    abort "! 模块包内缺少地理数据（payload/geo/$geo）"
  fi
done

kernel="$(uname -r 2>/dev/null)"
major="$(echo "$kernel" | cut -d. -f1)"
minor="$(echo "$kernel" | cut -d. -f2)"
if [ -n "$major" ] && [ "$major" -lt 5 ]; then
  ui_print "! 当前内核 $kernel 低于建议的 5.15，eBPF 功能可能不可用"
elif [ "$major" -eq 5 ] && [ "$minor" -lt 15 ]; then
  ui_print "! 当前内核 $kernel 低于建议的 5.15，eBPF 功能可能不可用"
fi
if [ ! -e /sys/kernel/btf/vmlinux ]; then
  ui_print "! 未检测到 BTF（/sys/kernel/btf/vmlinux），dae 可能无法加载 eBPF"
fi
if ! grep -q cgroup2 /proc/mounts 2>/dev/null; then
  ui_print "! 未检测到 cgroup2，dae 的 cgroup 钩子可能不可用"
fi

ui_print "— 安装数据面到 $BASE"
mkdir -p "$BASE/bin" "$BASE/config" "$BASE/logs" "$BASE/run" "$BASE/staging" "$BASE/geo"
mkdir -p "$BASE/rootfs/bin" "$BASE/rootfs/config" "$BASE/rootfs/run" \
  "$BASE/rootfs/proc" "$BASE/rootfs/dev" "$BASE/rootfs/tmp" "$BASE/rootfs/var" \
  "$BASE/rootfs/root" "$BASE/rootfs/etc/ssl/certs" \
  "$BASE/rootfs/sys/class" "$BASE/rootfs/sys/devices" \
  "$BASE/rootfs/sys/fs/cgroup" "$BASE/rootfs/sys/fs/bpf" "$BASE/rootfs/sys/kernel/btf"
if [ ! -L "$BASE/rootfs/var/run" ]; then
  rm -rf "$BASE/rootfs/var/run"
  ln -s ../run "$BASE/rootfs/var/run"
fi

cp -f "$MODPATH/payload/bin/dae" "$BASE/bin/dae"
cp -f "$MODPATH/payload/bin/daepilotctl" "$BASE/bin/daepilotctl"
cp -f "$MODPATH/payload/bin/VERSION" "$BASE/bin/VERSION" 2>/dev/null || echo "unknown" > "$BASE/bin/VERSION"
cp -f "$MODPATH/payload/geo/geosite.dat" "$BASE/geo/geosite.dat"
cp -f "$MODPATH/payload/geo/geoip.dat" "$BASE/geo/geoip.dat"
# dae resolves geo assets relative to the config file, i.e. /config inside
# the chroot, so a copy must live there as well.
cp -f "$BASE/geo/geosite.dat" "$BASE/rootfs/config/geosite.dat"
cp -f "$BASE/geo/geoip.dat" "$BASE/rootfs/config/geoip.dat"
chmod 755 "$BASE/bin/dae" "$BASE/bin/daepilotctl"
chmod 644 "$BASE/geo/geosite.dat" "$BASE/geo/geoip.dat" \
  "$BASE/rootfs/config/geosite.dat" "$BASE/rootfs/config/geoip.dat"
chmod 700 "$BASE"
chmod 755 "$BASE/rootfs" "$BASE/rootfs/bin" "$BASE/rootfs/run"
chmod 1777 "$BASE/rootfs/tmp"
chmod 700 "$BASE/config"
[ -f "$BASE/config/config.dae" ] && chmod 600 "$BASE/config/config.dae"

# The module itself only needs the small control metadata after payload copy.
rm -rf "$MODPATH/payload"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# Optional live policy hook for SELinux-enforcing devices where the magisk
# domain is not permitted to call bpf(). Most devices (including the target
# Xiaomi/HyperOS unit) need no change; failure here is intentionally non-fatal.
if command -v magiskpolicy >/dev/null 2>&1; then
  magiskpolicy --live "allow magiskdomain self:bpf { map_create map_read map_write prog_load prog_run }" 2>/dev/null || true
fi

ui_print "— 数据面已安装；开机自启由 App 中的开关控制"
ui_print "— 重启设备后打开 DaePilot 应用完成配置"
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
