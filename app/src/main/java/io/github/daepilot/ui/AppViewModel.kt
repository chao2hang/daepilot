package io.github.daepilot.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.daepilot.BuildConfig
import io.github.daepilot.DaePilotApp
import io.github.daepilot.core.model.AppSettings
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.core.config.ConfigRedactor
import io.github.daepilot.data.ImportResult
import io.github.daepilot.core.root.ActionResult
import io.github.daepilot.core.root.RootRequestResult
import io.github.daepilot.data.net.LogUploadRequest
import io.github.daepilot.data.net.LogUploader
import io.github.daepilot.service.ControlService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LogLine(val raw: String, val level: String)

data class RootUiState(
    val requesting: Boolean = false,
    val installingModule: Boolean = false,
    val granted: Boolean? = null,
    val message: String? = null,
)

data class LogUploadState(
    val uploading: Boolean = false,
    val lastId: String? = null,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as DaePilotApp).container
    private val repo = container.repository
    private val daemon = container.daemon

    val snapshot = repo.snapshot

    private val _status = MutableStateFlow(DaemonStatus())
    val status: StateFlow<DaemonStatus> = _status.asStateFlow()

    private val _compatibility = MutableStateFlow<CompatibilityReport?>(null)
    val compatibility: StateFlow<CompatibilityReport?> = _compatibility.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _rootState = MutableStateFlow(RootUiState())
    val rootState: StateFlow<RootUiState> = _rootState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _logUpload = MutableStateFlow(LogUploadState())
    val logUpload: StateFlow<LogUploadState> = _logUpload.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var logJob: Job? = null
    private var statusJob: Job? = null
    private val recentEvents = ArrayDeque<String>()
    private val eventTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        recordEvent("应用启动")
        statusJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(12_000)
            }
        }
    }

    fun requestRoot() = viewModelScope.launch {
        requestRootInternal(installModule = true)
    }

    private suspend fun requestRootInternal(installModule: Boolean): Boolean {
        if (_rootState.value.requesting) {
            notify("正在等待 root 授权结果…")
            return false
        }
        _rootState.value = _rootState.value.copy(requesting = true, message = null)
        val result = runCatching { container.root.requestRoot() }
            .getOrElse { RootRequestResult.ERROR }
        _rootState.value = _rootState.value.copy(requesting = false)

        val message = when (result) {
            RootRequestResult.GRANTED -> "已获得 root 权限"
            RootRequestResult.DENIED ->
                "未获得 root 权限。请在系统授权框中允许 DaePilot；若之前拒绝过，" +
                    "请到 Magisk / KernelSU / APatch 中清除本应用的拒绝记录后重试。"
            RootRequestResult.TIMEOUT -> "等待 root 授权超时，请重新点击「请求 root 权限」。"
            RootRequestResult.ERROR -> "无法发起 root 请求，请确认已安装 Magisk / KernelSU / APatch。"
        }
        _rootState.value = _rootState.value.copy(
            granted = result == RootRequestResult.GRANTED,
            message = message,
        )
        notify(message)

        if (result != RootRequestResult.GRANTED) {
            refreshStatus()
            return false
        }
        if (installModule) {
            val installed = runCatching { daemon.isModuleInstalled() }.getOrDefault(false)
            if (!installed) {
                _rootState.value = _rootState.value.copy(installingModule = true)
                val installation = runCatching { daemon.installBundledModule() }
                    .getOrElse { ActionResult(false, it.message ?: "模块安装失败") }
                _rootState.value = _rootState.value.copy(installingModule = false)
                notify(installation.message)
            }
        }
        refreshStatus()
        return true
    }

    fun ensureForegroundService() {
        if (!snapshot.value.settings.keepServiceRunning) return
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), ControlService::class.java),
            )
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _status.value = runCatching { daemon.status() }.getOrElse {
                DaemonStatus(lastError = it.message ?: "状态读取失败")
            }
        }
    }

    fun diagnose() = viewModelScope.launch {
        _busy.value = true
        _compatibility.value = runCatching { daemon.diagnose() }.getOrElse {
            CompatibilityReport(rootAvailable = false, note = it.message ?: "诊断失败")
        }
        _busy.value = false
    }

    fun toggleProxy(enable: Boolean) = viewModelScope.launch {
        _busy.value = true
        try {
            if (enable) {
                if (!container.root.hasRootShell() &&
                    !requestRootInternal(installModule = true)
                ) {
                    return@launch
                }
                val spec = repo.renderSpec(snapshot.value)
                if (spec.nodes.isEmpty() && spec.template != RoutingTemplate.DIRECT) {
                    notify("请先导入并启用至少一个节点")
                    return@launch
                }
                val rendered = repo.render(snapshot.value)
                val applied = daemon.applyConfig(rendered, reload = _status.value.running)
                if (!applied.ok) {
                    notify("无法应用配置：${applied.message}")
                    return@launch
                }
                val started = daemon.start()
                if (!started.ok) {
                    notify("启动失败：${started.message}")
                } else if (snapshot.value.settings.bootStart) {
                    daemon.enableBootStart()
                }
            } else {
                val stopped = daemon.stop()
                notify(if (stopped.ok) "dae 已停止" else "停止失败：${stopped.message}")
            }
        } finally {
            _busy.value = false
            refreshStatus()
        }
    }

    fun applyConfig() = viewModelScope.launch {
        _busy.value = true
        try {
            val rendered = repo.render(snapshot.value)
            val result = daemon.applyConfig(rendered, reload = _status.value.running)
            notify(if (result.ok) "配置已应用：${result.message}" else "应用失败：${result.message}")
        } finally {
            _busy.value = false
            refreshStatus()
        }
    }

    private suspend fun applyIfRunning() {
        if (!_status.value.running) return
        val rendered = repo.render(snapshot.value)
        val result = daemon.applyConfig(rendered, reload = true)
        if (!result.ok) notify("热更新失败：${result.message}")
    }

    fun previewConfig(): String = repo.render(snapshot.value)

    fun reloadConfig() = viewModelScope.launch {
        _busy.value = true
        val result = daemon.reload()
        notify(if (result.ok) "配置已热重载" else "热重载失败：${result.message}")
        _busy.value = false
        refreshStatus()
    }

    fun addSubscription(name: String, url: String) = viewModelScope.launch {
        _busy.value = true
        val result = repo.addSubscription(name, url)
        notify(describeImport(result))
        _busy.value = false
    }

    fun refreshSubscription(id: String) = viewModelScope.launch {
        _busy.value = true
        val result = repo.refreshSubscription(id, force = true)
        notify(describeImport(result))
        _busy.value = false
        applyIfRunning()
    }

    fun deleteSubscription(id: String) = viewModelScope.launch {
        repo.deleteSubscription(id)
        notify("订阅已删除")
        refreshStatus()
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.setSubscriptionEnabled(id, enabled)
        if (!enabled) applyIfRunning()
    }

    fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.setSubscriptionAutoUpdate(id, enabled)
    }

    fun importLinks(text: String, namePrefix: String?) = viewModelScope.launch {
        _busy.value = true
        val result = repo.importLinks(text, namePrefix)
        notify(describeImport(result))
        _busy.value = false
    }

    fun deleteNode(id: String) = viewModelScope.launch {
        repo.deleteNode(id)
        notify("节点已删除")
        applyIfRunning()
    }

    fun setFavorite(id: String, favorite: Boolean) = viewModelScope.launch {
        repo.setFavorite(id, favorite)
    }

    fun selectNode(id: String?) = viewModelScope.launch {
        repo.selectNode(id)
        applyIfRunning()
    }

    fun testAllNodes() = viewModelScope.launch {
        _busy.value = true
        val results = repo.testAllNodes()
        val ok = results.count { it.latencyMs != null }
        val icmp = results.count { it.method == "icmp" && it.latencyMs != null }
        notify(
            "延迟测试完成：$ok/${results.size} 可用" +
                if (icmp > 0) "，其中 $icmp 个 QUIC 节点使用 ICMP" else "",
        )
        _busy.value = false
    }

    fun testNode(id: String) = viewModelScope.launch {
        val result = repo.testNode(id)
        notify(
            when {
                result?.latencyMs != null && result.method == "icmp" ->
                    "ICMP 延迟 ${result.latencyMs} ms（QUIC 节点）"
                result?.latencyMs != null -> "延迟 ${result.latencyMs} ms"
                result?.error != null -> result.error
                else -> "测试失败"
            },
        )
    }

    fun setTemplate(template: RoutingTemplate) = viewModelScope.launch {
        repo.setTemplate(template)
        applyIfRunning()
    }

    fun saveRule(expression: String, outbound: String, existingId: String? = null) =
        viewModelScope.launch {
            val validation = io.github.daepilot.core.config.DaeConfigValidator.validateRule(expression)
            if (!validation.ok) {
                notify(validation.message)
                return@launch
            }
            repo.upsertRule(
                RoutingRule(
                    id = existingId ?: UUID.randomUUID().toString(),
                    expression = expression.trim(),
                    outbound = outbound,
                    enabled = true,
                ),
            )
            notify("规则已保存")
            applyIfRunning()
        }

    fun deleteRule(id: String) = viewModelScope.launch {
        repo.deleteRule(id)
        notify("规则已删除")
        applyIfRunning()
    }

    fun setRuleEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.setRuleEnabled(id, enabled)
        applyIfRunning()
    }

    fun saveGroup(group: OutboundGroup) = viewModelScope.launch {
        repo.upsertGroup(group)
        notify("分组已保存")
        applyIfRunning()
    }

    fun deleteGroup(id: String) = viewModelScope.launch {
        repo.deleteGroup(id)
        notify("分组已删除")
        applyIfRunning()
    }

    fun saveDns(dns: DnsConfig) = viewModelScope.launch {
        repo.setDns(dns)
        notify("DNS 设置已保存")
        applyIfRunning()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repo.updateSettings(transform)
    }

    fun setBootStart(enabled: Boolean) = viewModelScope.launch {
        repo.updateSettings { it.copy(bootStart = enabled) }
        val result = if (enabled) daemon.enableBootStart() else daemon.disableBootStart()
        notify(
            when {
                !result.ok -> "开机自启设置失败：${result.message}"
                enabled -> "已开启开机自启"
                else -> "已关闭开机自启"
            },
        )
    }

    fun setKeepService(enabled: Boolean) = viewModelScope.launch {
        repo.updateSettings { it.copy(keepServiceRunning = enabled) }
        val intent = Intent(getApplication(), ControlService::class.java)
        if (enabled) {
            runCatching { ContextCompat.startForegroundService(getApplication(), intent) }
        } else {
            getApplication<Application>().stopService(intent)
        }
    }

    fun setBypassPackages(values: List<String>) = viewModelScope.launch {
        repo.updateSettings { it.copy(bypassPackages = values.distinct()) }
        notify("绕过应用已更新")
        applyIfRunning()
    }

    fun setTproxyPort(port: Int) = viewModelScope.launch {
        if (port !in 1..65535) {
            notify("端口必须在 1-65535 之间")
            return@launch
        }
        repo.updateSettings { it.copy(tproxyPort = port) }
        notify("tproxy 端口已更新")
        applyIfRunning()
    }

    fun setLogLevel(level: String) = viewModelScope.launch {
        repo.updateSettings { it.copy(logLevel = level) }
        notify("日志等级已更新")
        applyIfRunning()
    }

    fun setGlobalAutoUpdate(enabled: Boolean) = viewModelScope.launch {
        repo.updateSettings { it.copy(subscriptionAutoUpdate = enabled) }
    }

    fun installModule() = viewModelScope.launch {
        if (_rootState.value.installingModule) return@launch
        if (!container.root.hasRootShell() &&
            !requestRootInternal(installModule = false)
        ) {
            return@launch
        }
        _rootState.value = _rootState.value.copy(installingModule = true)
        val result = runCatching { daemon.installBundledModule() }
            .getOrElse { ActionResult(false, it.message ?: "模块安装失败") }
        _rootState.value = _rootState.value.copy(installingModule = false)
        notify(result.message)
        refreshStatus()
    }

    fun startLogStream() {
        if (logJob?.isActive == true) return
        logJob = viewModelScope.launch {
            while (isActive) {
                val raw = runCatching { daemon.readLog(800) }.getOrDefault("")
                _logs.value = parseLogs(raw)
                delay(2_500)
            }
        }
    }

    fun stopLogStream() {
        logJob?.cancel()
        logJob = null
    }

    fun clearLogs() = viewModelScope.launch {
        daemon.clearLog()
        _logs.value = emptyList()
        notify("日志已清空")
    }

    fun exportLogs(): String = _logs.value.joinToString("\n") { it.raw }

    fun uploadLogs() = viewModelScope.launch {
        if (_logUpload.value.uploading) return@launch
        _logUpload.value = LogUploadState(uploading = true)
        val raw = runCatching { daemon.readLog(2000) }.getOrElse {
            "读取日志失败：${it.message ?: it.javaClass.simpleName}"
        }
        recordEvent("上传运行日志")
        val status = _status.value
        val configText = runCatching {
            ConfigRedactor.redact(repo.render(snapshot.value))
        }.getOrElse {
            "配置渲染失败：${it.message ?: it.javaClass.simpleName}"
        }
        val diagnostics = runCatching {
            daemon.diagnose().let { report ->
                buildString {
                    append("root=${report.rootAvailable} ")
                    append("module=${report.moduleInstalled} ")
                    append("dae=${report.daeBinaryPresent} ")
                    append("kernel=${report.kernelRelease} ")
                    append("btf=${report.btfPresent} ")
                    append("cgroup2=${report.cgroup2Mounted} ")
                    append("bpfJit=${report.bpfJitEnabled} ")
                    append("memMb=${report.memAvailableMb} ")
                    append("conflicts=${report.conflicts.joinToString(",")}")
                }
            }
        }.getOrElse { "diagnose failed: ${it.message ?: it.javaClass.simpleName}" }
        val request = LogUploadRequest(
            app = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            manufacturer = Build.MANUFACTURER,
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            mode = status.mode,
            daeVersion = status.version ?: BuildConfig.DAE_VERSION,
            moduleVersion = BuildConfig.MODULE_VERSION,
            status = buildString {
                append("running=${status.running} ")
                append("pid=${status.pid ?: "-"} ")
                append("configValid=${status.configValid} ")
                append("watchdog=${status.watchdog} ")
                append("bootEnabled=${status.bootEnabled} ")
                append("rssKb=${status.rssKb} ")
                append("wan=${status.wan ?: "-"}/${status.wanIp ?: "-"} ")
                append("node=${status.node ?: "-"} ")
                append("dns=${status.dns ?: "-"} ")
                append("dnsMode=${status.dnsMode ?: "-"} ")
                append("lastError=${status.lastError ?: "-"}")
            },
            note = "manual upload",
            events = synchronized(recentEvents) { recentEvents.joinToString("\n") },
            config = configText,
            diagnostics = diagnostics,
            log = LogUploader.sanitizeLog(raw),
        )
        container.logUploader.upload(request).fold(
            onSuccess = { id ->
                _logUpload.value = LogUploadState(lastId = id)
                notify("日志已上传：$id")
            },
            onFailure = { error ->
                val message = error.message ?: "上传失败"
                _logUpload.value = LogUploadState(error = message)
                notify("日志上传失败：$message")
            },
        )
    }

    fun clearUploadStatus() {
        _logUpload.value = LogUploadState()
    }

    fun startDaemonFromQuickAction() = viewModelScope.launch {
        _busy.value = true
        val result = daemon.start()
        notify(if (result.ok) "dae 已启动" else "启动失败：${result.message}")
        _busy.value = false
        refreshStatus()
    }

    fun stopDaemonFromQuickAction() = viewModelScope.launch {
        _busy.value = true
        val result = daemon.stop()
        notify(if (result.ok) "dae 已停止" else "停止失败：${result.message}")
        _busy.value = false
        refreshStatus()
    }

    private fun notify(message: String) {
        recordEvent(message)
        messageChannel.trySend(message)
    }

    private fun recordEvent(message: String) {
        val line = "${eventTime.format(Date())} ${message.take(500)}"
        synchronized(recentEvents) {
            recentEvents.addLast(line)
            while (recentEvents.size > 80) recentEvents.removeFirst()
        }
    }

    private fun describeImport(result: ImportResult): String {
        if (!result.ok) return result.message
        val warnings = result.warnings.take(2).joinToString("；")
        return result.message + if (warnings.isNotBlank()) "（$warnings）" else ""
    }

    private fun parseLogs(raw: String): List<LogLine> {
        if (raw.isBlank()) return emptyList()
        val levelRegex = Regex("""\blevel=(trace|debug|info|warn|warning|error|fatal)\b""", RegexOption.IGNORE_CASE)
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val match = levelRegex.find(line)
                val levelText = match?.groupValues?.get(1)?.lowercase()
                val level = when (levelText) {
                    "warning", "warn" -> "warn"
                    "trace", "debug", "info", "error", "fatal" -> levelText
                    else -> when {
                        line.contains("ERROR", true) || line.contains("failed", true) -> "error"
                        line.contains("WARN", true) -> "warn"
                        line.contains("DEBUG", true) -> "debug"
                        else -> "info"
                    }
                }
                LogLine(line, level)
            }
            .toList()
    }

    override fun onCleared() {
        logJob?.cancel()
        statusJob?.cancel()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(application) as T
    }
}
