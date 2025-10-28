package com.brycewg.asrkb.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.AsrHistoryStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别历史页面
 * - 支持搜索、按供应商/来源筛选
 * - 支持单选/多选删除
 * - 单条一键复制
 * - 分组：2小时内/今天/近一周/近一个月/更早
 */
class AsrHistoryActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "AsrHistoryActivity"
  }

  private lateinit var store: AsrHistoryStore
  private lateinit var adapter: HistoryAdapter
  private lateinit var rv: RecyclerView
  private lateinit var etSearch: TextInputEditText
  private lateinit var tvEmpty: TextView

  private var allRecords: List<AsrHistoryStore.AsrHistoryRecord> = emptyList()
  private var filtered: List<AsrHistoryStore.AsrHistoryRecord> = emptyList()
  private var activeVendorIds: Set<String> = emptySet() // 为空表示不过滤
  private var activeSources: Set<String> = emptySet() // "ime"/"floating"；为空表示不过滤

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_asr_history)

    store = AsrHistoryStore(this)

    val tb = findViewById<MaterialToolbar>(R.id.toolbar)
    tb.setTitle(R.string.title_asr_history)
    tb.setNavigationOnClickListener { finish() }
    try {
      setSupportActionBar(tb)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to setSupportActionBar", e)
      // 兜底：手动管理菜单
      tb.inflateMenu(R.menu.menu_asr_history)
      tb.setOnMenuItemClickListener { onOptionsItemSelected(it) }
    }

    rv = findViewById(R.id.rvList)
    rv.layoutManager = LinearLayoutManager(this)
    adapter = HistoryAdapter(
      onCopy = { text -> copyToClipboard(text) },
      onSelectionChanged = { updateToolbarTitleWithSelection() }
    )
    rv.adapter = adapter

    etSearch = findViewById(R.id.etSearch)
    etSearch.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { applyFilterAndRender() }
    })

    tvEmpty = findViewById(R.id.tvEmpty)

    loadData()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_asr_history, menu)
    updateMenuVisibility(menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    updateMenuVisibility(menu)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.action_filter -> { showFilterDialog(); true }
      R.id.action_select_all -> { adapter.selectAll(true); updateToolbarTitleWithSelection(); true }
      R.id.action_clear_selection -> { adapter.selectAll(false); updateToolbarTitleWithSelection(); true }
      R.id.action_delete_selected -> { confirmDeleteSelected(); true }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun updateMenuVisibility(menu: Menu) {
    val anySelected = adapter.getSelectedCount() > 0
    menu.findItem(R.id.action_delete_selected)?.isVisible = anySelected
    menu.findItem(R.id.action_clear_selection)?.isVisible = anySelected
    menu.findItem(R.id.action_select_all)?.isVisible = !anySelected && adapter.hasData()
    menu.findItem(R.id.action_filter)?.isVisible = true
  }

  private fun updateToolbarTitleWithSelection() {
    try {
      invalidateOptionsMenu()
      val tb = findViewById<MaterialToolbar>(R.id.toolbar)
      val sel = adapter.getSelectedCount()
      tb.subtitle = if (sel > 0) "$sel" else null
    } catch (e: Exception) {
      Log.w(TAG, "Failed to update subtitle", e)
    }
  }

  private fun showFilterDialog() {
    val vendors = AsrVendor.values().toList()
    val vendorNames = vendors.map { getVendorName(it) }.toTypedArray()
    val vendorChecked = vendors.map { activeVendorIds.isNotEmpty() && activeVendorIds.contains(it.id) }.toBooleanArray()

    val sources = arrayOf("ime", "floating")
    val sourceNames = arrayOf(getString(R.string.source_ime), getString(R.string.source_floating))
    val sourceChecked = sources.map { activeSources.isNotEmpty() && activeSources.contains(it) }.toBooleanArray()

    val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
    // 采用双对话框简化：先选供应商再选来源
    AlertDialog.Builder(this)
      .setTitle(getString(R.string.dialog_filter_title) + " - " + getString(R.string.label_vendor))
      .setMultiChoiceItems(vendorNames, vendorChecked) { _, which, isChecked ->
        vendorChecked[which] = isChecked
      }
      .setPositiveButton(R.string.dialog_filter_ok) { dlg, _ ->
        val selVendorIds = vendors.filterIndexed { idx, _ -> vendorChecked[idx] }.map { it.id }.toSet()

        // 来源子对话框
        AlertDialog.Builder(this)
          .setTitle(getString(R.string.dialog_filter_title) + " - " + getString(R.string.label_source))
          .setMultiChoiceItems(sourceNames, sourceChecked) { _, which, isChecked ->
            sourceChecked[which] = isChecked
          }
          .setPositiveButton(R.string.dialog_filter_ok) { _, _ ->
            activeVendorIds = if (selVendorIds.isEmpty()) emptySet() else selVendorIds
            val selSources = sources.filterIndexed { idx, _ -> sourceChecked[idx] }.toSet()
            activeSources = if (selSources.isEmpty()) emptySet() else selSources
            applyFilterAndRender()
          }
          .setNegativeButton(R.string.dialog_filter_cancel, null)
          .show()

        dlg.dismiss()
      }
      .setNegativeButton(R.string.dialog_filter_cancel, null)
      .show()
  }

  private fun confirmDeleteSelected() {
    val ids = adapter.getSelectedIds()
    if (ids.isEmpty()) return
    AlertDialog.Builder(this)
      .setTitle(R.string.dialog_delete_selected_title)
      .setMessage(getString(R.string.dialog_delete_selected_msg, ids.size))
      .setPositiveButton(R.string.dialog_filter_ok) { _, _ ->
        val deleted = try { store.deleteByIds(ids) } catch (e: Exception) { Log.e(TAG, "deleteByIds failed", e); 0 }
        Toast.makeText(this, getString(R.string.toast_deleted, deleted), Toast.LENGTH_SHORT).show()
        adapter.selectAll(false)
        loadData()
      }
      .setNegativeButton(R.string.dialog_filter_cancel, null)
      .show()
  }

  private fun copyToClipboard(text: String) {
    try {
      val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("ASR", text))
      Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Log.e(TAG, "copyToClipboard failed", e)
    }
  }

  private fun loadData() {
    allRecords = try { store.listAll() } catch (e: Exception) { Log.e(TAG, "listAll failed", e); emptyList() }
    applyFilterAndRender()
  }

  private fun applyFilterAndRender() {
    val q = etSearch.text?.toString()?.trim().orEmpty()
    filtered = allRecords.filter { r ->
      val okVendor = activeVendorIds.isEmpty() || activeVendorIds.contains(r.vendorId)
      val okSrc = activeSources.isEmpty() || activeSources.contains(r.source)
      val okText = q.isEmpty() || r.text.contains(q, ignoreCase = true)
      okVendor && okSrc && okText
    }
    val rows = buildRows(filtered)
    adapter.submit(rows)
    tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    updateToolbarTitleWithSelection()
  }

  private fun getVendorName(v: AsrVendor): String {
    return when (v) {
      AsrVendor.Volc -> getString(R.string.vendor_volc)
      AsrVendor.SiliconFlow -> getString(R.string.vendor_sf)
      AsrVendor.ElevenLabs -> getString(R.string.vendor_eleven)
      AsrVendor.OpenAI -> getString(R.string.vendor_openai)
      AsrVendor.DashScope -> getString(R.string.vendor_dashscope)
      AsrVendor.Gemini -> getString(R.string.vendor_gemini)
      AsrVendor.Soniox -> getString(R.string.vendor_soniox)
      AsrVendor.SenseVoice -> getString(R.string.vendor_sensevoice)
    }
  }

  private fun buildRows(list: List<AsrHistoryStore.AsrHistoryRecord>): List<Row> {
    val now = System.currentTimeMillis()
    val startOfToday = java.util.Calendar.getInstance().apply {
      timeInMillis = now
      set(java.util.Calendar.HOUR_OF_DAY, 0)
      set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val twoHoursMs = 2 * 60 * 60 * 1000L
    val weekMs = 7 * 24 * 60 * 60 * 1000L
    val monthMs = 30 * 24 * 60 * 60 * 1000L

    val rows = mutableListOf<Row>()
    fun addSection(titleRes: Int, items: List<AsrHistoryStore.AsrHistoryRecord>) {
      if (items.isNotEmpty()) {
        rows.add(Row.Header(getString(titleRes)))
        items.forEach { rows.add(Row.Item(it)) }
      }
    }

    val s2h = list.filter { it.timestamp >= now - twoHoursMs }
    val sToday = list.filter { it.timestamp in startOfToday..(now - twoHoursMs) }
    val s7d = list.filter { it.timestamp in (now - weekMs)..(startOfToday - 1) }
    val s30d = list.filter { it.timestamp in (now - monthMs)..(now - weekMs - 1) }
    val older = list.filter { it.timestamp < now - monthMs }

    addSection(R.string.history_section_2h, s2h)
    addSection(R.string.history_section_today, sToday)
    addSection(R.string.history_section_7d, s7d)
    addSection(R.string.history_section_30d, s30d)
    addSection(R.string.history_section_older, older)
    return rows
  }

  // ================= Adapter =================
  private sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val rec: AsrHistoryStore.AsrHistoryRecord, var selected: Boolean = false) : Row()
  }

  private class HistoryDiff(
    private val old: List<Row>,
    private val new: List<Row>
  ) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
      val a = old[oldItemPosition]
      val b = new[newItemPosition]
      return if (a is Row.Header && b is Row.Header) a.title == b.title
      else if (a is Row.Item && b is Row.Item) a.rec.id == b.rec.id
      else false
    }
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
      old[oldItemPosition] == new[newItemPosition]
  }

  private inner class HistoryAdapter(
    private val onCopy: (String) -> Unit,
    private val onSelectionChanged: () -> Unit
  ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val TYPE_HEADER = 1
    private val TYPE_ITEM = 2
    private var rows: MutableList<Row> = mutableListOf()

    fun submit(list: List<Row>) {
      val diff = HistoryDiff(rows, list)
      val result = DiffUtil.calculateDiff(diff)
      rows.clear()
      rows.addAll(list)
      result.dispatchUpdatesTo(this)
    }

    fun getSelectedIds(): Set<String> = rows.mapNotNull { (it as? Row.Item)?.takeIf { it.selected }?.rec?.id }.toSet()
    fun getSelectedCount(): Int = rows.count { (it as? Row.Item)?.selected == true }
    fun hasData(): Boolean = rows.any { it is Row.Item }
    fun selectAll(flag: Boolean) {
      rows.forEach { (it as? Row.Item)?.let { it.selected = flag } }
      notifyDataSetChanged()
      onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
      is Row.Header -> TYPE_HEADER
      is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      return if (viewType == TYPE_HEADER) {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_header, parent, false)
        HeaderVH(v)
      } else {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_asr_history, parent, false)
        ItemVH(v)
      }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      when (val row = rows[position]) {
        is Row.Header -> (holder as HeaderVH).bind(row)
        is Row.Item -> (holder as ItemVH).bind(row)
      }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
      private val tv = v.findViewById<TextView>(R.id.tvHeader)
      fun bind(row: Row.Header) {
        tv.text = row.title
      }
    }

    inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
      private val tvTimestamp = v.findViewById<TextView>(R.id.tvTimestamp)
      private val tvText = v.findViewById<TextView>(R.id.tvText)
      private val tvMeta = v.findViewById<TextView>(R.id.tvMeta)
      private val cb = v.findViewById<CheckBox>(R.id.cbSelect)
      private val btnCopy = v.findViewById<MaterialButton>(R.id.btnCopy)

      private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

      fun bind(row: Row.Item) {
        val r = row.rec
        tvTimestamp.text = fmt.format(Date(r.timestamp))
        tvText.text = r.text
        val vendor = mapVendorName(r.vendorId)
        val source = mapSourceFullName(r.source)
        val ai = if (r.aiProcessed) itemView.context.getString(R.string.ai_processed_yes) else itemView.context.getString(R.string.ai_processed_no)
        val charsPart = "${r.charCount}${itemView.context.getString(R.string.unit_chars)}"
        val durPart = itemView.context.getString(R.string.meta_cost_seconds, r.audioMs / 1000.0)
        tvMeta.text = listOf(vendor, source, ai, charsPart, durPart).joinToString("·")

        cb.visibility = if (getSelectedCount() > 0) View.VISIBLE else View.GONE
        cb.isChecked = row.selected
        cb.setOnCheckedChangeListener { _, isChecked ->
          row.selected = isChecked
          onSelectionChanged()
        }

        itemView.setOnLongClickListener {
          val before = getSelectedCount()
          row.selected = !row.selected
          val after = getSelectedCount()
          if ((before == 0 && after > 0) || (before > 0 && after == 0)) {
            notifyDataSetChanged()
          } else {
            notifyItemChanged(bindingAdapterPosition)
          }
          onSelectionChanged()
          true
        }
        itemView.setOnClickListener {
          val beforeAny = getSelectedCount() > 0
          if (beforeAny) {
            val before = getSelectedCount()
            row.selected = !row.selected
            val after = getSelectedCount()
            if ((before == 0 && after > 0) || (before > 0 && after == 0)) {
              notifyDataSetChanged()
            } else {
              notifyItemChanged(bindingAdapterPosition)
            }
            onSelectionChanged()
          }
        }

        btnCopy.setOnClickListener { onCopy(r.text) }
      }

      private fun mapVendorName(id: String): String = try {
        val v = AsrVendor.fromId(id)
        when (v) {
          AsrVendor.Volc -> itemView.context.getString(R.string.vendor_volc)
          AsrVendor.SiliconFlow -> itemView.context.getString(R.string.vendor_sf)
          AsrVendor.ElevenLabs -> itemView.context.getString(R.string.vendor_eleven)
          AsrVendor.OpenAI -> itemView.context.getString(R.string.vendor_openai)
          AsrVendor.DashScope -> itemView.context.getString(R.string.vendor_dashscope)
          AsrVendor.Gemini -> itemView.context.getString(R.string.vendor_gemini)
          AsrVendor.Soniox -> itemView.context.getString(R.string.vendor_soniox)
          AsrVendor.SenseVoice -> itemView.context.getString(R.string.vendor_sensevoice)
        }
      } catch (e: Exception) {
        id
      }

      private fun mapSourceFullName(src: String): String =
        if (src == "floating") itemView.context.getString(R.string.source_floating_full)
        else itemView.context.getString(R.string.source_ime_full)
    }
  }
}
