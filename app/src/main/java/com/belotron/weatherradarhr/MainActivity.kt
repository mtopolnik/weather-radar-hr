package com.belotron.weatherradarhr

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.preference.PreferenceManager
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

private const val KEY_ACTIONBAR_VISIBLE = "actionbar-visible"

fun adRequest(): AdRequest = AdRequest.Builder().build()

class MainActivity : AppCompatActivity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "MainActivity.onCreate" }
        if (adsEnabled) {
            MobileAds.initialize(this, ADMOB_ID)
        }
        PreferenceManager.setDefaultValues(this, R.xml.preference_screen, false)
        recordAppUsage()
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        if (supportFragmentManager.findFragmentById(R.id.radar_img_fragment) != null) {
            return
        }
        RadarImageFragment().also {
            supportFragmentManager.beginTransaction()
                    .add(R.id.radar_img_fragment, it)
                    .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        info { "MainActivity.onSaveInstanceState" }
        super.onSaveInstanceState(outState)
        outState.recordSavingTime()
        outState.putBoolean(KEY_ACTIONBAR_VISIBLE, supportActionBar!!.isShowing)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        info { "MainActivity.onRestoreInstanceState" }
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.savedStateRecently && !savedInstanceState.getBoolean(KEY_ACTIONBAR_VISIBLE)) {
            supportActionBar!!.hide()
        }
    }

    override fun onBackPressed() {
        supportFragmentManager.findFragmentById(R.id.radar_img_fragment)
                ?.let { it as RadarImageFragment? }
                ?.takeIf { it.ds.isInFullScreen }
                ?.apply {
                    exitFullScreen()
                    return
                }
        super.onBackPressed()
    }
}

