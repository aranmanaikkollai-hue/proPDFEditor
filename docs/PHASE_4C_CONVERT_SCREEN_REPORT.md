# PHASE 4c - THE CONVERT SCREEN (Tools hub > Convert)

**BUILD NOT RUN - needs CI** for all Kotlin in this phase. The screen itself has no automated UI test (the project has no Compose
test setup); it relies on the device checklist rows 61-80. The runner behind it is covered by 11 device tests.

## What the user sees
Tools hub > **Convert**. Choose one of four modes: PDF to images, PDF to text, Images to PDF, Text to PDF.
1. Pick the file (one PDF, several images, or one text file). Images show their order and can be moved up/down or removed.
2. Options for the mode: image type and resolution (72/150/300 dpi) and page list ("1-3,5,8-"); text page separator; page size A4/Letter/match-picture and margin;
   text size. Every control has a spoken name.
3. Choose the output folder (Storage Access Framework). The folder is remembered if the permission is still valid.
4. **Convert**: progress with Cancel. Result card with the names of the files created and plain-language notes (reduced resolution, no text layer,
   assumed encoding, replaced characters, ...). Errors have their own plain messages. A password dialog appears for protected PDFs (wrong password asks again).

## How it is built
* `:pdfedit` (Android, testable without UI): `ConvertRunner` (copies the chosen files to a private work folder, runs the converter, always deletes the work folder),
  `DocumentTreeSink` (writes into the chosen folder and **never overwrites**: a taken name gets "(1)", "(2)"; it lists the folder once), `InputStager`, `UniqueName`,
  `ConvertJob`/`ConvertMode`.
* `:app`: `ConvertViewModel` (state, folder memory, cancel on leaving), `ConvertScreen` (Compose), route `convert` in `AppNavigation`, a "Convert" card in `ToolsHubScreen`,
  all text in `values/strings_convert.xml`. `:app` now depends on `:pdfedit` (about 0.8 MB of fonts in the APK).
* All rules from 4a/4b apply: renderer under a mutex, white background, pixel limit, passwords, text permissions, no overwriting, cleanup on failure and cancel.

## Tests
* JVM: `UniqueNameTest` (7).
* Device (`ConvertRunnerInstrumentedTest`, 11): each mode end to end into a folder; folder listing equals the result; no work folder left in cache; a second run never overwrites (names get "(1)", originals keep their hash);
  encrypted PDF asks for a password and works with it; wrong input counts; unreadable input; invalid page list; **cancel leaves no output and no work folder**; the sink never touches an existing file and deletes only what it created.
* Device checklist rows 61-80 for the screen.

## Decisions I took (tell me if you want them changed)
1. Conversions run while the screen is open; leaving the screen cancels (and cleans up). There is no background service or notification yet.
2. Results are listed by name; there is no "open folder" or "share" button yet.
3. Encrypted PDFs are handled by asking for the password in a dialog; the password is only held in memory for that run.
4. Defaults: PNG, 150 dpi, A4, margin 18 pt (54 pt for text), 11 pt text.

## Known limits
* A raw (non-SAF) DocumentFile adds the MIME extension to the name; real SAF providers do not when it already matches. The tests read names from the result for that reason.
* No progress for the very first step (copying the input); a very large PDF may show an indeterminate bar for a moment.
* The Tools hub entry text ("Convert") is hard-coded like its neighbours; the screen's own text is in string resources.
* `AppNavigation.kt` already contained 3 non-ASCII characters (pre-existing, not added here).

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Phases 4a and 4b on CI (conversion and creation engines, Poppler/qpdf checks, 0 failures) | Nothing known | All Phase 4c Kotlin (compilation, 7 JVM and 11 device tests), the screen itself, checklist rows 61-80 |

## Files
New: `pdfedit/src/main/java/com/propdf/pdfedit/convert/{UniqueName,ConvertJob,DocumentTreeSink,InputStager,ConvertRunner}.kt`; `pdfedit/src/test/java/com/propdf/pdfedit/UniqueNameTest.kt`;
`app/src/androidTest/java/com/propdfeditor/pdfedit/ConvertRunnerInstrumentedTest.kt`; `app/src/main/java/com/propdfeditor/ui/convert/{ConvertViewModel,ConvertScreen}.kt`;
`app/src/main/res/values/strings_convert.xml`; this report.
Changed: `pdfedit/build.gradle` (documentfile), `app/build.gradle` (`implementation project(':pdfedit')`), `app/.../ui/tools/ToolsHubScreen.kt`, `app/.../ui/navigation/AppNavigation.kt`,
`.github/workflows/android-tests.yml` (verification step now gating), `docs/REAL_DEVICE_TEST_CHECKLIST.md`, `docs/PHASE_4B_CREATION_REPORT.md`, `docs/PDFEDIT_FILE_MANIFEST.txt`. Moves/deletions: none.

## Next
Phase 5: compare PDFs, then licence and notices (I will ask you before choosing a licence). Optional follow-ups for Convert: share/open-folder buttons, background run with a notification, OCR for scanned PDFs.
