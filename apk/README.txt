Rapid Office Pro — v1.0
=======================

RapidOfficePro-v1.0-b12.apk  — signed release build. Install this on your phone.

A fast reader for PDF, Word, Excel, PowerPoint, OpenDocument, CSV, web pages and text files.
It installs straight over Rapid PDF and the earlier Rapid Office Pro 2.x builds and keeps your
reading history.

Coming from 2.x: install this APK by hand once. The 2.x updater compares version names and
would see 1.0 as older than 2.6, so it will not offer it. From 1.0 on, updates go by build
number and arrive inside the app again.


Installing
----------
Copy the APK to the phone (Google Drive, memory card, USB — anything), tap it, and allow
"install unknown apps" for whichever app you copied it with.

Every build is signed with the same key (keystore/rapidpdf.jks), so newer versions install
over this one without uninstalling anything.


First run
---------
Grant "All files access" when asked (Settings > Apps > Rapid Office Pro > All files access).
Without it the app still works through the system file picker, but searching your whole
phone for a document will not.


What it opens
-------------
PDF                         .pdf
Word                        .docx  .docm  .dotx  .dotm  .doc  .dot  .rtf
Excel                       .xlsx  .xlsm  .xltx  .xltm  .xls  .xlt
PowerPoint                  .pptx  .pptm  .potx  .potm  .ppsx  .ppsm  .ppt  .pot  .pps
OpenDocument                .odt  .ott  .ods  .ots  .odp  .otp  .odg
Tables                      .csv  .tsv  .tab
Web pages                   .html  .htm  .xhtml
E-books                     .epub  .mobi  .azw  .azw3  .prc
WordPerfect                 .wpd  .wp  .wp5  .wp6   (text only)
Text                        .txt  .md  .log  .json  .xml  .yaml  .ini  .srt  .sql  and
                            source code — shown in a fixed-width font

