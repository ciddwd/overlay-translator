package com.gameocr.app.translate

/** Apps whose editors cannot currently be read and replaced safely through Accessibility. */
object InputTranslationAppPolicy {
    private val blockedPackages = setOf(
        "com.tencent.mm",
    )

    fun isBlocked(packageName: String?): Boolean =
        packageName != null && packageName in blockedPackages
}
