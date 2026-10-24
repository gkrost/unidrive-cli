# Golden unicode tree

A fixed, deterministic test tree for the whole unidrive chain (Windows client, daemon, Internxt or any other provider): names in every
major script, symbols, cat emoji, and a catalogue of name hazards. It is meant as a reference ("golden") suite: the same tree goes in,
the same tree has to come out, and every difference is a finding.

| file | what it is |
|---|---|
| `layout.json` | the pin of the layout: every directory, file name, text and size. Literal UTF-8 plus `{U+XXXX}` / `{rep:X:N}` tokens for what cannot be typed |
| `manifest.tsv` | generated from the layout: one row per directory and file with size, SHA-256, modification time, normalisation form, ASCII-escaped path and path |
| `manifest.sha256` | SHA-256 of `manifest.tsv`: **this one value identifies the layout** |
| `generate.ps1` | builds the tree (`-Out <dir>`, refused when the layout no longer matches the committed manifest), checks the committed manifest (`-Check`), or rewrites it after a deliberate layout change (`-UpdateManifest`) |
| `verify.ps1` | compares a directory (a mount, a download, a restore) with the manifest: missing, extra, renamed (NFC/NFD), case, size, hash, mtime. Exact names are matched first, so the result does not depend on iteration order (twin pairs) |
| `compare-expected.ps1` | sorts a tool's findings (`verify`, `daemon-view`, `remote-live`) against a file of known deviations: **unexpected** (fails the run), **expected** (counted per rule, with its issue), **stale** rules that matched nothing (fixed? remove the rule). The tools themselves stay strict |
| `expected/<suite>@<provider>@<engine>+<client>.tsv` | the known deviations for one provider and one engine and client build (some are provider-specific: empty files fail only on Internxt): `tool[:surface], class, path (* wildcard, all else literal), issue (required), note`, tab-separated. `verify:mirror` applies only to a run compared with `-Tool verify:mirror`, plain `verify` to every surface. A deviation becomes "expected" only through a reviewed commit with an issue, and leaves when the fix lands. The run report names the file it used |
| `selftest.ps1` | feeds `compare-expected.ps1` made-up findings (expected, unexpected, stale, literal brackets, case, a rule without an issue), and damages fresh trees one way per case (rename to NFD, truncate, flip a byte, touch, add, delete, case swap, folder to file, either NFC/NFD twin deleted, the ligature or the angstrom-sign entry deleted) and checks that `verify.ps1` classifies each correctly; the `seed` half seeds the download-direction suite on a plain temp volume and checks what the volume made of it |
| `daemon-view.ps1` | asks the running daemon (`hydration.list` over its IPC socket, one connection for the whole walk) for the cloud-side view and compares it with the manifest; `-Watch` waits for the uploads to drain. state.db keys paths in NFC (archive#171), so an NFD name that collapses onto another entry is reported as `NFCMERGE`, and an entry listed under the wrong parent as `LEAK` |
| `remote-live.ps1` | asks the provider itself (`unidrive ls --live`, folder by folder, bypassing state.db) and compares names with the manifest; limited to folders with an ASCII-only path (the Windows CLI cannot take other arguments, archive#487) |
| `probes/` | read-only probes behind the numbers of the run report: `subscribe-events.ps1` (the daemon's event stream, flags lines that are not JSON), `daemon-counts.ps1` (upload progress), `local-vs-cloud-mtime.ps1` (times of the placeholders against the cloud) |
| `run-localfs.ps1` | **the whole hermetic run in one command**: builds an isolated localfs profile (own config, provider directory, engine mirror, mount root), starts a daemon and a mount, copies the tree into the mount, waits until the uploads settle, verifies the provider directory, the mirror, the mount, the daemon's view and a fresh mount root (round trip, with hashes), sorts every result with `compare-expected.ps1` and prints one summary. Uses the installed builds or private ones (`-EngineJar`, `-ClientDir`); installs nothing; refuses to run next to another mount. Exit 1 = unexpected findings, 2 = the upload queue got stuck |
| `download.json` | the pin of the **download-direction** suite: names created on the cloud side that Windows cannot create through the normal API (trailing dot or space, reserved device names, a case-only twin pair). Kept out of the upload tree above on purpose |
| `seed-download.ps1` | seeds `download.json` on the provider side of a hermetic localfs profile (the `\\?\` prefix makes the impossible names legal to the raw call) and writes `download-manifest.tsv` (+ its SHA-256) for `verify.ps1 -Manifest`. A name the volume refuses (a folding volume keeps only the first of the twin pair) is reported and noted in the manifest header |
| `RUN-2026-10-03.md` | the first run through the whole chain: setup, procedure, interventions, results, findings (interim until it says otherwise) |

PowerShell 7 (`pwsh`) is needed. The scripts are ASCII only.

## Pin

```
layout:   291 directories, 253 files, 4,228,448 bytes
manifest: 88ba07f15f58f6ca6390ea6ecd634364e7d5dca66f9d6a71a2d56bdff1179e86   (sha256 of manifest.tsv)
rules:    no path has more than 7 segments below the golden root (directories plus file name)
          no file is larger than 1,000,000 bytes
          every file has the modification time 2026-01-01T12:00:00Z unless the layout says otherwise (hazards/timestamps)
          text files end with their own relative path, binary files are SHA-256 counter-mode streams: the same bytes on every machine
```

`pwsh generate.ps1 -Check` fails when `layout.json` and the committed manifest drift apart. Change the layout only deliberately, and
bump the `id` (`golden-unicode-v2`) when you do: the id is part of every file's content.

## What is in it

Top-level groups, each with native language directories two to five levels deep, native file names and a few empty directories:

| group | content |
|---|---|
| `arabic-and-hebrew` | Arabic, Persian, Urdu, Pashto, Hebrew, Yiddish, Syriac, Thaana (right to left, joining, points) |
| `cjk` | simplified and traditional Chinese (with plane-2 ideographs), Cantonese, Japanese (kanji, kana, half-width), Korean, Bopomofo, Yi, Mongolian |
| `cyrillic` | Russian, Ukrainian, Bulgarian, Serbian, Belarusian, Macedonian, Mongolian, Kazakh |
| `european-latin` | German, French, Spanish, Portuguese, Italian, Polish, Czech, Turkish, Icelandic, Nordic, Hungarian, Romanian, Vietnamese, Finnish, Baltic, Celtic, Maltese, Esperanto |
| `greek-and-caucasus` | Greek (also polytonic), Georgian, Armenian |
| `african` | Amharic (Ge'ez), Yoruba, Hausa, Swahili, Zulu, Afrikaans, Tifinagh, N'Ko, Adlam (plane 1), Vai, Malagasy and Somali |
| `americas` | Spanish and Portuguese (Americas), Quechua, Guarani, Mapudungun, Nahuatl, Cherokee, Inuktitut, Navajo, Haitian Creole, K'iche' |
| `indic` | Hindi, Marathi, Sanskrit, Nepali, Bengali, Tamil, Telugu, Kannada, Malayalam, Gujarati, Punjabi, Odia, Sinhala, Tibetan, Santali |
| `southeast-asia` | Thai, Lao, Khmer, Burmese, Javanese, Baybayin, Indonesian and Tagalog |
| `historic-and-exotic` | runes, Ogham, Braille, hieroglyphs, Linear B, cuneiform, Gothic, Phoenician, Glagolitic, Coptic, Shavian, Deseret, mathematical alphanumerics, musical symbols, Mahjong, cards, chess, IPA |
| `symbols` | stars, check marks, arrows, maths, currencies, music, key caps, enclosed alphanumerics, box drawing, warning signs, fractions, quotation marks, weather |
| `cats` | six directory levels of cat emoji, cat names with `&`, `#`, quotes, brackets, ZWJ sequences, cat words in nine scripts |
| `hazards` | see below |
| `binary` | file sizes at every boundary from 0 to 1,000,000 bytes; one 1 MB file below a path of five scripts |

### Hazards (`hazards/...`)

| directory | what it tests |
|---|---|
| `nfc-nfd-twins` | the same visible name in NFC and in NFD in one directory (accented Latin, Hangul syllables and jamo, katakana with and without a separate dakuten), plus the deliberate ANGSTROM SIGN spelling (U+212B), which NFC turns into the U+00C5 name and NFD into the A-plus-combining-ring name |
| `invisible-and-lookalike-twins` | names that differ only by an invisible or look-alike code point: zero width space, joiner and word joiner, no-break and ideographic space, direction marks, variation selector 16, stacked combining marks, a Cyrillic `a`, full-width letters |
| `emoji-sequences` | ZWJ family, flag pairs, keycap, skin tone, rainbow flag, tag sequence (England), person-astronaut |
| `windows-and-url-special-characters` | full-width look-alikes of the characters Windows forbids, legal punctuation with meaning in URLs and shells, leading and doubled spaces, dotfile, no extension, many dots, `%41%2F%2e%2e`, non-ASCII and mixed-case extensions |
| `case-fold` | `Straße`/`STRASSE`, `Σ`/`ς`, `ǅ`, `Å` (U+00C5) next to the angstrom sign (U+212B) |
| `nfkc-only` | names that NFKC folds and NFC and NFD leave alone: the ligature `ﬁ` (U+FB01) next to its ASCII twin, `ﬂ ﬀ ﬃ` |
| `long-names` | a 120 character name, 80 CJK characters, 60 emoji (the last two are 240 bytes in UTF-8) |
| `deep-and-long-path` | six directory levels of 20 characters each |
| `timestamps` | modification times at 1970-01-02, 1980-01-01, 2000-02-29, 2038-01-19 03:14:08 and 2099-12-31 |
| `content-encodings` | UTF-8 with BOM, UTF-16 LE and BE with BOM, Latin-1, CRLF, no trailing newline, NUL-heavy binary |
| `duplicates` | identical content under three names in three scripts; identical binary content under two names |
| `empty-things` | zero-byte files and empty directories with ASCII, CJK, emoji and Cyrillic names |

### Not covered, on purpose

In the upload tree: paths of 260 characters or more, control characters, unpaired surrogates (not valid UTF-8), and an actual
case-only twin in one directory (NTFS merges them; the cloud-side copy of that hazard lives in the download-direction suite).
The names Windows cannot create through the normal API — reserved device names, a trailing dot or space, the case-only twin
pair — moved into the **download-direction suite** (`download.json` + `seed-download.ps1`), which seeds them on the provider
side of a hermetic localfs profile; what the mount and the mirror show for them is then a finding. Still on purpose out: a
download-direction run against a real cloud provider (seeding there is the provider's own job).

### The download direction

```powershell
# seed the impossible names on the provider side of a hermetic localfs profile (never your live profile)
pwsh scripts/golden/unicode-tree/seed-download.ps1 -ProviderRoot <profile's provider directory>
# the provider directory itself holds exactly what was seeded
pwsh scripts/golden/unicode-tree/verify.ps1 -Root <provider directory>\golden-unicode-download-v1 `
    -Manifest scripts/golden/unicode-tree/download-manifest.tsv
# after the engine gathered: what the mirror and the mount show of the cloud-side names
pwsh scripts/golden/unicode-tree/verify.ps1 -Root <mirror>\golden-unicode-download-v1 `
    -Manifest scripts/golden/unicode-tree/download-manifest.tsv
```

## Use

The quick way, no cloud account needed (stop any other unidrive mount first; about 5 minutes):

```powershell
pwsh scripts/golden/unicode-tree/run-localfs.ps1 -Expected "scripts/golden/unicode-tree/expected/golden-unicode-v2@localfs@<engine>+<client>.tsv"
# private builds instead of the installed ones:
pwsh scripts/golden/unicode-tree/run-localfs.ps1 -EngineJar <unidrive.jar> -ClientDir <folder with unidrive-win.exe> -Expected <file>
```

The steps by hand:

```powershell
# build the tree somewhere outside the mount
pwsh scripts/golden/unicode-tree/generate.ps1 -Out C:\Users\me\unidrive-golden
pwsh scripts/golden/unicode-tree/verify.ps1 -Root C:\Users\me\unidrive-golden\golden-unicode-v2        # 0 findings expected

# put it through the chain: copy it into the mount (this is what a person does in Explorer)
Copy-Item C:\Users\me\unidrive-golden\golden-unicode-v2 <mount>\_INBOX\golden-unicode-v2 -Recurse

# cloud side, as the daemon sees it (waits for the uploads)
pwsh scripts/golden/unicode-tree/daemon-view.ps1 -Profile <profile> -Watch

# the provider's own listing (ASCII-only folders; one engine start per folder, minutes)
pwsh scripts/golden/unicode-tree/remote-live.ps1 -Profile <profile> -Subtree hazards

# local side after the round trip: names, sizes, hashes (reading hydrates every placeholder)
# -StrictMtime makes a changed modification time a finding (the golden files carry fixed times on purpose)
pwsh scripts/golden/unicode-tree/verify.ps1 -Root <mount>\_INBOX\golden-unicode-v2 -StrictMtime
```

Findings of verify.ps1 and daemon-view.ps1 use `{U+XXXX}` for every non-ASCII character, so they survive terminals and loggers that mangle
Unicode. The first run and its findings: `RUN-2026-10-03.md`.
