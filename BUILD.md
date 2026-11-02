# TikTok Grab — কীভাবে বানানো হয়েছে (বিস্তারিত)

এই ফাইলটা অ্যাপটার **পুরো ইতিহাস ও নকশা** এক জায়গায় রাখে: কী আছে, কেন আছে, কোন ফাইলে কী কাজ,
কোন নিয়ম ভাঙলে কী ফেল করে, আর কীভাবে নিরাপদে বদলাবে। `PROJECT.md` শুধু **সিদ্ধান্তের লগ** (কেন কী
choose করা হলো আর কী বাতিল হলো) — এটা সেটার বিস্তারিত সংস্করণ।

শেষ হালনাগাদ: ২০২৬-১০-৩০ (Instagram সাপোর্ট যোগ হওয়ার পর)।

---

## ১. অ্যাপটা কী, কার জন্য

TikTok (এবং এখন Instagram) এর পোস্ট/রিল **নিজের ফোনে লাইব্রেরি হিসেবে** রেখে দেওয়ার অ্যাপ। কোনো
cloud নেই, কোনো account system নেই, কোনো ad নেই। একজনের (rejwane) নিজের সংগ্রহ — বাংলা/বিদেশি
কনটেন্ট, মোটরসাইকেল পার্টসের রেফারেন্স ভিডিও, নানা ক্রিয়েটরের পোস্ট — যা TikTok অ্যাপের ভিতরে খুঁজে
পাওয়া যায় না, আর অ্যাপ আনইনস্টল করলেও হারায় না।

মূল প্রেরণা (এটা বুঝলে পুরো আর্কিটেকচার বুঝে যাওয়া): **ফাইলগুলো যেন ফাইলই থাকে।** MediaStore-এর
row-তে লিখলে সেই row প্যাকেজের মালিকানাধীন হয়, অ্যাপ আনইনস্টল করলে সিস্টেম ডিলিট করে দেয় — এই
অ্যাপের প্রথম লাইব্রেরি ঠিক এইভাবে হারিয়ে গিয়েছিল।

---

## ২. যে নিয়মগুলো কখনো বদলায় না

| নিয়ম | কেন (প্রমাণসহ) |
|---|---|
| ভিডিও সাধারণ ফাইল, MediaStore row না | MediaProvider প্যাকেজ-মালিকানাধীন ফাইল আনইনস্টলে মুছে ফেলে |
| একটাই ফোল্ডার, প্রোফাইল হলো **রেকর্ড** | প্রতি-লেখক ফোল্ডার মানে ফাইলই membership-এর source of truth; রেকর্ড (clipId → author, caption) রিইনস্টল ও মুভ টিকে থাকে |
| কমেন্ট শুধু public reader থেকে, TikTok থেকে না | TikTok-এর `api/comment/list/` signature (X-Bogus/Argus) ছাড়া 200 + খালি বডি দেয়। ৫টা প্যারামিটার শেপ, আসল cookie, curl_cffi, replayed WebView — সব মরা |
| WebView thread-এ কিছু block করে না | `shouldInterceptRequest` WebView-এর নিজের thread-এ চলে; ওখানে 30–60s OkHttp কল বা load চলাকালীন `destroy()` = Java stack ছাড়া native crash |
| WebView-তে desktop UA না | Android WebView দাবি করে Windows Chrome → TikTok-এর browser check fail, পাতাই লোড হয় না |
| প্রতিটা host-কে শুধু তার নিজের header | TikTok CDN session চায়; Instagram যে cookie নিজে ইস্যু করেনি তা দেখলে 403 দেয় |
| Source ফাইল Write/Edit টুল দিয়ে, shell heredoc দিয়ে না | shell `\n` আর `\'` খেয়ে ফেলে — ভাঙা string literal আর AAPT "Invalid unicode escape sequence" |
| Session-এর স্বাস্থ্য = শেষ **আসল read**, cookie নয় | cookie প্রমাণ করে শুধু সেভ হয়েছিল। Settings-এ যে লাইন দেখা যায় সেটা শেষ listing-এর ফলাফল |
| যেটা disk ছুঁয় সেটা frame thread-এ না | স্ক্যান, MediaStore query, bitmap decode, rename — সব IO thread-এ; Settings-এ ms সংখ্যা দেখানো হয় কারণ logcat নেই |
| ফাইল নাম caption থেকে সরাসরি নেওয়া হয় না | কটা যদি `.`-এর পরে পড়ে → নাম শেষে ডট → storage layer ফাইল বানাতে-ই দেয় না (FileNotFound, message = পুরো path) |

