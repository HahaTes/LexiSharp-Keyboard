package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtherSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_other_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_other_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val etP1 = findViewById<EditText>(R.id.etPunct1)
    val etP2 = findViewById<EditText>(R.id.etPunct2)
    val etP3 = findViewById<EditText>(R.id.etPunct3)
    val etP4 = findViewById<EditText>(R.id.etPunct4)

    etP1.setText(prefs.punct1)
    etP2.setText(prefs.punct2)
    etP3.setText(prefs.punct3)
    etP4.setText(prefs.punct4)

    fun EditText.bind(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
      })
    }

    etP1.bind { prefs.punct1 = it }
    etP2.bind { prefs.punct2 = it }
    etP3.bind { prefs.punct3 = it }
    etP4.bind { prefs.punct4 = it }

    // ---- SyncClipboard 设置 ----
    val switchSync = findViewById<MaterialSwitch>(R.id.switchSyncClipboard)
    val layoutSync = findViewById<View>(R.id.layoutSyncClipboard)
    val etServer = findViewById<TextInputEditText>(R.id.etScServerBase)
    val etUser = findViewById<TextInputEditText>(R.id.etScUsername)
    val etPass = findViewById<TextInputEditText>(R.id.etScPassword)
    val switchAutoPull = findViewById<MaterialSwitch>(R.id.switchScAutoPull)
    val etInterval = findViewById<TextInputEditText>(R.id.etScPullInterval)
    val btnTestPull = findViewById<MaterialButton>(R.id.btnScTestPull)
    val btnProjectHome = findViewById<MaterialButton>(R.id.btnScProjectHome)

    fun refreshSyncVisibility() {
      layoutSync.visibility = if (switchSync.isChecked) View.VISIBLE else View.GONE
    }

    // 初始化 UI 值
    switchSync.isChecked = prefs.syncClipboardEnabled
    etServer.setText(prefs.syncClipboardServerBase)
    etUser.setText(prefs.syncClipboardUsername)
    etPass.setText(prefs.syncClipboardPassword)
    switchAutoPull.isChecked = prefs.syncClipboardAutoPullEnabled
    etInterval.setText(prefs.syncClipboardPullIntervalSec.toString())
    refreshSyncVisibility()

    switchSync.setOnCheckedChangeListener { _, checked ->
      prefs.syncClipboardEnabled = checked
      refreshSyncVisibility()
    }
    switchAutoPull.setOnCheckedChangeListener { _, checked ->
      prefs.syncClipboardAutoPullEnabled = checked
    }

    etServer.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardServerBase = s?.toString() ?: "" }
    })
    etUser.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardUsername = s?.toString() ?: "" }
    })
    etPass.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardPassword = s?.toString() ?: "" }
    })

    etInterval.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        val v = (s?.toString() ?: "").trim()
        val sec = v.toIntOrNull()?.coerceIn(1, 600)
        if (sec != null) prefs.syncClipboardPullIntervalSec = sec
      }
    })

    btnTestPull.setOnClickListener {
      // 仅用于验证配置：执行一次 GET，并不写入系统剪贴板
      val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, lifecycleScope)
      lifecycleScope.launch(Dispatchers.IO) {
        val (ok, _) = try { mgr.pullNow(updateClipboard = false) } catch (_: Throwable) { false to null }
        withContext(Dispatchers.Main) {
          if (ok) {
            Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_test_success), Toast.LENGTH_SHORT).show()
          } else {
            Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_test_failed), Toast.LENGTH_SHORT).show()
          }
        }
      }
    }

    btnProjectHome.setOnClickListener {
      try {
        val uri = android.net.Uri.parse("https://github.com/Jeric-X/SyncClipboard")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        startActivity(intent)
      } catch (_: Throwable) {
        Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_open_browser_failed), Toast.LENGTH_SHORT).show()
      }
    }
  }
}
