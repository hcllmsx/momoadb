package com.hcllmsx.momoadb.lite.adb

/** ADB 操作失败时抛出，message 直接展示给用户。 */
class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
