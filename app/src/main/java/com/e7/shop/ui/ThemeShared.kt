package com.e7.shop.ui

import android.content.Context
import android.widget.Toast

/**
 * 共享小工具（跨主题复用）。
 *
 * 主题拆分后，这类与设计语言无关的辅助函数集中在这里，
 * 避免在多个主题文件里各复制一份。
 */

/** 短提示。所有主题的按钮回调共用。 */
internal fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
