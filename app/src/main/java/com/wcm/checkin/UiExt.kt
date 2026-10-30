package com.wcm.checkin

import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 小 UI 辅助：超话页 / 图库页 / 首页各自复制过一份的同一段代码。
 *
 * 这些函数原先在 `TopicFragment`、`GalleryBrowseActivity`、`HomeFragment`、
 * `GalleryFragment` 里各写一遍（三处 `paintChip`、两处空态提示、四处 toast），
 * 改一处样式要同步改四遍，属于典型的「复制粘贴型重复」。
 */

/** 可点胶囊（筛选条 / 账号选择）的选中态：品牌软底 + `brand_strong` 文字。 */
internal fun TextView.applyChipStyle(selected: Boolean) {
    setBackgroundResource(if (selected) R.drawable.bg_pill_brand else R.drawable.bg_pill_line)
    setTextColor(
        ContextCompat.getColor(context, if (selected) R.color.brand_strong else R.color.ink_2)
    )
}

/** 列表空态提示：套统一文本样式，避免各处写死字号 / 颜色（深色主题下同样可读）。 */
internal fun Context.emptyHintView(text: String): TextView = TextView(this).apply {
    setTextAppearance(R.style.Widget_Wcm_Text_Empty)
    this.text = text
}

/** Fragment 内的短提示；页面已分离时静默丢弃，不冒 `IllegalStateException`。 */
internal fun Fragment.toastShort(text: String) {
    if (!isAdded) return
    Toast.makeText(requireContext().applicationContext, text, Toast.LENGTH_SHORT).show()
}
