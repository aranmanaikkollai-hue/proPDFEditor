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

## Phase 2a additions (viewer polish) - all NOT RUN

| # | Area | Steps | Expected | Status |
|---|---|---|---|---|
| 31 | Bookmarks | Open a PDF, go to page 5, tap the bookmark icon | Icon fills; TalkBack says "Remove bookmark" | NOT RUN |
| 32 | Bookmarks | Bookmark pages 9 and 2, open Bookmarks from the More sheet | Listed as Page 2, Page 9 in that order; tapping one jumps there | NOT RUN |
| 33 | Bookmarks | Force-stop the app, reopen the same PDF from the same place | Bookmarks still present | NOT RUN |
| 34 | Bookmarks | Delete a bookmark from the list | Row disappears; icon on that page is outline again | NOT RUN |
| 35 | Bookmarks | Open a password-protected PDF, unlock, bookmark a page, close, reopen and unlock | Bookmark still there | NOT RUN |
| 36 | Go to page | Tap the "n / N" title, enter 0, 999, letters, then a valid number | Invalid input shows an error and disables Go; valid input jumps | NOT RUN |
| 37 | Reading mode | More sheet > Reading mode > Night, scroll, zoom in far | Pages inverted at every zoom level, including sharp tiles; background dark | NOT RUN |
| 38 | Reading mode | Choose Sepia, leave the viewer, force-stop, reopen any PDF | Sepia still selected | NOT RUN |
| 39 | Reading mode | Annotate in Night mode, save, open the saved PDF in another app | Saved file is not inverted or tinted | NOT RUN |
| 40 | Tablet | 600 dp+ tablet: open Thumbnails | Sidebar sits beside the page, page is not covered, tapping a thumbnail keeps it open | NOT RUN |
| 41 | Tablet | Same, then turn on annotation mode and draw | Strokes land where you touch (overlay and page aligned) | NOT RUN |
| 42 | Landscape phone | Rotate with the More sheet open | Sheet scrolls to every item | NOT RUN |
| 43 | Phone | Portrait phone: Thumbnails | Sidebar overlays the page and closes after choosing a page | NOT RUN |

## Phase 2b additions (page layouts) - all NOT RUN

| # | Area | Steps | Expected | Status |
|---|---|---|---|---|
| 44 | Layout | More > Page layout > Two pages, tablet landscape, 20-page PDF | Page 1 alone on the right, then pairs 2-3, 4-5; gap between pages; whole spread fits width | NOT RUN |
| 45 | Layout | Two pages: scroll down through 20 pages | Rows scroll smoothly; page counter follows the page nearest the screen centre (left or right of the row) | NOT RUN |
| 46 | Layout | Two pages: pinch zoom in on the right page, pan left and right | Zoom keeps the pinched point; pan stops at the content edges | NOT RUN |
| 47 | Layout | Two pages: PDF with mixed page sizes (portrait + landscape) | Pages scaled to equal width, top-aligned in the row, no overlap | NOT RUN |
| 48 | Layout | More > Page layout > Horizontal | Pages in one row fitted to screen height; swiping left shows the next page | NOT RUN |
| 49 | Layout | Horizontal: fling quickly through 50 pages, then to the very end and back | Fling decays smoothly, stops at both ends, never shows empty space past the last page | NOT RUN |
| 50 | Layout | Horizontal: pinch zoom to 4x, pan up/down and left/right | Zoom works; vertical pan limited to the page; no jump when releasing | NOT RUN |
| 51 | Layout | Horizontal: tap Go to page 30, Bookmarks entry, thumbnail 12 | Each lands on the chosen page, left-aligned (or at the end if it is among the last pages) | NOT RUN |
| 52 | Layout | Horizontal: look at the right edge of the screen | No vertical page scrubber is shown | NOT RUN |
| 53 | Layout | Switch Vertical -> Two pages -> Horizontal -> Vertical on page 15 | Page 15 stays in view after every switch; no blank screen | NOT RUN |
| 54 | Layout | Close and reopen the app | Last chosen layout is remembered | NOT RUN |
| 55 | Annotate | Choose Two pages, then start annotating | Pages switch to one column; More sheet layout chips are disabled with an explanation; strokes land where you touch | NOT RUN |
| 56 | Annotate | Stop annotating | Previously chosen layout returns on the same page | NOT RUN |
| 57 | Reading mode | Night and Sepia in each of the three layouts | Colours applied in every layout, including sharp tiles on the current page | NOT RUN |
| 58 | Rotation | Rotate the device in each layout | Layout refits; no crash; page stays | NOT RUN |
| 59 | Large file | 300-page PDF in each layout, scroll to the middle quickly | No crash; pages render as they come into view | NOT RUN |
| 60 | Password | Open a password-protected PDF, unlock, switch layouts | Works as for any PDF | NOT RUN |

