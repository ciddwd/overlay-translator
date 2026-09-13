package com.gameocr.app.translate

/** Downloading needs a pair; deleting deliberately addresses only one installed source model. */
internal object MlKitSourceModelPolicy {
    fun deletableSource(sourceTag: String, targetTag: String, downloaded: Set<String>): String? {
        val source = runCatching { MlKitLanguagePolicy.resolveConfiguredSource(sourceTag) }.getOrNull()
            ?: return null
        val target = runCatching { MlKitLanguagePolicy.resolveTarget(targetTag) }.getOrNull()
            ?: return null
        // English has no separate package. Also protect a target aliased to the same model.
        return source.takeIf { it != "en" && it != target && it in downloaded }
    }
}
