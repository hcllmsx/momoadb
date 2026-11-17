package com.hcllmsx.momoadb.model

import com.hcllmsx.momoadb.R
import com.hcllmsx.momoadb.i18n.appString

data class DeviceInfo(
    val brand: String = appString(R.string.unknown),
    val model: String = appString(R.string.unknown),
    val androidVersion: String = appString(R.string.unknown),
    val sdk: String = appString(R.string.unknown),
    val abi: String = appString(R.string.unknown),
    val resolution: String = appString(R.string.unknown),
    val battery: String = appString(R.string.unknown),
)
