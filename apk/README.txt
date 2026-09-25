Rapid Office Pro — build v2.6
=============================

RapidOfficePro-v2.6.apk  — signed release build. Install this on your phone.

This is the same app as Rapid PDF, renamed, because it is no longer only a PDF reader:
Word, Excel, PowerPoint, CSV and text files now open in it too. It installs straight over
the old version and keeps your reading history.


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
Word                        .docx  .docm  .dotx  .doc  .dot  .rtf
Excel                       .xlsx  .xlsm  .xltx  .xls  .xlt
Text and tables             .csv  .tsv  .txt  .md  .log  .json  .xml
PowerPoint                  .pptx  .pptm  .potx  .ppt  .pot  .pps  .ppsx

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


New in v2.6
-----------
- Pinching or double-tapping only zooms. The zoom bar (slider, Out, In, Fit) opens only from the
  Zoom button. If it is already open, it stays open while you pinch.

New in v2.5
-----------
- The Phone tab is a file manager. It opens on the phone's storage and the memory card (with free
  space shown). Tap a folder to go into it, and ".." or Back to go up. The path is shown at the top
  (Internal storage › Download › Books). Each folder lists its subfolders first, then the documents
  the app can open.
- The search box at the top looks everywhere, whichever tab is open: Recent, Favorite and the whole
  phone and memory card. Files you have read come first.
- The bottom bar is one row again. In landscape every button shows. In portrait the first five show
  and the rest swipe in from the right; a sliver of the next button shows that there is more.
- The zoom slider is slimmer.
- Brush is no longer on the bottom bar; it is under Edit, next to the page tools.
- Search results start at the top with PDFs first. The list no longer lands scrolled down when many
  results come in.
- Long document names wrap onto up to three lines instead of being cut to one; folder names get two.
- New icons everywhere: Google's Material Symbols, outlined and light, the thin and precise style of
  Google's own apps. The bar buttons are slightly more compact to match.
- View shows each choice with its icon (continuous, page by page, two pages) and ticks the one in
  use.

New in v2.4
-----------
- Updates from GitHub. "Install latest version" is in the ⋮ menu of the reader and in Settings. It
  downloads the newest release and installs it over this one, keeping your history. The first
  time, Android asks you to allow this app to install apps. The home screen also checks once a
  day and only says something when a new version is out.
- Pinch and double-tap zoom stay exactly where your fingers are, in landscape and in the page by
  page and two-page views too. Before, the zoom slid towards the middle of the screen.
- The bottom bar has two rows: the page number, Zoom, View, Screen and Lock on top, and Contents,
  Pages, Edit, Brush and Colour underneath.
- The extra "Go to" button is gone; tap the page number to go to a page. Settings moved from the
  bottom bar to the ⋮ menu.
- The brush bar has a ✕ in its corner to close it.

New in v2.3
-----------
- Documents always open in portrait. The Screen button turns only the document in front of you;
  it is no longer remembered for every document after it. (Settings > Screen orientation still
  chooses how documents start.)
- Turning the phone sideways fits the page again, instead of keeping the portrait zoom, which made
  pages far too big in landscape.
- New PDF > Share works. The job that builds the new PDF was being thrown away whenever the page
  moved, so it never finished; rotate, crop and page measuring were hit by the same thing.
- The drag handle on the right is slimmer and only moves when you mean it to. While it is hidden,
  touching it just shows it. Once it is showing, grab the handle itself and drag. A brush of the
  thumb, or a tap, does nothing, and it no longer jumps to where the finger lands.
- Documents are remembered by where they really are on the phone. One opened from WhatsApp or a
  file manager keeps opening from the list, even after that app withdraws its access. Older history
  entries that stopped opening are found again by name and size.
- The home screen asks for "All files access", which search needs. There is a new Phone tab that
  lists every document on the phone and the memory card without typing anything, and searching
  now reliably covers the memory card too.
- Scrolling shows a clearer preview of each page as it passes, and pages already scrolled past no
  longer hold up the page you land on.
- Zoom bar in two rows: a full-width slider on top, and Out, %, In and Fit underneath.
- The reading percentage is gone from the bottom bar. The page number has "Page" written under it.
- Everything that was under More is now on the bottom bar, each with its name: Contents, Pages, Go
  to, Edit, Brush, Colour, Settings. The top-right menu no longer repeats them.
- A cleaner, more compact brush bar: colours and size on top, tools underneath, and a clear Done.
- New Move tool on the brush bar puts the pen down, so one finger scrolls without drawing and the
  marks stay on screen. Tap Brush to write again.
- The title bar shows the document's name, not the app's.
- In Edit pages, "All" becomes "None" once every page is selected. Tap it again to deselect them all.
- A copy left behind by a save that got interrupted is now cleaned up, so it can't fill the phone
  over time.

New in v2.2
-----------
- Undo and Redo are on the Edit pages bar as well as the brush bar, so a crop or a rotation
  can be taken back where it was made.

New in v2.1
-----------
- A brush and an eraser, with six colours, an adjustable size, and undo and redo.
- Undo and redo cover cropping and rotation too, so a crop can be taken back off and the
  page restored exactly as it was.
- The last page read is remembered properly for every kind of document, along with the zoom
  and the colour mode that document was being read in. The write no longer belongs to the
  screen that started it, so closing the reader can no longer cancel it, and it is also
  written a couple of seconds after you settle on a page in case the phone drops the app.
- Fixed a crash: reading a page's links on one thread while another rendered a page put two
  callers inside pdfium at once. Every call into pdfium is now behind one lock.


New in v2.0
-----------
- Word, Excel, PowerPoint, CSV, RTF and text files open in the app.
- Renamed to Rapid Office Pro, with a new icon.
- Search the whole phone from the home screen, with a count of what matched.
- Links in a PDF work: web addresses open in the browser (whether the file marks them as
  links or just has them as text), and links to another page jump to it.
- Page editing has Split, for scans of two book pages on one sheet.
- Fixed: "New PDF" failed for any document opened through the system file picker. The read
  permission never travelled with it, so pdfium could not reopen the file.
- Fixed: the last page read was often forgotten. The write was tied to the reading screen
  and was cancelled by the screen closing — which is exactly when it mattered.
- Fixed: two-page view showed four pages when the phone was turned sideways.
- The page starts under the title bar instead of behind it, and fills the screen when the
  toolbars are tapped away.
- The page number sits on the bar itself, before the zoom button.
- Double tap zooms to 1.5x.


From v1.3, still here
---------------------
- Every icon on every bar has its name written under it.
- Hold a word to select text — copy, search, translate or share it.
- Crop pages, applied across a whole selection at once.
- One tap on the screen icon turns the screen.
- The drag handle answers the first touch; the phone's back-swipe strip no longer steals it.
- Dragging the handle is fast on huge documents.
- Page by page turns one page at a time, with a shadow down the page edges.
- The open-documents list slides down from the top, WPS-style.
- Colourful header on both screens.


Known limits
------------
- .doc, .xls and .ppt show content, not layout. See above.
- Formulas in a spreadsheet are not recalculated; the value Excel last saved is shown, which
  is the same figure the author saw.
- A sheet is shown to 20 000 rows; longer ones say so.
- PowerPoint transitions, animations, charts and SmartArt are not drawn. Text, pictures and
  simple shapes are.
- Office files are read, not written. Nothing this app does can change one.
