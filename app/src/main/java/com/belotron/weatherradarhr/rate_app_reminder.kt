package com.belotron.weatherradarhr

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val APP_TITLE = "Vrijeme na radaru"
private const val APP_ID = "com.belotron.weatherradarhr"

private const val DAYS_UNTIL_PROMPT = 10
private const val LAUNCHES_UNTIL_PROMPT = 10

private const val PREFS_NAME = "rate_app_reminder"
private const val KEY_DONT_SHOW_AGAIN = "dont_show_again"
private const val KEY_LAUNCH_COUNT = "launch_count"
private const val KEY_FIRST_LAUNCH = "date_firstlaunch"

fun maybeShowRateReminder(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, 0)
    prefs.applyUpdate {
        val now = System.currentTimeMillis()
        val launchCount = (prefs.getLong(KEY_LAUNCH_COUNT, 0) + 1).also {
            putLong(KEY_LAUNCH_COUNT, it)
        }
        val firstLaunchTimestamp = prefs.getLong(KEY_FIRST_LAUNCH, 0L).let {
            if (it == 0L) {
                putLong(KEY_FIRST_LAUNCH, now)
                now
            } else it
        }
        if (prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false)) {
            return
        }
        if (launchCount >= LAUNCHES_UNTIL_PROMPT && now >= firstLaunchTimestamp + DAYS_UNTIL_PROMPT * DAY_IN_MILLIS) {
            showRateDialog(context, prefs)
        }
    }
}

private fun showRateDialog(mContext: Context, prefs: SharedPreferences) {
    val dialog = Dialog(mContext)
    dialog.setTitle("Rate $APP_TITLE")

    val ll = LinearLayout(mContext)
    ll.orientation = LinearLayout.VERTICAL

    val tv = TextView(mContext)
    tv.text = "If you enjoy using $APP_TITLE, please take a moment to rate it. Thanks for your support!"
    tv.width = 240
    tv.setPadding(4, 0, 4, 10)
    ll.addView(tv)

    val b1 = Button(mContext)
    b1.text = "Rate $APP_TITLE"
    b1.setOnClickListener {
        mContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$APP_ID")))
        dialog.dismiss()
        prefs.applyUpdate {
            putBoolean(KEY_DONT_SHOW_AGAIN, true)
        }
    }
    ll.addView(b1)

    val b2 = Button(mContext)
    b2.text = "Remind me later"
    b2.setOnClickListener { dialog.dismiss() }
    ll.addView(b2)

    val b3 = Button(mContext)
    b3.text = "No, thanks"
    b3.setOnClickListener {
        dialog.dismiss()
        prefs.applyUpdate {
            putBoolean(KEY_DONT_SHOW_AGAIN, true)
        }
    }
    ll.addView(b3)

    dialog.setContentView(ll)
    dialog.show()
}
