package com.hcllmsx.momoadb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.hcllmsx.momoadb.ui.components.AppTopBar as TopAppBar
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hcllmsx.momoadb.BuildConfig
import com.hcllmsx.momoadb.data.ThemeMode
import com.hcllmsx.momoadb.i18n.AppLanguage
import com.hcllmsx.momoadb.scrcpy.MirrorQualityPreset
import com.hcllmsx.momoadb.ui.components.SettingBlock
import com.hcllmsx.momoadb.ui.components.SettingGroupCard
import com.hcllmsx.momoadb.ui.theme.AdbManagerTheme
import com.hcllmsx.momoadb.ui.theme.AppDimens
import com.hcllmsx.momoadb.update.UpdateCheckState
import com.hcllmsx.momoadb.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    bottomPadding: Dp = 0.dp,
    onDiagnosticsClick: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    SettingsContent(
        bottomPadding = bottomPadding,
        uiState = uiState,
        onDefaultPortChanged = viewModel::onDefaultPortChanged,
        onConnectionTimeoutChanged = viewModel::onConnectionTimeoutChanged,
        onCommandTimeoutChanged = viewModel::onCommandTimeoutChanged,
        onScanRangesChanged = viewModel::onScanRangesChanged,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        onMirrorQualityPresetSelected = viewModel::onMirrorQualityPresetSelected,
        onLanguageSelected = viewModel::onLanguageSelected,
        onClearRecentDevicesClicked = viewModel::onClearRecentDevicesClicked,
        onDiagnosticsClick = onDiagnosticsClick,
        updateState = updateState,
        onCheckUpdateClicked = viewModel::onCheckUpdateClicked,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsContent(
    bottomPadding: Dp = 0.dp,
    uiState: SettingsUiState,
    onDefaultPortChanged: (String) -> Unit,
    onConnectionTimeoutChanged: (String) -> Unit,
    onCommandTimeoutChanged: (String) -> Unit,
    onScanRangesChanged: (String) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onMirrorQualityPresetSelected: (MirrorQualityPreset) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onClearRecentDevicesClicked: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    updateState: UpdateCheckState = UpdateCheckState.Idle,
    onCheckUpdateClicked: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppDimens.ScreenPadding,
                top = 14.dp,
                end = AppDimens.ScreenPadding,
                bottom = 14.dp + bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.SectionGap),
        ) {
            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.SettingsEthernet,
                        title = stringResource(R.string.settings_default_port_title),
                        description = stringResource(R.string.settings_default_port_desc),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = uiState.defaultPort,
                            onValueChange = onDefaultPortChanged,
                            singleLine = true,
                            suffix = { Text(stringResource(R.string.unit_port)) },
                            isError = uiState.defaultPortError != null,
                            supportingText = uiState.defaultPortError?.let { { Text(it) } },
                        )
                    }
                    SettingBlock(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.settings_connection_timeout_title),
                        description = stringResource(R.string.settings_connection_timeout_desc),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = uiState.connectionTimeoutSeconds,
                            onValueChange = onConnectionTimeoutChanged,
                            singleLine = true,
                            suffix = { Text(stringResource(R.string.unit_seconds)) },
                            isError = uiState.connectionTimeoutError != null,
                            supportingText = uiState.connectionTimeoutError?.let { { Text(it) } },
                        )
                    }
                    SettingBlock(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.settings_command_timeout_title),
                        description = stringResource(R.string.settings_command_timeout_desc),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = uiState.commandTimeoutSeconds,
                            onValueChange = onCommandTimeoutChanged,
                            singleLine = true,
                            suffix = { Text(stringResource(R.string.unit_seconds)) },
                            isError = uiState.commandTimeoutError != null,
                            supportingText = uiState.commandTimeoutError?.let { { Text(it) } },
                        )
                    }
                    SettingBlock(
                        icon = Icons.Outlined.SettingsEthernet,
                        title = stringResource(R.string.settings_scan_ranges_title),
                        description = stringResource(R.string.settings_scan_ranges_desc),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = uiState.scanRanges,
                            onValueChange = onScanRangesChanged,
                            placeholder = { Text(stringResource(R.string.settings_scan_ranges_placeholder)) },
                            minLines = 1,
                            maxLines = 4,
                            isError = uiState.scanRangesError != null,
                            supportingText = uiState.scanRangesError?.let { { Text(it) } },
                        )
                    }
                }
            }

            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.settings_mirror_quality_title),
                        description = stringResource(R.string.settings_mirror_quality_desc),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MirrorQualityPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = uiState.mirrorQualityPreset == preset,
                                    onClick = { onMirrorQualityPresetSelected(preset) },
                                    label = { Text(stringResource(preset.labelRes)) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.DarkMode,
                        title = stringResource(R.string.settings_theme_mode_title),
                        description = stringResource(R.string.settings_theme_mode_desc),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = uiState.themeMode == mode,
                                    onClick = { onThemeModeSelected(mode) },
                                    label = { Text(stringResource(mode.labelRes)) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.Language,
                        title = stringResource(R.string.settings_language),
                        description = stringResource(R.string.settings_language_desc),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppLanguage.entries.forEach { language ->
                                FilterChip(
                                    selected = uiState.language == language,
                                    onClick = { onLanguageSelected(language) },
                                    label = { Text(stringResource(language.labelRes)) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_diagnostics_title),
                        description = stringResource(R.string.settings_diagnostics_desc),
                        onClick = onDiagnosticsClick,
                    )
                    SettingBlock(
                        icon = Icons.Outlined.CleaningServices,
                        title = stringResource(R.string.settings_clear_recent_devices_title),
                        description = stringResource(R.string.settings_clear_recent_devices_desc),
                        onClick = onClearRecentDevicesClicked,
                    )
                }
            }

            item {
                SettingGroupCard {
                    SettingBlock(
                        icon = Icons.Outlined.Refresh,
                        title = stringResource(R.string.settings_check_update_title),
                        description = updateCheckDescription(updateState),
                        onClick = {
                            when (val current = updateState) {
                                is UpdateCheckState.Available -> uriHandler.openUri(current.releaseUrl)
                                else -> onCheckUpdateClicked()
                            }
                        },
                    )
                    SettingBlock(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        title = stringResource(R.string.settings_project_url_title),
                        description = "hcllmsx/momoadb",
                        onClick = { uriHandler.openUri(ProjectUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun updateCheckDescription(state: UpdateCheckState): String =
    when (state) {
        UpdateCheckState.Idle ->
            stringResource(R.string.settings_check_update_desc, BuildConfig.VERSION_NAME)
        UpdateCheckState.Checking -> stringResource(R.string.settings_check_update_checking)
        is UpdateCheckState.UpToDate ->
            stringResource(R.string.settings_check_update_latest, state.currentVersion)
        is UpdateCheckState.Available ->
            stringResource(R.string.settings_check_update_available, state.latestVersion)
        UpdateCheckState.Failed -> stringResource(R.string.settings_check_update_failed)
    }

private const val ProjectUrl = "https://github.com/hcllmsx/momoadb"

@Preview(name = "设置页", showBackground = true, widthDp = 390)
@Composable
private fun SettingsContentPreview() {
    AdbManagerTheme(dynamicColor = false) {
        SettingsContent(
            uiState = SettingsUiState(),
            onDefaultPortChanged = {},
            onConnectionTimeoutChanged = {},
            onCommandTimeoutChanged = {},
            onScanRangesChanged = {},
            onThemeModeSelected = {},
            onMirrorQualityPresetSelected = {},
            onLanguageSelected = {},
            onClearRecentDevicesClicked = {},
            onDiagnosticsClick = {},
            updateState = UpdateCheckState.Idle,
            onCheckUpdateClicked = {},
        )
    }
}
