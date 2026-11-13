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
import io.github.daepilot.core.config.ConfigRedactor
import io.github.daepilot.core.config.DaeConfigRenderer
import io.github.daepilot.core.config.SpeedTestPlan
import io.github.daepilot.core.log.LogAlertParser
import io.github.daepilot.core.log.LogAlerts
import io.github.daepilot.core.model.AppSettings
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.network.DomainTargets
import io.github.daepilot.core.network.DomainTestResult
import io.github.daepilot.core.network.DomainTester
import io.github.daepilot.core.network.ExitInfo
import io.github.daepilot.core.network.SpeedTestResult
import io.github.daepilot.core.network.SpeedTester
import io.github.daepilot.core.network.TlsTrustProbe
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.ConnectionPhase
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.ShareLink
import io.github.daepilot.core.model.daeKey
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.data.ImportResult
import io.github.daepilot.data.SubscriptionPreview
import io.github.daepilot.core.root.ActionResult
import io.github.daepilot.core.root.RootRequestResult
import io.github.daepilot.data.net.LogUploadRequest
import io.github.daepilot.data.net.LogUploader
import io.github.daepilot.service.ControlService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LogLine(val raw: String, val level: String)

/** A transient toast; [actionLabel] asks the UI to offer one follow-up action. */
data class UiMessage(val text: String, val actionLabel: String? = null)

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

data class SubscriptionPreviewUiState(
    val running: Boolean = false,
    val url: String = "",
    val preview: SubscriptionPreview? = null,
)

data class SpeedTestState(
    val runningNodeId: String? = null,
    val results: Map<String, SpeedTestResult> = emptyMap(),
)

/** Home-screen probe results keyed by [DomainTarget.host]. */
data class SiteProbeState(
    val results: Map<String, DomainTestResult> = emptyMap(),
    val testing: Set<String> = emptySet(),
    val lastRunAt: Long = 0L,
)

