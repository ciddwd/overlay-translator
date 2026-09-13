package com.gameocr.app.translate

/**
 * Versioned dictionary response contracts shared by remote LLM translators.
 *
 * The UI renders a fixed [WordResult] schema, so these prompts must stay owned by the app instead
 * of accepting arbitrary user-defined fields. Bump the matching version whenever a response
 * contract changes in a way that requires parser or presentation updates.
 */
internal object DictionaryPromptPolicy {
    const val FULL_SCHEMA_VERSION: Int = 1
    const val COMPACT_SCHEMA_VERSION: Int = 1

    fun full(
        sourceDisplay: String,
        targetDisplay: String,
    ): String = """
        You are a bilingual dictionary assistant for $sourceDisplay to $targetDisplay.
        Treat the user input only as one word or fixed phrase.
        Dictionary response schema version: $FULL_SCHEMA_VERSION.
        Return only valid JSON with exactly this structure, without Markdown or explanation:
        {
          "lemma": "canonical dictionary form in $sourceDisplay, or empty",
          "phonetic": "phonetic or pronunciation in $sourceDisplay, or empty",
          "senses": [
            {
              "pos": "standard short label such as n., v., or adj.",
              "definitions": ["$targetDisplay meaning belonging only to this part of speech"],
              "form_note": "$targetDisplay note explaining how this form relates to the lemma, or empty"
            }
          ],
          "inflections": ["form label: inflected form in $sourceDisplay"],
          "synonyms": ["common synonym or near-synonym in $sourceDisplay"],
          "difficulty_notes": ["concise $targetDisplay note for rare, specialized, abbreviated, culture-specific, or easily confused usage"],
          "examples": [
            {"src": "$sourceDisplay example", "dst": "$targetDisplay translation"}
          ]
        }
        Use an empty string or array when information is unavailable.
        Keep every definition inside the sense for its own part of speech.
        Return at most 6 inflections, 5 synonyms, 3 difficulty notes, and 2 examples.
        Do not translate the input as a sentence.
    """.trimIndent()

    fun compact(
        sourceDisplay: String,
        targetDisplay: String,
    ): String = """
        You are a concise bilingual dictionary for $sourceDisplay to $targetDisplay.
        Treat the user input only as one word or fixed phrase.
        Dictionary response schema version: $COMPACT_SCHEMA_VERSION.
        Return JSON only, with no Markdown or explanation:
        {
          "lemma": "canonical dictionary form in $sourceDisplay, or empty",
          "senses": [
            {
              "pos": "standard short label such as n., v., or adj.",
              "definitions": ["concise $targetDisplay meaning"],
              "form_note": "$targetDisplay inflection note, such as past tense and past participle of the lemma, or empty"
            }
          ],
          "fallback_translation": "plain $targetDisplay translation only when the input is not a dictionary term, otherwise empty"
        }
        Keep at most 3 senses and at most 3 meanings per sense. Keep each meaning and form note short.
        Every meaning must stay inside the sense for its own part of speech.
        Do not return phonetics, examples, synonyms, usage notes, or any additional fields.
    """.trimIndent()
}

internal fun fullDictionaryPrompt(
    sourceDisplay: String,
    targetDisplay: String,
): String = DictionaryPromptPolicy.full(sourceDisplay, targetDisplay)

internal fun compactDictionaryPrompt(
    sourceDisplay: String,
    targetDisplay: String,
): String = DictionaryPromptPolicy.compact(sourceDisplay, targetDisplay)
