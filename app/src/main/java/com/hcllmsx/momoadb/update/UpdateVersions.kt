package com.hcllmsx.momoadb.update

/**
 * 版本号校验与比较。
 *
 * 远端版本来自 GitHub Release 的 `tag_name`（形如 `v26.11.16`）：先去掉 `v` 前缀，
 * 再要求符合 `^\d+(\.\d+)+$`；比较时按 `.` 切分转为整型逐级比较，禁止字符串字典序。
 */
object UpdateVersions {
    private val VersionPattern = Regex("^\\d+(\\.\\d+)+$")

    /** 规范化远端标签，格式非法时返回 null（防止代理返回错误页或空标签）。 */
    fun normalize(rawTag: String?): String? {
        val trimmed = rawTag?.trim().orEmpty().removePrefix("v").removePrefix("V")
        return trimmed.takeIf { VersionPattern.matches(it) }
    }

    /**
     * 取版本号主干，忽略 `-dev`、`+build` 这类构建后缀。
     *
     * 调试版的 `versionName` 形如 `26.11.17-dev`，若不剥离，`17-dev` 会解析成 0，
     * 导致调试版把同一个正式版误判为「有新版本」。
     */
    fun baseVersion(raw: String?): String = raw.orEmpty().trim().substringBefore('-').substringBefore('+').trim()

    /** 判断 candidate 是否比 current 更新，缺失的段按 0 处理。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val right = current.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val candidatePart = left.getOrElse(index) { 0 }
            val currentPart = right.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }
}
