package com.hcllmsx.momoadb.ui.install

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hcllmsx.momoadb.AppServices
import com.hcllmsx.momoadb.R
import com.hcllmsx.momoadb.adb.adbTransferRunning
import com.hcllmsx.momoadb.files.LocalFileManager
import com.hcllmsx.momoadb.i18n.appString
import com.hcllmsx.momoadb.model.AdbOperationResult
import com.hcllmsx.momoadb.model.OperationStatus
import com.hcllmsx.momoadb.model.operationCatching
import com.hcllmsx.momoadb.repository.AdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstallApkUiState(
    val selectedName: String? = null,
    val selectedUriText: String? = null,
    val installEnabled: Boolean = false,
    val operationStatus: OperationStatus = OperationStatus.Idle,
)

class InstallApkViewModel(
    private val fileManager: LocalFileManager = AppServices.localFileManager,
    private val adbRepository: AdbRepository = AppServices.adbRepository,
) : ViewModel() {
    private val state = MutableStateFlow(InstallApkUiState())
    val uiState: StateFlow<InstallApkUiState> = state.asStateFlow()

    private var selectedUri: Uri? = null

    fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        if (state.value.operationStatus is OperationStatus.Running) return
        selectedUri = uri
        state.update { snapshot ->
            snapshot.copy(
                selectedName = null,
                selectedUriText = uri.toString(),
                installEnabled = false,
                operationStatus = OperationStatus.Idle,
            )
        }
        viewModelScope.launch {
            val name = operationCatching {
                withContext(Dispatchers.IO) { fileManager.displayName(uri) }
            }
                .getOrElse { error ->
                    if (selectedUri == uri) {
                        state.update { snapshot ->
                            snapshot.copy(
                                operationStatus =
                                    OperationStatus.Failed(
                                        appString(R.string.install_read_failed),
                                        error.message
                                            ?: appString(R.string.install_confirm_file_exists),
                                    )
                            )
                        }
                    }
                    return@launch
                }
            if (selectedUri != uri) return@launch
            // 下载管理器遇到重名会存成 app.apk.1 / app.apk.2，这类文件同样是可安装的 APK。
            val isApk = isApkFileName(name)
            state.update { snapshot ->
                snapshot.copy(
                    selectedName = name,
                    selectedUriText = uri.toString(),
                    installEnabled = isApk,
                    operationStatus =
                        if (isApk) {
                            OperationStatus.Idle
                        } else {
                            OperationStatus.Failed(
                                appString(R.string.install_wrong_file_type),
                                appString(R.string.install_select_apk_hint),
                            )
                        },
                )
            }
        }
    }

    fun onInstallClick() {
        if (state.value.operationStatus is OperationStatus.Running) return
        if (!state.value.installEnabled) return
        val uri = selectedUri
        if (uri == null) {
            state.update { snapshot ->
                snapshot.copy(
                    installEnabled = false,
                    operationStatus =
                        OperationStatus.Failed(
                            appString(R.string.install_no_apk_selected),
                            appString(R.string.install_select_local_apk_hint),
                        ),
                )
            }
            return
        }

        state.update { snapshot ->
            snapshot.copy(
                installEnabled = false,
                operationStatus = OperationStatus.Running(appString(R.string.install_preparing)),
            )
        }

        viewModelScope.launch {
            operationCatching {
                    withContext(Dispatchers.IO) { fileManager.copyToCache(uri) }
                }
                .fold(
                    onSuccess = { file ->
                        try {
                            state.update { snapshot ->
                                snapshot.copy(
                                    operationStatus =
                                        OperationStatus.Running(
                                            appString(R.string.common_transferring_arg, file.name)
                                        )
                                )
                            }
                            when (
                                val result =
                                    adbRepository.install(file) { transferred, total ->
                                        state.update { snapshot ->
                                            snapshot.copy(
                                                operationStatus =
                                                    adbTransferRunning(
                                                        transferringLabel =
                                                            appString(
                                                                R.string.common_transferring_arg,
                                                                file.name,
                                                            ),
                                                        finishingLabel =
                                                            appString(
                                                                R.string.common_installing_arg,
                                                                file.name,
                                                            ),
                                                        transferred = transferred,
                                                        total = total,
                                                    )
                                            )
                                        }
                                    }
                            ) {
                                is AdbOperationResult.Success -> {
                                    state.update { snapshot ->
                                        snapshot.copy(
                                            installEnabled = true,
                                            operationStatus =
                                                OperationStatus.Success(
                                                    appString(R.string.common_apk_install_success)
                                                ),
                                        )
                                    }
                                }
                                is AdbOperationResult.Failure -> {
                                    state.update { snapshot ->
                                        snapshot.copy(
                                            installEnabled = true,
                                            operationStatus =
                                                OperationStatus.Failed(
                                                    result.message,
                                                    result.suggestion,
                                                ),
                                        )
                                    }
                                }
                            }
                        } finally {
                            operationCatching { file.delete() }
                        }
                    },
                    onFailure = { error ->
                        state.update { snapshot ->
                            snapshot.copy(
                                installEnabled = true,
                                operationStatus =
                                    OperationStatus.Failed(
                                        text = appString(R.string.install_read_failed),
                                        suggestion =
                                            error.message
                                                ?: appString(R.string.install_confirm_file_exists),
                                    ),
                            )
                        }
                    },
                )
        }
    }
}

/**
 * 判断文件名是否为可安装的 APK。
 *
 * 除标准 `.apk` 外，还接受下载管理器遇到重名时追加的数字后缀（`app.apk.1`、`app.apk.2`）。
 */
internal fun isApkFileName(name: String): Boolean = name.matches(Regex("(?i).*\\.apk(?:\\.[0-9]+)?"))
