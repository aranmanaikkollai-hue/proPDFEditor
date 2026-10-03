# PHASE 3 - Security: Encryption / Decryption / Permissions -> PDFBox

PHASE: 3 (password security only). Stopped after this phase.
BUILD STATUS: BUILD NOT RUN - ENVIRONMENT BLOCKED (no Android SDK, emulator or network here).
Nothing below is RUNTIME VERIFIED. Labels: STATICALLY VERIFIED = read / brace-balance / grep checked;
NOT RUN = written but not executed.

## 0. Prerequisites
Phase 1 and Phase 2 were not assumed. Latest CI evidence available: Phase 2 compile errors were fixed
(`PdfBoxPageEngine.kt` "Val cannot be reassigned" x4 -> `resetPageTree()`); no log yet proves the whole
`:app` module compiles or that any Phase 1/2 test passes. Phase 1/2 files are untouched in Phase 3
(the Phase 2 page-engine fix is NOT part of this delivery; this work was done on the uploaded tree).

## 1. Active call graph (as found)

    AppNavigation "security/{uri}" -> SecurityHubScreen -> com.propdfeditor.ui.security.SecurityViewModel
      Password Protect / AES Encrypt -> EncryptDocumentUseCase -> SecurityRepository.applyPasswordProtection
      Remove Password                -> DecryptDocumentUseCase -> SecurityRepository.decryptPdf
      Remove Metadata                -> SanitizeDocumentUseCase -> SecurityRepository.removeMetadata   (iText, OUT OF SCOPE)
    -> new PdfBoxPasswordEngine (:security)

Other password-security entry points, all migrated onto the same engine:
* `PdfOperationsRepositoryImpl.encrypt/decrypt` (used by `com.propdf.editor.presentation.security.SecurityViewModel`
  and `EncryptPdfUseCase` / `DecryptPdfUseCase`)
* `PdfOperationsManager.encryptPdf/removePdfPassword` (used by `PdfOperationWorker` OP_ENCRYPT / OP_DECRYPT)
* `SecurityRepository.setPermissions`, `getDocumentInfo`
* `EncryptionManager`, `SecurityValidator.isPdfEncrypted/canDecrypt` (no callers; kept, migrated)
* Legacy fragments `EncryptionFragment` / `PermissionsFragment` (+ `security/.../ui/viewmodel/SecurityViewModel`):
  only the permission constants changed (iText `EncryptionConstants` -> `PdfPermissions`).
Already PDFBox before this phase and left unchanged: `PdfProcessor.encryptPdf/decryptPdf` (Phase 2A batch
workers). It already enforces "decrypt needs owner rights" and the Latin-1 password rule, which this phase
copies as the product precedent.
Hilt: no module binds `EncryptionManager`, `SecurityValidator` or the new engine; `SecurityRepository` creates
the engine itself (lazy). `SecurityModule` / `WorkerModule` unchanged. `SecurityWorker` only handles
METADATA_REMOVE / SANITIZE (no password operations, unchanged).

## 2. Operations migrated

| Operation | Before | After |
|---|---|---|
| Password protect / AES encrypt (Hub) | iText `setStandardEncryption` | PDFBox `StandardProtectionPolicy` |
| Remove password (Hub, batch worker, Manager, Impl) | iText; two copies used `setUnethicalReading(true)` or ignored the password | PDFBox, correct password AND owner rights required |
| Set permissions | iText | PDFBox (owner-only AES-256) |
| Document info / encryption check / can-open | iText | PDFBox |
| `EncryptionManager.decrypt` | ignored password, `setUnethicalReading(true)` | requires password + owner rights |

`setUnethicalReading` appears nowhere in code any more (3 mentions remain, all in explanatory comments).
NOT migrated (other phases): `removeMetadata` / `sanitizeDocument` (iText), redaction, signatures, converters,
compression, and the AES-GCM *file* encryption (`encryptWithAes` / `decryptWithAes`, `javax.crypto`, not PDF encryption).

## 3. Encryption compatibility
Mapping of the existing `EncryptionType` values (iText names -> PDFBox settings):
* AES_256 -> key length 256, AES. STANDARD_128 -> 128-bit RC4. AES_128 -> 128-bit AES. STANDARD_40 -> 40-bit RC4.
* NONE / anything else -> 128-bit RC4, the same fallback the iText code had.
Metadata encryption: PDFBox encrypts metadata by default with the standard handler and exposes no switch here;
"encrypt metadata" is therefore not configurable (limitation, not tested).
Claimed only to the extent the instrumented tests cover (NOT RUN): iText-written fixtures with RC4-40, RC4-128,
AES-128 and AES-256 open and decrypt in PDFBox; PDFBox-written files of the same four types open in iText with
the right password. No other encryption revisions, public-key security, crypt filters or embedded-file-only
encryption are claimed.

