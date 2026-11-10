# DEPENDENCY LICENCE REVIEW

Status: **BLOCKED - no licence was reviewed.** Nothing here is legal advice.

## Evidence actually available
- `DEPENDENCY_LICENSES.csv`: **not in the archive** (searched). `insight-*` reports: **not in the archive**. No CI artifact was accessible from this sandbox.
- Historical only (earlier report, other tree): a CI log line "280 dependencies ... 22 need manual review". The 22 entries are unknown and **must not be inferred**.

## How to obtain it (exact)
Run `release-audit.yml` (manual). It uploads artifact `release-audit` containing `reports/DEPENDENCY_LICENSES.csv`, `deps-app-release.txt`, `deps-core-release.txt`, `insight-*`. Then review every row where `manual_review = Y`, one by one, and record the result here. The generator is `tools/licence/generate_dependency_licenses.py` (POM `<licenses>` with parent-POM fallback; self-test runs first).

## Project licence (separate from third-party notices)
Owner decision: **decide later.** No LICENSE/NOTICE file was created, no licence chosen, no copyright changed. Conflict to resolve: `README.md` says "MIT License - see `LICENSE` file", but no `LICENSE` file exists. Not edited.

## Third-party notices
`app/build.gradle` packaging excludes `META-INF/LICENSE*`, `NOTICE*`, `DEPENDENCIES`, `INDEX.LIST`, so notice files from dependencies are not shipped in the APK. Do not remove the excludes: the androidTest JUnit 5 jars already caused duplicate-resource build failures. Options, to decide after the CSV exists: (a) generate a notices asset from the actual dependency list and bundle it; (b) an in-app open-source licences screen reading that asset; (c) a generated `THIRD_PARTY_NOTICES` document. A notices file must be generated from real rows, never written by hand now.

## Facts from source
iText is absent from production and from the release runtime classpath by configuration (verification NOT RUN); it remains only as `androidTestImplementation('com.itextpdf:itext7-core:7.2.5')` for `PdfBoxPasswordInstrumentedTest` fixtures. PDFBox-Android 2.0.27.0 and Bouncy Castle 1.72 (`bcprov/bcpkix-jdk15to18`) are production dependencies; their licence text must appear in the final review.

## Follow-up round
README no longer claims MIT (now "licence not chosen yet"). Dependency review still **BLOCKED**: `DEPENDENCY_LICENSES.csv` and `insight-*` were not available this round; run `release-audit.yml` and review the `manual_review = Y` rows.

## Round 4
The generator now runs in CI: 280 dependencies, 22 flagged for manual review (artifact `release-audit`, ID 13722213288, run 42725079976, commit `578ab77c...`). The CSV and `insight-*` files were not included in the uploads, so **the 22 rows are still unreviewed** and no notices file has been generated. Next step: upload that artifact.
