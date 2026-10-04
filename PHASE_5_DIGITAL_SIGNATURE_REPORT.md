# PHASE 5 - DIGITAL SIGNATURES: AUDIT, SEPARATION, AND PDFBOX FEASIBILITY

Status: **PDFBOX MIGRATION NOT VERIFIED** (cryptographic signing stays on iText 7.2.5 + BouncyCastle, isolated).
Visual signature stamping was migrated to PDFBox. Nothing in this phase was compiled or run on a device (no Gradle/SDK in the authoring environment); only the fixtures and the placement math were validated with independent tools here.

## 1. Active call graph

```
Compose: SecurityHubScreen  ("Sign PDF" tile, "Check Signatures" tile)
 |-- SIGN   -> ApplySignatureActivity (legacy View stack, manifest-registered)
 |      |-- SignatureManagerActivity (pick drawn/typed/image signature; Draw/Type activities create them)
 |      |-- SignatureViewModel.applyVisualSignature(...)  --> PdfSignatureEngine.applyVisualSignature
 |      |        --> PdfBoxVisualSignatureEngine.stamp            [VISUAL SIGNATURE, PDFBox]
 |      |-- certificate picker (CertificateEntity list) -> password dialog
 |      |-- SignatureViewModel.applyDigitalSignature(...) --> PdfSignatureEngine.applyDigitalSignature
 |               --> iText PdfSigner.signDetached (CMS) + BouncyCastle   [CRYPTOGRAPHIC SIGNATURE, iText]
 |-- VERIFY -> SignatureVerificationActivity -> SignatureViewModel.verifyDocumentSignatures
 |               --> PdfSignatureEngine.verifySignatures --> iText SignatureUtil / PdfPKCS7
 `-- CertificateManagerActivity -> SignatureViewModel.importCertificate
         --> CertificateRepository.importP12Certificate (copies .p12 to filesDir/keystores/)

