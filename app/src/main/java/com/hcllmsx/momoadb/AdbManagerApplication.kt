package com.hcllmsx.momoadb

import android.app.Application
import com.hcllmsx.momoadb.adb.AdbIdentityManager
import com.hcllmsx.momoadb.update.UpdateCenter
import timber.log.Timber

class AdbManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AppServices.initialize(this)
        // Identity configuration is immediate; key loading/generation stays off the UI thread.
        AdbIdentityManager.initialize(this)
        // 启动时静默检查更新，失败保持静默，不影响任何功能。
        UpdateCenter.checkOnStartup()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
