package com.gameocr.app.di

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/**
 * 强制明文 HTTP 只能访问私有/回环地址，或用户在 Settings 里显式白名单的 host。
 *
 * 网络安全配置（network_security_config.xml）层面已对全局放开 cleartext，让自架 deeplx / 局域网
 * 服务能用 `http://192.168.x.x:port/`。**真正的安全边界在这里**：
 *
 * - URL scheme = `https` → 直接放行。
 * - URL scheme = `http`：
 *   - host = `localhost` 或 IP literal 属于 loopback / site-local (RFC1918) / link-local → 放行。
 *   - host 在 [allowedHosts] 用户白名单（精确匹配、忽略大小写）→ 放行。
 *   - 其它（公网 hostname、公网 IP）→ 抛 IOException。
 *
 * [allowedHosts] 由 GameOcrApp 订阅 Settings.cleartextAllowedHosts 实时同步。
 */
@Singleton
class PrivateCleartextInterceptor @Inject constructor() : Interceptor {

    @Volatile
    var allowedHosts: Set<String> = emptySet()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.scheme.equals("http", ignoreCase = true)) {
            val host = req.url.host
            if (!isAllowed(host)) {
                throw IOException(
                    "Cleartext HTTP only allowed for private/loopback addresses (RFC1918, 127.0.0.0/8, ::1, fe80::/10) " +
                        "or user-whitelisted hosts. Use HTTPS for $host."
                )
            }
        }
        return chain.proceed(req)
    }

    private fun isAllowed(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (allowedHosts.any { it.equals(host, ignoreCase = true) }) return true
        val addr = parseIpLiteral(host) ?: return false
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        val looksLikeIp = host.contains(':') || IPV4_PATTERN.matches(host)
        if (!looksLikeIp) return null
        return try {
            // 对纯 IP literal，InetAddress.getByName 不会触发 DNS 查询。
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val IPV4_PATTERN = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    }
}
