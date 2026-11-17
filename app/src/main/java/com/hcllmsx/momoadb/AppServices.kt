package com.hcllmsx.momoadb

import android.content.Context
import com.hcllmsx.momoadb.adb.KadbManager
import com.hcllmsx.momoadb.adb.FastbootOtgManager
import com.hcllmsx.momoadb.data.AppSettingsStore
import com.hcllmsx.momoadb.data.RecentDeviceStore
import com.hcllmsx.momoadb.discovery.AndroidAdbMdnsDiscovery
import com.hcllmsx.momoadb.discovery.AdbMdnsDiscovery
import com.hcllmsx.momoadb.discovery.LanAdbScanner
import com.hcllmsx.momoadb.discovery.NetworkInfoProvider
import com.hcllmsx.momoadb.download.NetworkDownloadManager
import com.hcllmsx.momoadb.files.LocalFileManager
import com.hcllmsx.momoadb.localapps.LocalAppExporter
import com.hcllmsx.momoadb.repository.DefaultAdbRepository
import com.hcllmsx.momoadb.scrcpy.ScrcpyRepository
import com.hcllmsx.momoadb.usb.UsbOtgHost
import com.hcllmsx.momoadb.usb.UsbOtgActions

object AppServices {
    private var appContext: Context? = null

    val context: Context
        get() = requireNotNull(appContext) { "AppServices 尚未初始化 Context" }

    val kadbManager: KadbManager by lazy { KadbManager() }
    val fastbootOtgManager: FastbootOtgManager by lazy { FastbootOtgManager() }
    val usbOtgHost: UsbOtgHost by lazy {
        UsbOtgHost(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val usbOtgActions: UsbOtgActions by lazy {
        UsbOtgActions(usbOtgHost)
    }
    val downloadManager: NetworkDownloadManager by lazy { NetworkDownloadManager(appContext) }
    val localFileManager: LocalFileManager by lazy {
        LocalFileManager(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val localAppExporter: LocalAppExporter by lazy {
        LocalAppExporter(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val settingsStore: AppSettingsStore by lazy {
        AppSettingsStore(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val recentDeviceStore: RecentDeviceStore by lazy {
        RecentDeviceStore(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val networkInfoProvider: NetworkInfoProvider by lazy {
        NetworkInfoProvider(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val lanAdbScanner: LanAdbScanner by lazy { LanAdbScanner() }
    val adbMdnsDiscovery: AdbMdnsDiscovery by lazy {
        AndroidAdbMdnsDiscovery(requireNotNull(appContext) { "AppServices 尚未初始化 Context" })
    }
    val adbRepository: DefaultAdbRepository by lazy {
        DefaultAdbRepository(kadbManager, fastbootOtgManager, usbOtgHost, recentDeviceStore, settingsStore)
    }
    val scrcpyRepository: ScrcpyRepository by lazy {
        ScrcpyRepository(requireNotNull(appContext) { "AppServices 尚未初始化 Context" }, kadbManager)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}
