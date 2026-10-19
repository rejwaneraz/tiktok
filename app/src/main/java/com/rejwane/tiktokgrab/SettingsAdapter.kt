package com.rejwane.tiktokgrab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.RecyclerView

/** Dark, light or the phone's own setting — remembered, and applied before any screen draws. */
object Theme {

    const val DARK = 0
    const val LIGHT = 1
    const val SYSTEM = 2

    fun mode(context: Context) = stored(context).toIntOrNull() ?: DARK

    fun set(context: Context, chosen: Int) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("mode", chosen.toString()).apply()
        apply(context)
    }

    private fun stored(context: Context) =
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("mode", "$DARK") ?: "$DARK"

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(when (mode(context)) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }

    fun label(context: Context) = when (mode(context)) {
        LIGHT -> "Light"
        SYSTEM -> "Follow system"
        else -> "Dark"
    }
}

class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        Backup.restoreIfEmpty(this)
        Theme.apply(this)
        // Pictures saved by the older builds were plain .jpg and a gallery picked them up. Renaming
        // them is cheap, and it has to happen before the next media scan runs.
        Thread { runCatching { Store.hideExisting(this) } }.start()
    }
}

/** One TikTok-shaped settings list: grey section captions, then tappable rows with a value. */
class SettingsAdapter(private val rows: List<SettingsAdapter.Row>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class Row(val title: String = "", val value: String = "", val section: String = "",
                   val onClick: (() -> Unit)? = null)

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.settingTitle)
        val value: TextView = view.findViewById(R.id.settingValue)
    }

    class HeadHolder(view: View) : RecyclerView.ViewHolder(view) {
        val caption: TextView = view.findViewById(R.id.settingSection)
    }

    /** Section captions are folded into the list here, so callers just hand over rows. */
    private val flat: List<Row> by lazy {
        val out = ArrayList<Row>()
        var last = ""
        rows.forEach { row ->
            if (row.section != last) {
                out.add(Row(section = row.section))
                last = row.section
            }
            out.add(row)
        }
        out
    }

    override fun getItemViewType(position: Int) = if (flat[position].title.isBlank()) 1 else 0

    override fun getItemCount() = flat.size

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (type == 1) HeadHolder(inflater.inflate(R.layout.item_setting_head, parent, false))
        else RowHolder(inflater.inflate(R.layout.item_setting, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = flat[position]
        when (holder) {
            is HeadHolder -> holder.caption.text = row.section
            is RowHolder -> {
                holder.title.text = row.title
                holder.value.text = row.value
                holder.itemView.setOnClickListener { row.onClick?.invoke() }
            }
        }
    }
}
