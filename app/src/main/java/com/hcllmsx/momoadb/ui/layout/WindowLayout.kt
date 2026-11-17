package com.hcllmsx.momoadb.ui.layout

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** 手机类窄窗口与平板 / 电视类宽窗口。 */
enum class WindowForm { Compact, Wide }

/**
 * 当前窗口的形态信息：宽窄、是否电视设备、工具栅格列数。
 * 页面只依赖这些语义信息，不直接判断设备类型。
 */
data class WindowLayout(
    val form: WindowForm = WindowForm.Compact,
    val isTelevision: Boolean = false,
    val gridColumns: Int = 2,
) {
    val isWide: Boolean get() = form == WindowForm.Wide
}

val LocalWindowLayout: ProvidableCompositionLocal<WindowLayout> =
    staticCompositionLocalOf { WindowLayout() }

@Composable
fun rememberWindowLayout(): WindowLayout {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision = remember(context) { context.isTelevisionOrTvBox() }
    val widthDp = configuration.screenWidthDp
    val wide = widthDp >= WideWindowWidthDp || isTelevision
    val columns = when {
        widthDp >= 1600 -> 5
        widthDp >= 1100 -> 4
        widthDp >= 760 -> 3
        else -> 2
    }
    return WindowLayout(
        form = if (wide) WindowForm.Wide else WindowForm.Compact,
        isTelevision = isTelevision,
        gridColumns = columns,
    )
}

private const val WideWindowWidthDp = 600

private fun Context.isTelevisionOrTvBox(): Boolean {
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
