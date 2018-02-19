package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.TextView
import com.belotron.weatherradarhr.Side.*
import com.google.android.gms.ads.MobileAds

class AboutDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.about, null)
        view.findViewById<TextView>(R.id.about_text_view).setText(R.string.about_text)
        if (!activity.adsEnabled()) {
            view.findViewById<TextView>(R.id.about_text_ads_disabled).visibility = VISIBLE
        }
        val gd = GestureDetector(activity, EasterEggDetector(view))
        view.setOnTouchListener { v, event ->  gd.onTouchEvent(event); true }
        return AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setIcon(R.mipmap.ic_launcher)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .create()
    }
}

private class EasterEggDetector(
        private val view: View
) : GestureDetector.SimpleOnGestureListener() {
    private val sequence = listOf(L, R, R, L, L, L, R, R, R, R)
    private var indexInSequence = 0

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val middle = view.width / 2
        val tapSide = if (e.x < middle) L else R
        MyLog.i { "Tap side: $tapSide" }
        if (tapSide == sequence[indexInSequence]) {
            indexInSequence++
            if (indexInSequence == sequence.size) {
                indexInSequence = 0
                invertAdsEnabled()
                MyLog.i { "Easter egg!" }
            }
        } else {
            indexInSequence = 0
        }
        return true
    }

    @SuppressLint("CommitPrefEdits")
    private fun invertAdsEnabled() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(view.context)
        with(prefs.edit()) {
            val adsEnabled = !prefs.getBoolean(KEY_ADS_ENABLED, true)
            putBoolean(KEY_ADS_ENABLED, adsEnabled)
            commit()
            val adsDisabledText = view.findViewById<View>(R.id.about_text_ads_disabled)
            if (adsEnabled) {
                adsDisabledText.visibility = GONE
                MobileAds.initialize(view.context, ADMOB_ID)
            } else {
                adsDisabledText.visibility = VISIBLE
            }
        }
    }
}

private enum class Side { L, R }
