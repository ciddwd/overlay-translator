#!/usr/bin/env python3
"""Build app-compatible offline dictionary SQLite packages.

The converter intentionally uses only the Python standard library.  All source
formats are processed incrementally so full dictionaries do not need to fit in
memory.
"""

from __future__ import annotations

import argparse
import codecs
import contextlib
import csv
import gzip
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from dataclasses import dataclass, field
from typing import BinaryIO, Iterable, Iterator, Sequence, TextIO
import unicodedata
import xml.etree.ElementTree as ET
import zipfile


SCHEMA_VERSION = 1
BUFFER_ENTRY_COUNT = 2_000
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"

PACK_METADATA = {
    "ecdict": {
        "source_name": "ECDICT",
        "source_url": "https://github.com/skywind3000/ECDICT",
        "license_name": "MIT",
        "license_url": "https://raw.githubusercontent.com/skywind3000/ECDICT/master/LICENSE",
        "definition_language": "zh",
    },
    "jmdict": {
        "source_name": "JMdict / EDRDG",
        "source_url": "https://www.edrdg.org/pub/Nihongo/JMdict_e.gz",
        "license_name": "CC BY-SA 4.0",
        "license_url": "https://www.edrdg.org/edrdg/licence.html",
        "definition_language": "en",
    },
    "korean-basic": {
        "source_name": "Korean Basic Dictionary / National Institute of Korean Language",
        "source_url": "https://github.com/spellcheck-ko/korean-dict-nikl/tree/master/krdict",
        "license_name": "CC BY-SA 2.0 KR (text data)",
        "license_url": "https://creativecommons.org/licenses/by-sa/2.0/kr/",
        "definition_language": "zh",
    },
}


@dataclass(frozen=True)
class Sense:
    part_of_speech: str
    definition: str
    form_note: str = ""


@dataclass(frozen=True)
class Entry:
    headword: str
    lemma: str = ""
    reading: str = ""
    phonetic: str = ""
    inflections: tuple[str, ...] = ()
    forms: tuple[str, ...] = ()
    senses: tuple[Sense, ...] = ()


@dataclass(frozen=True)
class BuildSummary:
    output: Path
    pack_id: str
    data_version: str
    entry_count: int
    form_count: int
    sense_count: int
    byte_count: int

    def as_dict(self) -> dict[str, object]:
        return {
            "output": str(self.output),
            "pack_id": self.pack_id,
            "data_version": self.data_version,
            "entry_count": self.entry_count,
            "form_count": self.form_count,
            "sense_count": self.sense_count,
            "byte_count": self.byte_count,
        }


def normalize_headword(value: str) -> str:
    return unicodedata.normalize("NFKC", value.strip()).lower()


def distinct_non_blank(values: Iterable[str]) -> tuple[str, ...]:
    result: list[str] = []
    seen: set[str] = set()
    for raw in values:
        value = raw.strip()
        if not value or value in seen:
            continue
        seen.add(value)
        result.append(value)
    return tuple(result)


