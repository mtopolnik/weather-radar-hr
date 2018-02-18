package com.belotron.weatherradarhr

import android.app.Activity
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN

private const val KEY_INSTANCE_STATE_SAVED_AT = "instance-state-saved-at"
private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_ACTIONBAR_VISIBLE = "actionbar-visible"
const val RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS = 10L * 60 * 1000 // 10 minutes

var lastReloadedTimestamp = 0L

class MainActivity : Activity()  {
    var isFullScreenMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyLog.i { "MainActivity.onCreate" }
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
        outState.putLong(KEY_LAST_RELOADED_TIMESTAMP, lastReloadedTimestamp)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        MyLog.i { "MainActivity.onRestoreInstanceState" }
        super.onRestoreInstanceState(savedInstanceState)
        lastReloadedTimestamp = savedInstanceState.getLong(KEY_LAST_RELOADED_TIMESTAMP)
        val restoredTimestamp = savedInstanceState.getLong(KEY_INSTANCE_STATE_SAVED_AT)
        if (restoredTimestamp == 0L) {
            return
        }
        val timeDiff = System.currentTimeMillis() - restoredTimestamp
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