data class BandwidthState(
    val running: Boolean = false,
    val result: SpeedTestResult? = null,
    val testedAt: Long = 0L,
    val proxied: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as DaePilotApp).container
    private val repo = container.repository
    private val daemon = container.daemon

    val snapshot = repo.snapshot

    val status: StateFlow<DaemonStatus> = container.statusUpdates

    private val _compatibility = MutableStateFlow<CompatibilityReport?>(null)
    val compatibility: StateFlow<CompatibilityReport?> = _compatibility.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _rootState = MutableStateFlow(RootUiState())
    val rootState: StateFlow<RootUiState> = _rootState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _logAlerts = MutableStateFlow(LogAlerts())
    val logAlerts: StateFlow<LogAlerts> = _logAlerts.asStateFlow()

    private val _logUpload = MutableStateFlow(LogUploadState())
    val logUpload: StateFlow<LogUploadState> = _logUpload.asStateFlow()

    private val domainTester = DomainTester()

    private val _subscriptionPreview = MutableStateFlow(SubscriptionPreviewUiState())
    val subscriptionPreview: StateFlow<SubscriptionPreviewUiState> = _subscriptionPreview.asStateFlow()
    private var subscriptionPreviewJob: Job? = null

    private val speedTester = SpeedTester()
    private val _speedTest = MutableStateFlow(SpeedTestState())
    val speedTest: StateFlow<SpeedTestState> = _speedTest.asStateFlow()
    private var speedTestJob: Job? = null
    private var speedTestNoticeSeen = false

    private val _siteProbes = MutableStateFlow(SiteProbeState())
    val siteProbes: StateFlow<SiteProbeState> = _siteProbes.asStateFlow()
    private var siteProbeJob: Job? = null

    private val _connection = container.connection
    val connection: StateFlow<ConnectionState> = container.connection
    private var verifyJob: Job? = null
    private var verifyToken = 0
    private val switchMutex = Mutex()

    private val _bandwidth = MutableStateFlow(BandwidthState())
    val bandwidth: StateFlow<BandwidthState> = _bandwidth.asStateFlow()
    private var bandwidthJob: Job? = null

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var logJob: Job? = null
    private var statusJob: Job? = null
    private var alertsFingerprint: String? = null
    private var bypassUidsSynced = false
    private val recentEvents = ArrayDeque<String>()
    private val eventTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        recordEvent("应用启动")
        viewModelScope.launch {
            runCatching { repo.migrateLegacyDns() }
        }
    }

    fun onUiStarted() {
        refreshStatus()
        startStatusPolling()
    }

    fun onUiStopped() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun startStatusPolling() {
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            var tick = 0
            var lastRunning: Boolean? = null
            while (isActive) {
                // While the control service runs it already polls for us.
                if (!container.controlServiceRunning.value) refreshStatus()
                val running = status.value.running
                if (running != lastRunning) {
                    lastRunning = running
                    if (running) {
                        refreshConnection(announce = false)
                    } else {
                        onProxyStopped()
                        _logAlerts.value = LogAlerts()
                    }
                }
                if (running) refreshLogAlerts()
                // Real traffic through the node is the only honest proxy check,
                // so re-probe the home sites every couple of minutes.
                if (tick > 0 && running) {
                    if (tick % 10 == 0) refreshSiteProbes()
                    if (tick % 25 == 0) refreshConnection(announce = false)
                }
                tick++
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
        if (result == RootRequestResult.GRANTED || result == RootRequestResult.DENIED) {
            runCatching { container.settings.setRootGranted(result == RootRequestResult.GRANTED) }
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
        viewModelScope.launch {
            // The debounced snapshot still holds the initial AppSnapshot() during
            // cold start, whose keepServiceRunning default would restart the
            // service for users who turned it off.
            if (!repo.snapshotNow().settings.keepServiceRunning) return@launch
            runCatching {
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), ControlService::class.java),
                )
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            container.ensureRootSession()
            if (!bypassUidsSynced && container.root.hasRootShell()) {
                bypassUidsSynced = true
                runCatching {
                    val packages = repo.snapshotNow().settings.bypassPackages
                    daemon.syncBypassUids(packages)
                }
            }
            // The container drops this read when another one is already in
            // flight, so waiters can never pile up on the root shell.
            container.refreshStatus()
        }
    }

    /** Keeps [busy] recoverable: a throw inside [block] must not park every button. */
    private suspend fun withBusy(block: suspend () -> Unit) {
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }

    fun diagnose() = viewModelScope.launch {
        withBusy {
            _compatibility.value = runCatching { daemon.diagnose() }.getOrElse {
                CompatibilityReport(rootAvailable = false, note = it.message ?: "诊断失败")
            }
        }
    }

    fun toggleProxy(enable: Boolean) = viewModelScope.launch {
        var started = false
        withBusy {
            if (enable) {
                if (!container.root.hasRootShell() &&
                    !requestRootInternal(installModule = true)
                ) {
                    return@withBusy
                }
                // Rendered from a fresh store read: snapshot.value still holds the
                // previous state right after an edit, which would make dae reload
                // the config the user just changed away from.
                val snap = repo.snapshotNow()
                val spec = repo.renderSpec(snap)
                if (spec.nodes.isEmpty() && spec.template != RoutingTemplate.DIRECT) {
                    notify("请先导入并启用至少一个节点")
                    return@withBusy
                }
                val applied = daemon.applyConfig(
                    DaeConfigRenderer.render(spec),
                    reload = status.value.running,
                )
                if (!applied.ok) {
                    notify("无法应用配置：${applied.message}")
                    return@withBusy
                }
                val startedResult = daemon.start()
                if (!startedResult.ok) {
                    notify("启动失败：${startedResult.message}")
                } else {
                    started = true
                    if (snap.settings.bootStart) {
                        daemon.enableBootStart()
                    }
                }
            } else {
                val stopped = daemon.stop()
                notify(
                    if (stopped.ok) "dae 已停止" else "停止失败：${stopped.message}",
                    actionLabel = "重新启动".takeIf { stopped.ok },
                )
            }
        }
        container.ensureRootSession()
        val live = container.refreshStatus()
        val running = live?.running ?: status.value.running
        if (enable && started && running) {
            refreshConnection(announce = true)
        } else if (!running) {
            onProxyStopped()
        }
    }

    fun applyConfig() = viewModelScope.launch {
        withBusy {
            val result = daemon.applyConfig(
                repo.render(repo.snapshotNow()),
                reload = status.value.running,
            )
            notify(if (result.ok) "配置已应用：${result.message}" else "应用失败：${result.message}")
        }
        refreshStatus()
    }

    private suspend fun applyIfRunning() {
        if (!status.value.running) return
        val rendered = repo.render(repo.snapshotNow())
        val result = daemon.applyConfig(rendered, reload = true)
        if (!result.ok) notify("热更新失败：${result.message}")
    }

    suspend fun previewConfig(): String = withContext(Dispatchers.Default) {
        repo.render(repo.snapshotNow())
    }

    fun reloadConfig() = viewModelScope.launch {
        withBusy {
            val result = daemon.reload()
            notify(if (result.ok) "配置已热重载" else "热重载失败：${result.message}")
        }
        refreshStatus()
    }

    fun addSubscription(name: String, url: String) = viewModelScope.launch {
        withBusy {
            val result = repo.addSubscription(name, url)
            notify(describeImport(result))
        }
    }

    fun previewSubscription(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (_subscriptionPreview.value.running && _subscriptionPreview.value.url == trimmed) return
        subscriptionPreviewJob?.cancel()
        subscriptionPreviewJob = viewModelScope.launch {
            _subscriptionPreview.value = SubscriptionPreviewUiState(running = true, url = trimmed)
            val preview = runCatching { repo.previewSubscription(trimmed) }
                .getOrElse { SubscriptionPreview(false, it.message ?: "预览失败") }
            _subscriptionPreview.value = SubscriptionPreviewUiState(url = trimmed, preview = preview)
        }
    }

    fun clearSubscriptionPreview() {
        subscriptionPreviewJob?.cancel()
        subscriptionPreviewJob = null
        _subscriptionPreview.value = SubscriptionPreviewUiState()
    }

    fun refreshSubscription(id: String) = viewModelScope.launch {
        withBusy {
            val result = repo.refreshSubscription(id, force = true)
            notify(describeImport(result))
        }
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
        withBusy {
            val result = repo.importLinks(text, namePrefix)
            notify(describeImport(result))
        }
    }

    fun deleteNode(id: String) = viewModelScope.launch {
        repo.deleteNode(id)
        notify("节点已删除")
        applyIfRunning()
    }

    fun setFavorite(id: String, favorite: Boolean) = viewModelScope.launch {
        repo.setFavorite(id, favorite)
    }

    fun setNodeSkipCertVerify(id: String, skip: Boolean) = viewModelScope.launch {
        repo.setNodeSkipCertVerify(id, skip)
        applyIfRunning()
    }

    fun selectNode(id: String?) = viewModelScope.launch {
        val label = repo.snapshotNow().nodes.firstOrNull { it.id == id }?.name ?: "自动选择"
        switchOutbound(label) { repo.selectNode(id) }
    }

    fun selectGroup(id: String) = viewModelScope.launch {
        val label = repo.snapshotNow().groups.firstOrNull { it.id == id }?.name ?: "分组"
        switchOutbound(label) { repo.selectGroup(id) }
    }

    fun selectAuto() = viewModelScope.launch {
        switchOutbound("自动选择") { repo.selectAuto() }
    }

    /**
     * A switch is only reported as successful once traffic actually left through
     * the new outbound, so a broken node cannot look connected just because the
     * config reload returned without an error.
     */
    private suspend fun switchOutbound(label: String, select: suspend () -> Unit) =
        switchMutex.withLock {
            if (!status.value.running) {
                select()
                _connection.value = ConnectionState(
                    phase = ConnectionPhase.STOPPED,
                    target = label,
                    detail = "代理未启用",
                )
                notify("已选择 $label（代理未启用）")
                return@withLock
            }
            verifyJob?.cancel()
            verifyToken++
            _connection.value = ConnectionState(
                phase = ConnectionPhase.APPLYING,
                target = label,
                detail = "正在应用 $label 的配置",
                checkedAt = System.currentTimeMillis(),
            )
            select()
            val applied = daemon.applyConfig(repo.render(repo.snapshotNow()), reload = true)
            if (!applied.ok) {
                _connection.value = ConnectionState(
                    phase = ConnectionPhase.FAILED,
                    target = label,
                    detail = applied.message,
                    checkedAt = System.currentTimeMillis(),
                )
                notify("切换到 $label 失败：${applied.message}")
                return@withLock
            }
            _connection.value = _connection.value.copy(
                phase = ConnectionPhase.VERIFYING,
                detail = "配置已应用，正在验证出口",
            )
            verifyConnection(label, announce = true, switched = true)
        }

    /** Re-reads the exit IP without touching the selection. */
    fun refreshExit() {
        if (!status.value.running) {
            notify("代理未启用，无法读取出口 IP")
            return
        }
        viewModelScope.launch { refreshConnection(announce = true) }
    }

    private suspend fun refreshConnection(announce: Boolean) {
        if (_connection.value.phase == ConnectionPhase.APPLYING) {
            if (announce) notify("正在切换出口，请稍候")
            return
        }
        if (verifyJob?.isActive == true) {
            if (announce) notify("正在验证出口，请稍候")
            return
        }
        if (!status.value.running) {
            if (announce) notify("代理未启用，无法读取出口 IP")
            return
        }
        val label = currentOutboundLabel()
        if (announce) {
            _connection.value = _connection.value.copy(
                phase = ConnectionPhase.VERIFYING,
                target = _connection.value.target.ifBlank { label },
                detail = "正在验证出口",
            )
        }
        verifyConnection(label, announce = announce)
    }

    private suspend fun currentOutboundLabel(): String {
        val snap = repo.snapshotNow()
        return snap.groups.firstOrNull { it.id == snap.defaultGroupId }?.name
            ?: snap.nodes.firstOrNull { it.id == snap.selectedNodeId }?.name
            ?: "自动选择"
    }

    /**
     * Confirms the selection with a request that has to leave through the
     * tunnel. The Cloudflare trace only decorates the result with the exit IP:
     * it can be answered over a direct route, so it never proves the switch.
     */
    private fun verifyConnection(
        target: String,
        announce: Boolean,
        switched: Boolean = false,
        allowCertFix: Boolean = true,
    ) {
        verifyJob = viewModelScope.launch {
            val token = ++verifyToken
            var probe: DomainTestResult? = null
            var exit: ExitInfo? = null
            var failure = "出口探测失败"
            // Both probes run together: a dead node otherwise costs two full
            // timeouts per attempt before the switch is reported as failed.
            for (attempt in 0 until 2) {
                if (!status.value.running) return@launch
                coroutineScope {
                    val site = async { probeProxiedSite() }
                    val trace = async {
                        runCatching { domainTester.fetchExit() }.getOrNull()
                    }
                    probe = site.await()
                    exit = trace.await()
                }
                // Only a request that really left through the tunnel proves the
                // switch: the trace on its own can be answered by a direct route
                // while the node is broken.
                if (probe?.reachable == true) break
                failure = probe?.detail ?: "出口探测失败"
                if (attempt == 0) delay(1_500)
            }
            // A newer switch or a stop makes this result stale.
            if (token != verifyToken) return@launch
            if (!status.value.running) {
                onProxyStopped()
                return@launch
            }
            if (probe?.reachable == true) {
                val state = _connection.value.copy(
                    phase = ConnectionPhase.CONNECTED,
                    target = target,
                    exit = exit ?: _connection.value.exit,
                    latencyMs = probe?.latencyMs ?: _connection.value.latencyMs,
                    checkedAt = System.currentTimeMillis(),
                    detail = "",
                )
                _connection.value = state
                if (announce) notify(if (switched) switchedMessage(state) else exitMessage(state))
            } else if (allowCertFix && token == verifyToken && applyCertFix(token)) {
                notify("节点证书无法校验，已自动启用跳过校验，正在重连")
                verifyConnection(target, announce, switched, allowCertFix = false)
            } else {
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.FAILED,
                    target = target,
                    exit = null,
                    checkedAt = System.currentTimeMillis(),
                    detail = failure,
                )
                if (announce) {
                    notify(
                        if (switched) "切换到 $target 后连接失败：$failure"
                        else "出口探测失败：$failure",
                    )
                }
            }
        }
    }

    /**
     * Google proves the tunnel; YouTube is the tie-breaker so one blocked site
     * cannot be reported as a dead node. The home screen list keeps showing the
     * Google result it measured.
     */
    private suspend fun probeProxiedSite(): DomainTestResult? {
        val google = runCatching { domainTester.probeOne(DomainTargets.google) }.getOrNull()
        google?.let { result ->
            _siteProbes.update {
                it.copy(results = it.results + (DomainTargets.google.host to result))
            }
        }
        if (google?.reachable == true) return google
        val youtube = runCatching { domainTester.probeOne(DomainTargets.youtube) }.getOrNull()
        return if (youtube?.reachable == true) youtube else google
    }

    /**
     * Before a failed selection is reported as dead, ask its TLS servers for
     * their certificates. A self-signed certificate makes dae reject an
     * otherwise healthy node with `x509: certificate signed by unknown
     * authority`, which looks like a dead node to the exit probe. Only nodes
     * whose certificate actually fails verification get the per-node override,
     * then the config is re-applied and the caller retries once.
     */
    private suspend fun applyCertFix(staleToken: Int): Boolean {
        val candidates = selectionNodes(repo.snapshotNow())
            .filter { !it.skipCertVerify && ShareLink.usesTls(it.link) }
        if (candidates.isEmpty()) return false
        val rejected = withContext(Dispatchers.IO) {
            coroutineScope {
                val permits = Semaphore(4)
                candidates.map { node ->
                    async {
                        permits.withPermit {
                            val endpoint = node.endpoint ?: return@withPermit null
                            val port = endpoint.port ?: return@withPermit null
                            val serverName = ShareLink.tlsServerName(node.link) ?: endpoint.host
                            val trusted = TlsTrustProbe.probe(endpoint.host, port, serverName)
                            if (trusted == false) node else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        if (rejected.isEmpty()) return false
        switchMutex.withLock {
            if (staleToken != verifyToken || !status.value.running) return false
            rejected.forEach { repo.setNodeSkipCertVerify(it.id, true) }
            val applied = daemon.applyConfig(repo.render(repo.snapshotNow()), reload = true)
            if (!applied.ok) {
                rejected.forEach { repo.setNodeSkipCertVerify(it.id, false) }
                return false
            }
        }
        return true
    }

    /** Nodes dae is currently sending traffic through. */
    private fun selectionNodes(snapshot: AppSnapshot): List<Node> {
        val byId = snapshot.nodes.associateBy { it.id }
        val group = snapshot.groups.firstOrNull { it.id == snapshot.defaultGroupId }
        if (group != null) return group.nodeIds.mapNotNull { byId[it] }
        return listOfNotNull(snapshot.selectedNodeId?.let { byId[it] })
    }

    private fun switchedMessage(state: ConnectionState): String =
        "已切换到 ${state.target} · ${exitMessage(state)}"

    private fun exitMessage(state: ConnectionState): String {
        val exit = state.exit ?: return "已连接，但出口 IP 未知"
        val place = listOf(exit.country, exit.colo)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return "出口 ${exit.ip}" + (if (place.isNotBlank()) " · $place" else "")
    }

    private fun onProxyStopped() {
        verifyJob?.cancel()
        verifyJob = null
        verifyToken++
        _connection.value = ConnectionState(phase = ConnectionPhase.STOPPED)
    }

    fun testAllNodes() = viewModelScope.launch {
        withBusy {
            val results = repo.testAllNodes()
            val ok = results.count { it.latencyMs != null }
            val icmp = results.count { it.method == "icmp" && it.latencyMs != null }
            notify(
                "延迟测试完成：$ok/${results.size} 可用" +
                    if (icmp > 0) "，其中 $icmp 个 QUIC 节点退回 ping 备用" else "",
            )
        }
    }

    fun testNode(id: String) = viewModelScope.launch {
        val result = repo.testNode(id)
        notify(
            when {
                result?.latencyMs != null && result.method == "icmp" ->
                    "延迟 ${result.latencyMs} ms（QUIC 端口无响应，退回 ping）"
                result?.latencyMs != null -> "握手延迟 ${result.latencyMs} ms"
                result?.error != null -> result.error
                else -> "测试失败"
            },
        )
    }

    fun speedTestNeedsNotice(): Boolean = !speedTestNoticeSeen

    fun acknowledgeSpeedTestNotice() {
        speedTestNoticeSeen = true
    }

    /**
     * Measures a node by temporarily routing only the speed-test host through
     * it. The user's selected node and every other rule stay untouched, and the
     * original config is re-applied when the test ends.
     */
    fun testNodeSpeed(nodeId: String) {
        if (_speedTest.value.runningNodeId != null) {
            notify("已有测速正在进行")
            return
        }
        if (_bandwidth.value.running) {
            notify("首页带宽测速正在进行，请稍后")
            return
        }
        speedTestJob = viewModelScope.launch {
            withBusy {
                if (!container.root.hasRootShell() &&
                    !requestRootInternal(installModule = true)
                ) {
                    return@withBusy
                }
                val live = runCatching { daemon.status() }.getOrNull()
                if (live?.running != true) {
                    notify("请先启用代理后再测速")
                    return@withBusy
                }
                val snapshot = repo.snapshotNow()
                val node = snapshot.nodes.firstOrNull { it.id == nodeId }
                if (node == null) {
                    notify("节点不存在")
                    return@withBusy
                }
                val plan = SpeedTestPlan.build(
                    spec = repo.renderSpec(snapshot),
                    nodeId = nodeId,
                    selfPackage = getApplication<Application>().packageName,
                )
                if (plan == null) {
                    notify("节点所在订阅已停用，无法测速")
                    return@withBusy
                }
                _speedTest.update { it.copy(runningNodeId = nodeId) }
                notify("正在测速：${node.name}")
                var tempApplied = false
                try {
                    val temp = runCatching {
                        daemon.applyConfig(DaeConfigRenderer.render(plan), reload = true)
                    }.getOrElse {
                        ActionResult(false, it.message ?: "应用测速配置失败")
                    }
                    if (!temp.ok) {
                        notify("无法切换到测速节点：${temp.message}")
                        return@withBusy
                    }
                    tempApplied = true
                    val result = runCatching { speedTester.run() }
                        .getOrElse { SpeedTestResult(error = it.message ?: "测速失败") }
                    _speedTest.update { it.copy(results = it.results + (nodeId to result)) }
                    if (result.downloadMbps == null && result.uploadMbps == null) {
                        notify("测速失败：${result.error ?: "没有可用结果"}")
                    } else {
                        notify(
                            "测速完成：↓ ${Format.mbps(result.downloadMbps)} Mbps · " +
                                "↑ ${Format.mbps(result.uploadMbps)} Mbps",
                        )
                    }
                } finally {
                    _speedTest.update { it.copy(runningNodeId = null) }
                    if (tempApplied) {
                        val restored = runCatching {
                            val current = repo.snapshotNow()
                            daemon.applyConfig(repo.render(current), reload = true)
                        }.getOrNull()
                        if (restored == null || !restored.ok) {
                            notify("测速后恢复分流配置失败，请到分流页重新应用")
                        }
                    }
                }
            }
        }
    }

    /**
     * Probes the home-screen sites one at a time through the live network. Only
     * auto-runs while the proxy is up; otherwise the latencies would describe a
     * direct connection and make a broken node look healthy.
     */
    fun refreshSiteProbes() {
        if (siteProbeJob?.isActive == true) return
        if (!status.value.running) return
        val now = System.currentTimeMillis()
        if (now - _siteProbes.value.lastRunAt < 90_000) return
        siteProbeJob = viewModelScope.launch {
            val targets = DomainTargets.home
            _siteProbes.update { it.copy(testing = targets.map { target -> target.host }.toSet()) }
            var results = _siteProbes.value.results
            var completed = true
            for (target in targets) {
                // The daemon may stop mid-run; the remaining probes would then
                // measure a direct connection and overwrite valid results.
                if (!status.value.running) {
                    completed = false
                    break
                }
                val result = runCatching { domainTester.probeOne(target) }.getOrNull()
                if (result != null) results = results + (target.host to result)
                _siteProbes.update { it.copy(results = results, testing = it.testing - target.host) }
            }
            _siteProbes.update {
                it.copy(
                    testing = emptySet(),
                    lastRunAt = if (completed) System.currentTimeMillis() else it.lastRunAt,
                )
            }
        }
    }

    fun testSite(host: String) {
        val target = DomainTargets.home.firstOrNull { it.host == host } ?: return
        if (_siteProbes.value.testing.contains(host)) return
        _siteProbes.update { it.copy(testing = it.testing + host) }
        viewModelScope.launch {
            val result = runCatching { domainTester.probeOne(target) }.getOrNull()
            _siteProbes.update {
                val updated = if (result == null) it.results else it.results + (host to result)
                it.copy(results = updated, testing = it.testing - host)
            }
            if (result != null && !result.reachable) {
                notify("${target.label} 不可达：${result.detail}")
            }
        }
    }

    /** Measures the current egress. Runs on the normal app network, so with the
     *  proxy up these are the proxied down/up speeds. */
    fun testBandwidth() {
        if (_bandwidth.value.running) return
        if (_speedTest.value.runningNodeId != null) {
            notify("节点测速正在进行，请稍后")
            return
        }
        _bandwidth.update { it.copy(running = true) }
        val proxied = status.value.running
        bandwidthJob = viewModelScope.launch {
            val result = runCatching { speedTester.run() }
                .getOrElse { SpeedTestResult(error = it.message ?: "测速失败") }
            _bandwidth.value = BandwidthState(
                running = false,
                result = result,
                testedAt = System.currentTimeMillis(),
                proxied = proxied,
            )
            if (result.downloadMbps == null && result.uploadMbps == null) {
                notify("带宽测速失败：${result.error ?: "没有可用结果"}")
            }
        }
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

    fun moveRule(id: String, offset: Int) = viewModelScope.launch {
        repo.moveRule(id, offset)
        applyIfRunning()
    }

    fun duplicateRule(id: String) = viewModelScope.launch {
        repo.duplicateRule(id)
        notify("规则已复制")
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
        val snap = repo.snapshotNow()
        val group = snap.groups.firstOrNull { it.id == id } ?: return@launch
        val refs = snap.rules.count { rule ->
            rule.enabled && (rule.outbound == group.daeKey() || rule.outbound == group.name)
        }
        if (refs > 0) {
            notify("有 $refs 条规则正在使用「${group.name}」，请先修改规则")
            return@launch
        }
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
        val packages = values.distinct()
        repo.updateSettings { it.copy(bypassPackages = packages) }
        val sync = runCatching { daemon.syncBypassUids(packages) }
            .getOrElse { ActionResult(false, it.message ?: "分应用标记失败") }
        notify(if (sync.ok) sync.message else "分应用标记失败：${sync.message}")
        applyIfRunning()
    }

    fun setTetherShare(enabled: Boolean) = viewModelScope.launch {
        repo.updateSettings { it.copy(tetherShare = enabled) }
        notify(
            if (enabled) "热点共享已开启，开启热点后约 30 秒内生效"
            else "热点共享已关闭",
        )
        applyIfRunning()
        refreshStatus()
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
            var fingerprint: String? = null
            while (isActive) {
                val tail = readLogIfChanged(800, fingerprint)
                if (tail != null) {
                    fingerprint = tail.second
                    _logs.value = parseLogs(tail.first)
                    publishLogAlerts(tail.first)
                }
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

    private suspend fun refreshLogAlerts() {
        // The log stream already publishes alerts from the same tail; a second
        // read here would double the root-shell traffic while the page is open.
        if (logJob?.isActive == true) return
        val tail = readLogIfChanged(800, alertsFingerprint) ?: return
        alertsFingerprint = tail.second
        publishLogAlerts(tail.first)
    }

    /**
     * The tail is ~200KB over the su pipe; on an idle daemon it is identical
     * every poll. stat is a few bytes, so skip the read until the log moves.
     * Each consumer keeps its own fingerprint: a shared one would let the first
     * reader starve the other, which then never populates its initial state.
     */
    private suspend fun readLogIfChanged(lines: Int, fingerprint: String?): Pair<String, String>? {
        val current = runCatching { daemon.logFingerprint() }.getOrDefault("")
        if (current.isNotEmpty() && current == fingerprint) return null
        val raw = runCatching { daemon.readLog(lines) }.getOrDefault("")
        return raw to current
    }

    private fun publishLogAlerts(raw: String) {
        _logAlerts.value = LogAlertParser.parse(
            raw.lineSequence().filter { it.isNotBlank() }.toList(),
        )
    }

    fun uploadLogs() = viewModelScope.launch {
        if (_logUpload.value.uploading) return@launch
        _logUpload.value = LogUploadState(uploading = true)
        val raw = runCatching { daemon.readLog(2000) }.getOrElse {
            "读取日志失败：${it.message ?: it.javaClass.simpleName}"
        }
        recordEvent("上传运行日志")
        val status = status.value
        // Rendering the whole config walks every node link (base64 + canonicalize);
        // keep it off the main thread and read the stores, not the debounced snapshot.
        val configText = runCatching {
            withContext(Dispatchers.Default) {
                ConfigRedactor.redact(repo.render(repo.snapshotNow()))
            }
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
                append("ipv6Mode=${status.ipv6Mode ?: "-"} ")
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

    /** Toast for work the UI performs itself — clipboard writes, file exports —
     *  which still needs the same acknowledgement every daemon action gets. */
    fun toast(message: String) = notify(message)

    private fun notify(message: String, actionLabel: String? = null) {
        recordEvent(message)
        messageChannel.trySend(UiMessage(message, actionLabel))
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
                        // The level tag wins over the message text: dae logs a warning as
                        // "WARN DNS forward to upstream failed", which is not an error line.
                        line.contains("ERROR", true) || line.contains("FATAL", true) -> "error"
                        line.contains("WARN", true) -> "warn"
                        line.contains("DEBUG", true) -> "debug"
                        line.contains("failed", true) -> "error"
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
        subscriptionPreviewJob?.cancel()
        speedTestJob?.cancel()
        siteProbeJob?.cancel()
        bandwidthJob?.cancel()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(application) as T
    }
}
