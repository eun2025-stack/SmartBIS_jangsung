package com.smartbis.vehiclenumber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Return to the passenger display as soon as this management app finishes updating. */
class VehicleNumberUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Headwind may also honor "Run after installation" and start this app's activity.
        // Keep a short-lived marker so that activity redirects to the passenger display too.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        openClient(context)
    }

    companion object {
        const val PREFERENCES = "vehicle_selector"
        const val KEY_UPDATED_AT = "vehicle_number_updated_at"
        const val REDIRECT_WINDOW_MS = 90_000L

        fun openClient(context: Context): Boolean = runCatching {
            val launch = Intent().apply {
                setClassName(CLIENT_PACKAGE, CLIENT_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launch)
            true
        }.getOrDefault(false)

        private const val CLIENT_PACKAGE = "com.smartbis.client"
        private const val CLIENT_ACTIVITY = "com.smartbis.client.MainActivity"
    }
}
