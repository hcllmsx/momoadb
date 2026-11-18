package com.hcllmsx.momoadb.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用级的更新检查入口：启动时静默检查一次，失败不打扰用户；
 * 用户主动检查时失败要明确提示。
 */
object UpdateCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checker = UpdateChecker()
    private val manualRequested = AtomicBoolean(false)
    private val startupRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private var running: Job? = null

    /** 横幅展示的可用更新；用户关闭后记住版本号，同版本不再打扰，新版本号会重新显示。 */
    private val dismissedVersion = AtomicReference<String?>(null)
    private val _banner = MutableStateFlow<UpdateCheckState.Available?>(null)
    val banner: StateFlow<UpdateCheckState.Available?> = _banner.asStateFlow()

    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    /** 应用启动时调用，只生效一次。 */
    fun checkOnStartup() {
        if (!startupRequested.compareAndSet(false, true)) return
        launchCheck()
    }

    fun refresh() {
        manualRequested.set(true)
        launchCheck()
    }

    fun dismissBanner() {
        _banner.value?.let { available -> dismissedVersion.set(available.latestVersion) }
        _banner.value = null
    }

    private fun launchCheck() {
        if (running?.isActive == true) return
        _state.value = UpdateCheckState.Checking
        running =
            scope.launch {
                val result = checker.check()
                val silent = !manualRequested.getAndSet(false)
                _state.value =
                    if (silent && result == UpdateCheckState.Failed) {
                        UpdateCheckState.Idle
                    } else {
                        result
                    }
                _banner.value =
                    (result as? UpdateCheckState.Available)
                        ?.takeIf { it.latestVersion != dismissedVersion.get() }
            }
    }
}
