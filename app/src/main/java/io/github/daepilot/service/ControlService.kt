package io.github.daepilot.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import io.github.daepilot.DaePilotApp
import io.github.daepilot.data.AppContainer
import io.github.daepilot.core.model.DaemonStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground status/control service. It never owns the daemon: it only calls
 * `daepilotctl`, so killing the app or this service does not stop the proxy.
 */
class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer
    private var pollJob: Job? = null
    private var lastStatus = DaemonStatus()

    override fun onCreate() {
        super.onCreate()
        container = (application as DaePilotApp).container
        val notification = NotificationHelper.controlNotification(this, lastStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.CONTROL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.CONTROL_NOTIFICATION_ID, notification)
        }
        pollJob = scope.launch {
            while (isActive) {
                refreshStatus()
                delay(15_000)
            }
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

    private suspend fun refreshStatus() {
        val status = withContext(Dispatchers.IO) {
            runCatching { container.daemon.status() }.getOrDefault(
                DaemonStatus(lastError = "无法读取 daemon 状态"),
            )
        }
        lastStatus = status
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.CONTROL_NOTIFICATION_ID,
                NotificationHelper.controlNotification(this, status),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
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
