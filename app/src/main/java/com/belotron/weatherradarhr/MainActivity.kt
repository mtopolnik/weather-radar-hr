package com.belotron.weatherradarhr

import android.app.Activity
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import com.google.android.gms.ads.MobileAds

private const val KEY_INSTANCE_STATE_SAVED_AT = "instance-state-saved-at"
private const val KEY_ACTIONBAR_VISIBLE = "actionbar-visible"


class MainActivity : Activity()  {
    var isFullScreenMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyLog.i { "MainActivity.onCreate" }
        if (adsEnabled()) {
            MobileAds.initialize(this, ADMOB_ID)
        }
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        if (fragmentManager.findFragmentById(R.id.radar_img_fragment) == null) {
            val newFragment = RadarImageFragment()
            fragmentManager.beginTransaction()
                    .add(R.id.radar_img_fragment, newFragment)
                    .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        MyLog.i { "MainActivity.onSaveInstanceState" }
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_INSTANCE_STATE_SAVED_AT, System.currentTimeMillis())
        outState.putBoolean(KEY_ACTIONBAR_VISIBLE, actionBar.isShowing)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        MyLog.i { "MainActivity.onRestoreInstanceState" }
        super.onRestoreInstanceState(savedInstanceState)
        val stateSavedTimestamp = savedInstanceState.getLong(KEY_INSTANCE_STATE_SAVED_AT)
        if (stateSavedTimestamp == 0L) {
            return
        }
        val timeDiff = System.currentTimeMillis() - stateSavedTimestamp
        val didRotate = timeDiff < SECOND_IN_MILLIS
        MyLog.i { "Time diff $timeDiff, did rotate? $didRotate" }
        if (didRotate && !savedInstanceState.getBoolean(KEY_ACTIONBAR_VISIBLE)) {
            actionBar.hide()
        }
    }

    override fun onBackPressed() {
        fragmentManager.findFragmentById(R.id.radar_img_fragment)
                ?.takeIf { isFullScreenMode }
                ?.let { it as RadarImageFragment? }
                ?.also {
                    it.exitFullScreen()
                    return
                }
        super.onBackPressed()
    }
}

