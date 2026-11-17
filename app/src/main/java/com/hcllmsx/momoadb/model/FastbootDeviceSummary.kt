package com.hcllmsx.momoadb.model

data class FastbootDeviceSummary(val values: Map<String, String?> = emptyMap()) {
    companion object {
        val Variables =
            listOf("product", "serialno", "version-bootloader", "current-slot", "is-userspace")
    }
}
