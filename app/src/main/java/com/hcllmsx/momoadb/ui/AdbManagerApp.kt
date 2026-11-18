package com.hcllmsx.momoadb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hcllmsx.momoadb.R
import androidx.navigation.NavDestination.Companion.hierarchy
import com.hcllmsx.momoadb.ui.apps.AppsScreen
import com.hcllmsx.momoadb.ui.device.DeviceScreen
import com.hcllmsx.momoadb.ui.diagnostics.DiagnosticLogScreen
import com.hcllmsx.momoadb.ui.discovery.DeviceDiscoveryScreen
import com.hcllmsx.momoadb.ui.download.OnlineDownloadScreen
import com.hcllmsx.momoadb.ui.files.FileTransferScreen
import com.hcllmsx.momoadb.ui.home.HomeScreen
import com.hcllmsx.momoadb.ui.install.InstallApkScreen
import com.hcllmsx.momoadb.ui.layout.LocalWindowLayout
import com.hcllmsx.momoadb.ui.layout.rememberWindowLayout
import com.hcllmsx.momoadb.ui.localapps.LocalAppsScreen
import com.hcllmsx.momoadb.ui.logs.SystemLogScreen
import com.hcllmsx.momoadb.ui.mirror.MirrorScreen
import com.hcllmsx.momoadb.ui.pairing.PairingScreen
import com.hcllmsx.momoadb.ui.qrcode.DeviceQrScreen
import com.hcllmsx.momoadb.ui.remote.RemoteControlScreen
import com.hcllmsx.momoadb.ui.screenshot.ScreenshotScreen
import com.hcllmsx.momoadb.ui.settings.SettingsScreen
import com.hcllmsx.momoadb.ui.shared.LocalSharedTransitionScope
import com.hcllmsx.momoadb.ui.shared.SharedMotion
import com.hcllmsx.momoadb.ui.shared.SharedToolKeys
import com.hcllmsx.momoadb.ui.shared.appComposable
import com.hcllmsx.momoadb.ui.shell.ShellScreen
import com.hcllmsx.momoadb.ui.update.UpdateBanner

@Composable
fun AdbManagerApp() {
    val windowLayout = rememberWindowLayout()
    CompositionLocalProvider(LocalWindowLayout provides windowLayout) {
        AppShell(isWide = windowLayout.isWide)
    }
}

