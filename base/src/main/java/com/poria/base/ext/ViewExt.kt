package com.poria.base.ext

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

private const val PRESSED_SCALE = 0.95f
private const val PRESSED_ALPHA = 0.95f
private const val DEFAULT_ANIM_DURATION = 50L
private const val DEFAULT_DEBOUNCE_MILLIS = 500L

/**
 * 带按压动画的点击监听。
 *
 * - 按下缩小到 0.95、抬起复原（`ACTION_CANCEL` 也会复原，滑动列表时不会卡在缩小状态）
 * - [debounceMillis] > 0 时做点击防抖，避免连点触发多次（游戏里点按钮最容易踩）
 *
 * 注意：它会覆盖 View 现有的 OnTouchListener。
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setOnClickListenerWithClickAnimation(
    duration: Long = DEFAULT_ANIM_DURATION,
    debounceMillis: Long = 0L,
    listener: (View) -> Unit
) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animateScale(PRESSED_SCALE, PRESSED_ALPHA, duration)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animateScale(1f, 1f, duration)
        }
        false
    }
    setOnDebouncedClickListener(debounceMillis, listener)
}

/**
 * 点击防抖：`intervalMillis` 内只认第一次点击。
 *
 * 适用于所有"点了会跳转/扣资源/弹窗"的按钮。默认 500ms。
 */
fun View.setOnDebouncedClickListener(
    intervalMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    listener: (View) -> Unit
) {
    if (intervalMillis <= 0L) {
        setOnClickListener(listener)
        return
    }
    var lastClickAt = 0L
    setOnClickListener { v ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt >= intervalMillis) {
            lastClickAt = now
            listener(v)
        }
    }
}

private fun View.animateScale(scale: Float, alpha: Float, duration: Long) {
    animate().cancel()
    animate()
        .setDuration(duration)
        .scaleX(scale)
        .scaleY(scale)
        .alpha(alpha)
        .start()
}
