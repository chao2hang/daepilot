package io.github.daepilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.daepilot.MainActivity
import io.github.daepilot.R
import io.github.daepilot.core.model.DaemonStatus

object NotificationHelper {
    const val CHANNEL_CONTROL = "control"
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
    }

    fun controlNotification(context: Context, status: DaemonStatus): Notification {
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

        val title = "DaePilot"
        val detail = when {
            status.running && status.pid != null -> {
                val uptime = status.startedAt.takeIf { it > 0 }
                    ?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
                "运行 ${formatDuration(uptime)} · PID ${status.pid}" +
                    (status.version?.let { " · dae $it" } ?: "")
            }
            !status.lastError.isNullOrBlank() -> status.lastError
            else -> "代理已停止"
        }

        return NotificationCompat.Builder(context, CHANNEL_CONTROL)
            .setSmallIcon(R.drawable.ic_stat_daepilot)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
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
            .build()
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

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0 秒"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}天 ${hours}小时"
            hours > 0 -> "${hours}小时 ${minutes}分"
            minutes > 0 -> "${minutes}分 ${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }
}
