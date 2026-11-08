package io.github.daepilot.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import io.github.daepilot.DaePilotApp
import io.github.daepilot.data.AppContainer
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground status/control service. It never owns the daemon: it only calls
 * `daepilotctl`, so killing the app or this service does not stop the proxy.
 */
class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer
    private var pollJob: Job? = null
    private var notificationJob: Job? = null
    private val notificationMutex = Mutex()
    private var lastNotifiedKey: String? = null
    private var lastOutboundKey: String = ""

    override fun onCreate() {
        super.onCreate()
        container = (application as DaePilotApp).container
        container.controlServiceRunning.value = true
        val notification = NotificationHelper.controlNotification(this, DaemonStatus())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.CONTROL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.CONTROL_NOTIFICATION_ID, notification)
        }
        lastNotifiedKey = notificationKey(DaemonStatus(), ConnectionState())
        lastOutboundKey = connectionKey(ConnectionState())
        pollJob = scope.launch {
            while (isActive) {
                val status = refreshStatus()
                // The watchdog can die independently of dae (a replaced script
                // kills the running shell). Heal it instead of leaving the
                // daemon unattended until the next manual start.
                if (status?.running == true && !status.watchdog) {
                    runCatching { container.daemon.start() }
                }
                // A stopped daemon changes state rarely; polling it every 30s
                // only burns root-shell wakeups.
                delay(if (status?.running == true) 30_000 else 120_000)
            }
        }
        // The UI triggers reads too (e.g. after start/stop); publish those to the
        // notification as well instead of waiting for the next poll.
        notificationJob = scope.launch {
            combine(container.statusUpdates, container.connection) { status, connection ->
                status to connection
            }.collect { (status, connection) -> postNotification(status, connection) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DAEMON -> scope.launch {
                val result = withContext(Dispatchers.IO) { container.daemon.start() }
                if (!result.ok) {
                    NotificationHelper.notify(this@ControlService, "dae 启动失败", result.message)
                }
                refreshStatus()
            }
            ACTION_STOP_DAEMON -> scope.launch {
                withContext(Dispatchers.IO) { container.daemon.stop() }
                refreshStatus()
            }
            ACTION_RELOAD -> scope.launch {
                val result = withContext(Dispatchers.IO) { container.daemon.reload() }
                NotificationHelper.notify(
                    this@ControlService,
                    if (result.ok) "配置已热重载" else "热重载失败",
                    result.message,
                )
                refreshStatus()
            }
            ACTION_REFRESH -> scope.launch { refreshStatus() }
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_STICKY
    }

    private suspend fun refreshStatus(): DaemonStatus? {
        container.ensureRootSession()
        return container.refreshStatus()
    }

    private suspend fun postNotification(status: DaemonStatus, connection: ConnectionState) {
        val key = notificationKey(status, connection)
        // Reposting an identical notification wakes the notification pipeline
        // (and relights the screen LED on some ROMs). The key ignores the fields
        // that move on every poll: startedAt and rss_kb.
        notificationMutex.withLock {
            val outbound = connectionKey(connection)
            if (key == lastNotifiedKey) return
            lastNotifiedKey = key
            val outboundChanged = outbound != lastOutboundKey
            lastOutboundKey = outbound
            val islandExtras = runCatching {
                XiaomiIsland.focusExtras(this, status, connection, expand = outboundChanged)
            }.getOrNull()
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    NotificationHelper.CONTROL_NOTIFICATION_ID,
                    NotificationHelper.controlNotification(this, status, connection, islandExtras),
                )
            }
        }
    }

    private fun notificationKey(status: DaemonStatus, connection: ConnectionState): String {
        // Uptime is deliberately absent: the notification draws it with a system
        // chronometer, and bucketing it here used to force a repost every five
        // minutes, each taking a NotificationManagerService wakelock.
        val outbound = "${connectionKey(connection)}|${connection.latencyMs}|${connection.detail}"
        return with(status) {
            "$running|$pid|$version|$configValid|$lastError|$mode|$bootEnabled|$watchdog|" +
                "$desiredRunning|$wan|$wanIp|$node|$dns|$dnsMode|$ipv6Mode|$outbound"
        }
    }

    /**
     * What the user reads as "the outbound changed": which phase we are in,
     * which node or group is selected and where the traffic actually exits.
     * Latency is deliberately left out: the periodic re-probe moves it, and that
     * must not be allowed to expand the island.
     */
    private fun connectionKey(connection: ConnectionState) =
        "${connection.phase}|${connection.target}|${connection.exit?.ip}"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        container.controlServiceRunning.value = false
        pollJob?.cancel()
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_DAEMON = "io.github.daepilot.action.START_DAEMON"
        const val ACTION_STOP_DAEMON = "io.github.daepilot.action.STOP_DAEMON"
        const val ACTION_RELOAD = "io.github.daepilot.action.RELOAD"
        const val ACTION_REFRESH = "io.github.daepilot.action.REFRESH"
        const val ACTION_STOP_SERVICE = "io.github.daepilot.action.STOP_SERVICE"
    }
}
