package com.gameocr.app.overlay

import com.gameocr.app.translate.WordResult

internal data class DictionaryTextLabels(
    val phonetic: String,
    val partOfSpeech: String,
    val definitions: String,
    val inflections: String,
    val synonyms: String,
    val difficultyNotes: String,
    val examples: String,
)

internal enum class DictionaryTextRole {
    LABEL,
    BODY,
    MUTED,
    MONOSPACE,
}

internal data class DictionaryTextSegment(
    val text: String,
    val role: DictionaryTextRole,
)

internal fun dictionaryTextSegments(
    wordResult: WordResult,
    labels: DictionaryTextLabels,
): List<DictionaryTextSegment> {
    val blocks = mutableListOf<List<DictionaryTextSegment>>()

    val metadata = buildList {
        if (wordResult.phonetic.isNotBlank()) {
            add(DictionaryTextSegment("${labels.phonetic}  ", DictionaryTextRole.LABEL))
            add(DictionaryTextSegment(wordResult.phonetic, DictionaryTextRole.MONOSPACE))
        }
        if (wordResult.pos.isNotEmpty()) {
            if (isNotEmpty()) add(DictionaryTextSegment("\n", DictionaryTextRole.BODY))
            add(DictionaryTextSegment("${labels.partOfSpeech}  ", DictionaryTextRole.LABEL))
            add(DictionaryTextSegment(wordResult.pos.joinToString(" / "), DictionaryTextRole.BODY))
        }
    }
    if (metadata.isNotEmpty()) blocks.add(metadata)

    if (wordResult.definitions.isNotEmpty()) {
        blocks.add(listOf(
            DictionaryTextSegment(labels.definitions, DictionaryTextRole.LABEL),
            DictionaryTextSegment(
                wordResult.definitions.mapIndexed { index, definition ->
                    "${index + 1}. $definition"
                }.joinToString(separator = "\n", prefix = "\n"),
                DictionaryTextRole.BODY,
            ),
        ))
    }

    if (wordResult.inflections.isNotEmpty()) {
        blocks.add(listOf(
            DictionaryTextSegment(labels.inflections, DictionaryTextRole.LABEL),
            DictionaryTextSegment(
                wordResult.inflections.joinToString(separator = "\n", prefix = "\n") { "・$it" },
                DictionaryTextRole.BODY,
            ),
        ))
    }

    if (wordResult.synonyms.isNotEmpty()) {
        blocks.add(listOf(
            DictionaryTextSegment(labels.synonyms, DictionaryTextRole.LABEL),
            DictionaryTextSegment(
                wordResult.synonyms.joinToString(separator = " / ", prefix = "\n"),
                DictionaryTextRole.BODY,
            ),
        ))
    }

    if (wordResult.difficultyNotes.isNotEmpty()) {
        blocks.add(listOf(
            DictionaryTextSegment(labels.difficultyNotes, DictionaryTextRole.LABEL),
            DictionaryTextSegment(
                wordResult.difficultyNotes.joinToString(separator = "\n", prefix = "\n") { "・$it" },
                DictionaryTextRole.BODY,
            ),
        ))
    }

    if (wordResult.examples.isNotEmpty()) {
        blocks.add(buildList<DictionaryTextSegment> {
            add(DictionaryTextSegment(labels.examples, DictionaryTextRole.LABEL))
            wordResult.examples.forEach { example ->
                add(DictionaryTextSegment("\n・${example.src}", DictionaryTextRole.MUTED))
                add(DictionaryTextSegment("\n  ${example.dst}", DictionaryTextRole.BODY))
            }
        })
    }

    return buildList {
        blocks.forEachIndexed { index, block ->
            if (index > 0) add(DictionaryTextSegment("\n\n", DictionaryTextRole.BODY))
            block.forEach { segment ->
                val previous = lastOrNull()
                if (previous?.role == segment.role) {
                    removeAt(lastIndex)
                    add(previous.copy(text = previous.text + segment.text))
                } else {
                    add(segment)
                }
            }
        }
    }
}

internal fun dictionaryPlainText(
    wordResult: WordResult,
    labels: DictionaryTextLabels,
): String = dictionaryTextSegments(wordResult, labels).joinToString(separator = "") { it.text }
