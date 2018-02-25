package com.belotron.weatherradarhr

import android.app.Activity
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN

private const val KEY_ACTIONBAR_VISIBLE = "actionbar-visible"

class MainActivity : Activity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "MainActivity.onCreate" }
        PreferenceManager.setDefaultValues(this, R.xml.preference_screen, false)
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
        info { "MainActivity.onSaveInstanceState" }
        super.onSaveInstanceState(outState)
        outState.recordSavingTime()
        outState.putBoolean(KEY_ACTIONBAR_VISIBLE, actionBar.isShowing)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        info { "MainActivity.onRestoreInstanceState" }
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.wasFastResume && !savedInstanceState.getBoolean(KEY_ACTIONBAR_VISIBLE)) {
            actionBar.hide()
        }
    }

    override fun onBackPressed() {
        fragmentManager.findFragmentById(R.id.radar_img_fragment)
                ?.let { it as RadarImageFragment? }
                ?.takeIf { it.isInFullScreen }
                ?.also {
                    it.exitFullScreen()
                    return
                }
        super.onBackPressed()
    }
}