---

## ৩. ফোনে ডিস্কের লেআউট

```
Download/TikTokGrab/
├── library/                      ← সব ক্লিপ এখানে, এক ফোল্ডারে
│   ├── .nomedia                  ← গ্যালারি index করে না
│   ├── <caption> [<clipId>].nomedia            ← ভিডিও
│   └── <caption> [<clipId>] p<i>.jpg.nomedia   ← স্লাইডশো-র প্রতিটা ছবি
└── Backup/
    ├── meta/tiktokgrab.json      ← সব preference-এর দলিল (snapshot)
    ├── comments/<clipId>.json
    ├── avatars/<user>[-big].jpg.nomedia
    ├── photos/<clipId>_<commentId>[_<i>].jpg.nomedia
    └── frames/<tag>/<key>.jpg    ← ট্রেনিং সেট (+ .nomedia)
```

**নামের নিয়ম** (`Vault.titleOf`): caption → UTF-8 **byte** হिसাবে কেটা (ভিডিও 150, ছবি 120),
control character বাদ, ভাঙা emoji বাদ, শেষের `.`/space বাদ, তারপর `<>:"/\|?*` বাদ। কারণ বাংলা
অক্ষর ৩ বাইট, emoji ৪ বাইট — ৬০ অক্ষর "ছোট" দেখালেও একটা নাম ২৫৫ বাইটের লিমিট টপকে যায়।

**`.nomedia` extension-ই পুরো মূলমন্ত্র**: কোনো গ্যালারি অজানা extension index করে না, কোনো
player খুলতে চায় না, অথচ ফাইলটা সাধারণ ফাইলই থাকে (আনইনস্টলে টিকে যায়)। পুরনো বিল্ডের `.tgr`
বা `.mp4` ফাইল পরের স্ক্যানে জায়গামতো rename হয়। ক্র্যাশ করা ডাউনলোডের `.part` ফাইল ৩০ মিনিট
পরে সরানো হয় (চলমান download কখনো ছোঁয়া হয় না)।

**সতীম সীমা**: ফাইল ম্যানেজার shared Downloads-এ সব ফাইল দেখায়, extension যাই হোক। পুরোপুরি
লুকানোর একমাত্র উপায় app-private storage, আর সেটা আনইনস্টলে মুছে যায় — ট্রেড আগেই করা হয়েছে।

---

## ৪. কোড ম্যাপ (37টা Kotlin ফাইল, ~7,500 লাইন)

