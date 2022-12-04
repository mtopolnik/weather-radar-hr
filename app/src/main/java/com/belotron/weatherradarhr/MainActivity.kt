package com.belotron.weatherradarhr

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "MainActivity.onCreate" }
        PreferenceManager.setDefaultValues(this, R.xml.preference_screen, false)
        recordAppUsage()
        setContentView(R.layout.activity_main)
        if (supportFragmentManager.findFragmentById(R.id.radar_img_fragment) == null) {
            RadarImageFragment().also {
                supportFragmentManager.beginTransaction()
                    .add(R.id.radar_img_fragment, it)
                    .commit()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        info { "MainActivity.onSaveInstanceState" }
        super.onSaveInstanceState(outState)
        outState.recordSavingTime()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        info { "MainActivity.onRestoreInstanceState" }
        super.onRestoreInstanceState(savedInstanceState)
    }
}

