package com.obdlogger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** «Запускать при включении планшета»: after a reboot auto mode waits for the engine again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!Prefs.auto(context) || !Prefs.boot(context) || Prefs.device(context) == null) return
        try {
            Compat.startForegroundService(context, LoggerService.intent(context, LoggerService.ACTION_AUTO))
        } catch (e: Exception) {
            // Some firmwares refuse background starts; the app starts auto mode when opened.
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
