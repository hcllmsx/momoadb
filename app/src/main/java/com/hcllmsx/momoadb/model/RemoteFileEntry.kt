package com.hcllmsx.momoadb.model

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)
