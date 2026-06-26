package com.gameocr.app.update

import com.gameocr.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * 升级检测：调用 GitHub Releases API 拿最新 release 信息，与当前 [BuildConfig.VERSION_NAME] 比较。
 *
 * 不需要后端：完全依赖 GitHub 提供的 `api.github.com/repos/{owner}/{repo}/releases/latest`，
 * 公开 repo 不需要 token；匿名调用对每个 IP 每小时 60 次额度，对升级检测远超需求。
 *
 * 失败时（国内访问 api.github.com 不稳定 / 网络异常）由调用方 fallback 让用户手动打开
 * [RELEASE_PAGE_URL]，不报错阻塞。
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json
) {

    /**
     * 调一次 API。返回 [Result] —— 成功时 [UpdateInfo]（含是否有新版本），失败时附 Throwable。
     */
    suspend fun checkLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ScreenTranslator/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("empty body")
                val parsed = json.decodeFromString(GithubRelease.serializer(), body)
                val latestVersion = stripV(parsed.tagName)
                val currentVersion = BuildConfig.VERSION_NAME
                val hasUpdate = compareSemver(latestVersion, currentVersion) > 0
                val apkUrl = parsed.assets
                    .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?.browserDownloadUrl
                UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    hasUpdate = hasUpdate,
                    releaseUrl = parsed.htmlUrl,
                    apkUrl = apkUrl,
                    changelog = parsed.body?.takeIf { it.isNotBlank() }
                )
            }
        }.onFailure { Timber.w(it, "Update check failed") }
    }

    private fun stripV(tag: String): String =
        if (tag.startsWith("v", ignoreCase = true)) tag.substring(1) else tag

    /**
     * 简化 semver 比较：把 "0.2.0" / "1.10.3" 等按 . 分段比整数。
     * 不支持 -alpha / -rc 等后缀（项目目前没用），有后缀时降级到字符串比较。
     */
    private fun compareSemver(a: String, b: String): Int {
        val partsA = a.split('.').mapNotNull { it.toIntOrNull() }
        val partsB = b.split('.').mapNotNull { it.toIntOrNull() }
        if (partsA.isEmpty() || partsB.isEmpty()) return a.compareTo(b)
        val len = maxOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            val ai = partsA.getOrElse(i) { 0 }
            val bi = partsB.getOrElse(i) { 0 }
            if (ai != bi) return ai - bi
        }
        return 0
    }

    companion object {
        // 主仓库 owner/repo —— 与 README badge URL 一致
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/ciddwd/overlay-translator/releases/latest"
        const val RELEASE_PAGE_URL =
            "https://github.com/ciddwd/overlay-translator/releases/latest"
    }
}

/** API 调用结果 —— 给 UI 层用。 */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseUrl: String,
    /** 直链下载 APK，没有 .apk 资产时为 null（应当跳转 [releaseUrl] 让用户手动下）。 */
    val apkUrl: String?,
    val changelog: String?
)

/** GitHub Releases API 返回的 JSON 子集。kotlinx.serialization 反序列化。 */
@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String
)
