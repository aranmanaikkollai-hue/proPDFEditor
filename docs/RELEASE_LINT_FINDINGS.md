# RELEASE LINT FINDINGS

First real release lint result (`:app:lintRelease`, gate `tools/release/lint_gate.py`), commit `6f248056e8d3ef6b09f03f1a48e83066ba7e5f4e`, workflow run in `logs_115318566843`:
**892 issues: 49 Error, 843 Warning, 0 Fatal. Gate result: LINT_ERRORS (exit 1).** The gate worked as designed. minSdk 23 / targetSdk 34 unchanged; no `@SuppressLint`, no baseline, no `abortOnError` change.

## Errors and fixes (all in source; NOT yet re-run, see status)
| Lint id | Count | Where | Real-world effect (API 23 = Android 6) | Fix |
|---|---|---|---|---|
| MissingConstraints | 1 | `fragment_compression_sw600dp.xml` `fileInfoCard` | on tablets the card was in no Flow and had no constraints: it would appear at (0,0) over other cards | added to the Flow's `constraint_referenced_ids` |
| StringFormatInvalid | 3 | `strings_batch.xml` `progress_format`, `batch_progress_title` (used by `BatchJobAdapter`, `BatchNotificationManager`) | `formatted="false"` marks a string as not-a-format; it was passed to `getString(id, args)` | removed `formatted="false"`, positional placeholders (`%1$d`...); argument counts verified (3 and 3) |
| NewApi `windowLightNavigationBar` (API 27) | 2 | `values/themes.xml` | attribute is ignored on older APIs (no crash) | `tools:targetApi="o_mr1"` on the two items (the documented fix; behaviour unchanged) |
| NewApi `Collection.removeIf` (API 24) | 4 | `AnnotationLayer`, `SelectionTool`, `AsyncPageRenderer` x2 | `NoSuchMethodError` on API 23 | Kotlin stdlib `removeAll { }` (same call pattern already compiles elsewhere in `:viewer`) |
| NewApi `ConcurrentHashMap.newKeySet` + KeySetView members (API 24) | 17 | `PageCacheManager`, `PdfExportManager`, `RenderScheduler` | `NoSuchMethodError` on API 23 | `Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>())` (API 1, same concurrency) |
| NewApi `ConcurrentHashMap.getOrDefault` (API 24) | 2 | `PremiumPageAdapter` | crash on API 23 | `map[key] ?: 1.0f` |
| NewApi `ProgressBar.setProgress(int, boolean)` (API 24) | 1 | `CompressionFragment` | crash on API 23 | Material `setProgressCompat(int, boolean)` |
| NewApi `ExifInterface(InputStream)` (API 24) | 1 | `ImageConverter` | crash on API 23 | SDK check; API 23 reads via a short-lived temp file |
| NewApi `setInvalidatedByBiometricEnrollment` (API 24) | 1 | `KeystoreManager` | crash on API 23 | called only on API >= 24 |
| NewApi `java.time` (API 26) | 17 | `:security` entities, converter, `HistoryAdapter` | `Instant.now()` in entity defaults: class missing on API 23-25 whenever a security operation or secure document is created | timestamps are `Long` epoch milliseconds; `HistoryAdapter` uses `SimpleDateFormat`; `InstantConverter` deleted (no longer used) |
| **Total** | **49** (1+3+2+4+17+2+1+1+1+17) | | | |
Counts are by lint finding (lint can report several findings on one source line, e.g. `HistoryAdapter.kt:38`). After the next run the gate output is the authority.

### Security database compatibility
`SecurityOperationEntity.createdAt/completedAt` and `SecureDocumentEntity.createdAt/lastAccessed` were `Instant` stored through a converter as epoch milliseconds; they are now `Long`/`Long?` holding the same values. Column type (INTEGER), nullability, table names and `SecurityDatabase` version (1) are unchanged, so existing rows remain readable and no migration is needed. This is the one place a Kotlin type changed in a Room entity; the first generated `security` schema JSON should be compared with the declared entities to confirm.

## Warnings (843, not gating)
Largest: `GradleDependency` 197 (newer versions exist; versions deliberately unchanged), `HardcodedText` 115, `LogNotTimber` 94, `ClickableViewAccessibility` 12, `DrawAllocation` 8, `ContentDescription` 7. `KaptUsageInsteadOfKsp` x1 (`:app` uses KAPT for Room/Hilt). Not addressed here.

## Status
Fixes: implemented, NOT compiled. Gate on the fixed tree: NOT RUN. Expect additional findings to surface only if lint reports different lines after edits; send the next gate output.
