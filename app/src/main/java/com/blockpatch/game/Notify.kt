package com.blockpatch.game

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Android system notifications: "your tree is ripe" while the app is in the background. */
object Notify {
    const val CHANNEL = "ripe"
    private const val REQ = 4242

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tree reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun pending(ctx: Context, world: String): PendingIntent {
        val i = Intent(ctx, RipeReceiver::class.java).putExtra("world", world)
        return PendingIntent.getBroadcast(ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(ctx: Context, at: Long, world: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC_WAKEUP, at, pending(ctx, world))
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, ""))
        ctx.getSystemService(NotificationManager::class.java).cancel(REQ)
    }
}

class RipeReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    private fun builder(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, Notify.CHANNEL) else Notification.Builder(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        val world = intent.getStringExtra("world") ?: return
        Notify.ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, LobbyActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = "Your tree is ripe!"
        val text = "Harvest it in world ${world.uppercase()}."
        val b = builder(ctx)
        b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true)
        try { ctx.getSystemService(NotificationManager::class.java).notify(4242, b.build()) } catch (_: SecurityException) { }
        Profile(ctx).apply { addNote("Tree ripe in ${world.uppercase()}"); save() }
    }
}
