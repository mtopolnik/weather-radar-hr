package com.belotron.weatherradarhr

import android.app.Activity
import android.app.Fragment
import android.app.FragmentManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v13.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN

private const val KEY_SAVED_TIMESTAMP = "previous-orientation"

class MainActivity : Activity()  {
    internal var didRotate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyLog.i("onCreate MainActivity")
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        actionBar.hide()
        setContentView(R.layout.activity_main)
        val viewPager = findViewById<ViewPager>(R.id.my_pager)
        viewPager.adapter = FlipThroughRadarImages(fragmentManager)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        MyLog.i("onSaveInstanceState")
        super.onSaveInstanceState(outState)
        val timestamp = System.currentTimeMillis()
        outState.putLong(KEY_SAVED_TIMESTAMP, timestamp)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        MyLog.i("onRestoreInstanceState")
        super.onRestoreInstanceState(savedInstanceState)
        val restoredTimestamp = savedInstanceState.getLong(KEY_SAVED_TIMESTAMP)
        if (restoredTimestamp == 0L) {
            return
        }
        val timeDiff = System.currentTimeMillis() - restoredTimestamp
        didRotate = timeDiff < SECOND_IN_MILLIS
        MyLog.i("Time diff $timeDiff, did rotate? $didRotate")
    }

}

private class FlipThroughRadarImages internal constructor(fm: FragmentManager) : FragmentPagerAdapter(fm) {

    override fun getCount() = 1

    override fun getPageTitle(position: Int) = "Radar"

    override fun getItem(i: Int): Fragment {
        when (i) {
            0 -> return RadarImageFragment()
            else -> throw AssertionError("Invalid tab index: " + i)
        }
    }
}

