package com.gameocr.app.dictionary

/** Stable SQLite contract used by generated/imported offline dictionary packages. */
object DictionaryPackContract {
    const val SCHEMA_VERSION = 1
    const val METADATA_TABLE = "dictionary_metadata"
    const val ENTRIES_TABLE = "dictionary_entries"
    const val FORMS_TABLE = "dictionary_forms"
    const val SENSES_TABLE = "dictionary_senses"

    val requiredColumns: Map<String, Set<String>> = mapOf(
        METADATA_TABLE to setOf("key", "value"),
        ENTRIES_TABLE to setOf(
            "id",
            "headword",
            "normalized_headword",
            "lemma",
            "reading",
            "phonetic",
            "inflections",
        ),
        FORMS_TABLE to setOf("entry_id", "normalized_form"),
        SENSES_TABLE to setOf(
            "entry_id",
            "position",
            "part_of_speech",
            "definition",
            "form_note",
        ),
    )
}
