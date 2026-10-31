# DaePilot

DaePilot 是 [dae](https://github.com/daeuniverse/dae) eBPF 透明代理的 Android 原生图形管理器。它由一个 APK 和一个配套 Magisk 模块组成：App 负责订阅、节点、分流规则与状态展示，模块负责在 `/data/adb/daepilot` 下以 chroot + watchdog 的方式运行 dae，因此 App 被杀死或从最近任务划掉都不会中断代理。

- 目标设备：已 root 的 arm64 Android（开发与验证目标为小米 25102RKBEC / Android 17 / kernel 6.12）
- 数据面：dae v2.1.1 静态二进制
- 不内置任何机场、订阅或节点；除用户手动上传日志外，不上传任何数据

## 功能

- 一键启动 / 停止 / 重启 / 热重载 dae，前台服务只展示状态，不持有 daemon
- 订阅导入：分享链接列表、base64 链接列表、Clash / Clash.Meta YAML、sing-box JSON
- 订阅自动更新（WorkManager，支持机场返回的 `profile-update-interval`）
- 节点列表、搜索、收藏、TCP 延迟测试；HY2 / TUIC 等 QUIC 节点使用 ICMP 延迟并在界面标注
- 域名连通性测试：首页「连通」检查 Google / YouTube / GitHub / Telegram / 百度，显示各站点的 HTTP 状态、耗时和失败原因，并读取代理出口 IP / 地区，区分“节点没有国际出口”和“本地代理链路异常”
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
- **DNS 交接**：App 把当前网络的 `LinkProperties.dnsServers` 写入 `run/dns.servers`，`daepilotctl` 再写入 rootfs 的 `/etc/resolv.conf`，供 dae 内 Go resolver 解析节点域名；配置里不再写死 `bootstrap_resolver`。解析器优先使用当前 WAN 的实况 DNS：部分运营商丢弃 `119.29.29.29` 等公共 DNS，只有运营商自己的 DNS 可达，因此内置的国内上游会在落配置时自动替换为当前 WAN 的 DNS。
- **蜂窝 DNS 回退**：在部分移动网络上，dae 的 DNS 劫持会捕获它自己发往公网 DNS 的查询，导致运营商 DNS 直连超时（原始 UDP 查询正常）。`daepilotctl` 在 WAN 为 `rmnet*` 且存在代理节点时，把 `qname(geosite:cn)` 的解析改走代理的 `googledns`；Wi-Fi 仍使用路由器/运营商 DNS 直连。诊断弹窗会显示当前 WAN、IP、DNS 列表、DNS 模式（直连/国内经代理）、节点与最近错误。
- **IPv4 节点 AAAA 过滤**：节点没有 IPv6 出口时，Chrome / X 等应用优先使用 AAAA，代理拨号 IPv6 会 `network is unreachable`。存在代理节点时，`daepilotctl` 在 DNS request 中拒绝非国内域名的 AAAA（国内 IPv6 保留），应用自动回退 IPv4；诊断弹窗显示 IPv6 模式。
- **海外 DNS 走 DoH**：默认 `https://1.1.1.1/dns-query`，经节点出站，避免运营商 DNS 污染和节点丢弃 UDP 53；旧配置里的 `tcp+udp://8.8.8.8:53` 会在 App 启动时自动迁移，自定义上游保持不动。可在「分流 → DNS」改回或换成自己的解析器。连通性测试改为串行探测，避免慢节点被五个并发 TLS 握手压垮后误报。
- **Resolver 清洗**：Android `dumpsys` 输出的 DNS 形如 `/10.0.2.3`，写入 `resolv.conf` 前会去掉 `/`、方括号和 zone id；否则整份文件非法，dae 的 Go resolver 会回落到 `[::1]:53`，网络检查/节点解析直接失败。
- **Wi-Fi / 蜂窝双 WAN**：`wan_interface: auto` 由 `daepilotctl` 从 Android policy routing 解析当前默认接口（`wlan0`、`rmnet_dataN` 等），watchdog 检测到接口变化后自动重启 dae，避免 Wi-Fi/移动数据切换后仍绑定旧接口。模拟器上已验证 Wi-Fi → 移动数据切换后自动 `Bind to WAN: eth0` 并继续代理。
- **rmnet 兼容**：模块自带的 dae payload 使用 L3 WAN egress hook，适配 Android rmnet 的 raw-IP；官方 L2 构建在 rmnet 上抓不到本地流量，因此不要替换移动网络场景下的 payload。
- **节点地址固定**：启动/应用配置前用 Android 系统 resolver 解析代理组节点域名，写入 chroot 的 `/etc/hosts`，并把配置里的节点 URL 直接替换为解析出的 IPv4（同时把该域名的 `must_direct` 规则改为 IP），因为该 dae 构建的 bootstrap resolver 不读取 `/etc/hosts`；重写后恢复 `0600`，否则 dae 会拒绝权限过宽的配置。热重载时保留已解析结果，并为新节点补做解析。
- **进程判定**：`status` / watchdog 不再只看 `kill -0`：还要排除 zombie（读 `/proc/<pid>/stat` 状态位）并校验 cmdline 是本次 chroot 的 `dae run -c /config/config.dae`，避免残留 PID 被误判为运行中而阻止重启。
- **watchdog**：`daepilotctl watch` 在 `enabled` 存在时循环检查 PID，异常退出自动重启。进程发现使用单次 `ps -A -o PID,ARGS` 扫描，替代逐进程 fork（toybox grep 不支持 `-a`，会跳过 NUL 分隔的 cmdline），蜂窝/Wi-Fi 切换后的 daemon 重启从约 60 秒降到 7–15 秒。
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

覆盖分享链接解析、三种订阅格式、配置渲染（分组/策略/DNS/自环绕过）、规则校验与内核版本解析，以及域名探测的结果分类和 Cloudflare 出口信息解析。

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
- 「连通」测试若显示百度可用、Google / YouTube 不可达，且出口地区为 CN，通常表示当前节点没有可用的国际出口（节点隧道本身可能是通的）；请更换节点，这不是 App 的本地代理链路故障。测试串行执行，但低带宽或限速节点仍可能让单次请求超时，重试或换节点即可。
- Clash / sing-box 转换覆盖常用字段；SSR、WireGuard、Snell 等类型会跳过并在导入结果中给出警告。
- 订阅若使用明文 HTTP，App 已允许 cleartext；仍建议优先使用 HTTPS。
- `dial_mode` 默认 `ip`：由手机侧解析后把目标 IP 交给节点，避开节点侧二次解析。部分节点的上游 DNS 会把域名解析到绕路地址，连接会卡住数十秒（同一节点实测 `domain` 1.3 KB/s、`ip` 2.5 MB/s）。分流仍按嗅探到的域名匹配；若确认节点侧解析更优，可改回 `domain`。
- hysteria2 / TUIC 走 QUIC，蜂窝链路丢包会同时拖慢所有 QUIC 客户端。实测 15% 丢包时独立 hysteria 客户端与 dae 一起掉到几十 KB/s，属于链路问题而非 dae。
- `DAE_ALLOW_TCP_SOCKMAP=1` 的 TCP relay eBPF offload 需要支持 inner accounting 的 kprobe ABI；部分内核会以 `attach tcp_offload_sent_account` 失败并回落到用户态转发。实测直连吞吐约 980 KB/s，未成为瓶颈。

## 回归测试覆盖

`sh tools/test-daepilotctl.sh` 目前覆盖：Wi-Fi / 蜂窝 rmnet policy routing、VPN tun/dummy 排除、CRLF 配置、`dumpsys` 的 `/IP` 清洗、无 IPv6 默认路由时过滤 IPv6 DNS、运营商 DNS 注入（含移动网络）、自定义上游保留、节点 URL IP 固定、蜂窝国内 DNS 经代理回退、非国内 AAAA 过滤、热重载换节点重启、geo 配置损坏回滚、私有 mount namespace、zombie watchdog、节点 hosts pin。真机另验证了蜂窝/Wi-Fi 双向切换、Google/YouTube 代理、节点切换与 5 轮启停后 `/dev/binderfs` 完好。

## 许可

本项目代码以仓库声明为准。模块内分发的 dae 二进制来自 [daeuniverse/dae](https://github.com/daeuniverse/dae)，采用 AGPL-3.0；再分发时请同时提供对应源代码与许可文本。
