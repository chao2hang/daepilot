package io.github.daepilot.data

import android.content.Context
import io.github.daepilot.core.root.DaemonController
import io.github.daepilot.core.root.RootShell
import io.github.daepilot.data.db.AppDatabase
import io.github.daepilot.data.net.LatencyTester
import io.github.daepilot.data.net.LogUploader
import io.github.daepilot.data.net.SubscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        subscriptionDao = db.subscriptionDao(),
        nodeDao = db.nodeDao(),
        settingsStore = settings,
        client = client,
        latencyTester = LatencyTester(),
        scope = scope,
    )
}
