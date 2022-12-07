package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
        findViewById<Toolbar>(R.id.toolbar).also {
            setSupportActionBar(it)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val recyclerView = findViewById<RecyclerView>(R.id.add_remove_recycler).also {
            it.layoutManager = LinearLayoutManager(this)
        }
        items = mainPrefs.configuredRadarSources().toTypedArray()
        val adapter = ItemViewAdapter(recyclerView, items,
            resources.getColor(R.color.text_primary),
            resources.getColor(R.color.text_disabled)
        )
        recyclerView.adapter = adapter
        adapter.itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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
    val itemTouchHelper = ItemTouchHelper(ItemMoveCallback(this))

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEWTYPE_ITEM) R.layout.add_remove_recycler_item else R.layout.add_remove_divider,
            parent, false
        )
        val viewHolder = ItemViewHolder(itemView)
        viewHolder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper.startDrag(viewHolder)
                true
            } else {
                false
            }
        }
        return viewHolder
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
            itemView.findViewById<TextView?>(R.id.add_remove_item_text)?.apply { text = value }
        }
        get() = (itemView as? TextView)?.run { text.toString() }

    var textColor: Int
        set(value) {
            itemView.findViewById<TextView?>(R.id.add_remove_item_text)?.apply { setTextColor(value) }
        }
        get() = TODO("Implement if the need arises")

    val dragHandle: View = itemView.findViewById(R.id.add_remove_drag_handle)
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
