package io.github.daepilot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The module service starts the data plane at boot. The app receiver only
 * re-arms the periodic subscription refresh; it never starts a foreground
 * service from the background (restricted since Android 12).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            WorkScheduler.schedule(context.applicationContext)
        }
    }
}
