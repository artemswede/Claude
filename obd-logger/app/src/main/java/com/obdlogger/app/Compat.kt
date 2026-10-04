package com.obdlogger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/** Calls that differ between Android 5 (API 21, old head units) and newer versions. */
object Compat {
    /** Runtime permissions exist since Android 6; before that they are granted at install. */
    fun granted(ctx: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Battery optimisation («Doze») exists since Android 6. */
    fun batteryFree(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** «Поверх других окон» is a separate permission since Android 6. */
    fun canOverlay(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(ctx)

    fun bluetooth(ctx: Context): android.bluetooth.BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /** Notification button; icons as [android.graphics.drawable.Icon] only since Android 6. */
    fun action(ctx: Context, icon: Int, title: String, intent: android.app.PendingIntent): Notification.Action =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, icon), title, intent).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(icon, title, intent).build()
        }

    fun startForegroundService(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
    }

    /** Leaves the foreground and removes the notification. */
    fun stopForeground(service: Service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(true)
        }
    }

    fun startForeground(service: Service, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            service.startForeground(id, notification)
        }
    }

    fun notificationBuilder(ctx: Context, channelId: String, channelName: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(ctx, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW)
        }
}
