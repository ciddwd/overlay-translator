from __future__ import annotations

import csv
from contextlib import closing
import gzip
import importlib.util
from pathlib import Path
import re
import sqlite3
import sys
import tempfile
import textwrap
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build_dictionary_packs.py"
SPEC = importlib.util.spec_from_file_location("build_dictionary_packs", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def android_lookup_sql() -> tuple[str, str]:
    """Execute the app's actual SQL, not a separately maintained test query."""
    package = SCRIPT.parents[2] / "app/src/main/java/com/gameocr/app/dictionary"
    constants = dict(re.findall(r'const val (\w+) = "([^"]+)"',
                               (package / "DictionaryPackContract.kt").read_text(encoding="utf-8")))
    source = (package / "OfflineDictionaryRepository.kt").read_text(encoding="utf-8")
    queries = re.findall(r'"""(\s*SELECT\b.*?)"""\.trimIndent\(\)', source, re.DOTALL)
    if len(queries) != 2:
        raise AssertionError("Update the SQL extractor for the real app query representation")
    return tuple(textwrap.dedent(re.sub(
        r'\$\{DictionaryPackContract\.(\w+)\}', lambda m: constants[m[1]], query,
    )).strip() for query in queries)


def android_lookup(database: Path, word: str) -> list[tuple[object, ...]]:
    entries_sql, senses_sql = android_lookup_sql()
    normalized = module.normalize_headword(word)
    connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        entries = list(connection.execute(entries_sql, (normalized,) * 3))
        return [sense for entry in entries for sense in connection.execute(senses_sql, (entry[0],))]
    finally:
        connection.close()


def rows(database: Path, query: str) -> list[tuple[object, ...]]:
    connection = sqlite3.connect(database)
    try:
        return list(connection.execute(query))
    finally:
        connection.close()


class DictionaryPackBuilderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_normalization_table_driven_matches_android_contract(self) -> None:
        cases = {
            "  DISPLAYED ": "displayed",
            "Ｄｉｓｐｌａｙ": "display",
            "食べる": "食べる",
            "한국어": "한국어",
        }
        for source, expected in cases.items():
            with self.subTest(source=source):
                self.assertEqual(expected, module.normalize_headword(source))

    def test_ecdict_table_driven_builds_forms_senses_and_metadata(self) -> None:
        source = self.root / "ecdict.csv"
        fieldnames = [
            "word", "phonetic", "definition", "translation", "pos", "collins", "oxford",
            "tag", "bnc", "frq", "exchange", "detail", "audio",
        ]
        with source.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(
                [
                    {
                        "word": "displayed",
                        "phonetic": "dɪˈspleɪd",
                        "translation": "a. 显示的\\nv. 展示；陈列",
                        "exchange": "p:displayed/d:displayed/i:displaying/3:displays/0:display",
                    },
                    {"word": "empty"},
                ]
            )
        output = self.root / "ecdict.bin"
        summary = module.convert_ecdict(source, output, "fixture", False)
        self.assertEqual(1, summary.entry_count)
        self.assertEqual(
            [("displayed", "display", "dɪˈspleɪd", '["displayed","displaying","displays"]')],
            rows(output, "SELECT headword, lemma, phonetic, inflections FROM dictionary_entries"),
        )
        self.assertEqual(
            [("adj.", "显示的"), ("v.", "展示；陈列")],
            rows(output, "SELECT part_of_speech, definition FROM dictionary_senses ORDER BY position"),
        )
        self.assertEqual(
            [("display",), ("displaying",), ("displays",)],
            rows(output, "SELECT normalized_form FROM dictionary_forms ORDER BY normalized_form"),
        )
        self.assertEqual("ecdict", module.validate_pack(output, "ecdict")["pack_id"])
        for term in ("displayed", "DISPLAYED", "ＤＩＳＰＬＡＹＥＤ", "displaying", "displays", "display"):
            with self.subTest(term=term):
                self.assertEqual([("adj.", "显示的", ""), ("v.", "展示；陈列", "")], android_lookup(output, term))

    def test_jmdict_table_driven_builds_kanji_reading_and_english_glosses(self) -> None:
        cases = [
            ("食べる", "たべる", "Ichidan verb", "to eat"),
            ("ありがとう", "ありがとう", "expression", "thank you"),
        ]
        entries = []
        for index, (written, reading, pos, gloss) in enumerate(cases, start=1):
            kanji = f"<k_ele><keb>{written}</keb></k_ele>" if written != reading else ""
            entries.append(
                f"<entry><ent_seq>{index}</ent_seq>{kanji}<r_ele><reb>{reading}</reb></r_ele>"
                f"<sense><pos>{pos}</pos><gloss>{gloss}</gloss></sense></entry>"
            )
        source = self.root / "JMdict_e.gz"
        with gzip.open(source, "wt", encoding="utf-8") as stream:
            stream.write("<?xml version='1.0' encoding='UTF-8'?><JMdict>" + "".join(entries) + "</JMdict>")
        output = self.root / "jmdict.bin"
        summary = module.convert_jmdict(source, output, "fixture", False)
        self.assertEqual(2, summary.entry_count)
        self.assertEqual(
            [("ありがとう", "ありがとう"), ("食べる", "たべる")],
            rows(output, "SELECT headword, reading FROM dictionary_entries ORDER BY headword"),
        )
        self.assertEqual(
            [("expression", "thank you"), ("Ichidan verb", "to eat")],
            rows(output, "SELECT part_of_speech, definition FROM dictionary_senses ORDER BY definition"),
        )
        for written, reading, pos, gloss in cases:
            for term in (written, reading):
                with self.subTest(term=term):
                    self.assertEqual([(pos, gloss, "")], android_lookup(output, term))

    def test_korean_table_driven_prefers_chinese_definition_and_keeps_inflection(self) -> None:
        cases = [
            ("가다", "가", "动词", "去。"),
            ("집", "", "名词", "房子。"),
        ]
        lexical_entries = []
        for index, (word, form, chinese_lemma, chinese_definition) in enumerate(cases, start=1):
            word_form = (
                f"<WordForm><feat att='type' val='활용'/><feat att='writtenForm' val='{form}'/></WordForm>"
                if form else ""
            )
            lexical_entries.append(
                f"<LexicalEntry att='id' val='{index}'><feat att='partOfSpeech' val='명사'/>"
                f"<Lemma><feat att='writtenForm' val='{word}'/></Lemma>"
                f"<WordForm><feat att='type' val='발음'/><feat att='pronunciation' val='{word}'/></WordForm>"
                f"{word_form}<Sense att='id' val='1'><feat att='definition' val='한국어 \x08뜻'/>"
                f"<Equivalent><feat att='language' val='중국어'/><feat att='lemma' val='{chinese_lemma}'/>"
                f"<feat att='definition' val='{chinese_definition}'/></Equivalent></Sense></LexicalEntry>"
            )
        source = self.root / "001.xml"
        source.write_text(
            "<?xml version='1.0' encoding='UTF-8'?><LexicalResource><GlobalInformation>"
            "<feat att='creationDate' val='2026/08/01'/></GlobalInformation><Lexicon>"
            + "".join(lexical_entries)
            + "</Lexicon></LexicalResource>",
            encoding="utf-8",
        )
        output = self.root / "korean-basic.bin"
        summary = module.convert_korean([source], output, "fixture", False)
        self.assertEqual(2, summary.entry_count)
        self.assertEqual(
            [("가다", '["가"]'), ("집", "[]")],
            rows(output, "SELECT headword, inflections FROM dictionary_entries ORDER BY headword"),
        )
        self.assertEqual(
            [("去。", "对译：动词"), ("房子。", "对译：名词")],
            rows(output, "SELECT definition, form_note FROM dictionary_senses ORDER BY entry_id"),
        )
        for word, form, _, definition in cases:
            for term in filter(None, (word, form)):
                with self.subTest(term=term):
                    self.assertEqual(definition, android_lookup(output, term)[0][1])
        self.assertEqual(
            [("2",)],
            rows(
                output,
                "SELECT value FROM dictionary_metadata "
                "WHERE key = 'sanitized_xml_character_count'",
            ),
        )

    def test_validator_table_driven_rejects_wrong_pack_and_corrupt_schema(self) -> None:
        source = self.root / "ecdict.csv"
        source.write_text(
            "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n"
            "word,,n a unit,词语,n,,,,,,,,\n",
            encoding="utf-8",
        )
        output = self.root / "ecdict.db"
        module.convert_ecdict(source, output, "fixture", False)
        cases = [
            ("wrong pack", lambda: module.validate_pack(output, "jmdict"), "Expected pack_id"),
            (
                "missing schema",
                lambda: module.validate_pack(self._empty_database(), None),
                "missing columns",
            ),
        ]
        for name, action, message in cases:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, message):
                action()

    def test_package_extension_table_driven_preserves_validation_and_actual_app_lookup(self) -> None:
        cases = [
            ("ecdict", "display", "displayed", "展示"),
            ("jmdict", "食べる", "たべる", "to eat"),
            ("korean-basic", "가다", "가", "去"),
        ]
        for pack_id, headword, form, definition in cases:
            for index, extension in enumerate((".bin", ".BIN", ".db")):
                with self.subTest(pack_id=pack_id, extension=extension):
                    output = self.root / f"{pack_id}-{index}{extension}"
                    with module.PackWriter(output, pack_id, "fixture") as writer:
                        writer.add(module.Entry(headword, forms=(form,), senses=(module.Sense("v.", definition),)))
                        writer.finish()
                    self.assertEqual("ok", module.validate_pack(output, pack_id)["quick_check"])
                    for word in (headword, form):
                        self.assertEqual([("v.", definition, "")], android_lookup(output, word))
                    self.assertEqual([], android_lookup(output, "absent-word"))

        for extension in (".bin", ".db"):
            with self.subTest(invalid_extension=extension):
                invalid = self.root / f"invalid{extension}"
                invalid.write_bytes(b"not a dictionary package")
                with self.assertRaises(sqlite3.DatabaseError):
                    module.validate_pack(invalid, "ecdict")

    def _empty_database(self) -> Path:
        path = self.root / "empty.db"
        connection = sqlite3.connect(path)
        connection.close()
        return path

    def test_actual_app_query_supports_both_table_kinds_and_explicit_sense_order(self) -> None:
        for without_rowid in (True, False):
            with self.subTest(without_rowid=without_rowid):
                output = self.root / f"ordered-{without_rowid}.db"
                with module.PackWriter(output, "ecdict", "fixture") as writer:
                    writer.add(module.Entry("display", forms=("displayed",), senses=(module.Sense("v.", "展示"),)))
                    writer.finish()
                with closing(sqlite3.connect(output)) as database:
                    # Keep an ordinary rowid table as a compatibility control as well.
                    if not without_rowid:
                        database.execute("ALTER TABLE dictionary_senses RENAME TO original_senses")
                        database.execute("CREATE TABLE dictionary_senses AS SELECT * FROM original_senses WHERE 0")
                        database.execute("DROP TABLE original_senses")
                    database.execute("DELETE FROM dictionary_senses")
                    database.executemany("INSERT INTO dictionary_senses VALUES (?, ?, ?, ?, ?)", [
                        (1, 3, "n.", "陈列", ""), (1, 1, "v.", "展示", ""), (1, 2, "v.", "表现", "过去分词"),
                    ])
                    database.commit()
                expected = [("v.", "展示", ""), ("v.", "表现", "过去分词"), ("n.", "陈列", "")]
                for term in ("display", "displayed"):
                    self.assertEqual(expected, android_lookup(output, term))
                for term in ("missing", "", "' OR 1=1 --"):
                    self.assertEqual([], android_lookup(output, term))
                with closing(sqlite3.connect(output)) as database:
                    database.execute("DROP TABLE dictionary_senses")
                    database.commit()
                with self.assertRaises(sqlite3.OperationalError):
                    android_lookup(output, "display")


if __name__ == "__main__":
    unittest.main()
