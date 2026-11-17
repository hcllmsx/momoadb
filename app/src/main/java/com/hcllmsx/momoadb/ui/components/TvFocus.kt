package com.hcllmsx.momoadb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 为遥控器 / 键盘焦点绘制可见的描边。
 *
 * 触屏上焦点通常不可见，只有在方向键或 Tab 移动焦点时才会出现，因此统一启用不会带来噪音。
 */
@Composable
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 2.dp,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(width = width, color = color, shape = shape) else Modifier)
}

/** 列表行获得焦点时的底色高亮，比描边更适合通栏条目。 */
@Composable
fun Modifier.tvFocusBackground(
    color: Color = MaterialTheme.colorScheme.primary,
    alpha: Float = 0.16f,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.background(color.copy(alpha = alpha)) else Modifier)
}

/**
 * 页面进入时把焦点交给首个可操作项。
 *
 * 电视上如果没有任何元素持有焦点，方向键按下去不会有任何反应，用户会以为界面卡死。
 * 首帧布局完成前 `requestFocus` 会抛「not initialized」，因此这里逐帧重试。
 *
 * 用法：`val firstFocus = rememberTvInitialFocus(isWide)`，
 * 再在第一项上加 `Modifier.tvInitialFocus(firstFocus, isWide)`。
 */
@Composable
fun rememberTvInitialFocus(enabled: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(TvInitialFocusAttempts) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    return requester
}

/** 把 [rememberTvInitialFocus] 拿到的 requester 挂到元素上，仅在宽屏 / 电视生效。 */
fun Modifier.tvInitialFocus(requester: FocusRequester, enabled: Boolean = true): Modifier =
    if (enabled) this.focusRequester(requester) else this

private const val TvInitialFocusAttempts = 4
