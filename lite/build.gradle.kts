plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hcllmsx.momoadb.lite"
    compileSdk = 37

    // 根目录 VERSION 仍是版本号的单一事实来源，CI 可用环境变量覆盖（与主应用保持一致）。
    val versionFile = rootProject.file("VERSION")
    val localVersionName = if (versionFile.exists()) versionFile.readText().trim() else "1.0.0"
    val versionNameValue = providers.environmentVariable("VERSION_NAME").orNull ?: localVersionName
    val versionCodeValue = providers.environmentVariable("VERSION_CODE").map { it.toInt() }.orNull ?: run {
        val parts = versionNameValue.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size >= 3) (parts[0] * 10000 + parts[1] * 100 + parts[2]) * 10 + 9 else 10000
    }

    defaultConfig {
        // 与主应用分开的 applicationId，两版可以共存于同一台设备。
        applicationId = "com.hcllmsx.momoadb.lite"
        // 面向 Android 4.4（API 19）的电视与盒子，不使用任何 AndroidX 依赖。
        minSdk = 19
        targetSdk = 19
        versionCode = versionCodeValue
        versionName = versionNameValue
    }

    buildFeatures {
        buildConfig = true
    }

    val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
    val keyAliasValue = providers.environmentVariable("KEY_ALIAS").orNull
    val keyStorePasswordValue = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
    val keyPasswordValue = providers.environmentVariable("KEY_PASSWORD").orNull
    val releaseSigningEnabled = !signingStoreFile.isNullOrBlank() &&
        !keyAliasValue.isNullOrBlank() &&
        !keyStorePasswordValue.isNullOrBlank() &&
        !keyPasswordValue.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (releaseSigningEnabled) {
                storeFile = file(signingStoreFile!!)
                storeType = "PKCS12"
                keyAlias = keyAliasValue!!
                storePassword = keyStorePasswordValue!!
                keyPassword = keyPasswordValue!!
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            // 轻量版只有几行代码，混淆收益极低，保持不混淆更便于排查。
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 4.4 设备普遍没有 dex 合并需求，保持单 dex 更稳。
    lint {
        abortOnError = false
        // 轻量版刻意 targetSdk = 19（面向 Android 4.4 设备），这条 Google Play 规则不适用。
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
