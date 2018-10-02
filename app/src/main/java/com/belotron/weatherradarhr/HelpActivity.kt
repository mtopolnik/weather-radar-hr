package com.belotron.weatherradarhr

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_help)
    }
}
