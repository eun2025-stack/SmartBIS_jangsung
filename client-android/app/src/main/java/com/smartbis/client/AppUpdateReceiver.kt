package com.smartbis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Reopens the signage display after a reboot or an in-place package update. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent == null) {
            Log.e(TAG, "No launcher activity found for " + intent.action)
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        runCatching { context.startActivity(launchIntent) }
            .onFailure { Log.e(TAG, "Unable to launch display for " + intent.action, it) }
    }

    private companion object {
        const val TAG = "AppUpdateReceiver"
    }
}
