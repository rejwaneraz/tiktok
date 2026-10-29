# TikTok Grab — decisions and why

Every architectural choice, the evidence behind it, and the alternatives that were killed so they are
not paid for twice. Dates are the day the decision was made. Update this file whenever a decision
changes — the old entry stays, marked as superseded.

## Ground rules that never change

| Rule | Why |
|---|---|
| Videos are plain files, never MediaStore rows | MediaProvider deletes files the package owns when the app is uninstalled. That is how the first library disappeared. |
| One folder for every clip, profile is a record | A per-author folder made the file the source of truth for membership; a record (clipId → author, caption) survives a reinstall and a move. |
| Comments come from the public reader, never from TikTok | TikTok's `api/comment/list/` answers 200 with an empty body unless the request is signed (X-Bogus/Argus). Five parameter shapes, real cookies, curl_cffi and a replayed WebView request all failed. |
| Nothing blocks on the WebView thread | `shouldInterceptRequest` runs on the WebView's own thread; a 30–60 s OkHttp call there, or `destroy()` during a load, is a native crash with no Java stack. |
| No desktop UA inside the WebView | Android WebView claiming Windows Chrome fails TikTok's own browser check and the page never loads. |
| Source files are written with file tools, never a shell heredoc | The shell eats `\n` and `\'`, which produced broken string literals and AAPT "Invalid unicode escape sequence". |
| A session is only as good as the last real read | A cookie proves it was saved, nothing more. The last listing outcome is what Settings shows. |

## Decisions

### 2026-10-20 — the feed keeps its pages; only the list moves
The offline feed used to rebuild its adapter on every tab change (`notifyDataSetChanged` plus a cleared
holder map). The pager rebound every page, `playAt` had nobody to start, and the video restarted from
zero with a black flash.

Now: the adapter is created once, the current clip is rotated to the page index it already occupies in
the new list, and `bind()` refuses to touch the player when the URI has not changed. Gestures are read
by one state machine in the activity (`onDrag`): the page holds the touch on DOWN, decides vertical vs
horizontal on the first real movement, and hands it back to the pager when the drag is vertical.
Sideways swipes follow the finger and need a quarter of the screen, not 130 dp.

*Killed:* a second adapter per tab (restarts the video), per-page gesture thresholds (a vertical pager
cancels the child gesture within ~8 dp), and reading touches on the ViewPager2 wrapper (it never gets
them).

### 2026-10-20 — the feed is never hidden behind another screen
Explore, Me and a profile are opaque siblings stacked over the feed in `stage`. Hiding the feed stopped
the player and coming back recreated it. Back from a profile now resumes the same holder at the same
position.

### 2026-10-20 — clips end in `.nomedia`
The extension is the whole trick: no gallery indexes an unknown type and no other player offers to open
it, while the file stays a plain file that survives an uninstall. Files from older builds (`.tgr`, or a
stray `.mp4`) are renamed in place on the next scan. A `.nomedia` marker still sits in the folder.

*Honest limit:* a file manager shows every file in shared Downloads whatever the extension. Only
app-private storage would hide them, and that is deleted on uninstall — the trade was already made.

### 2026-10-20 — no silent MediaStore fallback for videos
Without All files access the downloader used to fall back to `MediaStore.Downloads/<author>/x.mp4`:
visible in the gallery, playable everywhere, and deleted with the app. A download now fails with
"All files access is off" instead, and the library stays honest.

### 2026-10-20 — records live in plain files too
`Backup/` (comments, avatars, meta) was written through MediaStore on Android 10+. Those rows are
package-owned, so a reinstall took them as well. With All files access granted, `Store` now writes plain
files under `Download/TikTokGrab/Backup/`, and only falls back to MediaStore when it must.

### 2026-10-20 — a rate-limit answer is not a dead clip
The comment reader answers `code:-1, "Free Api Limit: 1 request/second."` with HTTP 200. That was read
as a failure. It is now waited out (1.4 s, two tries) before the clip is called unreachable.

