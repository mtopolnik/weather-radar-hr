package com.belotron.weatherradarhr

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import android.widget.TextView

class ViewLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_view_log)
        findViewById<TextView>(R.id.log_content).text = viewPrivateLog()
    }
}