### নেটওয়ার্ক / রিডিং
| ফাইল | কাজ |
|---|---|
| `Http.kt` | OkHttp wrapper। UA, TikTok-cookie (শুধু tiktok.com হলে), 120s callTimeout। `get()` JSON, `openGet()` (no cookie), `finalUrl()` short-link expand, `save()` বাইনারি (text/* বাতিল) |
| `TikTokApi.kt` | লিস্টিং। `normalize()`, `username()`, `secUid()` (WebView দিয়ে প্রোফাইল খুলে), `list()` = **page-by-page stream** (15/পেজ, `hasMorePrevious` না হওয়া পর্যন্ত), `parse()` → `Clip`, `probe()` session check, `msToken()` |
| `SingleClip.kt` | একটা লিঙ্ক প্রোফাইল না খুলেই resolve (`tikwm /api/?url=&hd=1`)। `/photo/` বনাম `/video/` বোঝে; photo post-এর `play` ফিল্ডটা **গান** — সেটা ফেলে দেয় |
| `Comments.kt` | tikwm comment list। 1 req/sec pace (`synchronized`), throttle (code:-1 "Free Api Limit") ২ বার wait করে, `Fetched(comments, failed)` — "কমেন্ট নেই" আর "রিডার উত্তর দেয়নি" আলাদা |
| `TikTokWeb.kt` | অদৃশ্য WebView দিয়ে TikTok প্রোফাইল খোলা, `__UNIVERSAL_DATA_FOR_REHYDRATION__` থেকে JSON। `busy`/`gone` flag — মরা screen-এ নতুন WebView বানায় না |
| `InstagramApi.kt` | Instagram reader (anonymous, **crawler UA**)। `clipFor(code)` = একটা post → best picture/video; `gridClips(user)` = প্রোফাইলের ~১২ post-এর ছবি; ৪টা post একসাথে parallel read |
| `InstagramWeb.kt` | অদৃশ্য (screen-size, `INVISIBLE`) WebView — প্রোফাইল scroll করে DOM থেকে `/p/`-`/reel/` **code** জোগাড় করে (log-out ১২, log-in অনেক বেশি) |
| `Cookies.kt` | `CookieManager` wrapper। TikTok session (শুধু `msToken` আপডেট হয়) + Instagram session (`hasInstagramLogin`, `signOutInstagram` শুধু `.instagram.com`) |
| `Session.kt` | শেষ আসল TikTok read-এর ফলাফল + "keno" — Settings-এর লাইন |

### ডাউনলোড
| ফাইল | কাজ |
|---|---|
| `Downloader.kt` | `save()` → ভিডিও (`attempt`/`saveVault`) বা স্লাইডশো (`savePhotos`)। **সব `.part`-এ, তারপর rename**; ৩টা integrity guard (খালি/১০KB-ছোট/declared length-এর সাথে মিলে না)। Instagram clip fail হলে **post-tা আবার read করে** (signed URL ঘণ্টার মধ্যে মরে যায়)। নাম `FileNotFound` দিলে id-দিয়ে fallback নাম |
| `Downloads.kt` | কিউ। নিজের `CoroutineScope(SupervisorJob + Default)` — screen বন্ধ হলেও batch চলে। `State` (running/done/total/percent/failed/savedId/**lastError**), `watch()`, `stop()` (cancel করে, job null করে না), `failedClips` Retry-র জন্য |
| `DownloadService.kt` | progress notification (dataSync FGS)। `startForeground()` fail করলে সাধারণ notification + `stopSelf()` — নাহলে Android 15 realme process মেরে দিত |

### লাইব্রেরি / রেকর্ড
| ফাইল | কাজ |
|---|---|
| `LocalVideos.kt` | ফোল্ডার স্ক্যান (`scanOf` IO-te), `LocalVideo` model, স্লাইডশোকে **এক ক্লিপ** করা (clipId দিয়ে গ্রুপ), `forYou` shuffle order, `delete` (পুরো post-এর সব ছবি), `LocalThumbs` (200-entry LRU, ৩ thread, 360px decode) |
| `Vault.kt` | লাইব্রেরি ফোল্ডার, `titleOf`, `file`/`photoFile`, `list`, `normalise` (পুরনো extension + stale `.part`), `record`/`authorOf`/`captionOf`/`pageOf` (ক্লিপ → author, caption, **source page**), `migrateLegacy` (MediaStore → plain file, সাইজ মিলিয়ে তারপর row delete) |
| `Store.kt` | Backup ফোল্ডারগুলোর লেখা-পড়া: granted হলে plain file, না হলে MediaStore fallback; `.nomedia` মার্কার; `adoptStored` (গ্যালারি থেকে ছিনানোর কপি, সাইজ-চেক সহ) |
| `Backup.kt` | সব preference-এর snapshot → `Backup/meta/tiktokgrab.json` (IO thread-এ)। type-aware (Int/Boolean আলাদা করে রাখে) — নাহলে restore-পর `getInt` crash |
| `ClipStats.kt` | `ClipStats` (plays/likes/comments/collects/shares/sound), `Follows`, `Collection` — এক lock-এ read+merge+write |
| `Authors.kt` | লেখকের নাম/অ্যাভাটার/বায়ো cache + অ্যাভাটার ডাউনলোড |
| `Likes.kt` / `ClipTags.kt` | নিজের heart; হাতে-দেওয়া tag/collection |
| `FrameExport.kt` | প্রতি ক্লিপে একটা 480px ফ্রেম, tag-এর নামে ফোল্ডার, `.nomedia` সহ; compress fail গণনা হয় না |
| `Names.kt` | কমেন্টের অ্যাভাটার placeholder (boy/girl/neutral) — **পুরো শব্দ** মিলিয়ে |
| `Startup.kt` / `CrashRecorder.kt` | start-up/scan timing (Settings-এ দেখায়); শেষ crash-এর stack |

### স্ক্রিন / UI
| ফাইল | কাজ |
|---|---|
| `MainActivity.kt` (১০৪৮) | ৩টা section: **LIST** (link/username → grid + tick + batch), **LIBRARY** (ফোনের ক্লিপ, sort), **SETTINGS**। নিচে ৫টা tab: Library / Offline / Link / List / Settings। clipboard paste flow |
| `OfflineActivity.kt` (৮৯৪) | **FEED** (TikTok-সদৃশ vertical swipe: For You / Following / প্রোফাইল), **EXPLORE** (collection chip + search, 250ms debounce), **AUTHORS**, **PROFILE**। একটাই gesture state machine |
| `OfflineAdapter.kt` | feed-এর page holder: একবারই adapter তৈরি, `bind()` একই URI হলে player ছোঁয় না, rail, double-tap heart |
| `PlayerActivity.kt` / `PlayerAdapter.kt` | অনলাইন preview (stream) — একই rail, একই tap নিয়ম |
| `GridAdapter.kt` / `LocalGridAdapter.kt` / `AuthorAdapter.kt` | LIST grid (Latest/Popular), লাইব্রেরি grid, author list |
| `CommentSheet.kt` | bottom sheet: কমেন্ট, heart, ছবি (sheet খুললে ধাপে ধাপে ডাউনলোড) |
| `ImageViewerActivity.kt` | ছবির pager (প্রতিটা slide আলাদা page), Save → `Pictures/TikTokGrab` |
| `LoginActivity.kt` | TikTok **ও** Instagram লগইন (intent extra `site`) + cookies.txt import |
| `SettingsAdapter.kt` | Settings row + `Theme` + `Ui` (non-string preference, self-healing) + `App` |

---

## ৫. স্ক্রিন ও নেভিগেশন

```
MainActivity (LINK tab = launcher-এর পর default নয়; Library)
  ├─ Link   : paste → "Show list"
  ├─ List   : টিক → Download / Select all / Only new / Retry
  ├─ Library: স্ক্যান করা ক্লিপ, sort chip (Newest/Largest/Most played/Random)
  ├─ Offline: OfflineActivity (FEED/EXPLORE/AUTHORS/PROFILE)
  └─ Settings: থিম, স্টোরেজ, TikTok session, Instagram session, backup, frames, crash, start-up

OfflineActivity-র swipe ক্রম (TikTok-এর মতো): Following ↔ For You ↔ প্রোফাইল
FEED কখনো অন্য স্ক্রিনের পেছনে লুকোয় না — back করলে একই holder একই জায়গা থেকে চলতে থাকে
```

---

## ৬. ডেটা ফ্লো (প্রতিটা পথ)

### ৬.১ TikTok প্রোফাইল
```
"@user" বা link → normalize() → username()
   → secUid(): প্রয়োজনে TikTokWeb (অদৃশ্য WebView) দিয়ে প্রোফাইল পাতা → JSON থেকে uid
   → creator/item_list (15/পেজ) → parse() → Clip
   → onPage() প্রতিটা পেজ সাথে সাথে grid-এ বসায় (১ সেকেন্ডেই ১৫টা দেখা যায়)
   → cursor: উত্তরের নিজের cursor → না পেলে শেষ item-এর createTime×1000 → দুটোই না থাকলে থামে (জানিয়ে)
   → ClipStats.save() count গুলো রাখে; Session.good() শুধু TikTok নিজে উত্তর দিলে
```
একটা পেজ fail করলে পুরো walk ফেলে না — যা এসেছে তা থাকে, শুধু কিছু না এলে throw।

### ৬.২ একটা TikTok লিঙ্ক
`/video/<id>` বা `/photo/<id>` → `SingleClip.clip()` (reader, ~1s) → একটাই clip → **সাথে সাথে download**।

### ৬.৩ Instagram
```
instagram.com/... paste
  ├─ /p/ বা /reel/ → InstagramApi.clipFor(code)  [crawler UA, ~0.86MB]  → এক clip → download
  └─ প্রোফাইল → InstagramWeb.profileCodes(user)   [অদৃশ্য WebView, scroll, DOM থেকে code]
                → প্রতিটা code → clipFor() ৪টা করে parallel
                → কিছু না পেলে gridClips(user)  [একটাই request, ~১২ post-এর ছবি, video নেই]
```
কেন এই জটিলতা: Instagram **browser না হলে প্রোফাইলের post-এর নামেই না** (crawler page-এ numeric id
ও cover আছে, code/video নেই); আবার **session লাগানো crawler page media-ই দেয় না**। তাই browser →
code, code → anonymous read।

### ৬.৪ ডাউনলোড
```
Downloads.start(ctx, clips) → DownloadService.start() → State(running)
প্রতিটা clip: Downloader.save()
   → doneIds-তে থাকলে skip
   → play খালি + images থাকলে → savePhotos (প্রতিটা ছবি .part → rename)
   → নইলে attempt() → saveVault (২ বার try; IG হলে আগে post আবার read)
   → Vault.record(id, author, caption, page) + doneIds += id
publish() → notification (শুধু done বদলালে) + watch করা screen
finally → job নিজেই হলে DownloadService.stop()
```

---

## ৭. Thread-এর নিয়ম (কোনে কোন thread)

| কাজ | Thread |
|---|---|
| listing / post read / download | `Dispatchers.IO` (কিউ: `Dispatchers.Default`) |
| WebView বানানো/লোড/evaluateJavascript | `Dispatchers.Main` (callback সবশেষে Main-এই) |
| ফোল্ডার স্ক্যান, MediaStore query, bitmap decode, rename | IO / ৩-thread pool |
| grid bind | Main — কিন্তু **কোনো disk নেই** (থাম্বনেইল cache থেকে, না থাকলে async) |
| `Backup.snapshot` | নিজের single-thread executor |

---

## ৮. জেস্চার ও tap (feed)

* page DOWN নিজে ধরে রাখে (long-click listener-এর side effect-এ clickable হয়) → প্রথম আসল
  movement-এ সিদ্ধান্ত: উল্লম্ব হলে pager-কে ফেরত (`requestDisallowInterceptTouchEvent(false)`),
  আড়াআড়ি হলে activity নিজে নেয়
* আড়াআড়ি swipe = **প্রস্থের ২৫%** (প্রোফাইলেও এখন একই ২৫%, আগে ১৮% ছিল)
* **double tap কখনো pause করে না**: page-এ কোনো click listener নেই; single tap তখনই যখন দ্বিতীয়টা
  আসে না (`onSingleTapConfirmed`)
* double tap = heart + আঙুলের জায়গায় বড় heart pop
* প্রোফাইল স্ক্রিন (scroll view + button ভরা) → জেস্চার `dispatchTouchEvent`-এ, children-এর আগে

---

## ৯. UI-এর ভাষা

* Rail TikTok-এর মাপে: 44dp avatar + 1.5dp সাদা ring, তার নিচে 20dp লাল plus; 32dp icon; 12sp bold
  count + shadow; গ্রুপের ফাঁক 12dp; ডান প্রান্ত থেকে 6dp
* Heart accent-red, save bookmark-yellow (TikTok-র নিজের রং), বাকি accent cyan
* Icon **ট্রেস করা**: রেফারেন্স শীটের pixel outline → Douglas–Peucker → Chaikin → 24×24 viewport-এ
  cubic Bezier (প্রতি glyph ~0.7–1.2KB)। state = tint, দ্বিতীয় ফাইল না
* ১৫টা layout, ৩৭টা drawable, dark/light/system theme (`Theme.TikTokGrab`), serif টাইটেল ফন্ট
* FileProvider `${applicationId}.files` দিয়ে share/play-in-other-app (file:// নিষেধ)

---

## ১০. বিল্ড ও CI

| জিনিস | মান |
|---|---|
| Gradle | 8.7 (wrapper) |
| AGP | 8.5.2 |
| Kotlin | 1.9.24, jvmTarget 17 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| Dependencies | androidx core 1.13.1, appcompat 1.7.0, material 1.12.0, constraintlayout 2.1.4, recyclerview 1.3.2, viewpager2 1.1.0, swiperefresh 1.1.0, coroutines 1.8.1, okhttp 4.12.0, coil 2.7.0, junit 4.13.2 |
| Signing | `keystore/tiktokgrab.p12` (রিপোতেই), alias `tiktokgrab`, password `android` — **প্রতিটা CI build একই key** যাতে আপডেট আনইনস্টল ছাড়াই বসে |
| versionCode | `GITHUB_RUN_NUMBER + 1` (প্রতিবার বেশি) |

`.github/workflows/build.yml`: checkout → JDK 17 (temurin) → setup-gradle →
**`python3 tools/check_sources.py .`** → `./gradlew --no-daemon --continue testDebugUnitTest
assembleDebug` → artifact `tiktokgrab-debug-apk` (fail করলেও artifact)।

**এই মেশিনে JDK/Android SDK নেই** — compile gate = CI। তাই static gate গুলোই আসল প্রতিরক্ষা।

### `tools/check_sources.py` কী কী ধরে
1. Kotlin bracket balance (comment/string বাদ দিয়ে গোনা)
2. **local `val/var` যার নাম function-এর নিজের parameter-এর সাথে মিলে যায়** (Kotlin-এ "conflicting
   declarations" = compile error)। lambda/if-block-এর ভেতরের shadowing ধরে না (ওটা শুধু warning),
   expression-body function-এর পরেও ঢোকে না — দুবার ভুল করে শিখেছি
3. XML parse + `\u` escape (AAPT অস্বীকার করে)
4. `R.string.*` / `R.id.*` আসলে res/-এ আছে কি না
5. পুরনো call-shape (`Comments.fetch` list না Fetched, `pullComments` Boolean)
6. Gradle wrapper ফাইল আছে কি না

---

## ১১. Instagram: কী কাজ করে, কী না (2026-10-30)

| বিষয় | অবস্থা |
|---|---|
| একটা post/carousel লিঙ্ক | ✅ সব slide, original-res পর্যন্ত (১২৮৪×১৪১১, ৩০৭২×৪০৯৬ দেখা গেছে) |
| একটা reel | ✅ direct `.mp4` (নমুনা: ৭২০×১২৮০, ৩৩.২৭s, h264+AAC, ২.৮Mbps, ১১.৬MB), Range/resume চলে |
| প্রোফাইল (log-out) | ⚠️ শেষ ~১২ post, **শুধু ছবি** (code/video নেই) |
| প্রোফাইল (app-এ log-in) | ✅ ৪৮+ code — Settings → "Check the Instagram login now" ৪৮ দেখিয়েছে |
| একবারে সব post (৮৮/১৫২৩) | ⚠️ scroll লাগে; cap: "everything" = ১২০ |
| কমেন্ট | ❌ নেই (preview স্পষ্ট বলে দেয়) |
| Story/highlight | ❌ নেই (লিঙ্ক দিলে বলে দেয়) |
| Private account | ❌ প্রমাণিত নয় |

**খরচ** (BD data-র কথা মাথায় রেখে): metadata read ~০.৮৬MB/post · ৪-ছবির carousel ১.৬MB ·
৩৩s reel ১১.৬MB · ১২-post প্রোফাইল read ১.৩MB।

**সীমা ও ঝুঁকি**: লগইন = তোমার নিজের account; automated paging-তে Instagram restrict করতে পারে,
তাই scroll-to-scroll ১.৩s বিরতি + cap। Address গুলো signed (`oh`/`oe`) — কয়েক ঘণ্টার মধ্যে
ব্যবহার করতে হয় (এজন্যই download fail হলে clip আবার read করা হয়)।

---

## ১২. কী কী ভেঙেছিল, কী শেখা হলো (রাউন্ড-ওয়ার ইতিহাস)

1. **লাইব্রেরি হারানো** → MediaStore row প্যাকেজের মালিকানাধীন → plain file-এ যাওয়া
2. **ট্যাব বদলালে ভিডিও রিস্টার্ট + কালো ঝলক** → প্রতি refresh-এ নতুন adapter → adapter একবারই,
   `bind()` একই URI হলে player ছোঁয় না; rescan-ও `swapTo` ব্যবহার করে (একবার regression হয়েছিল)
3. **Sideways swipe মাঝে মাঝে কাজ না করা** → vertical pager DOWN-এর পর MOVE চুরি করত → একটাই
   state machine, page প্রথমে DOWN ধরে রাখে
4. **Double tap-এ pause** → click listener + detector দুটোই ছিল → click listener বাদ
5. **Rate limit = "ক্লিপ মরা"** → code:-1 wait করে আবার চাওয়া হয়
6. **গ্যালারিতে ছবি চলে যাওয়া** → `.jpg` → `.jpg.nomedia` + মার্কার, পুরনো rows সরানো
7. **Stop চাপলে notification থেকে যাওয়া** → `job = null` করার আগে `finally` চেক করে `job === mine`
   → stop() আর null করে না
8. **Watch করা batch শেষ হলে library refresh না হওয়া** → `!downloadsWereRunning` উল্টো ছিল
9. **আধা-ভাঙা ছবি/ভিডিও "complete" হয়ে যাওয়া** → সব `.part`-এ, সাইজ মিলিয়ে rename
10. **"All" ১৫-তে থেমে যাওয়া** → `optLong` কখনো null দেয় না, `?: break` কখনো চলত না
11. **Reinstall-এ হাতে-দেওয়া tag হারানো** → `"tags"` backup list-এই ছিল না; Int preference text
    হিসেবে ফিরে এসে `getInt` crash করাত
12. **`var fresh` parameter-এর সাথে নাম এক** → **build ভেঙেছিল**; তারপর `val read`-ও একই ফাঁদে
    পড়েছে — এখন সেটা CI gate-এ ধরে
13. **File name-এ শেষে `.`** → reel "ডাউনলোড হয় না" (FileNotFound = path message) → byte-safe
    code-point cut + trailing dot বাদ + id-only fallback নাম
14. **`ForegroundServiceDidNotStartInTimeException` crash (Android 15, realme)** → `startForeground`
    fail চুপ করে গেছে → এখন plain notification + `stopSelf()`
15. **2×2 WebView** → Instagram grid lazy-load ১২-তে আটকে যেতে পারে → screen-size + `INVISIBLE`

---

## ১৩. ফোনে টেস্ট চেকলিস্ট (নতুন APK-র পর)

1. TikTok প্রোফাইল → ১৫টা ~১s-এ দেখা যায় কি না, "All" ৫০০+ পর্যন্ত চলে কি না
2. একটা link paste → auto download → notification progress
3. Batch চলাকালীন **Stop** → notification বন্ধ হয় কি না
4. Batch চলাকালীন wifi বন্ধ → আবার চালু → আধা ফাইল লাইব্রেরিতে আসে কি না
5. ১৫+ ক্লিপ library → দ্রুত up/down swipe → পেছনের clip 0 থেকে start হয় কি না / কালো হয় কি না
6. Instagram: একটা carousel link → সব slide নামে; একটা reel → ভিডিও নামে
7. Instagram: প্রোফাইল link + "everything" → কতটা আসে, Status line-এ কারণ দেখায় কি না
8. Settings → Check the Instagram login now → ১২ না ৪৮
9. Settings → Log out of Instagram → TikTok session ঠিক থাকে কি না
10. Reinstall → Backup restore → tag/collection/library order ফেরত আসে কি না, crash করে কি না
11. Long-press → "Open the post" → IG clip Instagram-এ খোলে কি না

---

## ১৪. বদলানোর নিয়ম (নিরাপদে)

1. **Risk-এর আগে backup**: `git tag <name>` + সোর্স zip (`C:\tiktokgrab\backup\...`)
2. Source লেখা **Write/Edit** দিয়ে, heredoc-তে না
3. প্রতিবার: `python tools/check_sources.py .` (local gate) → তারপর commit/push (push তুমি নিজে)
4. নতুন network path হলে: **প্রথমে আলাদা probe** (PC-তে, `C:\tiktokgrab\insta-probe\` দ্রষ্টব্য) — তারপর
   অ্যাপের কোড
5. নতুন entry point বানাবে না — পুরনো paste button-এর ভিতরেই route করবে
6. সিদ্ধান্ত বদলালে `PROJECT.md`-তে পুরনো entry রাখা, superseded বলে চিহ্নিত করা

---

## ১৫. যা এখনো বাকি (open questions)

* Instagram-এ browser-এর নিজের JSON response intercept করে ০.৮৬MB/post খরচ বাঁচানো যায় কি না
  (probe করেছি: grid response-এ `code`, `video_versions`, `end_cursor` আছে — replay করা যায়নি,
  relay header-এর কারণে HTML ফেরত দিয়েছে)
* App-এর ভিতরে Instagram login দিয়ে পুরো প্রোফাইল (৮৮/১৫২৩) কত নিরাপদে সম্ভব
* Story/highlight সাপোর্ট
* কমেন্ট (Instagram) — সম্ভব না-ই প্রমাণিত
* লাইব্রেরি ফোল্ডার আরও গভীরভাবে লুকানো যায় কি না (trade-off: আনইনস্টলে হারানো)