class PackWriter:
    def __init__(self, output: Path, pack_id: str, data_version: str, force: bool = False) -> None:
        if pack_id not in PACK_METADATA:
            raise ValueError(f"Unsupported pack id: {pack_id}")
        self.output = output.resolve()
        self.pack_id = pack_id
        self.data_version = data_version.strip()
        if not self.data_version:
            raise ValueError("data_version must not be blank")
        self.force = force
        self.temporary = self.output.with_name(f"{self.output.name}.building")
        self.connection: sqlite3.Connection | None = None
        self.entries: list[tuple[object, ...]] = []
        self.forms: list[tuple[object, ...]] = []
        self.senses: list[tuple[object, ...]] = []
        self.entry_count = 0
        self.form_count = 0
        self.sense_count = 0

    def __enter__(self) -> "PackWriter":
        self.output.parent.mkdir(parents=True, exist_ok=True)
        if self.output.exists() and not self.force:
            raise FileExistsError(f"Output already exists: {self.output}; pass --force to replace it")
        self.temporary.unlink(missing_ok=True)
        self.connection = sqlite3.connect(self.temporary)
        self.connection.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            PRAGMA temp_store = MEMORY;
            PRAGMA page_size = 4096;
            CREATE TABLE dictionary_metadata (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            );
            CREATE TABLE dictionary_entries (
                id INTEGER PRIMARY KEY,
                headword TEXT NOT NULL,
                normalized_headword TEXT NOT NULL,
                lemma TEXT NOT NULL DEFAULT '',
                reading TEXT NOT NULL DEFAULT '',
                phonetic TEXT NOT NULL DEFAULT '',
                inflections TEXT NOT NULL DEFAULT '[]'
            );
            CREATE TABLE dictionary_forms (
                entry_id INTEGER NOT NULL,
                normalized_form TEXT NOT NULL,
                PRIMARY KEY (entry_id, normalized_form)
            ) WITHOUT ROWID;
            CREATE TABLE dictionary_senses (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                part_of_speech TEXT NOT NULL DEFAULT '',
                definition TEXT NOT NULL,
                form_note TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (entry_id, position)
            ) WITHOUT ROWID;
            """
        )
        return self

    def add(self, entry: Entry) -> bool:
        headword = entry.headword.strip()
        normalized = normalize_headword(headword)
        senses = tuple(sense for sense in entry.senses if sense.definition.strip())
        if not headword or not normalized or not senses:
            return False
        self.entry_count += 1
        entry_id = self.entry_count
        inflections = distinct_non_blank(entry.inflections)
        self.entries.append(
            (
                entry_id,
                headword,
                normalized,
                entry.lemma.strip() or headword,
                entry.reading.strip(),
                entry.phonetic.strip(),
                json.dumps(inflections, ensure_ascii=False, separators=(",", ":")),
            )
        )
        normalized_forms = distinct_non_blank(
            normalize_headword(form)
            for form in (*entry.forms, *inflections)
            if normalize_headword(form) and normalize_headword(form) != normalized
        )
        self.forms.extend((entry_id, form) for form in normalized_forms)
        self.senses.extend(
            (
                entry_id,
                position,
                sense.part_of_speech.strip(),
                sense.definition.strip(),
                sense.form_note.strip(),
            )
            for position, sense in enumerate(senses, start=1)
        )
        if len(self.entries) >= BUFFER_ENTRY_COUNT:
            self.flush()
        return True

    def flush(self) -> None:
        if not self.entries:
            return
        assert self.connection is not None
        self.connection.executemany(
            "INSERT INTO dictionary_entries VALUES (?, ?, ?, ?, ?, ?, ?)", self.entries
        )
        self.connection.executemany(
            "INSERT OR IGNORE INTO dictionary_forms VALUES (?, ?)", self.forms
        )
        self.connection.executemany(
            "INSERT INTO dictionary_senses VALUES (?, ?, ?, ?, ?)", self.senses
        )
        self.form_count += len(self.forms)
        self.sense_count += len(self.senses)
        self.entries.clear()
        self.forms.clear()
        self.senses.clear()

    def finish(self, extra_metadata: dict[str, str] | None = None) -> BuildSummary:
        assert self.connection is not None
        self.flush()
        metadata = {
            "pack_id": self.pack_id,
            "schema_version": str(SCHEMA_VERSION),
            "data_version": self.data_version,
            "entry_count": str(self.entry_count),
            **PACK_METADATA[self.pack_id],
            **(extra_metadata or {}),
        }
        self.connection.executemany(
            "INSERT INTO dictionary_metadata(key, value) VALUES (?, ?)", sorted(metadata.items())
        )
        self.connection.execute(
            "CREATE INDEX dictionary_entries_headword_idx "
            "ON dictionary_entries(normalized_headword)"
        )
        self.connection.execute(
            "CREATE INDEX dictionary_forms_form_idx "
            "ON dictionary_forms(normalized_form, entry_id)"
        )
        self.connection.execute(
            "CREATE INDEX dictionary_senses_entry_idx "
            "ON dictionary_senses(entry_id, position)"
        )
        self.connection.commit()
        self.connection.execute("ANALYZE")
        self.connection.commit()
        integrity = self.connection.execute("PRAGMA quick_check(1)").fetchone()
        if integrity is None or str(integrity[0]).lower() != "ok":
            raise RuntimeError(f"Generated database failed quick_check: {integrity}")
        foreign_orphans = self.connection.execute(
            """
            SELECT
                (SELECT COUNT(*) FROM dictionary_forms f
                 LEFT JOIN dictionary_entries e ON e.id = f.entry_id WHERE e.id IS NULL) +
                (SELECT COUNT(*) FROM dictionary_senses s
                 LEFT JOIN dictionary_entries e ON e.id = s.entry_id WHERE e.id IS NULL)
            """
        ).fetchone()[0]
        if foreign_orphans:
            raise RuntimeError(f"Generated database has {foreign_orphans} orphan rows")
        self.connection.close()
        self.connection = None
        if self.output.exists():
            self.output.unlink()
        self.temporary.replace(self.output)
        return BuildSummary(
            output=self.output,
            pack_id=self.pack_id,
            data_version=self.data_version,
            entry_count=self.entry_count,
            form_count=self.form_count,
            sense_count=self.sense_count,
            byte_count=self.output.stat().st_size,
        )

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if self.connection is not None:
            self.connection.close()
        if exc_type is not None:
            self.temporary.unlink(missing_ok=True)


@contextlib.contextmanager
def open_text_source(path: Path, suffix: str) -> Iterator[TextIO]:
    lower = path.name.lower()
    if lower.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            candidates = sorted(name for name in archive.namelist() if name.lower().endswith(suffix))
            if not candidates:
                raise ValueError(f"No {suffix} source found in {path}")
            with archive.open(candidates[0]) as raw:
                with contextlib.closing(
                    __import__("io").TextIOWrapper(raw, encoding="utf-8-sig", newline="")
                ) as text:
                    yield text
    elif lower.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8-sig", newline="") as text:
            yield text
    else:
        with path.open("r", encoding="utf-8-sig", newline="") as text:
            yield text


ECDICT_POS_CODES = {
    "a": "adj.",
    "j": "adj.",
    "c": "conj.",
    "d": "det.",
    "m": "num.",
    "n": "n.",
    "p": "prep.",
    "r": "adv.",
    "u": "aux.",
    "v": "v.",
}
ECDICT_LINE_POS = re.compile(
    r"^\s*((?:n|v|vi|vt|a|ad|adj|adv|prep|pron|conj|num|art|aux)\.)\s*(.+)$",
    re.I,
)
ECDICT_WORDNET_POS = re.compile(r"^\s*([nvars])\s+(.+)$", re.I)
INVALID_XML_10_CHARACTERS = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f\ud800-\udfff\ufffe\uffff]")


def split_source_lines(value: str) -> list[str]:
    normalized = value.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
    return [line.strip() for line in normalized.splitlines() if line.strip()]


def ecdict_senses(row: dict[str, str]) -> tuple[Sense, ...]:
    translation = row.get("translation", "").strip()
    source = translation or row.get("definition", "").strip()
    fallback_pos = ECDICT_POS_CODES.get(row.get("pos", "").strip().lower(), row.get("pos", "").strip())
    result: list[Sense] = []
    for line in split_source_lines(source):
        match = ECDICT_LINE_POS.match(line)
        if match:
            label = match.group(1).lower()
            result.append(Sense({"a.": "adj.", "ad.": "adv."}.get(label, label), match.group(2)))
            continue
        wordnet = ECDICT_WORDNET_POS.match(line) if not translation else None
        if wordnet:
            result.append(Sense(ECDICT_POS_CODES.get(wordnet.group(1).lower(), wordnet.group(1)), wordnet.group(2)))
        else:
            result.append(Sense(fallback_pos, line))
    return tuple(result)


def ecdict_forms(exchange: str) -> tuple[tuple[str, ...], tuple[str, ...], str]:
    forms: list[str] = []
    inflections: list[str] = []
    lemma = ""
    for segment in exchange.split("/"):
        if ":" not in segment:
            continue
        code, raw_value = segment.split(":", 1)
        values = [value.strip() for value in raw_value.split(",") if value.strip()]
        forms.extend(values)
        if code.strip() == "0" and values:
            lemma = values[0]
        elif code.strip() in {"p", "d", "i", "3", "r", "t", "s"}:
            inflections.extend(values)
    return distinct_non_blank(forms), distinct_non_blank(inflections), lemma


def convert_ecdict(
    source: Path,
    output: Path,
    data_version: str,
    force: bool = False,
    limit: int | None = None,
) -> BuildSummary:
    csv.field_size_limit(max(csv.field_size_limit(), 16 * 1024 * 1024))
    with open_text_source(source, ".csv") as text, PackWriter(
        output, "ecdict", data_version, force
    ) as writer:
        reader = csv.DictReader(text)
        if not reader.fieldnames or "word" not in reader.fieldnames:
            raise ValueError("ECDICT CSV must contain the word column")
        for row in reader:
            forms, inflections, lemma = ecdict_forms(row.get("exchange", ""))
            writer.add(
                Entry(
                    headword=row.get("word", ""),
                    lemma=lemma or row.get("word", ""),
                    phonetic=row.get("phonetic", ""),
                    inflections=inflections,
                    forms=forms,
                    senses=ecdict_senses(row),
                )
            )
            if limit is not None and writer.entry_count >= limit:
                break
        return writer.finish()


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child_texts(element: ET.Element, child_name: str, grandchild_name: str) -> list[str]:
    result: list[str] = []
    for child in element:
        if local_name(child.tag) != child_name:
            continue
        for grandchild in child:
            if local_name(grandchild.tag) == grandchild_name and grandchild.text:
                result.append(grandchild.text.strip())
    return [value for value in result if value]


def parse_jmdict_entry(element: ET.Element) -> Entry:
    written = child_texts(element, "k_ele", "keb")
    readings = child_texts(element, "r_ele", "reb")
    headword = (written or readings or [""])[0]
    senses: list[Sense] = []
    for sense_element in element:
        if local_name(sense_element.tag) != "sense":
            continue
        positions = [
            child.text.strip()
            for child in sense_element
            if local_name(child.tag) == "pos" and child.text and child.text.strip()
        ]
        notes = [
            child.text.strip()
            for child in sense_element
            if local_name(child.tag) == "s_inf" and child.text and child.text.strip()
        ]
        for child in sense_element:
            if local_name(child.tag) != "gloss" or not child.text or not child.text.strip():
                continue
            language = child.attrib.get(XML_LANG, child.attrib.get("lang", "eng"))
            if language not in {"", "en", "eng"}:
                continue
            senses.append(Sense("; ".join(positions), child.text.strip(), "; ".join(notes)))
    return Entry(
        headword=headword,
        lemma=headword,
        reading=readings[0] if readings else "",
        forms=distinct_non_blank((*written, *readings)),
        senses=tuple(senses),
    )


@contextlib.contextmanager
def open_binary_source(path: Path) -> Iterator[BinaryIO]:
    if path.name.lower().endswith(".gz"):
        with gzip.open(path, "rb") as source:
            yield source
    else:
        with path.open("rb") as source:
            yield source


def convert_jmdict(
    source: Path,
    output: Path,
    data_version: str,
    force: bool = False,
    limit: int | None = None,
) -> BuildSummary:
    with open_binary_source(source) as binary, PackWriter(
        output, "jmdict", data_version, force
    ) as writer:
        for _, element in ET.iterparse(binary, events=("end",)):
            if local_name(element.tag) != "entry":
                continue
            writer.add(parse_jmdict_entry(element))
            element.clear()
            if limit is not None and writer.entry_count >= limit:
                break
        return writer.finish()


def feature_map(element: ET.Element, direct_only: bool = True) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    candidates = list(element) if direct_only else element.iter()
    for child in candidates:
        if local_name(child.tag) != "feat":
            continue
        key = child.attrib.get("att", "").strip()
        value = child.attrib.get("val", "").strip()
        if key and value:
            result.setdefault(key, []).append(value)
    return result


class Xml10SanitizingBinaryReader:
    """Remove characters forbidden by XML 1.0 without loading the source file."""

    def __init__(self, source: BinaryIO) -> None:
        self.source = source
        self.decoder = codecs.getincrementaldecoder("utf-8-sig")("strict")
        self.buffer = bytearray()
        self.finished = False
        self.removed_count = 0

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            chunks = [bytes(self.buffer)]
            self.buffer.clear()
            while not self.finished:
                chunks.append(self._read_cleaned(64 * 1024))
            return b"".join(chunks)
        while len(self.buffer) < size and not self.finished:
            self.buffer.extend(self._read_cleaned(max(size, 64 * 1024)))
        result = bytes(self.buffer[:size])
        del self.buffer[:size]
        return result

    def _read_cleaned(self, size: int) -> bytes:
        raw = self.source.read(size)
        if raw:
            decoded = self.decoder.decode(raw, final=False)
        else:
            decoded = self.decoder.decode(b"", final=True)
            self.finished = True
        cleaned, removed = INVALID_XML_10_CHARACTERS.subn("", decoded)
        self.removed_count += removed
        return cleaned.encode("utf-8")


def first_feature(features: dict[str, list[str]], *names: str) -> str:
    for name in names:
        values = features.get(name, [])
        if values:
            return values[0]
    return ""


def parse_korean_entry(element: ET.Element) -> Entry:
    root_features = feature_map(element)
    lemma_element = next(
        (child for child in element if local_name(child.tag) == "Lemma"), None
    )
    lemma_features = feature_map(lemma_element) if lemma_element is not None else {}
    headword = first_feature(lemma_features, "writtenForm", "lemma")
    part_of_speech = first_feature(root_features, "partOfSpeech")
    pronunciations: list[str] = []
    forms: list[str] = []
    for child in element:
        if local_name(child.tag) != "WordForm":
            continue
        features = feature_map(child)
        pronunciations.extend(features.get("pronunciation", []))
        for name in ("writtenForm", "form", "wordForm", "abbreviation"):
            forms.extend(features.get(name, []))
    senses: list[Sense] = []
    for sense_element in element:
        if local_name(sense_element.tag) != "Sense":
            continue
        features = feature_map(sense_element)
        korean_definition = first_feature(features, "definition")
        annotation = first_feature(features, "annotation")
        chinese_definition = ""
        chinese_lemma = ""
        for equivalent in sense_element:
            if local_name(equivalent.tag) != "Equivalent":
                continue
            equivalent_features = feature_map(equivalent)
            if first_feature(equivalent_features, "language") != "중국어":
                continue
            chinese_definition = first_feature(equivalent_features, "definition")
            chinese_lemma = first_feature(equivalent_features, "lemma")
            break
        definition = chinese_definition or korean_definition
        note_parts = []
        if chinese_lemma and chinese_lemma not in definition:
            note_parts.append(f"对译：{chinese_lemma}")
        if not chinese_definition and annotation:
            note_parts.append(annotation)
        if definition:
            senses.append(Sense(part_of_speech, definition, "；".join(note_parts)))
    inflections = distinct_non_blank(forms)
    return Entry(
        headword=headword,
        lemma=headword,
        phonetic=pronunciations[0] if pronunciations else "",
        inflections=inflections,
        forms=inflections,
        senses=tuple(senses),
    )


@contextlib.contextmanager
def korean_xml_streams(paths: Sequence[Path]) -> Iterator[Iterator[tuple[str, BinaryIO]]]:
    stack = contextlib.ExitStack()
    try:
        streams: list[tuple[str, BinaryIO]] = []
        for path in paths:
            if path.is_dir():
                for xml_path in sorted(path.glob("*.xml")):
                    streams.append((str(xml_path), stack.enter_context(xml_path.open("rb"))))
            elif path.name.lower().endswith(".zip"):
                archive = stack.enter_context(zipfile.ZipFile(path))
                for name in sorted(item for item in archive.namelist() if item.lower().endswith(".xml")):
                    streams.append((f"{path}!{name}", stack.enter_context(archive.open(name))))
            elif path.name.lower().endswith(".xml"):
                streams.append((str(path), stack.enter_context(path.open("rb"))))
            else:
                raise ValueError(f"Unsupported Korean dictionary source: {path}")
        if not streams:
            raise ValueError("No KRDICT XML files found")
        yield iter(streams)
    finally:
        stack.close()


def convert_korean(
    sources: Sequence[Path],
    output: Path,
    data_version: str,
    force: bool = False,
    limit: int | None = None,
) -> BuildSummary:
    creation_dates: set[str] = set()
    sanitized_character_count = 0
    with korean_xml_streams(sources) as streams, PackWriter(
        output, "korean-basic", data_version, force
    ) as writer:
        reached_limit = False
        for _, binary in streams:
            sanitized = Xml10SanitizingBinaryReader(binary)
            for _, element in ET.iterparse(sanitized, events=("end",)):
                if local_name(element.tag) == "feat" and element.attrib.get("att") == "creationDate":
                    value = element.attrib.get("val", "").strip()
                    if value:
                        creation_dates.add(value)
                if local_name(element.tag) != "LexicalEntry":
                    continue
                writer.add(parse_korean_entry(element))
                element.clear()
                if limit is not None and writer.entry_count >= limit:
                    reached_limit = True
                    break
            sanitized_character_count += sanitized.removed_count
            if reached_limit:
                break
        return writer.finish(
            {
                "source_creation_dates": json.dumps(sorted(creation_dates), ensure_ascii=False),
                "sanitized_xml_character_count": str(sanitized_character_count),
            }
        )


REQUIRED_COLUMNS = {
    "dictionary_metadata": {"key", "value"},
    "dictionary_entries": {
        "id", "headword", "normalized_headword", "lemma", "reading", "phonetic", "inflections"
    },
    "dictionary_forms": {"entry_id", "normalized_form"},
    "dictionary_senses": {"entry_id", "position", "part_of_speech", "definition", "form_note"},
}


def validate_pack(path: Path, expected_pack_id: str | None = None) -> dict[str, object]:
    if not path.is_file():
        raise FileNotFoundError(path)
    connection = sqlite3.connect(f"file:{path.resolve().as_posix()}?mode=ro", uri=True)
    try:
        quick_check = connection.execute("PRAGMA quick_check(1)").fetchone()[0]
        if str(quick_check).lower() != "ok":
            raise ValueError(f"quick_check failed: {quick_check}")
        for table, expected in REQUIRED_COLUMNS.items():
            actual = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
            if not expected.issubset(actual):
                raise ValueError(f"{table} missing columns: {sorted(expected - actual)}")
        metadata = dict(connection.execute("SELECT key, value FROM dictionary_metadata"))
        if metadata.get("schema_version") != str(SCHEMA_VERSION):
            raise ValueError(f"Unsupported schema version: {metadata.get('schema_version')}")
        if expected_pack_id and metadata.get("pack_id") != expected_pack_id:
            raise ValueError(
                f"Expected pack_id={expected_pack_id}, found {metadata.get('pack_id')}"
            )
        entry_count = connection.execute("SELECT COUNT(*) FROM dictionary_entries").fetchone()[0]
        form_count = connection.execute("SELECT COUNT(*) FROM dictionary_forms").fetchone()[0]
        sense_count = connection.execute("SELECT COUNT(*) FROM dictionary_senses").fetchone()[0]
        if metadata.get("entry_count") != str(entry_count):
            raise ValueError("metadata entry_count does not match dictionary_entries")
        empty_count = connection.execute(
            "SELECT COUNT(*) FROM dictionary_entries WHERE normalized_headword = ''"
        ).fetchone()[0]
        if empty_count:
            raise ValueError(f"Found {empty_count} entries with empty normalized headwords")
        return {
            "path": str(path.resolve()),
            "pack_id": metadata.get("pack_id", ""),
            "schema_version": int(metadata["schema_version"]),
            "data_version": metadata.get("data_version", ""),
            "entry_count": entry_count,
            "form_count": form_count,
            "sense_count": sense_count,
            "byte_count": path.stat().st_size,
            "quick_check": quick_check,
        }
    finally:
        connection.close()


def positive_limit(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("limit must be greater than zero")
    return parsed


def add_build_arguments(parser: argparse.ArgumentParser, multiple_inputs: bool = False) -> None:
    parser.add_argument("--input", type=Path, required=True, nargs="+" if multiple_inputs else None)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--data-version", required=True)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--limit", type=positive_limit, help="Build only N valid entries for smoke tests")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    add_build_arguments(subparsers.add_parser("ecdict", help="Convert ECDICT CSV/ZIP/GZ"))
    add_build_arguments(subparsers.add_parser("jmdict", help="Convert JMdict XML/GZ"))
    add_build_arguments(
        subparsers.add_parser("korean", help="Convert KRDICT XML directory/files/ZIP"),
        multiple_inputs=True,
    )
    validate = subparsers.add_parser("validate", help="Validate a generated package")
    validate.add_argument("--input", type=Path, required=True)
    validate.add_argument("--pack-id", choices=sorted(PACK_METADATA))
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    if args.command == "ecdict":
        summary = convert_ecdict(args.input, args.output, args.data_version, args.force, args.limit)
        result = summary.as_dict()
    elif args.command == "jmdict":
        summary = convert_jmdict(args.input, args.output, args.data_version, args.force, args.limit)
        result = summary.as_dict()
    elif args.command == "korean":
        summary = convert_korean(args.input, args.output, args.data_version, args.force, args.limit)
        result = summary.as_dict()
    else:
        result = validate_pack(args.input, args.pack_id)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, ET.ParseError, sqlite3.Error) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
