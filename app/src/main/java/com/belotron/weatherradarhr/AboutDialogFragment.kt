package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.VISIBLE
import android.widget.TextView
import com.belotron.weatherradarhr.Side.*
import com.google.android.gms.ads.MobileAds
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine

const val TAG_ABOUT = "dialog_about"

suspend fun showAboutDialogFragment(activity: Activity) {
    suspendCoroutine<Unit> { cont ->
        AboutDialogFragment(cont).show(activity.fragmentManager, TAG_ABOUT)
    }
}

class AboutDialogFragment(
        private val continuation: Continuation<Unit>?
) : DialogFragment() {

    constructor(): this(null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val rootView = inflater.inflate(R.layout.about, null)
        val version =
                try { activity.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName }
                catch (e: Exception) { null } ?: "??"
        val textView = rootView.findViewById<TextView>(R.id.about_text_view).apply {
            text = getString(R.string.about_text, version)
        }
        if (!activity.adsEnabled()) {
            rootView.findViewById<TextView>(R.id.about_text_ads_disabled).visibility = VISIBLE
        }
        val gd = GestureDetector(activity, EasterEggDetector(rootView))
        textView.setOnTouchListener { v, event -> gd.onTouchEvent(event); true }
        return AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setIcon(R.mipmap.ic_launcher)
                .setView(rootView)
                .setPositiveButton(android.R.string.ok) { _, _ -> continuation?.resume(Unit) }
                .create()
    }
}

private class EasterEggDetector(
        private val view: View
) : GestureDetector.SimpleOnGestureListener() {
    private val sequence = listOf(L, R, R, L, L, L, R, R, R, R)
    private var indexInSequence = 0

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        val middle = view.width / 2
        val tapSide = if (e.x < middle) L else R
        info { "Tap side: $tapSide" }
        if (tapSide == sequence[indexInSequence]) {
            indexInSequence++
            if (indexInSequence == sequence.size) {
                indexInSequence = 0
                invertAdsEnabled()
                info { "Easter egg!" }
            }
        } else {
            indexInSequence = 0
        }
        return true
    }

    @SuppressLint("CommitPrefEdits")
    private fun invertAdsEnabled() {
        val enableAds = !view.context.adsEnabled()
        view.context.sharedPrefs.commitUpdate {
            putBoolean(KEY_ADS_ENABLED, enableAds)
        }
        view.findViewById<View>(R.id.about_text_ads_disabled).setVisible(!enableAds)
        if (enableAds) {
            MobileAds.initialize(view.context, ADMOB_ID)
        }
    }
}

private enum class Side { L, R }
