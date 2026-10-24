package com.gameocr.app.asr

/** Pairs user-picked files with a model's pinned manifest before anything is copied. */
internal object AsrImportPolicy {
    sealed interface Match {
        data class Matched(val files: List<Pair<AsrModelFile, AsrImportSource>>) : Match
        data class Missing(val name: String) : Match
    }

    /**
     * Exact file names win. Otherwise a file whose reported size equals the entry's pinned size is
     * accepted, but only when exactly one unused file has that size, because browsers and file
     * managers often rename downloads ("model.int8 (1).onnx"). Hashes are checked while copying.
     */
    fun match(model: AsrModelId, sources: List<AsrImportSource>): Match {
        val chosen = mutableMapOf<AsrModelFile, AsrImportSource>()
        // Names first for every entry, so a size match never takes a file another entry names.
        for (spec in model.files) {
            sources.firstOrNull { it.name == spec.name && it !in chosen.values }?.let { chosen[spec] = it }
        }
        for (spec in model.files) {
            if (spec in chosen) continue
            val candidate = sources.filter { it !in chosen.values && it.size == spec.bytes }.singleOrNull()
                ?: return Match.Missing(spec.name)
            chosen[spec] = candidate
        }
        return Match.Matched(model.files.map { it to chosen.getValue(it) })
    }
}
