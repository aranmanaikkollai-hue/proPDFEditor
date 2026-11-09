# MANUAL DEVICE TEST CHECKLIST (FileProvider / SAF / scanner)

All rows: **NOT RUN** (no real device used). Existing Run L provider tests are historical evidence and not attributed to this tree. Record device, Android version, build commit, date, tester. Never widen `file_paths.xml`; the original user document must never be exposed through a broad FileProvider path.

| # | Test | Expected | Status |
|---|---|---|---|
| 1 | Share a PDF | receiving app opens a copy staged under `cache/shared/<random>/`; original not exposed | NOT RUN |
| 2 | Share a scanned PDF | same; pages intact | NOT RUN |
| 3 | Open the shared PDF in another app | opens read-only via content URI | NOT RUN |
| 4 | Camera capture | image saved under scanner folders only | NOT RUN |
| 5 | Crop flow | crop screen receives staged URI; result correct | NOT RUN |
| 6 | OCR crop flow | `OcrCropActivity` works with staged URI | NOT RUN |
| 7 | Conversion export | no iText path; export succeeds or is cleanly unavailable | NOT RUN |
| 8 | Staging cleanup | `cache/shared/` emptied per `ShareStaging` rules | NOT RUN |
| 9 | Failed sharing (cancel, target crash, no space) | no leftover files, clear message, no raw exception text | NOT RUN |
| 10 | Large PDF sharing (hundreds of MB) | no OOM/ANR; staging copy cleaned | NOT RUN |
| 11 | Filename with `../`, slashes, unicode | sanitised; no traversal | NOT RUN |
| 12 | Document scan (only if the owner later activates it) favourite/category survive repeated scans | unchanged | NOT RUN |
