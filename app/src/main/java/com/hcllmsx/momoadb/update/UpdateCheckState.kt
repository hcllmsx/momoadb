package com.hcllmsx.momoadb.update

/** 更新检查的对外状态，界面据此决定提示文案与点击行为。 */
sealed interface UpdateCheckState {
    /** 尚未检查，或启动时的静默检查失败（不打扰用户）。 */
    data object Idle : UpdateCheckState

    data object Checking : UpdateCheckState

    data class UpToDate(val currentVersion: String) : UpdateCheckState

    data class Available(
        val latestVersion: String,
        val releaseUrl: String,
    ) : UpdateCheckState

    /** 手动检查失败：网络不可达，或返回内容未通过校验。 */
    data object Failed : UpdateCheckState
}
