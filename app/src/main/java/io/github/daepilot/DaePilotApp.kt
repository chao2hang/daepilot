package io.github.daepilot

import android.app.Application
import io.github.daepilot.data.AppContainer
import io.github.daepilot.core.root.RootShell
import io.github.daepilot.service.NotificationHelper
import io.github.daepilot.service.WorkScheduler
import kotlinx.coroutines.launch

class DaePilotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        RootShell.initialize()
        NotificationHelper.createChannels(this)
        container = AppContainer(this)
        WorkScheduler.schedule(this)
        // An update leaves the running daemon on the previous configuration;
        // the app reconciles it in the background instead of waiting for the
        // user to touch a setting.
        container.scope.launch { container.reconcileConfig() }
    }
}
