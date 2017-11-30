package com.belotron.weatherradarhr

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initOcr(applicationContext)
    }
}
