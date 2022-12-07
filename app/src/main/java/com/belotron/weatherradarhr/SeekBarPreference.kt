package com.belotron.weatherradarhr

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View.INVISIBLE
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.R.id.icon_frame

private const val NS_BELOTRON = "http://belotron.com"

class SeekBarPreference
constructor(context: Context, attrs: AttributeSet)
    : Preference(context, attrs), OnSeekBarChangeListener
{
    private lateinit var summaryView: TextView

    private var summary: String = super.getSummary()?.toString() ?: ""
    private val min: Int = attrs.getAttributeIntValue(NS_BELOTRON, "min", 0)
    private val max: Int = attrs.getAttributeIntValue(NS_BELOTRON, "max", 0)

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

    override fun onSetInitialValue(defaultValue: Any?) {
        value = if (defaultValue == null)
            getPersistedInt(value) else
            defaultValue as Int? ?: 0
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(icon_frame)?.visibility = INVISIBLE
        fun findSeekBar() = holder.findViewById(R.id.pref_seekbar) as SeekBar?
        if (findSeekBar() != null) {
            return
        }
        val summary = holder.findViewById(android.R.id.summary) ?: return
        val summaryParent = summary.parent as? ViewGroup ?: return
        val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        layoutInflater.inflate(R.layout.preference_seekbar, summaryParent)
        findSeekBar()!!.also {
            it.max = max - min
            it.progress = value - min
            it.setOnSeekBarChangeListener(this)
        }
        summaryView = summary as TextView
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
