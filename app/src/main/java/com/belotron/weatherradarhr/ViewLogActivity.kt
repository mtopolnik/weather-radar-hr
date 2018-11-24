package com.belotron.weatherradarhr

import android.os.Bundle
import android.view.View.FOCUS_DOWN
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ViewLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_view_log)
        findViewById<TextView>(R.id.log_content).text = appLogString()
        findViewById<ScrollView>(R.id.log_scroller).apply { post { fullScroll(FOCUS_DOWN) } }
    }
}
