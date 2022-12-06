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

private const val VIEWTYPE_ITEM = 1
private const val VIEWTYPE_DIVIDER = 2

class AddRemoveRadarActivity: AppCompatActivity() {
    private lateinit var items: Array<RadarSource?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info { "AddRemoveRadarActivity.onCreate" }
        setContentView(R.layout.activity_add_remove)
        val recyclerView = findViewById<RecyclerView>(R.id.add_remove_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
        }
        items = mainPrefs.configuredRadarSources().toTypedArray()
        val adapter = ItemViewAdapter(recyclerView, items,
            resources.getColor(R.color.text_primary),
            resources.getColor(R.color.text_disabled)
        )
        recyclerView.adapter = adapter
        ItemTouchHelper(ItemMoveCallback(adapter)).attachToRecyclerView(recyclerView)
    }

    override fun onPause() {
        super.onPause()
        info { "AddRemoveRadarActivity.onPause" }
        mainPrefs.applyUpdate {
            setConfiguredRadarSources(items.toList())
        }
    }
}

class ItemViewAdapter(
    private val recyclerView: RecyclerView,
    private val items: Array<RadarSource?>,
    private var textColorEnabled: Int,
    private var textColorDisabled: Int
) : RecyclerView.Adapter<ItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEWTYPE_ITEM) R.layout.add_remove_recycler_item else R.layout.add_remove_divider,
            parent, false
        )
        return ItemViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        items[position]?.also { holder.text = it.title }
        val positionOfSeparator = items.indexOfFirst { it == null }
        holder.textColor = if (holder.adapterPosition <= positionOfSeparator) textColorEnabled else textColorDisabled

    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] != null) VIEWTYPE_ITEM else VIEWTYPE_DIVIDER

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
        var textColor = textColorEnabled
        items.indices.forEach { i ->
            if (items[i] == null) {
                textColor = textColorDisabled
            } else {
                (recyclerView.findViewHolderForAdapterPosition(i) as ItemViewHolder)
                    .textColor = textColor
            }
        }
    }
}

class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    var text: String?
        set(value) {
            (itemView as? TextView)?.apply { text = value }
        }
        get() = (itemView as? TextView)?.run { text.toString() }

    var textColor: Int
        set(value) {
            (itemView as? TextView)?.apply { setTextColor(value) }
        }
        get() = throw UnsupportedOperationException("Getting text color is not supported")
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
