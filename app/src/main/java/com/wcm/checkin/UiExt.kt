package com.wcm.checkin

import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * 小 UI 辅助：Fragment 内的短提示。
 *
 * 页面已分离时静默丢弃，不冒 `IllegalStateException`。
 */
internal fun Fragment.toastShort(text: String) {
    if (!isAdded) return
    Toast.makeText(requireContext().applicationContext, text, Toast.LENGTH_SHORT).show()
}
