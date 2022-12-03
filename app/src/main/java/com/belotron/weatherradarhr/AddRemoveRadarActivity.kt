package com.belotron.weatherradarhr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AddRemoveRadarActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "AddRemoveRadarActivity.onCreate" }
        setContentView(R.layout.activity_add_remove)
        val recyclerView = findViewById<RecyclerView>(R.id.add_remove_recycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = RadarNameAdapter()
    }
}

class RadarNameAdapter : RecyclerView.Adapter<RadarNameHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RadarNameHolder {
        val textView = LayoutInflater.from(parent.context).inflate(R.layout.recycler_item_add_remove, parent, false)
        return RadarNameHolder(textView)
    }

    override fun onBindViewHolder(holder: RadarNameHolder, position: Int) {
        holder.radarNameView.text = RadarSource.values()[position].name
    }

    override fun getItemCount() = RadarSource.values().size
}

class RadarNameHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val radarNameView = itemView.findViewById<TextView>(R.id.text_radar_name)!!
}