### 2026-10-20 — comment pictures are pulled on tap
`images[]` is stored as a URL in the comment file; the JPEG is downloaded only when the picture is
tapped, then kept for offline. A batch fetch therefore stays ~34 KB per clip instead of megabytes.

### 2026-10-20 — pictures are named `.jpg.nomedia`
Avatars and comment pictures sit under `Backup/` and were plain `.jpg`, so a gallery picked them up.
They are now written as `<name>.jpg.nomedia` with a `.nomedia` marker in each folder: the bytes stay a
JPEG, the app finds them by the same name, and no gallery indexes them. Rows written through
MediaStore before the switch was granted are copied into the hidden file and their row dropped, so
the gallery loses them without the app losing the picture.

### 2026-10-20 — a profile's swipe is read before its children
The profile is a scroll view full of buttons, so a page-level touch listener never saw the drag and
"For You → profile" worked while "profile → For You" did not. The gesture is now read in
`dispatchTouchEvent`, before any child, and once it is clearly sideways the activity takes the rest of
the gesture.

### 2026-10-20 — the rail is TikTok's shape, and it is the same in both players
The rail follows TikTok's own grid: 44dp avatar with a 1.5dp white ring and a 20dp red plus on its
bottom edge, 32dp icons, 12sp bold counts with a shadow, 12dp between groups, 6dp from the right edge.
A heart turns the accent red (icon and number), a save turns the bookmark yellow — TikTok's own
choice, not our cyan. The same rail and the same single-tap/double-tap rules live in the online
preview, so a clip does not look like a different app before it is downloaded.

*Honest limit:* the glyphs are our own vectors drawn to TikTok's silhouettes; their exact artwork is
not something we have.

### 2026-10-20 — a double tap never pauses
The page used to carry a click listener as well as a gesture detector, so the first tap of a double
tap fired play/pause before the heart. The click is gone; a single tap is confirmed only when the
second one never arrives.

### 2026-10-20 — replies cannot be read, and the app says so
The reader has no reply endpoint: `comment/list/reply/` answers **404** for four different parameter
shapes, and TikTok's own comment API is signature-gated (verified dead earlier). So a comment shows
"3 replies" as plain grey text, without the word "View" and without a tap target — a count, not a door
that does nothing.

### 2026-10-20 — comment pictures load when the sheet opens
Downloading them only on tap looked like missing pictures, and the file names changed shape (index 0
keeps the old name so pictures saved by the previous build are found again). The sheet now fetches the
pictures it does not have, one comment at a time, and repaints each row as it lands. A batch fetch of
100 comments still downloads none.

### 2026-10-20 — For You rolls again on every open
The order used to be rolled once and kept forever. TikTok hands a new feed each time, so the feed
rolls on open and keeps that order while the app is open — a tab switch still never moves the clip
that is playing.

### 2026-10-20 — the library has an order, and a download has a notification
The library sorts by newest, largest, most played or random, chosen from a chip above the grid and
remembered. A batch download runs with a foreground service and a progress notification, so leaving
the app does not look like stopping; the service owns no work and every call into it is wrapped, so
it can never break the loop.

### 2026-10-20 — the icons are traced, not drawn
The rail glyphs come from the reference sheet by walking the pixel outline, simplifying it
(Douglas–Peucker) and smoothing it (Chaikin), scaled into a 24 x 24 viewport. Each icon is one
vector file of 0.7–1.2 KB, so it stays sharp at every size and a state is a tint, not a second file.
The heart, bubble, arrow, bookmark and note are the picture's own shapes; the launcher mark is the
artwork itself, with a black adaptive background and the disc inside the safe zone.

### 2026-10-25 — the listing streams, and "All" means all
The reader answers 15 clips per page and the app used to walk every page before touching the grid, so
a 500-clip account showed nothing for seconds and then stopped at the cap. Now each page is handed to
the UI the moment it is parsed: the first 15 appear in about a second, later pages append without
moving the scroll or the ticks. "All" no longer stops at 500 — paging continues until TikTok says
`hasMorePrevious=false`, and a page that adds nothing new ends the walk instead of spinning.