@Composable
private fun AppShell(isWide: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val bottomRoutes = remember { bottomDestinations.map { it.route }.toSet() }
    val showBottomBar = currentDestination?.route in bottomRoutes

    if (isWide) {
        // 电视 / 平板：左侧导航栏替代底部栏，内容限宽居中，避免手机上被横向拉伸的观感。
        Column(modifier = Modifier.fillMaxSize()) {
            UpdateBanner()
            Row(modifier = Modifier.weight(1f)) {
                AppNavigationRail(
                    navController = navController,
                    currentDestination = currentDestination,
                )
                AppNavHost(
                    navController = navController,
                    bottomRoutes = bottomRoutes,
                    bottomPadding = 0.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        UpdateBanner()
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = expandVertically(
                        animationSpec = SharedMotion.sizeTween(),
                        expandFrom = Alignment.Bottom,
                    ) + fadeIn(animationSpec = tween(SharedMotion.FadeMs)),
                    exit = shrinkVertically(
                        animationSpec = SharedMotion.sizeTween(),
                        shrinkTowards = Alignment.Bottom,
                    ) + fadeOut(animationSpec = tween(SharedMotion.FadeMs)),
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars,
                    ) {
                        bottomDestinations.forEach { destination ->
                            val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                            val label = stringResource(destination.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTab(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val systemNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // 收起过程中 Scaffold 底边距连续变化；始终不低于系统导航区，避免手势条穿透。
            val bottomPadding = maxOf(padding.calculateBottomPadding(), systemNavBottom)

            AppNavHost(
                navController = navController,
                bottomRoutes = bottomRoutes,
                bottomPadding = bottomPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .width(96.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cable,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(vertical = 18.dp)
                .size(28.dp),
        )
        bottomDestinations.forEach { destination ->
            val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
            val label = stringResource(destination.labelRes)
            NavigationRailItem(
                selected = selected,
                onClick = { navController.navigateToTab(destination.route) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = label,
                        modifier = Modifier.size(26.dp),
                    )
                },
                label = { Text(label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavHost(
    navController: NavHostController,
    bottomRoutes: Set<String>,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize(),
        ) {
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navController = navController,
                        startDestination = AppDestination.Home.route,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = {
                            val tabSwitch = initialState.destination.route in bottomRoutes &&
                                targetState.destination.route in bottomRoutes
                            fadeIn(
                                animationSpec = tween(
                                    if (tabSwitch) SharedMotion.TabFadeInMs else SharedMotion.PageFadeMs,
                                ),
                            )
                        },
                        exitTransition = {
                            val tabSwitch = initialState.destination.route in bottomRoutes &&
                                targetState.destination.route in bottomRoutes
                            fadeOut(
                                animationSpec = tween(
                                    if (tabSwitch) SharedMotion.TabFadeOutMs else SharedMotion.PageFadeMs,
                                ),
                            )
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(SharedMotion.PageFadeMs))
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(SharedMotion.PageFadeMs))
                        },
                    ) {
                        appComposable(AppDestination.Home.route) {
                            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
                            val discoveredHostState = savedStateHandle
                                ?.getStateFlow(DiscoveryHostKey, "")
                                ?.collectAsState()
                                ?: remember { mutableStateOf("") }
                            val discoveredPortState = savedStateHandle
                                ?.getStateFlow(DiscoveryPortKey, "")
                                ?.collectAsState()
                                ?: remember { mutableStateOf("") }
                            val discoveredHost by discoveredHostState
                            val discoveredPort by discoveredPortState
                            HomeScreen(
                                bottomPadding = bottomPadding,
                                onPairingClick = { navController.navigate(AppDestination.Pairing.route) },
                                onDiscoveryClick = { navController.navigate(AppDestination.Discovery.route) },
                                onQrDisplayClick = { navController.navigate(DeviceQrRoute) },
                                discoveredHost = discoveredHost,
                                discoveredPort = discoveredPort,
                                onDiscoveredEndpointConsumed = {
                                    savedStateHandle?.remove<String>(DiscoveryHostKey)
                                    savedStateHandle?.remove<String>(DiscoveryPortKey)
                                },
                            )
                        }
                        appComposable(AppDestination.Device.route) {
                            DeviceScreen(
                                bottomPadding = bottomPadding,
                                onAppsClick = { navController.navigate(AppDestination.Apps.route) },
                                onLocalAppsClick = { navController.navigate(AppDestination.LocalApps.route) },
                                onInstallClick = { navController.navigate(AppDestination.Install.route) },
                                onDownloadClick = { navController.navigate(AppDestination.Download.route) },
                                onFilesClick = { navController.navigate(AppDestination.Files.route) },
                                onScreenshotClick = { navController.navigate(AppDestination.Screenshot.route) },
                                onShellClick = { navController.navigate(AppDestination.Shell.route) },
                                onRemoteClick = { navController.navigate(RemoteRoute) },
                                onMirrorClick = { navController.navigate(MirrorRoute) },
                                onLogsClick = { navController.navigate(LogsRoute) },
                            )
                        }
                        appComposable(AppDestination.Settings.route) {
                            SettingsScreen(
                                bottomPadding = bottomPadding,
                                onDiagnosticsClick = { navController.navigate(DiagnosticsRoute) },
                            )
                        }
                        appComposable(AppDestination.Pairing.route) {
                            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
                            val pairingHostState = savedStateHandle
                                ?.getStateFlow(PairingHostKey, "")
                                ?.collectAsState()
                                ?: remember { mutableStateOf("") }
                            val pairingPortState = savedStateHandle
                                ?.getStateFlow(PairingPortKey, "")
                                ?.collectAsState()
                                ?: remember { mutableStateOf("") }
                            val pairingHost by pairingHostState
                            val pairingPort by pairingPortState
                            PairingScreen(
                                bottomPadding = bottomPadding,
                                onBackClick = { navController.popBackStack() },
                                onContinueToConnect = { host, connectPort ->
                                    val homeHandle = navController
                                        .getBackStackEntry(AppDestination.Home.route)
                                        .savedStateHandle
                                    homeHandle[DiscoveryHostKey] = host
                                    if (connectPort != null) {
                                        homeHandle[DiscoveryPortKey] = connectPort.toString()
                                    } else {
                                        homeHandle.remove<String>(DiscoveryPortKey)
                                    }
                                    navController.popBackStack(AppDestination.Home.route, inclusive = false)
                                },
                                discoveredHost = pairingHost,
                                discoveredPort = pairingPort,
                                onDiscoveredEndpointConsumed = {
                                    savedStateHandle?.remove<String>(PairingHostKey)
                                    savedStateHandle?.remove<String>(PairingPortKey)
                                },
                            )
                        }
                        appComposable(AppDestination.Discovery.route) {
                            DeviceDiscoveryScreen(
                                bottomPadding = bottomPadding,
                                onBackClick = { navController.popBackStack() },
                                onUseEndpoint = { host, port ->
                                    navController.previousBackStackEntry?.savedStateHandle?.set(DiscoveryHostKey, host)
                                    navController.previousBackStackEntry?.savedStateHandle?.set(DiscoveryPortKey, port.toString())
                                    navController.popBackStack()
                                },
                                onPairEndpoint = { host, port ->
                                    navController.navigate(AppDestination.Pairing.route)
                                    navController.currentBackStackEntry?.savedStateHandle?.set(PairingHostKey, host)
                                    navController.currentBackStackEntry?.savedStateHandle?.set(PairingPortKey, port.toString())
                                },
                            )
                        }
                        appComposable(AppDestination.Shell.route, SharedToolKeys.Shell) {
                            ShellScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.Apps.route, SharedToolKeys.Apps) {
                            AppsScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.LocalApps.route, SharedToolKeys.LocalApps) {
                            LocalAppsScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.Download.route, SharedToolKeys.Download) {
                            OnlineDownloadScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.Install.route, SharedToolKeys.Install) {
                            InstallApkScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.Files.route, SharedToolKeys.Files) {
                            FileTransferScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(AppDestination.Screenshot.route, SharedToolKeys.Screenshot) {
                            ScreenshotScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(RemoteRoute, SharedToolKeys.Remote) {
                            RemoteControlScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(MirrorRoute) {
                            MirrorScreen(onBackClick = { navController.popBackStack() })
                        }
                        appComposable(LogsRoute, SharedToolKeys.Logs) {
                            SystemLogScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(DiagnosticsRoute) {
                            DiagnosticLogScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                        appComposable(DeviceQrRoute) {
                            DeviceQrScreen(bottomPadding = bottomPadding, onBackClick = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private val ContentMaxWidth = 1280.dp

private val bottomDestinations = listOf(
    AppDestination.Home,
    AppDestination.Device,
    AppDestination.Settings,
)

private sealed class AppDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Home : AppDestination("home", R.string.nav_devices, Icons.Outlined.Devices)
    data object Device : AppDestination("device", R.string.nav_details, Icons.Outlined.PhoneAndroid)
    data object Settings : AppDestination("settings", R.string.nav_settings, Icons.Outlined.Settings)
    data object Pairing : AppDestination("pairing", R.string.nav_pairing, Icons.Outlined.PhoneAndroid)
    data object Discovery : AppDestination("discovery", R.string.nav_discovery, Icons.Outlined.Devices)
    data object Shell : AppDestination("shell", R.string.nav_shell, Icons.Outlined.PhoneAndroid)
    data object Apps : AppDestination("apps", R.string.nav_apps, Icons.Outlined.PhoneAndroid)
    data object LocalApps : AppDestination("local_apps", R.string.nav_local_apps, Icons.Outlined.PhoneAndroid)
    data object Download : AppDestination("download", R.string.nav_download, Icons.Outlined.PhoneAndroid)
    data object Install : AppDestination("install", R.string.nav_install, Icons.Outlined.PhoneAndroid)
    data object Files : AppDestination("files", R.string.nav_files, Icons.Outlined.PhoneAndroid)
    data object Screenshot : AppDestination("screenshot", R.string.nav_screenshot, Icons.Outlined.PhoneAndroid)
}

private const val DiscoveryHostKey = "discovery_host"
private const val DiscoveryPortKey = "discovery_port"
private const val PairingHostKey = "pairing_host"
private const val PairingPortKey = "pairing_port"
private const val RemoteRoute = "remote"
private const val MirrorRoute = "mirror"
private const val LogsRoute = "logs"
private const val DiagnosticsRoute = "diagnostics"
private const val DeviceQrRoute = "device_qr"
