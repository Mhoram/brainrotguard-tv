package com.mhoram.brainrotguardtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Launches the kiosk when the TV finishes booting — but only if a server URL
 * has already been configured (otherwise it would trap the device on the
 * setup screen with no way to escape).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs: SharedPreferences = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        val configured = prefs.getString("server_url", null)?.trim()?.isNotEmpty() == true
        if (!configured) return

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}
