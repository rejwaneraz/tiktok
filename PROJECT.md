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
