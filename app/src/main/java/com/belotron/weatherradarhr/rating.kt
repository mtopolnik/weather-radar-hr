package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.Intent.*
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.view.LayoutInflater
import android.widget.Button
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity

private const val DAYS_UNTIL_PROMPT = 2
private const val USES_UNTIL_PROMPT = 4
private const val DAYS_UNTIL_REPEAT_PROMPT = 1
private const val USES_UNTIL_REPEAT_PROMPT = 3

private const val TAG_RATE_ME: String = "rate_me_fragment"
private const val PREFS_NAME = "rate_app_reminder"
private const val KEY_DONT_SHOW_AGAIN = "dont_show_again"
private const val KEY_USE_COUNT = "use_count"
private const val KEY_TIMESTAMP_FIRST_USE = "timestamp_first_use"
private const val KEY_USE_COUNT_WHEN_PROMPTED = "use_count_when_prompted"
private const val KEY_TIMESTAMP_WHEN_PROMPTED = "timestamp_prompted"

fun FragmentActivity.openRateMeDialog() {
    RateMeDialogFragment().show(supportFragmentManager, TAG_RATE_ME)
}

fun Context.openAppRating() {
    val rateIntent = Intent(ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    packageManager.queryIntentActivities(rateIntent, 0)
            .map { it.activityInfo }
            .find { it.applicationInfo.packageName == "com.android.vending" }
            ?.also {
                // Play Store app is installed, use it to rate our app
                rateIntent.component = ComponentName(it.applicationInfo.packageName, it.name)
                rateIntent.addFlags(
                        // don't open Play Store in the stack of our activity
                        FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        // make sure Play Store opens our app page, whatever it was doing before
                        or FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(rateIntent)
            }
            // Play Store app not installed, open in web browser
            ?: startActivity(Intent(ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
}

fun Context.recordAppUsage() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    prefs.applyUpdate {
        prefs.getLong(KEY_TIMESTAMP_FIRST_USE, 0).takeIf { it == 0L } ?.also {
            putLong(KEY_TIMESTAMP_FIRST_USE, System.currentTimeMillis())
        }
        (1 + prefs.getLong(KEY_USE_COUNT, 0)).also {
            putLong(KEY_USE_COUNT, it)
            info { "Recording new usage, count: $it" }
        }
    }
}

fun FragmentActivity.maybeAskToRate() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val dayInMillis = DAY_IN_MILLIS
    prefs.applyUpdate {
        val useCount = prefs.getLong(KEY_USE_COUNT, 0)
        info { "useCount $useCount" }
        if (prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false)) {
            info { "Don't show again" }
            return
        }
        val now = System.currentTimeMillis()
        val timestampFirstUse = prefs.getLong(KEY_TIMESTAMP_FIRST_USE, now)
        info { "now $now, timestampFirstUse $timestampFirstUse"}
        if (useCount < USES_UNTIL_PROMPT || now < timestampFirstUse + DAYS_UNTIL_PROMPT * dayInMillis) {
            return
        }
        val timestampWhenPrompted = prefs.getLong(KEY_TIMESTAMP_WHEN_PROMPTED, Long.MIN_VALUE)
        val useCountWhenPrompted = prefs.getLong(KEY_USE_COUNT_WHEN_PROMPTED, Long.MIN_VALUE)
        info { "timestampWhenPrompted $timestampWhenPrompted, useCountWhenPrompted $useCountWhenPrompted"}
        if (useCount < useCountWhenPrompted + USES_UNTIL_REPEAT_PROMPT
                || now < timestampWhenPrompted + DAYS_UNTIL_REPEAT_PROMPT * dayInMillis
        ) {
            return
        }
        putLong(KEY_TIMESTAMP_WHEN_PROMPTED, now)
        putLong(KEY_USE_COUNT_WHEN_PROMPTED, useCount)
        info { "Ask to rate" }
        openRateMeDialog()
    }
}

class RateMeDialogFragment : DialogFragment() {
    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val rootView = LayoutInflater.from(activity).inflate(R.layout.rate_me, null).apply {
            findViewById<Button>(R.id.rateme_yes).setOnClickListener {
                activity.openAppRating()
                dismissAllowingStateLoss()
                dontShowAgain()
            }
            findViewById<Button>(R.id.rateme_later).setOnClickListener {
                dismissAllowingStateLoss()
            }
            findViewById<Button>(R.id.rateme_already_did).setOnClickListener {
                dismissAllowingStateLoss()
                dontShowAgain()
            }
            findViewById<Button>(R.id.rateme_no).setOnClickListener {
                dismissAllowingStateLoss()
                dontShowAgain()
            }
        }
        return AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle(R.string.rateme_title)
                .setIcon(R.mipmap.ic_launcher)
                .setView(rootView)
                .create()
    }

    private fun dontShowAgain() {
        requireActivity().getSharedPreferences(PREFS_NAME, MODE_PRIVATE).applyUpdate {
            putBoolean(KEY_DONT_SHOW_AGAIN, true)
        }
    }
}
