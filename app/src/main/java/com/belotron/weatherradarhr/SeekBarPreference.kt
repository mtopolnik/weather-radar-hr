package com.belotron.weatherradarhr

import android.content.Context
import android.content.res.TypedArray
import android.preference.Preference
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView

private const val NS_BELOTRON = "http://belotron.com"

class SeekBarPreference @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : Preference(context, attrs, defStyle), OnSeekBarChangeListener {

    private var summary: String
    private lateinit var summaryView: TextView

    private val min: Int
    private val max: Int

    init {
        layoutResource = R.layout.preference_seekbar
        summary = super.getSummary()?.toString() ?: ""
        min = attrs?.getAttributeIntValue(NS_BELOTRON, "min", 0) ?: 0
        max = attrs?.getAttributeIntValue(NS_BELOTRON, "max", 0) ?: 0
    }

    private var isTrackingTouch: Boolean = false
    private var valueWhileTrackingTouch = 0

    private var value = 0
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
            getPersistedInt(value) else
            defaultValue as Int? ?: 0
    }

    override fun onBindView(view: View) {
        super.onBindView(view)
        view.findViewById<SeekBar>(R.id.prefSeekbar).also {
            it.max = max - min
            it.progress = value - min
            it.setOnSeekBarChangeListener(this)
        }
        summaryView = view.findViewById(android.R.id.summary)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    override fun getSummary(): CharSequence {
        val intValue = if (isTrackingTouch) valueWhileTrackingTouch else value
        return String.format(summary, if (summary.contains("%.1f")) intValue.toFloat() / 1000 else intValue)
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        val valueFromProgress = progress + min
        if (isTrackingTouch) {
            valueWhileTrackingTouch = valueFromProgress
            summaryView.text = getSummary()
        } else {
            value = valueFromProgress
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        isTrackingTouch = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        isTrackingTouch = false
        value = valueWhileTrackingTouch
    }
}
