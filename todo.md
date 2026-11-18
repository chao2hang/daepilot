# DaePilot 优化 TODO

来源：2026-11-18 真机日志分析（dae.log / watchdog.log / dumpsys wifi）。
状态：代码修复与脚本回归测试已完成（2026-11-18）。部署需等真机重新连接。

## P0 · 热点绑定失败后的降级要持久

- [x] 记录失败的 LAN 接口集合与时间（`run/lan.failed`，10 分钟冷却）
- [x] `normalize_lan_config` / `stage_config` 在冷却期内保持 `lan_interface` 被移除，避免被 app 的 apply/reload 重新加回
- [x] 接口集合变化或冷却结束（10 分钟）后允许重试；绑定成功后清除失败标记
- [x] 验证：模拟 `wlan2` 存在但 TCX attach 失败时，不再出现连续 `FATAL`；冷却期内重启直接跳过热点绑定（`tools/test-daepilotctl.sh`）

## P0 · 冷启动 DNS 雪崩

- [x] 启动路径内增加一次 3 秒封顶的同步预热（成功即返回）
- [x] 预热失败继续交给现有异步重试，不阻塞启动
- [x] 验证：预热成功时仅探测 1 次；失败时 1 次内联 + 2 次后台重试（脚本回归）

## P1 · Wi-Fi 抖动导致的重启风暴

- [x] WAN debounce 改为非对称：绑 Wi-Fi、Wi-Fi 仍开启且候选是蜂窝时等待 4 拍（约 60 秒）；反向切回 Wi-Fi 仍为 2 拍
- [x] Wi-Fi 关闭时保持 2 拍，避免真实断网久等
- [x] watchdog 重启前复查 `enabled`，避免用户停止后又被拉起
- [ ] 部署后从日志确认：短暂 Wi-Fi 重连不再触发 `wlan0 -> rmnet_dataN` 重启

## P1 · 节点证书异常要可诊断

- [x] 从日志识别 `tls: failed to verify certificate` / `x509:` / `unknown authority`，计入“节点证书校验失败”
- [x] 首页告警与诊断页展示该计数，不自动放宽 DoH 校验
- [x] 验证：解析器单元测试覆盖证书错误计数与上游收集

## P1 · IPv6 重试日志/告警降噪

- [x] `LogAlertParser` 将 IPv6 目的地址的 `pure SYN refused` 单独计数
- [x] 不再计入 `datapathAnomalies` / `total`，首页以中性色提示“IPv6 回退重试”
- [x] 补充/更新单元测试（IPv4 异常与 IPv6 重试分开断言）

## P2 · DNS 只使用活动网络的解析器

- [x] `activeDnsServers()` 优先只写活动网络 DNS
- [x] 活动网络没有 DNS 时才回退到其他网络
- [x] 避免 Wi-Fi 下混入不可达的蜂窝 DNS

## P2 · 诊断能力

- [x] dae 恢复逐行时间戳（移除 `--disable-timestamp`），chroot 内生成最小 TZif 使日志为本地时间
- [x] `status` 增加 `restarts` 与 `last_restart`
- [x] App 状态模型与诊断页展示重启次数/最近原因
- [x] 确认 App 的 apply/reconcile 与 watchdog 的启动不重复拉起 daemon：统一走 `ctl.lock`，启动前复查 `is_running`

## 收尾

- [x] `sh -n magisk/payload/bin/daepilotctl` 通过
- [x] `tools/test-daepilotctl.sh` 全部通过（含热点冷却、预热、LAN 降级回归）
- [x] `tools/build-module.sh` 重新生成模块 zip（17 MB，已嵌入 APK assets）
- [x] 更新本文件全部勾选
- [x] 停止操作，等待真机连接后再部署

## 部署后验证（等真机）

- [x] 真机部署 `daepilotctl`（备份 `/data/adb/daepilot/bin/daepilotctl.bak`），启动正常，`status` 含 `restarts` / `last_restart`
- [x] 时间戳为本地时间，`__warmup` 正常，最后一次启动后无新增 FATAL
- [ ] 安装新模块（含重打包 zip）与新 APK；APK 需在有 JDK/SDK 的环境构建
- [ ] 检查 `watchdog.log` 重启次数与 Wi-Fi 抖动期间的 WAN 切换（需 Wi-Fi/热点实际使用）
- [ ] 检查首页告警计数与诊断页重启原因/证书提示
