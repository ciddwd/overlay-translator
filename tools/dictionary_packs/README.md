# Offline dictionary package builder

This tool converts upstream dictionary data into the SQLite schema consumed by
the Android app. It does not publish packages or change the app's download UI.

## Verified sources

| Pack | Input | Source | License |
|---|---|---|---|
| `ecdict.bin` | `ecdict.csv`, `.gz`, or release `.zip` | [ECDICT](https://github.com/skywind3000/ECDICT) | [MIT](https://raw.githubusercontent.com/skywind3000/ECDICT/master/LICENSE) |
| `jmdict.bin` | `JMdict_e.gz` or XML | [EDRDG daily JMdict](https://www.edrdg.org/pub/Nihongo/JMdict_e.gz) | [CC BY-SA 4.0](https://www.edrdg.org/edrdg/licence.html) |
| `korean-basic.bin` | KRDICT XML directory, XML files, or the official export ZIP | [KRDICT export mirror](https://github.com/spellcheck-ko/korean-dict-nikl/tree/master/krdict) | [CC BY-SA 2.0 KR](https://creativecommons.org/licenses/by-sa/2.0/kr/) |

The Korean Basic Dictionary site may require an interactive download flow. For
reproducible builds, the converter also accepts the XML files maintained in the
mirror linked above. That repository states that the files are exports from the
National Institute of Korean Language; it is not operated by the institute.

All links above were checked before this tool was introduced. Do not redistribute
generated packages without retaining the relevant attribution and license.

## Build

Python 3.10 or newer is required; no third-party package is needed.

```powershell
python build_dictionary_packs.py ecdict `
  --input build/dictionary-sources/ecdict.csv `
  --output build/dictionary-packs/ecdict.bin `
  --data-version 2026-08-29

python build_dictionary_packs.py jmdict `
  --input build/dictionary-sources/JMdict_e.gz `
  --output build/dictionary-packs/jmdict.bin `
  --data-version 2026-08-29

python build_dictionary_packs.py korean `
  --input build/dictionary-sources/korean-dict-nikl/krdict `
  --output build/dictionary-packs/korean-basic.bin `
  --data-version 2026-06-19
```

Use `--limit 100` for a small source smoke build and `--force` only when an
existing output should be replaced. Validate a finished package with:

```powershell
python build_dictionary_packs.py validate `
  --input build/dictionary-packs/ecdict.bin `
  --pack-id ecdict
```

The builders use bounded buffers and streaming XML parsing. Korean definitions
prefer the official Chinese equivalent; if a sense has no Chinese equivalent,
the Korean definition is retained. JMdict English contains English glosses, so
the generated Japanese pack currently displays English definitions. KRDICT
exports occasionally contain characters forbidden by XML 1.0; the converter
removes only those invalid control characters and records the exact count in
`sanitized_xml_character_count` instead of silently dropping an entry.

## Tests

```powershell
python -m unittest discover -s tests -p "test_*.py" -v
```

The table-driven fixtures cover normalization, ECDICT inflections and parts of
speech, JMdict kanji/readings, KRDICT Chinese definitions and package validation.
