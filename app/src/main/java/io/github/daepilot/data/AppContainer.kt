package io.github.daepilot.data

import android.content.Context
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.root.DaemonController
import io.github.daepilot.core.root.RootRequestResult
import io.github.daepilot.core.root.RootShell
import io.github.daepilot.data.db.AppDatabase
import io.github.daepilot.data.net.LatencyTester
import io.github.daepilot.data.net.LogUploader
import io.github.daepilot.data.net.SubscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual dependency container: the app is small enough to skip a DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val root = RootShell()
    val daemon = DaemonController(app, root)
    val settings = SettingsStore(app)
    private val db = AppDatabase.get(app)
    val client = SubscriptionClient()
    val logUploader = LogUploader()
    val repository = DaeRepository(
        db = db,
        subscriptionDao = db.subscriptionDao(),
        nodeDao = db.nodeDao(),
        settingsStore = settings,
        client = client,
        latencyTester = LatencyTester(),
        scope = scope,
    )

    private val statusMutex = Mutex()
    private val _statusUpdates = MutableStateFlow(DaemonStatus())
    val statusUpdates: StateFlow<DaemonStatus> = _statusUpdates.asStateFlow()
    val controlServiceRunning = MutableStateFlow(false)

    /** Written by the UI poller, read by the control service notification. */
    val connection = MutableStateFlow(ConnectionState())

    private val rootSessionMutex = Mutex()
    private var rootSessionChecked = false

    /**
     * After a reboot the daemon keeps running but this process has no cached su
     * session. The UI and the control service both need one; serialise the
     * acquisition so only one authorization prompt is ever in flight.
     */
    suspend fun ensureRootSession() {
        rootSessionMutex.withLock {
            if (rootSessionChecked) return
            rootSessionChecked = true
            if (root.hasRootShell()) {
                runCatching { settings.setRootGranted(true) }
                return
            }
            if (runCatching { settings.rootGranted() }.getOrNull() == false) return
            when (runCatching { root.requestRoot() }.getOrElse { RootRequestResult.ERROR }) {
                RootRequestResult.GRANTED ->
                    runCatching { settings.setRootGranted(true) }
                RootRequestResult.DENIED ->
                    runCatching { settings.setRootGranted(false) }
                RootRequestResult.TIMEOUT, RootRequestResult.ERROR -> Unit
            }
        }
    }

    /**
     * Single-flight status read shared by the UI poller and the control service,
     * so a visible app does not shell out to root twice per poll.
     */
    suspend fun refreshStatus(): DaemonStatus? {
        if (!statusMutex.tryLock()) return null
        return try {
            val status = runCatching { daemon.status() }.getOrElse {
                DaemonStatus(lastError = it.message ?: "状态读取失败")
            }
            _statusUpdates.value = status
            status
        } finally {
            statusMutex.unlock()
        }
    }
}
