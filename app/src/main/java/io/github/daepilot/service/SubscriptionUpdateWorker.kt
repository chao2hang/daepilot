package io.github.daepilot.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.daepilot.DaePilotApp
import kotlinx.coroutines.flow.first

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DaePilotApp ?: return Result.success()
        val config = runCatching { app.container.settings.config.first() }.getOrNull()
            ?: return Result.retry()
        if (!config.settings.subscriptionAutoUpdate) return Result.success()
        return runCatching {
            val updated = app.container.repository.refreshAllDue()
            if (updated > 0) {
                NotificationHelper.notify(
                    applicationContext,
                    "订阅已更新",
                    "已刷新 $updated 个订阅",
                )
            }
            Result.success()
        }.getOrElse {
            NotificationHelper.notify(applicationContext, "订阅更新失败", it.message ?: "未知错误")
            Result.retry()
        }
    }
}
