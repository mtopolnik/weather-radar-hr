package com.belotron.weatherradarhr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AddRemoveRadarActivity: AppCompatActivity() {
    private lateinit var adapter: ItemViewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "AddRemoveRadarActivity.onCreate" }
        setContentView(R.layout.activity_add_remove)
        val recyclerView = findViewById<RecyclerView>(R.id.add_remove_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
        }
        adapter = ItemViewAdapter(mainPrefs.configuredRadarSources().toTypedArray())
        recyclerView.adapter = adapter
        ItemTouchHelper(ItemMoveCallback(adapter)).attachToRecyclerView(recyclerView)
    }

    override fun onPause() {
        super.onPause()
        info { "AddRemoveRadarActivity.onPause" }
        mainPrefs.applyUpdate {
            setConfiguredRadarSources(adapter.items.toList())
        }
    }
}

class ItemViewAdapter(
    val items: Array<RadarSource?>
) : RecyclerView.Adapter<ItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recycler_item_add_remove, parent, false)
        return ItemViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.radarNameView.text = items[position]?.run { title } ?: "==================="
    }

    override fun getItemCount() = items.size

    fun onRowMoved(from: Int, to: Int) {
        val movedItem = items[from]
        val direction = to.compareTo(from)
        var oldIndex = from
        while (oldIndex != to) {
            val newIndex = oldIndex
            oldIndex += direction
            items[newIndex] = items[oldIndex]
        }
        items[to] = movedItem
        notifyItemMoved(from, to)
    }
}

class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val radarNameView = itemView.findViewById<TextView>(R.id.add_remove_item_text)!!
}

class ItemMoveCallback(
    private val adapter: ItemViewAdapter
) : ItemTouchHelper.Callback() {

    override fun onMove(
        recyclerView: RecyclerView,
        origin: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onRowMoved(origin.adapterPosition, target.adapterPosition)
        return true
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun isItemViewSwipeEnabled() = false
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
}
