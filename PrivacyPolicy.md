# Privacy Policy — ProPDF Editor

**Last updated:** March 2026  
**App name:** ProPDF Editor  
**Package:** com.propdf.editor  
**Developer:** [Your Name / Company]

---

## 1. Introduction

ProPDF Editor ("the App") is a free, offline PDF editor for Android. This Privacy Policy explains what data the App accesses, how it is used, and your rights.

**Key principle: ProPDF Editor does NOT collect, store, or transmit any personal data to any server.**

---

## 2. Data We Access (and Why)

### 2.1 PDF Files and Documents
- **What:** The App reads PDF files you choose to open. It writes output files (merged, annotated, compressed PDFs) to your Downloads folder.
- **Why:** Core app functionality — you cannot edit a PDF without reading it.
- **Storage:** All files remain on your device. No file content is uploaded anywhere.

### 2.2 Camera (for Document Scanner)
- **What:** Live camera preview and photo capture.
- **Why:** The document scanner feature uses your camera to photograph physical documents.
- **Storage:** Captured images are processed locally and immediately converted to PDF. Raw images are deleted after conversion.
- **When asked:** Runtime permission is requested — you can deny it and the rest of the app still works.

### 2.3 Storage / Downloads Folder
- **What:** The App saves output PDFs to your device's Downloads folder.
- **Why:** So you can find your files in the standard Files app.
- **API 29+:** Uses MediaStore API (Android standard, no extra permission needed).
- **API 21–28:** Requires WRITE_EXTERNAL_STORAGE permission.

### 2.4 Internet Connection
- **What:** The App requests INTERNET and ACCESS_NETWORK_STATE permissions.
- **Why:** Google ML Kit (the OCR/text recognition library) downloads language models on first use. This is a one-time download. After downloading, OCR works fully offline.
- **No tracking:** No analytics, no crash reporting, no advertising SDKs are included.

---

## 3. Third-Party Libraries

The App uses the following open-source libraries. None of these libraries collect user data:

| Library | Purpose | License |
|---------|---------|---------|
| iText 7 Community | PDF editing | AGPL-3.0 |
| PDFBox Android | PDF rendering fallback | Apache 2.0 |
| Google ML Kit | OCR text recognition | Google APIs ToS |
| CameraX | Camera for scanner | Apache 2.0 |
| Hilt/Dagger | Dependency injection | Apache 2.0 |
| Kotlin Coroutines | Async processing | Apache 2.0 |

**Google ML Kit:** Subject to [Google's Privacy Policy](https://policies.google.com/privacy). ML Kit processes data on-device. Language model files are downloaded via Google's servers on first use.

---

## 4. Data Storage

### On-Device Only
All data processed by the App stays on your device:
- PDF files are read from and written to your local storage only.
- The "Recent Files" list is stored in a local SQLite database (`recent_files.db`) in the app's private storage. This list is never synced.
- App settings (theme preference) are stored in local SharedPreferences.

### No Cloud Storage
ProPDF Editor has no user accounts, no cloud sync, and no backend servers.

---

## 5. Permissions Explained

| Permission | Why Needed | Required? |
|-----------|-----------|----------|
| READ_EXTERNAL_STORAGE (API ≤32) | Open PDF files from storage | Yes, for older Android |
| WRITE_EXTERNAL_STORAGE (API ≤29) | Save files to Downloads | Yes, for older Android |
| READ_MEDIA_IMAGES (API 33+) | Access images for image-to-PDF | Yes, for newer Android |
| CAMERA | Document scanner | No — app works without it |
| INTERNET | ML Kit model download | No — OCR requires it once |
| ACCESS_NETWORK_STATE | Check if online before ML Kit download | No |

---

## 6. Children's Privacy

ProPDF Editor does not knowingly collect any information from children under 13. The App is a productivity tool with no social features.

---

## 7. Changes to This Policy

If this policy is updated, the "Last updated" date above will change. Continued use of the App after changes constitutes acceptance.

---

## 8. Contact

For privacy questions:  
📧 [your-email@domain.com]  
🌐 [your-website.com]

---

## 9. Your Rights (GDPR / CCPA)

Since the App does not collect personal data or send it to any server, there is no personal data to access, correct, or delete. The only data stored is:
- Your recent files list (local, delete via Settings → Clear recent files)
- App preferences (local, uninstall app to delete)

You can uninstall the App at any time to remove all local data.