### 2026-10-25 — smooth icons, from the large reference art
The first icons were traced from a 40 px sheet and emitted as straight segments, which showed as
facets at 32 dp. The large reference art is traced instead, resampled evenly, smoothed, and written as
cubic Beziers (56 per glyph), so the rail is crisp at any density.

### 2026-10-25 — nothing that touches the disk runs on the frame thread
Opening the app used to list the library, query MediaStore and rename files on the main thread, decode
full-size TikTok covers into 120 dp cells, and read the backup file on every launch. Now the folder is
read on IO (`LocalVideos.scanOf`), screens redraw only when the file set actually changed, thumbnails
are decoded at 360 px, avatar pictures at 400 px, library frames with `getScaledFrameAtTime`, and the
backup is skipped entirely when the stores already hold something. A Settings row reports the last
start-up and scan in milliseconds, because without logcat that is the only honest way to know.

### 2026-10-25 — Popular is a re-order, not another endpoint
TikTok's grid offers Latest and Popular; the listing already arrives newest-first, so Latest is that
order and Popular sorts the same clips by the play count the cover already shows. No undocumented
parameter is guessed, and pages arriving later slide into their place.

### 2026-10-25 — Explore tags itself, from words, not from the picture
No ML model is bundled: a phone-side classifier that could tell a saree from a borka does not exist
without our own training data, and the generic 400-label model only says "Clothing". So a clip is
tagged by the words around it — caption, author, sound, file name, and any label TikTok itself sent
with the listing — against a small Bangla/Banglish/English table. A tag becomes a chip only when at
least two clips carry it, so a single clip never invents a category. The limit is real and stated in
the code: a clip whose caption says nothing lands in no tag.

### 2026-10-26 — tags can be taught, and frames can be exported for a model
Guessed tags only reach as far as a caption's words, so a person can now say what a clip is: long
press → Tags, pick from the list or type a new one. A hand-made tag beats a guessed one, shows on
Explore even with a single clip (a deliberate label is not a guess), and decides the folder a training
frame lands in. Settings → Export frames writes one 480 px JPEG per clip under
`Backup/frames/<tag>/`, skipping what is already there and stopping on demand; the folders are the
labelled training set, and nothing leaves the phone until the user copies them out.

### 2026-10-29 — collections are made by hand, and the queue outlives the screen
The word-table tags and the whole ML detour are gone: a person marks what a clip is. Library → "+ Collection"
names one, the grid goes into tick mode, and "Add" puts every ticked clip into it at once. A clip can
sit in as many collections as it deserves, and Explore's chips are exactly those collections — nothing
is guessed from a caption any more. The first frame of a clip is still exportable, so a model can be
tried later without the app committing to one.

A pasted video link now resolves to that one video through the reader, with no profile read and no
author shelf. And the download loop moved out of the activity into a queue with its own scope: the
screen may close, the batch keeps going, the notification keeps counting.

## Measured, from a real request (2026-10-20)

`https://www.tikwm.com/api/?url=<video>&hd=1` → 200, 7.2 KB, 2.1 s: `duration`, `size`, `hd_size`,
no-watermark `play`, `play_count/digg_count/comment_count/collect_count/share_count`.

`https://www.tikwm.com/api/comment/list/?url=…&count=50&cursor=0` → 200, 33.9 KB, 0.83 s for a clip
with 28 comments: `data{comments[], cursor, hasMore, total}`, comment fields `id, text, create_time,
digg_count, reply_total, images[], user{nickname, unique_id, avatar}`. A second page inside one second
returns `code:-1` (the limit above).

## Open questions

- Whether comment pictures should ever come down in a batch (currently: no).
- Whether the library folder should ever be hidden more aggressively than the extension allows.
