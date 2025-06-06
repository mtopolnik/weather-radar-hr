/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.belotron.weatherradarhr.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "MainActivity.onCreate" }
        PreferenceManager.setDefaultValues(this, R.xml.preference_screen, false)
        if (savedInstanceState?.savedStateRecently != true) {
            if (isFirstUse()) {
                mainPrefs.applyUpdate { setNewRadarIndicatorConsumedId(NEW_RADAR_INDICATOR_CURRENT_ID) }
            } else if (mainPrefs.newRadarIndicatorConsumedId != NEW_RADAR_INDICATOR_CURRENT_ID) {
                mainPrefs.ensureAllRadarSourcesAvailable()
            }
            recordAppUsage()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ActivityMainBinding.inflate(layoutInflater).root.also { root ->
            applyInsets(root)
            setContentView(root)
        }
        if (supportFragmentManager.findFragmentById(R.id.main_fragment) == null) {
            MainFragment().also {
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_fragment, it)
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

