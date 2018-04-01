package com.belotron.weatherradarhr

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.LayoutInflater
import android.widget.Button

private const val DAYS_UNTIL_PROMPT = 3
private const val USES_UNTIL_PROMPT = 3 * DAYS_UNTIL_PROMPT
private const val DAYS_UNTIL_REPEAT_PROMPT = 1
private const val USES_UNTIL_REPEAT_PROMPT = 3 * DAYS_UNTIL_REPEAT_PROMPT

private const val TAG_RATE_ME: String = "rate_me_fragment"
private const val PREFS_NAME = "rate_app_reminder"
private const val KEY_DONT_SHOW_AGAIN = "dont_show_again"
private const val KEY_USE_COUNT = "use_count"
private const val KEY_TIMESTAMP_FIRST_USE = "timestamp_first_use"
private const val KEY_USE_COUNT_WHEN_PROMPTED = "use_count_when_prompted"
private const val KEY_TIMESTAMP_WHEN_PROMPTED = "timestamp_prompted"

fun Context.clearRatemeState() {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).applyUpdate {
        clear()
    }
}

fun Activity.maybeAskToRate() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val fragmentManager = fragmentManager
    val dayInMillis = DAY_IN_MILLIS
    prefs.applyUpdate {
        val useCount = (prefs.getLong(KEY_USE_COUNT, 0) + 1).also {
            putLong(KEY_USE_COUNT, it)
        }
        info { "useCount $useCount" }
        if (prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false)) {
            info { "Don't show again" }
            return
        }
        val now = System.currentTimeMillis()
        val timestampFirstUse = prefs.getLong(KEY_TIMESTAMP_FIRST_USE, now).also {
            if (it == now) {
                putLong(KEY_TIMESTAMP_FIRST_USE, it)
            }
        }
        info { "timestampFirstUse $timestampFirstUse"}
        if (useCount < USES_UNTIL_PROMPT || now < timestampFirstUse + DAYS_UNTIL_PROMPT * dayInMillis) {
            return
        }
        val timestampWhenPrompted = prefs.getLong(KEY_TIMESTAMP_WHEN_PROMPTED, Long.MIN_VALUE)
        val useCountWhenPrompted = prefs.getLong(KEY_USE_COUNT_WHEN_PROMPTED, Long.MIN_VALUE)
        info { "timestampWhenPrompted $timestampWhenPrompted, useCountWhenPrompted $useCountWhenPrompted"}
        if (useCount < useCountWhenPrompted + USES_UNTIL_REPEAT_PROMPT
                || now < timestampWhenPrompted + DAYS_UNTIL_REPEAT_PROMPT * dayInMillis) {
            return
        }
        putLong(KEY_TIMESTAMP_WHEN_PROMPTED, now)
        putLong(KEY_USE_COUNT_WHEN_PROMPTED, useCount)
        info { "Ask to rate" }
        RateMeDialogFragment().show(fragmentManager, TAG_RATE_ME)
    }
}

class RateMeDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val rootView = inflater.inflate(R.layout.rate_me, null).apply {
            findViewById<Button>(R.id.rateme_yes).setOnClickListener {
                openAppRating()
                dismiss()
                dontShowAgain()
            }
            findViewById<Button>(R.id.rateme_later).setOnClickListener {
                dismiss()
            }
            findViewById<Button>(R.id.rateme_already_did).setOnClickListener {
                dismiss()
                dontShowAgain()
            }
            findViewById<Button>(R.id.rateme_no).setOnClickListener {
                dismiss()
                dontShowAgain()
            }
        }
        return AlertDialog.Builder(activity)
                .setTitle(R.string.rateme_title)
                .setIcon(R.mipmap.ic_launcher)
                .setView(rootView)
                .create()
    }

    private fun dontShowAgain() {
        activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).applyUpdate {
            putBoolean(KEY_DONT_SHOW_AGAIN, true)
        }
    }

    private fun openAppRating() {
        val appId = activity.packageName
        val rateIntent = Intent(ACTION_VIEW, Uri.parse("market://details?id=$appId"))
        activity.packageManager.queryIntentActivities(rateIntent, 0)
                .map { it.activityInfo }
                .find { it.applicationInfo.packageName == "com.android.vending" }
                ?.also {
                    // Play Store app is installed, use it to rate our app
                    // allow only Play Store to intercept the intent
                    rateIntent.component = ComponentName(it.applicationInfo.packageName, it.name)
                    // don't open Play Store in the stack of our activity
                    rateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    rateIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    // make sure Play Store opens our app page, whatever it was doing before
                    rateIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(rateIntent)
                }
                // Play Store app not installed, open in web browser
                ?: startActivity(Intent(ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$appId")))
    }
}
