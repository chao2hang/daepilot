# DaePilot（翼航）

DaePilot 是 [dae](https://github.com/daeuniverse/dae) eBPF 透明代理的 Android 原生图形管理器。它由一个 APK 和一个配套 Magisk 模块组成：App 负责订阅、节点、分流规则与状态展示，模块负责在 `/data/adb/daepilot` 下以 chroot + watchdog 的方式运行 dae，因此 App 被杀死或从最近任务划掉都不会中断代理。

- 目标设备：已 root 的 arm64 Android（开发与验证目标为小米 25102RKBEC / Android 17 / kernel 6.12）
- 数据面：dae v2.1.1 静态二进制
- 不内置任何机场、订阅或节点；除用户手动上传日志外，不上传任何数据

## 功能

- 一键启动 / 停止 / 重启 / 热重载 dae，前台服务只展示状态，不持有 daemon
- 订阅导入：分享链接列表、base64 链接列表、Clash / Clash.Meta YAML、sing-box JSON
- 订阅自动更新（WorkManager，支持机场返回的 `profile-update-interval`）
- 节点列表、搜索、收藏、TCP 延迟测试；HY2 / TUIC 等 QUIC 节点使用 ICMP 延迟并在界面标注
- 分流模板：绕过大陆 / 全局代理 / 白名单 / 仅直连
- 自定义分流规则（`domain` / `dip` / `pname` / `dport` …）与自定义出口分组
- DNS 上游与分流策略编辑
- 实时日志查看、级别过滤、搜索、导出，以及手动上传诊断日志
- 崩溃自动恢复：模块 watchdog 每 15 秒检查一次，daemon 退出后自动拉起
- 开机自启（App 开关 + 模块 `service.sh`，不依赖 App 启动）
- 兼容性自检：内核版本、BTF、cgroup2、BPF JIT、可用内存、root、模块与冲突进程

## 架构

```
App (Kotlin / Compose / Room / DataStore)
  │  libsu root shell
  ▼
/data/adb/daepilot/bin/daepilotctl        ← 唯一的数据面控制入口
  ├─ config/config.dae                     ← App 渲染后应用（600）
  ├─ rootfs/                               ← chroot 根
  │    ├─ bin/dae                          ← 静态二进制
  │    ├─ config/config.dae
  │    ├─ config/geosite.dat
  │    ├─ config/geoip.dat
  │    └─ proc, dev, sys/{class,devices,kernel/btf,fs/{cgroup,bpf}}, run(tmpfs)
  ├─ logs/dae.log, logs/watchdog.log
  └─ enabled / boot_enabled / staging / run

Magisk module
  ├─ service.sh    → 开机后按 boot_enabled 启动
  ├─ action.sh     → Magisk 动作按钮：启停切换
  ├─ customize.sh  → 安装 payload、创建 rootfs、基础检查
  └─ uninstall.sh  → 停止 daemon 并清理 /data/adb/daepilot
```

关键设计：

- **App 与 daemon 解耦**：`ControlService` 只轮询 `daepilotctl status` 并更新通知，杀掉 App 不影响 dae。
- **chroot 隔离**：dae 只看到 rootfs 内的 `/proc`、`/dev`、必要的 sysfs 子树、cgroup2 与 bpffs；卸载时只处理 rootfs 下自己的挂载点。
- **私有 mount namespace**：用 `busybox unshare -m` 启动 dae，并读取 `/proc/self/mountinfo` 确认根挂载已不在 `shared:` peer group 中。`mount -o rprivate` 只做兜底：busybox 会把单个目录参数当成 fstab 查询，toybox 则接受未知 `-o` 参数但不生效，所以不能用退出码判断成功。这样 `--rbind /dev` 之后的卸载不会传播到宿主，避免宿主 `/dev/binderfs` 被拆掉。
- **内存锁**：启动前把 `RLIMIT_MEMLOCK` 提到 unlimited。Android 默认上限 64 KiB，dae 加载 geoip/geosite 时 LPM map 更新会报 `newLpmMap: batch update: cannot allocate memory` 并直接退出。
- **DNS 交接**：App 把当前网络的 `LinkProperties.dnsServers` 写入 `run/dns.servers`，`daepilotctl` 再写入 rootfs 的 `/etc/resolv.conf`，供 dae 内 Go resolver 解析节点域名；配置里不再写死 `bootstrap_resolver`。
- **进程判定**：`status` / watchdog 不再只看 `kill -0`：还要排除 zombie（读 `/proc/<pid>/stat` 状态位）并校验 cmdline 是本次 chroot 的 `dae run -c /config/config.dae`，避免残留 PID 被误判为运行中而阻止重启。
- **watchdog**：`daepilotctl watch` 在 `enabled` 存在时循环检查 PID，异常退出自动重启。
- **配置安全**：渲染 → 使用 `.dae` 后缀的临时文件执行 `dae validate` → 备份 → 替换 → 热重载；热重载失败自动回滚并重启。校验失败时保留 dae 原始诊断到本地日志。
- **dae 出站路由**：配置 `so_mark_from_dae: 0x100`，启动时同时为 IPv4 与 IPv6 添加 `fwmark 0x100/0x1ffff` 规则（priority 9900），停止时删除；路由选择跳过 `dummy0`、`lo` 和 blackhole/unreachable 等伪默认路由。IPv6 优先选默认路由落在 WAN 接口所在的那张表——Android 会同时保留其它链路（如 `rmnet_data2`）的活动 v6 表，选错会从错误的链路发出。
- **节点自环规避**：自动为每个节点服务器生成 `dip/domain + dport -> must_direct` 规则，避免 tproxy 捕获 dae 自己的出站连接。
- **名称改写**：dae 的 `filter: name(...)` 匹配链接里的显示名而不是 `node {}` 的键，因此渲染时会先把节点链接的 fragment / vmess `ps` 改写为唯一 ASCII 标识，再在分组中引用。

## 构建 App

环境：JDK 17+、Android SDK 35、Gradle 8.9（无需 wrapper）。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

模块 zip 已内置于 `app/src/main/assets/daepilot-module.zip`。App 请求并取得 root 权限后会自动调用 Magisk / KernelSU / APatch 安装器刷入，也可在首页或「设置」中手动触发。若修改了模块脚本或 payload，重新生成：

```bash
./tools/build-module.sh        # dist/daepilot-module.zip + 自动复制到 assets
```

单元测试（纯 JVM，不需要设备）：

```bash
gradle :app:testDebugUnitTest
```

覆盖分享链接解析、三种订阅格式、配置渲染（分组/策略/DNS/自环绕过）、规则校验与内核版本解析。

## 刷新 dae payload

仓库内的 `magisk/payload` 已包含 dae v2.1.1 arm64 二进制与地理数据。需要升级时，从 [dae Releases](https://github.com/daeuniverse/dae/releases) 下载 `dae-linux-arm64.tar.xz`，解压后：

```bash
cp usr/bin/dae                         magisk/payload/bin/dae
cp usr/share/dae/geosite.dat           magisk/payload/geo/geosite.dat
cp usr/share/dae/geoip.dat             magisk/payload/geo/geoip.dat
chmod 755 magisk/payload/bin/dae
./tools/build-module.sh
```

dae 在 chroot 中以配置所在目录（`/config`）为基准查找 `geosite.dat` / `geoip.dat`，模块安装时会复制到 `rootfs/config/`，`daepilotctl` 启动前会校验并按大小修复。

## 安装与使用

1. 安装并打开 DaePilot。首次启动会自动发起 root 授权请求；如果弹窗被忽略，点击首页的「请求 root 权限」再次发起。也可以直接在 Magisk / KernelSU / APatch 中刷入 `dist/daepilot-module.zip`。
2. 授权成功后 App 会自动安装内置模块；安装完成建议重启一次，让模块服务完整加载。
3. 若系统未弹出授权框，请检查 root 管理器中是否已拒绝过 DaePilot，清除拒绝记录后重新点击「请求 root 权限」。
4. 在「设置」中查看兼容性自检；确认内核 ≥ 5.15、存在 `/sys/kernel/btf/vmlinux` 与 cgroup2。
5. 添加订阅，等待解析，选择节点。
6. 在「分流」中选择模板，按需编辑规则与 DNS。
7. 回到首页启动代理。TProxy 端口默认 `12345`，修改后需重新应用配置。

Magisk 管理器中的模块「动作」按钮可直接启停 dae。

## 数据与隐私

- 订阅、节点、规则保存在 App 私有目录（Room / DataStore），不参与云备份（`allowBackup=false`）。
- 渲染后的配置位于 `/data/adb/daepilot/config/config.dae`，权限 `600`，仅 root 可读。
- 模块不包含统计、遥测或账号体系；日志默认只写入设备本地。
- 日志上传仅在用户主动点击时发生，上传内容为运行状态、设备信息、最近操作记录、脱敏后的配置与最近日志；节点链接中的密码、UUID、token 等凭据会在本地替换为 `<redacted>`，但日志仍可能包含节点地址与访问域名。
- 上传的日志保存在日志接收端的私有目录（权限 600），不通过 HTTP 对外提供下载。

## 已知限制

- 仅支持 arm64 + root；非 root 设备与 iOS 不在范围内。
- 建议内核 ≥ 5.15 且启用 BTF。部分厂商内核缺少 cgroup2 或 BPF 权限，需自行调整 SELinux；`customize.sh` 会尝试一条非致命的 `magiskpolicy` 规则。
- hysteria2 / TUIC 基于 QUIC，TCP 延迟测试无法反映真实可用性。
- Clash / sing-box 转换覆盖常用字段；SSR、WireGuard、Snell 等类型会跳过并在导入结果中给出警告。
- 订阅若使用明文 HTTP，App 已允许 cleartext；仍建议优先使用 HTTPS。
- `dial_mode` 默认 `ip`：由手机侧解析后把目标 IP 交给节点，避开节点侧二次解析。部分节点的上游 DNS 会把域名解析到绕路地址，连接会卡住数十秒（同一节点实测 `domain` 1.3 KB/s、`ip` 2.5 MB/s）。分流仍按嗅探到的域名匹配；若确认节点侧解析更优，可改回 `domain`。
- hysteria2 / TUIC 走 QUIC，蜂窝链路丢包会同时拖慢所有 QUIC 客户端。实测 15% 丢包时独立 hysteria 客户端与 dae 一起掉到几十 KB/s，属于链路问题而非 dae。
- `DAE_ALLOW_TCP_SOCKMAP=1` 的 TCP relay eBPF offload 需要支持 inner accounting 的 kprobe ABI；部分内核会以 `attach tcp_offload_sent_account` 失败并回落到用户态转发。实测直连吞吐约 980 KB/s，未成为瓶颈。

## 许可

本项目代码以仓库声明为准。模块内分发的 dae 二进制来自 [daeuniverse/dae](https://github.com/daeuniverse/dae)，采用 AGPL-3.0；再分发时请同时提供对应源代码与许可文本。
