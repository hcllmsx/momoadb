package com.hcllmsx.momoadb.update

import com.hcllmsx.momoadb.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 更新检查：只读取正式 Release 的 tag，不读仓库里的 VERSION 文件。
 *
 * 请求顺序固定为「代理镜像 → GitHub 官方源 → 返回失败」，单次连接超时约 6 秒；
 * tag 必须符合版本号格式，且发布页链接必须属于本项目，否则视为检查失败。
 */
class UpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun check(): UpdateCheckState = withContext(Dispatchers.IO) {
        for (url in ReleaseApiUrls) {
            val state = requestLatest(url)
            if (state != null) return@withContext state
        }
        UpdateCheckState.Failed
    }

    private fun requestLatest(url: String): UpdateCheckState? =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                parseLatestRelease(response.body.string())
            }
        } catch (error: Exception) {
            Timber.d(error, "更新检查失败：%s", url)
            null
        }

    private fun parseLatestRelease(body: String): UpdateCheckState? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val latestVersion = UpdateVersions.normalize(json.optString("tag_name")) ?: return null
        val releaseUrl = json.optString("html_url")
        if (!releaseUrl.startsWith(ProjectUrl)) return null
        return if (UpdateVersions.isNewer(latestVersion, UpdateVersions.baseVersion(currentVersion))) {
            UpdateCheckState.Available(latestVersion, releaseUrl)
        } else {
            UpdateCheckState.UpToDate(currentVersion)
        }
    }

    companion object {
        private const val ProjectUrl = "https://github.com/hcllmsx/momoadb"
        private const val ApiUrl = "https://api.github.com/repos/hcllmsx/momoadb/releases/latest"

        /** 代理镜像优先，官方源兜底；镜像共享出口更容易触发未鉴权限额。 */
        private val ReleaseApiUrls =
            listOf(
                "https://gh-proxy.com/$ApiUrl",
                "https://gh-proxy.org/$ApiUrl",
                ApiUrl,
            )

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
    }
}
