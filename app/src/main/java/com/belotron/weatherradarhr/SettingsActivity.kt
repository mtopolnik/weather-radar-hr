package com.belotron.weatherradarhr

import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.support.v7.preference.PreferenceFragmentCompat
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_screen)
    }
}
