package io.github.daepilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.daepilot.MainActivity
import io.github.daepilot.R
import io.github.daepilot.core.model.ConnectionPhase
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus

object NotificationHelper {
    const val CHANNEL_CONTROL = "control"
    const val CHANNEL_FOCUS = "focus"
    const val CHANNEL_LOGS = "logs"
    const val CONTROL_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONTROL,
                context.getString(R.string.notification_channel_control),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "dae 数据面运行状态"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LOGS,
                context.getString(R.string.notification_channel_logs),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Focus notifications need a visible (non-low) channel, but an ongoing
        // status update must never make noise.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOCUS,
                context.getString(R.string.notification_channel_focus),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "在小米超级岛显示出口 IP 与切换状态"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun controlNotification(
        context: Context,
        status: DaemonStatus,
        connection: ConnectionState = ConnectionState(),
        islandExtras: Bundle? = null,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val toggleIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ControlService::class.java).apply {
                action = if (status.running) ControlService.ACTION_STOP_DAEMON else ControlService.ACTION_START_DAEMON
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // HyperOS shows notification actions as island buttons, so the exit IP
        // can be re-read without opening the app first.
        val refreshIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_REFRESH_EXIT, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = "DaePilot"
        val runtime = when {
            status.running && status.pid != null -> {
                // The elapsed time is drawn by the system chronometer, so the
                // notification does not have to be reposted every few minutes
                // just to keep "运行 X 分钟" current (each repost takes a
                // NotificationManagerService wakelock charged to our UID).
                "PID ${status.pid}" + (status.version?.let { " · dae $it" } ?: "")
            }
            !status.lastError.isNullOrBlank() -> status.lastError
            status.mode == "checking" || status.mode == "unknown" -> "正在同步运行状态…"
            else -> "代理已停止"
        }
        val outbound = when {
            !status.running -> null
            connection.phase == ConnectionPhase.CONNECTED -> {
                val exit = connection.exit
                if (exit == null) {
                    "出口 IP 未知"
                } else {
                    val place = listOf(exit.country, exit.colo)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    "出口 ${exit.ip}" + (if (place.isNotBlank()) " · $place" else "")
                }
            }
            connection.phase == ConnectionPhase.APPLYING ->
                "正在切换到 ${connection.target}"
            connection.phase == ConnectionPhase.VERIFYING -> "正在验证出口"
            connection.phase == ConnectionPhase.FAILED ->
                "连接异常：" + connection.detail.ifBlank { "出口不可达" }
            else -> null
        }
        val detail = listOfNotNull(outbound, runtime).joinToString(" · ")

        val builder = NotificationCompat.Builder(
            context,
            if (islandExtras != null) CHANNEL_FOCUS else CHANNEL_CONTROL,
        )
            .setSmallIcon(R.drawable.ic_stat_daepilot)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setShowWhen(status.running)
            .setWhen(if (status.startedAt > 0) status.startedAt else System.currentTimeMillis())
            .setUsesChronometer(status.running && status.startedAt > 0)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (status.running) "停止" else "启动",
                toggleIntent,
            )
            .apply {
                if (status.running) addAction(0, "刷新出口", refreshIntent)
            }
        islandExtras?.let(builder::addExtras)
        return builder.build()
    }

    fun notify(context: Context, title: String, text: String, id: Int = 2001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_LOGS)
            .setSmallIcon(R.drawable.ic_stat_daepilot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