## 4. Decryption behaviour (the contract)
* A password that PDFBox accepts is always required. Missing -> `PasswordRequired`; wrong -> `WrongPassword`.
* Removing protection additionally requires OWNER rights: a user password opens the PDF but cannot lift
  restrictions -> `OwnerPasswordRequired`. This is the same rule `PdfProcessor.decryptPdf` already used.
  I could not re-verify iText 7.2.5's behaviour for user-only passwords offline; the owner rule is the
  stricter reading and is documented as a possible behaviour tightening.
* Unprotected input -> `NotEncrypted` (before: silently re-saved a copy).
* Already protected input to encrypt / set permissions -> `AlreadyEncrypted` (remove the password first).
* Signed PDFs are refused for encrypt, set permissions and decrypt (the rewrite would invalidate the signature).
* Output is reopened before publishing: encrypted output must be encrypted, keep its page count, store the
  requested permission bits, reject the empty password when a user password is set, and open with the owner
  password with owner rights; decrypted output must open without a password and not be encrypted. Failure -> not published.
* A blank owner password is replaced by a random 192-bit one (as iText did), so restrictions stay enforceable.
* `setPermissions` with a blank owner password is refused (iText would have created an unliftable lock).
* Blank Hub password: the Hub still passes null/null; behaviour kept (encrypted, opens without a password).
* Passwords: never logged, stored, or put in exception messages (the DB stores only has-user/has-owner flags).

Password encoding (STATICALLY VERIFIED design, tests NOT RUN):
* RC4 / AES-128 store passwords in a single-byte encoding and PDFBox silently turns other characters into '?'.
  Encryption with such a password at those levels is REFUSED (`UnsupportedPassword`) rather than stored lossily;
  AES-256 uses UTF-8 and has no limit (same rule as `PdfProcessor`).
* Files written by the old iText code used UTF-8 bytes at every level. Opening tries the password as given and,
  for non-ASCII passwords, a second time as its UTF-8 bytes read as Latin-1. It is the same password, not a bypass.
  Files PDFBox writes with non-ASCII Latin-1 passwords at RC4/AES-128 use PDFBox's encoding and may not open in
  readers that expect UTF-8; AES-256 is the interoperable choice.

## 5. Permissions
`PdfPermissions` (new, library-independent) keeps the numeric values of the iText 7 `EncryptionConstants` the
app used, so persisted masks (`SecureDocumentEntity.permissions`) and UI masks keep their meaning:
print 2052 (bits 3+12), degraded print 4 (bit 3), modify 8, copy/extract 16, annotate 32, fill forms 256,
accessibility 512, assemble 1024. A mask lists what is ALLOWED; everything else is denied; reserved /P bits are
set as the specification requires. An instrumented test compares these values with the real iText constants.
Existing UI semantics preserved, not invented:
* Hub (Password Protect / AES): `FULL_PERMISSIONS` = every bit allowed.
* `SecurityConfig` path: accessibility always allowed, print/copy from the flags.
* Legacy fragments: seven checkboxes (print, modify, copy, annotate, fill forms, assembly, degraded print).
  There is no accessibility checkbox, so masks they build never allow accessibility extraction; kept as is.
Limitations: PDF permissions are advisory (a viewer must honour them); revision 2 (40-bit) only defines bits 3-6;
the document info `permissions` field is the file's /P value (-1 when unencrypted), as before.

