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
import android.view.LayoutInflater
import android.widget.Button

private const val DAYS_UNTIL_PROMPT = 0
private const val LAUNCHES_UNTIL_PROMPT = 0

private const val TAG_RATE_ME: String = "rate_me_fragment"
private const val PREFS_NAME = "rate_app_reminder"
private const val KEY_DONT_SHOW_AGAIN = "dont_show_again"
private const val KEY_LAUNCH_COUNT = "launch_count"
private const val KEY_FIRST_LAUNCH = "date_firstlaunch"

fun Activity.maybeAskToRate() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    prefs.commitUpdate {
        clear()
    }
    val fragmentManager = fragmentManager
    prefs.applyUpdate {
        val now = System.currentTimeMillis()
        val launchCount = (prefs.getLong(KEY_LAUNCH_COUNT, 0) + 1).also {
            putLong(KEY_LAUNCH_COUNT, it)
        }
        val firstLaunchTimestamp = prefs.getLong(KEY_FIRST_LAUNCH, now).also {
            if (it == now) {
                putLong(KEY_FIRST_LAUNCH, it)
            }
        }
        if (prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false)) {
            return
        }
        if (launchCount >= LAUNCHES_UNTIL_PROMPT && now >= firstLaunchTimestamp + DAYS_UNTIL_PROMPT * DAY_IN_MILLIS) {
            RateMeDialogFragment().show(fragmentManager, TAG_RATE_ME)
        }
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
