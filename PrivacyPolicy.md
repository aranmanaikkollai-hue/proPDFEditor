# Privacy Policy - ProPDF Editor

**Last updated:** March 2026
**App:** ProPDF Editor (com.propdf.editor)

## Summary

ProPDF Editor does NOT collect, upload, or share any personal data.
Everything happens on your device only.

---

## Data Access

### PDF Files
- The app reads PDF files you open and writes output PDFs to your Downloads folder.
- No file content is uploaded or sent anywhere.

### Camera
- Used only for the document scanner feature.
- Captured images are processed locally and immediately converted to PDF.
- Raw images are deleted after conversion.
- Permission is requested at runtime. You can deny it; all other features still work.

### Storage
- Saves output PDFs and JPG images to your Downloads folder.
- Uses Android MediaStore API (API 29+) or direct file write (API 21-28).

### Internet
- Google ML Kit downloads language recognition models on first use (one-time, ~10MB).
- After downloading, ML Kit works offline.
- No user data is sent to any server.

### Recent Files
- The "Recent Files" list is stored in a local database on your device only.
- Clear it via Settings > Clear recent files.

---

## Permissions Explained

| Permission | Purpose | Required |
|-----------|---------|---------|
| READ_EXTERNAL_STORAGE (API 21-32) | Open PDFs | For older Android |
| WRITE_EXTERNAL_STORAGE (API 21-29) | Save to Downloads | For older Android |
| READ_MEDIA_IMAGES (API 33+) | Image-to-PDF feature | For newer Android |
| CAMERA | Document scanner | No - app works without it |
| INTERNET | ML Kit model download | No - OCR needs it once |

---

## Third Parties

Only Google ML Kit communicates with external servers (to download models).
No analytics, no crash reporting, no advertising SDKs are included.

---

## Contact

For privacy questions: [your-email@domain.com]

For privacy policy hosting on GitHub Pages, save this file as
`privacy-policy.md` in your repository root and enable GitHub Pages.
