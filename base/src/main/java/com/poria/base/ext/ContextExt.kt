package com.poria.base.ext

import android.content.Context
import android.util.TypedValue

/**
 * dp → px。
 *
 * 用 `resources.displayMetrics` 而不是 `Resources.getSystem().displayMetrics`：
 * 后者拿到的是系统默认配置，会忽略多窗口、分屏、字体缩放和自定义 configuration。
 */
fun Context.dp2px(dp: Float): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    dp,
    resources.displayMetrics
).toInt()

fun Context.dp2px(dp: Int): Int = dp2px(dp.toFloat())

/** sp → px，只在需要手动算像素高度（比如自绘文字）时用。 */
fun Context.sp2px(sp: Float): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP,
    sp,
    resources.displayMetrics
).toInt()