Worker: BatchSignatureWorker -> PdfSignatureEngine.applyVisualSignature   (visual only; no enqueue site found in the codebase)
Form field "signature" (editor module, PdfFormEngine.addSignature): returns an error; no signing, by design.
Signed-PDF protection (PdfProcessor.isSigned, PdfBoxPageEngine.isSigned): PDFBox detection, refuses to rewrite signed files.
```

## 2. Visual signature behavior (VISUAL SIGNATURE - not cryptographic)

Drawn, typed and imported-image signatures are all bitmaps (SignatureRepository stores them in filesDir/signatures). `applyVisualSignature` draws the bitmap on a page. It adds no certificate, no CMS, no ByteRange, no /Sig dictionary; the output cannot be verified and proves nothing about who placed it.

Before this phase (iText): `pdfDoc.close()` was called inside `use`, no rotation / CropBox handling, no check for signed or encrypted input, output written directly to the final path, any failure swallowed into a generic message, and the signed-PDF case would silently invalidate an existing signature.

Now (PDFBox Android, `PdfBoxVisualSignatureEngine`): stages the input, refuses encrypted and already-signed/certified PDFs (`SignedDocument`), validates page and placement, maps the display rectangle (points, top-left origin) through CropBox and /Rotate (`VisualSignatureGeometry`), embeds the image losslessly with alpha, writes to `*.part`, re-opens and checks (page count unchanged, no signature appeared), then moves into place.

UI labels now say so: button "Add visible signature (no certificate)"; the certificate path is "Sign with certificate".

## 3. Cryptographic signing behavior (iText, kept, isolated)

- Certificate selection: user picks an imported certificate (CertificateEntity). Private key lives in a user-supplied PKCS#12 copied to `filesDir/keystores/<alias>_<ts>.p12`. Password is asked per signing and not stored.
- Signing: iText `PdfSigner` with `StampingProperties().useAppendMode()`, `PrivateKeySignature` + BouncyCastle, detached CMS (`PdfSigner.CryptoStandard.CMS`), SHA-256 default, certificate chain from the keystore. iText produces the ByteRange, placeholder and incremental revision.
- Not implemented: timestamping (requests with a TSA URL are now rejected rather than ignored), LTV/OCSP/CRL embedding, certification (DocMDP) signatures, PAdES profiles other than what iText's CMS mode yields.

Defects found and fixed in `applyDigitalSignature`:
1. Widget rectangle used the Android top-left rectangle as if it were PDF coordinates (flipped vertically, wrong on rotated pages). Now mapped with `VisualSignatureGeometry.userRect`.
2. `StampingProperties()` without append mode. Now append mode, so the original bytes stay an untouched prefix and earlier signatures survive.
3. `keyStore.getKey(...) as PrivateKey` crashed with a NullPointerException-style failure on a wrong alias; the app passes the user's display label, not the keystore alias. Now resolves the real key alias (single key entry) or fails with a clear message.
4. Expired / not-yet-valid certificates were used silently. Now refused.
5. Output was written straight to the destination and the temp copy leaked on failure. Now `*.part`, post-write self-check, publish only on success, temp files and password char arrays cleaned in `finally`.
6. History recorded `isVerified = true` unconditionally. Now reflects the post-write self-check.
7. `verifySignatures` returned an empty list for any failure ("no signatures") including unreadable files; now throws a typed error. Results also carry a failure reason, the certificate-validity-at-signing flag, and `signerTrustVerified` (always false).
8. Verification UI said "All signatures are valid", implying trust. Text now says signatures are intact and signer identity was not checked.

## 4. PDFBox feasibility (PDFBox Android 2.0.27.0)

**Verdict: PDFBOX MIGRATION NOT VERIFIED. The cryptographic migration was stopped; no PDFBox signing code was written.**

API evidence actually gathered:
- The desktop PDFBox 2.0.x API has `PDDocument.addSignature(PDSignature, SignatureInterface, SignatureOptions)`, `saveIncremental`, and `saveIncrementalForExternalSigning` (Apache javadocs, versions 2.0.0 - 2.0.9 fetched).
- The Android port is a separate fork (`com.tom_roush.pdfbox`). Its issue tracker (TomRoush/PdfBox-Android #108, fetched) shows `ExternalSigningSupport` was missing in older ports, then reports of `ArrayIndexOutOfBoundsException` inside `COSWriter.doWriteSignature` when using `saveIncrementalForExternalSigning`, and a maintainer comment that fixing it was "more difficult than hoped". I could not establish from that thread whether 2.0.27.0 contains a fix.
- PDFBox does not generate CMS itself; it needs BouncyCastle (`bcpkix`) code that the app would have to write (SignedData generation, signed attributes, chain handling). The project already pins `bcprov/bcpkix-jdk15to18:1.72`, so that part is available, but it is new security-critical code.
- I could not obtain the 2.0.27.0 AAR here (bash has no network, no Gradle cache), so I could not list its `digitalsignature` package, decompile `COSWriter`, or run a sign/verify round trip on it.

Why not proceed: the requirement is "do not invent an implementation" and "inspect actual APIs". Without the real 2.0.27.0 artifact and a device run, a PDFBox signer would be unverified security code. A partial migration (visual only) is preferred to a false security claim.

What would settle it (next step, requires CI/device): an instrumented test that signs `unsigned.pdf` via `addSignature` + `saveIncremental` / external signing on 2.0.27.0 and checks the output with the fixture verifier (`tools/signature_fixtures/verify_signature_fixtures.py`, or pdfsig). If that passes on a device and on CI, migration can be reconsidered.

## 5. Verification evidence

Independent of iText and PDFBox, in the authoring environment:
- `tools/signature_fixtures/make_signature_fixtures.py` builds a PDF with a detached CMS signature (cryptography library, SHA-256, RSA-2048, throwaway key held only in memory and discarded; only the public certificate is saved).
- `pdfsig` (poppler 24.02): `valid_signed.pdf` -> "Signature is Valid", total document signed, signer "ProPDF Test Signer", SHA-256, adbe.pkcs7.detached; `tampered_signed.pdf` -> "Digest Mismatch"; `appended_after_sign.pdf` -> "Signature is Valid" + "Not total document signed"; `unsigned.pdf` and `visual_signature.pdf` -> "does not contain any signatures". Certificate trust: "issuer isn't Trusted" (expected; self-signed).
- `openssl cms -verify` over the ByteRange bytes: valid=pass, tampered=fail, appended=pass. ByteRange starts at 0, ends at EOF for valid/tampered; CMS present and parseable. `qpdf --check` clean.
- Placement math: mirrored in Python and rendered with pdfium for /Rotate 0/90/180/270 with and without an offset CropBox: stamp lands in the requested rectangle, aspect ratio kept, image not mirrored/rotated (16 cases pass); widget-rectangle mapping (`userRect`) also passes 8 cases.

NOT verified (needs CI/device): any iText or PDFBox code path in the app, Kotlin compilation of the new/changed files, the instrumented tests below, rendering inside the Android app.

## 6. Tests added

| File | What it covers | Status |
|---|---|---|
| `core/src/test/.../VisualSignatureGeometryTest.kt` | rotation, CropBox offset, clipping, invalid input, userRect | written, not run |
| `core/src/androidTest/.../PdfSignatureEngineInstrumentedTest.kt` | sign -> ByteRange/CMS/cert info/BouncyCastle independent CMS check; original bytes kept as prefix; tamper -> invalid; second signature keeps first; alias mismatch; fixtures classified; unreadable file is an error; wrong password leaks nothing; expired cert; bad page/placement; timestamp rejected; visual is not cryptographic; visual refuses signed PDFs; visual placement on rotated pages via render | written, not run |
| `core/src/androidTest/assets/signature_fixtures/` | `valid_signed.pdf`, `tampered_signed.pdf`, `appended_after_sign.pdf`, `unsigned.pdf`, `visual_signature.pdf`, `certificate.pem` | verified with pdfsig/openssl |
| `tools/signature_fixtures/*.py` | fixture generators and independent verifier / geometry check | run here, all pass |

Secrets: tests generate a fresh key pair and random password per run in the app cache and delete them; no private key, keystore or password is committed (checked: no "PRIVATE KEY" in assets).

## 7. Private-key safety

- Never logged: no Log/Timber calls in the signing path; errors are typed with user-facing text and never include paths, passwords or key data (wrong-password test asserts this).
- Password `CharArray`s are zeroed after use. (`String` copies from the UI still exist until garbage-collected; not fixable without changing the dialog to char arrays.)
- No export: keys are only read from the app-private p12 inside one call.
- **Fixed:** `filesDir/keystores/` was included in Android auto-backup (allowBackup=true, no excludes). Excluded in `backup_rules.xml` and `data_extraction_rules.xml`.
- Remaining: p12 files sit in app-private storage protected only by the file's own password; they are not wrapped by Android Keystore.

## 8. Limitations / findings not fixed

1. **Signature placement UI is not connected to a real page.** `ApplySignatureActivity` has an empty placeholder where the PDF viewer should be, `totalPages` stays 1, page navigation is a no-op, and the overlay rectangle is in screen pixels but is passed as PDF points. Signatures will land in the wrong place until the page preview and a pixel-to-point conversion are added. This needs a UI change with a renderer; not done here.
2. Trust, revocation (OCSP/CRL), timestamps and LTV are not evaluated or produced; verification means integrity + authenticity of the CMS signature only.
3. `PdfSignatureEngine` reuses the post-write iText self-check, which is the same library that signed. It is a sanity check, not independent verification; independent checks are in the tests/tooling.
4. `BatchSignatureWorker` is not enqueued anywhere and only stamps page 1 of a single document.
5. `CertificateRepository.importP12Certificate` uses the first keystore alias, reads `keySize = null`, and does not check that a private key exists at import time.
6. Imported keystores are not hardware-backed.
7. Nothing compiled. Expect another CI round; iText API names were kept from the existing code (`SignatureUtil`, `PdfPKCS7`) except `StampingProperties().useAppendMode()`.

## 9. Remaining iText (after this phase)

- `core`: `PdfSignatureEngine` (signing and verification), `itext7-core:7.2.5`.
- `app`: `itext7-core:7.2.5` dependency in `app/build.gradle`.
- `security`: iText `kernel/io/layout/forms` 7.2.5 (Phase 3 left these; this phase did not touch them).
- Removed from visual signing: all iText use in `applyVisualSignature`.

## 10. Files changed

- `core/.../pdf/signature/PdfSignatureEngine.kt` (rewritten)
- `core/.../pdf/signature/PdfBoxVisualSignatureEngine.kt` (new)
- `core/.../pdf/signature/VisualSignatureGeometry.kt` (new)
- `core/.../pdf/signature/SignatureEngineException.kt` (new)
- `security/.../SignatureViewModel.kt`, `app/.../SecurityHubScreen.kt`
- `app/src/main/res/values/strings_signature.xml`, `layout/activity_apply_signature.xml`, `xml/backup_rules.xml`, `xml/data_extraction_rules.xml`
- tests, fixtures and tools listed in section 6
