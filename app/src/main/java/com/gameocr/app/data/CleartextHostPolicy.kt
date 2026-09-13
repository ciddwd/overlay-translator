package com.gameocr.app.data

import okhttp3.HttpUrl

internal enum class CleartextHostIssueKind {
    SCHEME,
    PATH_OR_PARAMETERS,
    PORT,
    INVALID_HOST,
}

internal data class CleartextHostIssue(
    val lineNumber: Int,
    val value: String,
    val kind: CleartextHostIssueKind,
)

internal data class CleartextHostValidation(
    val hosts: List<String>,
    val issues: List<CleartextHostIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()
    val firstIssue: CleartextHostIssue? get() = issues.firstOrNull()
}

/**
 * Validates the host-only values consumed by [Settings.cleartextAllowedHosts].
 *
 * The interceptor compares this list with OkHttp's canonical `request.url.host`, so URL schemes,
 * ports, paths, queries and fragments are not merely unnecessary: storing them would create a
 * whitelist entry that can never match. Canonicalising through [HttpUrl.Builder] also keeps IDNs,
 * IPv4 and IPv6 in the same form returned by OkHttp without performing DNS lookups.
 */
internal object CleartextHostPolicy {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val dottedNumericHost = Regex("^\\d+(?:\\.\\d+){3}$")

    fun validateMultiline(text: String): CleartextHostValidation =
        validateEntries(text.lines())

    fun normalize(hosts: Iterable<String>): List<String> =
        validateEntries(hosts).hosts

    private fun validateEntries(entries: Iterable<String>): CleartextHostValidation {
        val hosts = mutableListOf<String>()
        val issues = mutableListOf<CleartextHostIssue>()

        entries.forEachIndexed { index, rawValue ->
            val value = rawValue.trim()
            if (value.isEmpty()) return@forEachIndexed

            val result = validateHost(value)
            if (result.host != null) {
                if (hosts.none { it.equals(result.host, ignoreCase = true) }) {
                    hosts += result.host
                }
            } else {
                issues += CleartextHostIssue(
                    lineNumber = index + 1,
                    value = value,
                    kind = requireNotNull(result.issue),
                )
            }
        }

        return CleartextHostValidation(hosts = hosts, issues = issues)
    }

    private fun validateHost(value: String): HostResult {
        if (schemePrefix.containsMatchIn(value)) {
            return HostResult(issue = CleartextHostIssueKind.SCHEME)
        }
        if (value.any { it == '/' || it == '\\' || it == '?' || it == '#' }) {
            return HostResult(issue = CleartextHostIssueKind.PATH_OR_PARAMETERS)
        }
        if (value.any(Char::isWhitespace) || '@' in value || '*' in value) {
            return HostResult(issue = CleartextHostIssueKind.INVALID_HOST)
        }
        if (
            dottedNumericHost.matches(value) &&
            value.split('.').any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }
        ) {
            return HostResult(issue = CleartextHostIssueKind.INVALID_HOST)
        }

        val host = when {
            value.startsWith('[') -> {
                val closingBracket = value.indexOf(']')
                if (closingBracket < 0) {
                    return HostResult(issue = CleartextHostIssueKind.INVALID_HOST)
                }
                if (closingBracket != value.lastIndex) {
                    return HostResult(
                        issue = if (value.getOrNull(closingBracket + 1) == ':') {
                            CleartextHostIssueKind.PORT
                        } else {
                            CleartextHostIssueKind.INVALID_HOST
                        }
                    )
                }
                value.substring(1, closingBracket)
            }

            value.count { it == ':' } == 1 -> {
                return HostResult(issue = CleartextHostIssueKind.PORT)
            }

            else -> value
        }

        val canonicalHost = runCatching {
            HttpUrl.Builder()
                .scheme("http")
                .host(host)
                .build()
                .host
        }.getOrNull()

        return if (canonicalHost.isNullOrBlank()) {
            HostResult(issue = CleartextHostIssueKind.INVALID_HOST)
        } else {
            // Preserve the user's spelling for ordinary ASCII hostnames. Matching is already
            // case-insensitive, while IDNs and IPv6 must use OkHttp's canonical representation.
            val normalizedHost = if (host.any { it.code > 0x7f } || ':' in host) {
                canonicalHost
            } else {
                host
            }
            HostResult(host = normalizedHost)
        }
    }

    private data class HostResult(
        val host: String? = null,
        val issue: CleartextHostIssueKind? = null,
    )
}
