package com.obdlogger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Auto mode survives a reboot of the tablet: start waiting for the engine again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.auto(context) || Prefs.device(context) == null) return
        try {
            Compat.startForegroundService(context, LoggerService.intent(context, LoggerService.ACTION_AUTO))
        } catch (e: Exception) {
            // Some firmwares refuse background starts; the app starts auto mode when opened.
        }
    }
}