A file is recognised by what is inside it, not by its name. A Word file saved as ".bin", or a
PDF that WhatsApp sent with no extension, still opens in the right viewer. A damaged file says
so instead of crashing. Formats that cannot be shown (.xlsb, Apple Numbers/Pages/Keynote,
password-protected Office files, and e-books locked with a store's DRM) say that clearly.

Big spreadsheets open in seconds — a 250,000-row CSV in about 4 s — at a readable size on
their top-left corner. Only the cells on screen are drawn, so scrolling and zooming stay
smooth however big the sheet is. Pinch out to see all of it; zoom in much further than on a
normal page.

PowerPoint and other presentations have a Slide show button (▶) at the top: full screen,
upright or sideways — it turns with the phone — one slide at a time. Tap or swipe for the next slide, tap the left edge to go back,
press Back to finish.

Word, Excel and PowerPoint files from 2007 onwards are read fully: text with its bold,
italic, underline, colour, size and alignment; headings; bulleted and numbered lists;
tables; pictures; page size and margins; sheet tabs, cell values, number formats and dates;
slides with their text boxes and pictures where the file puts them.

The older binary formats — .doc, .xls and .ppt from 1997-2003 — are a different thing on
disk, and what comes out of them is the content, not the design. Every word of a .doc, every
cell value of a .xls and every slide's text of a .ppt is read correctly; the fonts and page
layout are not. The app says so in a line at the top when you open one.

This is a reader, not an editor. "Open with another app" in the three-dot menu hands the
file to a full Office app when you need to change something.


Finding a document
------------------
Type in the search box on the home screen and it looks across the whole phone and the memory
card, not just the files you have opened before. The line under the tabs says how many
matched and how many of those were found on storage. Tap one to open it.


Reading screen, at a glance
---------------------------
- Tap the page once            hide or show the toolbars; the page fills the screen
- Double tap                   zoom to 1.5x on that spot; double tap again to come back
- Two fingers                  pinch zoom, any amount
- Hold a word                  select text: Copy, whole page, Find, Translate, Share
                               drag the two round handles to take in more
- Tap a link                   web links open in the browser, links to another page jump
- Handle on the right          drag to fly through the document
- "12 / 600" pill              tap to jump to a page, hold to bookmark it
- Zoom                         slider with Out, %, In and Fit — hold Fit for fit-width
- View                         continuous scroll / page by page / two pages
                               page by page turns exactly one page per swipe, however hard
                               two pages stays two pages when you turn the phone sideways
- Screen                       one tap turns the screen; hold to follow the phone again
- Lock                         locks the controls: scrolling and zoom still work, taps do not
- More                         contents, thumbnails, go to page, edit pages, colour mode


Brush and eraser (PDF)
----------------------
Three-dot menu > Brush, or the Brush button in Edit pages.

  Colours        white, black, red, yellow, green, blue. White is first because covering
                 something up is the commonest reason to draw on a document
  Size           the slider, 2 to 60 pt, shown beside it
  Brush          one finger draws; two fingers still move and zoom the page
  Eraser         sweep over strokes to take them off; the circle shows its reach
  Undo / Redo    every stroke, every erase, every crop and every rotation, in one history
  Clear          removes every mark on the document
  Save in        writes the marks into the PDF itself

Marks are kept beside the document, not inside it, so they survive closing the app and can
always be undone or erased. "Save in" is the exception: after that they are page content,
every other PDF reader shows them, and whatever was underneath a mask is gone for good — so
it asks first, and cannot be undone. Press Save in the title bar afterwards to write the file.


Editing pages (PDF)
-------------------
Three-dot menu > Edit pages, then tap pages to pick them.

  Left / Right    turn the selected pages a quarter turn
  Crop            drag the frame on one page; it is applied to every page you selected
  Split           for scans where two book pages were photographed on one sheet. Each
                  selected page becomes two, in reading order, and a new PDF is made — you
                  choose whether the left or the right half comes first
  All             select every page
  New PDF         build a new PDF from the selected pages, then share it or keep it
  Undo / Redo     the same history the brush uses: strokes, crops and rotations
  Done            leave

Rotations are held until you press Save in the title bar. Crop, Split and New PDF write a
new file and never touch the original.


Battery and speed
-----------------
Nothing is rendered that is not on screen, nothing renders while a finger is moving, and an
idle reader draws nothing at all. A 526 MB, 600-page scan opens in about a third of a second
and a 3000-page document in about one second, because only the cross-reference table is read
up front.

Office files are measured once when opened and never again — zooming is a matrix, not a
re-layout — so a Word file stays sharp at any zoom without re-rendering anything.


What changed in 1.0
-------------------
Smooth however long it is used
- Up to 25 documents stay in the tab list. Only the two most recent hold an open file and
  memory; the rest keep their name, page and place and reopen in a moment. Before, every
  document stayed fully open until Android killed the app, which is what made pages turn
  blurry, scanned pages stop appearing and opening slow down after a few days.
- There is one reader. A PDF opened from another app becomes a new tab instead of stacking a
  whole new reader on top of the last one.
- Leaving the reader, or the phone running short of memory, gives memory back at once.
- The tab list shows a small picture of the page each document was left on.

Pages drawn the right shape
- Cached page sizes are thrown away when the file changes (replaced, re-downloaded under the
  same name, or saved after a crop or rotation), so pages are never squashed into old boxes.
- Measuring page sizes no longer stops for good after pressing Home mid-way.
- After Save, the document is reopened from the file just written.

Crash fixes
- A full phone or a damaged history database no longer crashes the app.
- Contents and document info no longer freeze the screen on large scanned PDFs.
- Finishing a split, new PDF, crop or rotation after the reader was closed no longer crashes.
- Cropping a very long or very wide page no longer crashes.
- Links with unusual characters no longer crash the reader.
- Turning the phone while an update downloads no longer crashes.
- A slow or removed memory card no longer freezes the home screen.
- Huge sheets, huge text files, very long lines, damaged Word/Excel/PowerPoint files and
  repeated pictures no longer run the phone out of memory.

New
- Slide show for presentations.
- EPUB, MOBI, AZW3 and WordPerfect files open. OpenDocument, web pages and many text formats
  are new too, and files are recognised by their contents rather than their names.
- Huge spreadsheets open fast and scroll smoothly; nothing is cut off up to a million rows.

Small things
- Settings has a coloured header, so its back arrow is visible.
- The ⋮ and ☆ on the home list and the ✕ in the tab list are visible on the light background.
- The bottom bar shows more of the next button, so it is clear there is more to swipe to.
- "Clear cache" keeps an update that is downloading, and covers are saved again afterwards.
