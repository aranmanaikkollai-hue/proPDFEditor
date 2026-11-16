# REAL DEVICE TEST CHECKLIST (Phase 1 reliability)

Every row is **NOT RUN**. No real device was used and nothing was built or executed for this document.
Record for each run: device model, Android version, build commit, date, tester. Use at least one API 23-25
device, one API 33-34 device and one tablet. The older FileProvider / SAF / scanner list stays in
`docs/MANUAL_DEVICE_TEST_CHECKLIST.md`; this list does not repeat it.

| # | Area | Steps | Expected | Status |
|---|---|---|---|---|
| 1 | Upgrade | Install the previous release, open several PDFs (creates recent files, bookmarks if any), install this build over it | App opens, recent files still listed, no crash on first launch | NOT RUN |
| 2 | Upgrade | Same as 1 but from the oldest build you still have | App opens; if data is reset, no crash | NOT RUN |
| 3 | Offline | Airplane mode, then use viewer, annotate, forms, merge, split, compress, scan, OCR | Every feature works; no "network" error text anywhere | NOT RUN |
| 4 | Permissions | Settings > Apps > ProPDF > Permissions | No Internet entry; camera asked only when scanning | NOT RUN |
| 5 | Open | Open a PDF from Files, from another app (Open with), from a share intent | Opens; original file unchanged (compare checksum) | NOT RUN |
| 6 | Save safety | Annotate and save, then force-stop the app mid-save | Original intact; no half-written output | NOT RUN |
| 7 | Save safety | Save to a SAF folder on SD card / cloud provider | Output created; original unchanged | NOT RUN |
| 8 | Viewer | 300-page PDF: scroll fast, pinch zoom, double tap | No crash, no blank pages that stay blank | NOT RUN |
| 9 | Viewer | Rotate device 5 times while on page 40 | Same page after rotation | NOT RUN |
| 10 | Process death | Open PDF, background app, kill it (Developer options: Don't keep activities), return | Returns to a usable state | NOT RUN |
| 11 | Password PDF | Open encrypted PDF, wrong then right password | Wrong password refused cleanly; right opens | NOT RUN |
| 12 | Annotate | Draw, highlight, undo, save, reopen | Annotations persist | NOT RUN |
| 13 | Forms | Fill text/checkbox fields, save, open in another PDF app | Values present | NOT RUN |
| 14 | Signature | Draw signature, place, resize, save | Signature visible in another viewer | NOT RUN |
| 15 | Redaction | Redact text, save, try to select/search the text in another viewer | Text gone, not just covered | NOT RUN |
| 16 | Scanner | Scan 3 pages, adjust corners, export | PDF with 3 pages | NOT RUN |
| 17 | OCR | Run OCR on a scan | Text recognised; no crash | NOT RUN |
| 18 | Compression | Compress a PDF with images | Smaller or "no gain" message; original unchanged | NOT RUN |
| 19 | Merge / split | Merge 3 files, split one | Page counts correct | NOT RUN |
| 20 | Low storage | Fill storage to under 50 MB, save a large file | Clear error; no partial file left | NOT RUN |
| 21 | Low memory | Open a 100 MB PDF on a 2-3 GB RAM device | No crash (slow is acceptable) | NOT RUN |
| 22 | TalkBack | Enable TalkBack, go through home, file list, viewer toolbar | Every control is announced with a meaningful name | NOT RUN |
| 23 | TalkBack | File list: focus the favourite star and the more button | Announced as "Add to favorites"/"Remove from favorites" and "More options"; double tap works | NOT RUN |
| 24 | TalkBack | Focus the signature pad, redaction area, crop corners, form overlay | Announced with a description (these areas still cannot be drawn on by TalkBack gestures; see report) | NOT RUN |
| 25 | Switch access | Activate a drawing view with a switch | No crash; click is delivered | NOT RUN |
| 26 | Font scale | System font 200%, display size largest | No clipped or overlapping controls on main screens | NOT RUN |
| 27 | Tablet | Landscape and portrait on a tablet, split screen | Layouts usable; compression tablet layout shows the file info card | NOT RUN |
| 28 | Dark mode | Toggle system dark mode while a PDF is open | No unreadable text | NOT RUN |
| 29 | Batch | Queue a batch encrypt job, reboot the device | Job resumes or asks for password re-entry; no plaintext password anywhere | NOT RUN |
| 30 | Notifications | Android 13+: deny notification permission, run a batch job | Job still runs; no crash | NOT RUN |