## 6. SAF
* Source: staged through `ContentResolver.openInputStream` (works for file:// and content://) into a private
  cache file; `Uri.path` is never used for files. The staging and output cache files are deleted in `finally`
  (before, a failure leaked the staging file).
* Output: nothing is written to the destination until the result is verified; then streamed with
  `openOutputStream(uri, "wt")`. If that copy itself fails the destination is truncated so no partial PDF stays.
* Remaining limitation: the destination is created by the caller (SAF picker); a failed run leaves that empty file.

## 7. Failure / cancellation behaviour
* Engine output goes to `.<name>.<nanoTime>.tmp` beside the target and is renamed only after verification; the
  temp file is deleted on any throwable including `CancellationException`.
* Repository methods rethrow `CancellationException` (they previously turned it into a failure) and check
  `ensureActive()` before publishing. A single PDFBox load/save call is not interruptible; cancellation takes
  effect at the step boundaries.
* Input files are never opened for writing; encrypted/signed/malformed/empty/missing input fails with a typed
  error and no output.
* User-visible text: typed `PdfSecurityException` messages are shown directly (Hub, presentation ViewModel, worker);
  other errors keep the existing mapper.

## 8. Tests (all NOT RUN)
JVM: `PdfPermissionsTest` (8 tests): bit values, /P construction, reserved bits, unknown bits dropped, full / empty masks, print vs degraded print, legacy UI never sets accessibility.
Instrumented: `PdfBoxPasswordInstrumentedTest` (22 tests). Fixtures are written by iText (independent) and by PDFBox:
1 unencrypted -> encrypt (all four algorithms, iText reopens the output); 2 correct password (4 revisions);
3 wrong password; 4 missing password; 5 decrypt with correct password; 6 decrypt with wrong password;
7 encrypted output cannot be opened without credentials (PDFBox, Android `PdfRenderer`, plaintext absent from bytes);
8 decrypted output readable and renderable; 9 original unchanged and no temp files after failure.
Also: owner vs user rights, empty password cannot unlock a restricted-but-openable file, already/not encrypted,
random owner password, permission bits per flag (incl. agreement with iText `/P`), degraded vs full print,
password encoding (Latin-1, long, spaces, CJK on AES-256, CJK refused elsewhere, old-iText UTF-8 files),
encrypt/decrypt cycles, signed PDF refused, malformed/empty/missing input, cancellation,
repository over content:// and file:// (in-memory Room), destination untouched on failure, staging cleanup.
Not covered: permissions enforced by real viewers; very large files; encrypted-metadata switch; public-key security.

## 9. Build
BUILD NOT RUN - ENVIRONMENT BLOCKED. No Gradle/AGP/Kotlin/PDFBox change; no dependency added (`:security`
already has pdfbox-android 2.0.27.0). Static checks: brace/paren balance on all touched files; no iText import left
in the migrated password-security files. PDFBox calls not independently verified against 2.0.27.0 sources here:
`StandardProtectionPolicy.setPreferAES` / `setEncryptionKeyLength`, `AccessPermission(int)`,
`PDEncryption.getLength/getRevision/getVersion/getPermissions`, `PDDocument.setAllSecurityToBeRemoved`.
An overly strict self-check would make encrypt fail safe (nothing saved), so the first on-device run is important.
The existing androidTest sources are not compiled by `assembleDebug`; add a `connectedAndroidTest` step.

## 10. Remaining iText (Kotlin sources importing com.itextpdf)
* `security/.../data/repository/SecurityRepository.kt` - removeMetadata, sanitizeDocument, redaction (other phases)
* `security/.../redaction/RedactionEngine.kt` - redaction
* `core/.../signature/PdfSignatureEngine.kt` - signatures
* `app/.../data/converter/ImageConverter.kt`, `MarkdownConverter.kt`, `TextConverter.kt` - converters
* `app/.../data/repository/LegacyITextPdfOperations.kt` - compress / optimize, saveAnnotations
* test only: `PdfBoxPasswordInstrumentedTest.kt` uses iText to build independent fixtures
Gradle still declares iText (global removal out of scope).

## 11. Known limitations / behaviour changes
* Removing protection needs the owner password; a user password no longer works (see section 4).
* Decrypting an unprotected PDF is refused instead of copied.
* Encrypting an already protected PDF is refused (remove the password first).
* RC4 / AES-128 refuse non-Latin-1 passwords.
* Signed PDFs are refused for all password operations.
* `SecurityValidator.isPdfEncrypted` now reports true for PDFs that need a user password (iText returned false).
  Its methods moved to `SecurityValidator.PdfChecks(context)` because PDFBox needs a Context; no callers existed.
* `EncryptionManager` now takes a Context and its methods are `suspend`; no callers existed.
* Sanitize / remove-metadata on an encrypted PDF still fails (iText, no bypass) until its own phase.
* Permission semantics are PDF-spec bits, not enforcement.

## 12. Next phase
Phase 4 per the master prompt (redaction), after CI + `connectedAndroidTest` have run for Phases 2 and 3 and any
real compile or device failures are fixed.
