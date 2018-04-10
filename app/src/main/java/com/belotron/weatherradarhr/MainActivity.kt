package com.belotron.weatherradarhr

import android.app.Activity
import android.location.Location
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

private const val KEY_ACTIONBAR_VISIBLE = "actionbar-visible"

class MainActivity : Activity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "MainActivity.onCreate" }
        if (adsEnabled) {
            MobileAds.initialize(this, ADMOB_ID)
        }
        PreferenceManager.setDefaultValues(this, R.xml.preference_screen, false)
        rateMeRecordUsage()
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        if (fragmentManager.findFragmentById(R.id.radar_img_fragment) != null) {
            return
        }
        RadarImageFragment().also {
            fragmentManager.beginTransaction()
                    .add(R.id.radar_img_fragment, it)
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
        if (savedInstanceState.savedStateRecently && !savedInstanceState.getBoolean(KEY_ACTIONBAR_VISIBLE)) {
            actionBar.hide()
        }
    }

    override fun onBackPressed() {
        fragmentManager.findFragmentById(R.id.radar_img_fragment)
                ?.let { it as RadarImageFragment? }
                ?.takeIf { it.ds.isInFullScreen }
                ?.also {
                    it.exitFullScreen()
                    return
                }
        super.onBackPressed()
    }
}


fun adRequest(): AdRequest = AdRequest.Builder().run {
    setLocation(Location("custom").apply {
        latitude = 45.8
        longitude = 16.0
    })
    build()
}

