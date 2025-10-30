package com.brycewg.asrkb

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub

/**
 * 将 Pro 专属 UI 动态注入到主工程预留的插槽中。
 * 注意：不得在此处引用仅存在于 pro 源集的资源常量或类。
 */
object ProUiInjector {
  private const val TAG = "ProUiInjector"

  /**
   * 备份设置页注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_backup
   * - pro 侧提供布局：res/layout/pro_backup_auto.xml（包含自定义 View/逻辑）
   */
  fun injectIntoBackupSettings(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_backup_auto", "layout", pkg)
      if (layoutId == 0) return

      val stubId = res.getIdentifier("pro_inject_stub_backup", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)

      if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else {
        val containerId = res.getIdentifier("pro_inject_slot_backup", "id", pkg)
        val container = if (containerId != 0) root.findViewById<ViewGroup?>(containerId) else null
        if (container != null) {
          inflater.inflate(layoutId, container, true)
        } else if (root is ViewGroup) {
          inflater.inflate(layoutId, root, true)
        }
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject: ${t.message}")
    }
  }

  /**
   * ASR 设置页注入：
   * - main 布局提供 ViewStub：@id/pro_inject_stub_asr
   * - pro 侧提供布局：res/layout/pro_asr_settings_extra.xml（包含入口按钮）
   * - 按钮 ID 约定：@id/btn_pro_asr_context，点击触发隐式 Action：com.brycewg.asrkb.pro.SHOW_ASR_CONTEXT
   */
  fun injectIntoAsrSettings(activity: Activity, root: View) {
    if (!Edition.isPro) return
    val res = activity.resources
    val pkg = activity.packageName
    try {
      val layoutId = res.getIdentifier("pro_asr_settings_extra", "layout", pkg)
      if (layoutId == 0) return

      val stubId = res.getIdentifier("pro_inject_stub_asr", "id", pkg)
      val stub = if (stubId != 0) root.findViewById<ViewStub?>(stubId) else null
      val inflater = LayoutInflater.from(activity)

      val inflatedRoot: View? = if (stub != null) {
        stub.layoutResource = layoutId
        stub.inflate()
      } else {
        val containerId = res.getIdentifier("pro_inject_slot_asr", "id", pkg)
        val container = if (containerId != 0) root.findViewById<ViewGroup?>(containerId) else null
        if (container != null) {
          inflater.inflate(layoutId, container, true)
          container
        } else if (root is ViewGroup) {
          inflater.inflate(layoutId, root, true)
          root
        } else {
          null
        }
      }

      // 绑定按钮点击 -> 隐式跳转至 Pro 页面
      val btnId = res.getIdentifier("btn_pro_asr_context", "id", pkg)
      val host = inflatedRoot ?: root
      val btn = if (btnId != 0) host.findViewById<View?>(btnId) else null
      btn?.setOnClickListener {
        try {
          val intent = android.content.Intent("com.brycewg.asrkb.pro.SHOW_ASR_CONTEXT")
          activity.startActivity(intent)
        } catch (t: Throwable) {
          if (BuildConfig.DEBUG) Log.d(TAG, "Failed to start pro ASR context activity: ${t.message}")
        }
      }
    } catch (t: Throwable) {
      if (BuildConfig.DEBUG) Log.d(TAG, "skip pro ui inject(asr): ${t.message}")
    }
  }
}
