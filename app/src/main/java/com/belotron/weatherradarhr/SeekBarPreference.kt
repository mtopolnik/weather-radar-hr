package com.belotron.weatherradarhr

import android.content.Context
import android.content.res.TypedArray
import android.preference.Preference
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener

class SeekBarPreference @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : Preference(context, attrs, defStyle), OnSeekBarChangeListener {
    init {
        layoutResource = R.layout.preference_seekbar
    }

    private var value: Int = 0
        set(newVal) {
            if (shouldPersist()) {
                persistInt(newVal)
            }
            if (newVal == field) {
                return
            }
            field = newVal
            notifyChanged()
        }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        value = if (restoreValue)
            getPersistedInt(value)
        else when (defaultValue) {
            is String -> defaultValue.toInt()
            is Int -> defaultValue
            else -> 0
        }
    }

    override fun onBindView(view: View) {
        super.onBindView(view)
        view.findViewById<SeekBar>(R.id.prefSeekbar).also {
            it.progress = value
            it.setOnSeekBarChangeListener(this)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            value = progress
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {}
    override fun onStopTrackingTouch(seekBar: SeekBar) {}
}
