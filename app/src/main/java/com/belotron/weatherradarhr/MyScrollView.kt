package com.belotron.weatherradarhr

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

class MyScrollView(context: Context, attrs: AttributeSet) : ScrollView(context, attrs) {
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        return super.onInterceptTouchEvent(e) || e.pointerCount > 1
    }
}
