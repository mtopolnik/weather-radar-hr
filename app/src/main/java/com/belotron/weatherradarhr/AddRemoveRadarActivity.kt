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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "AddRemoveRadarActivity.onCreate" }
        setContentView(R.layout.activity_add_remove)
        val recyclerView = findViewById<RecyclerView>(R.id.add_remove_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
        }
        val adapter = ItemViewAdapter()
        recyclerView.adapter = adapter
        ItemTouchHelper(ItemMoveCallback(adapter)).attachToRecyclerView(recyclerView)
    }
}

class ItemViewAdapter : RecyclerView.Adapter<ItemViewHolder>() {
    val items = mutableListOf(*RadarSource.values())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val textView = LayoutInflater.from(parent.context).inflate(R.layout.recycler_item_add_remove, parent, false)
        return ItemViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.radarNameView.text = items[position].title
    }

    override fun getItemCount() = items.size

    fun onRowMoved(from: Int, to: Int) {
        val itemAtFromPos = items[from]
        val direction = to.compareTo(from)
        var newIndex = from
        info { "onRowMoved($from -> $to)" }
        while (newIndex != to) {
            val oldIndex = newIndex
            newIndex += direction
            info { "items[$oldIndex] = items[$newIndex]" }
            items[oldIndex] = items[newIndex]
        }
        info { "items[$to] = previous items[$from]" }
        items[to] = itemAtFromPos
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
