package com.rejwane.tiktokgrab

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GridAdapter
    private lateinit var input: EditText
    private lateinit var statusView: TextView
    private lateinit var estimateView: TextView
    private lateinit var selectionView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var countPick: Spinner
    private lateinit var loadBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var retryBtn: Button
    private lateinit var selectBar: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var grid: RecyclerView
    private lateinit var libraryList: RecyclerView
    private lateinit var libraryPane: View

    private lateinit var settingsList: RecyclerView

    private var tikWeb: TikTokWeb? = null
    private var igWeb: InstagramWeb? = null
    private var task: Job? = null
    private var commentTask: Job? = null
    private var exportTask: Job? = null
    private var commentDone = 0
    private var commentTotal = 0
    private var section = LIST

    /** True while the queue reported itself running, so the grid is refreshed once when it ends. */
    private var downloadsWereRunning = false

    /** The last folder read, so Settings and the library do not each scan the disk again. */
    private var libraryCache: List<LocalVideo> = emptyList()

    /** Library multi-select while a collection is being filled. */
    private var collectionMode = false
    private var collectionName = ""
    private val collectionKeys = HashSet<String>()

    /** Built once: replacing the adapter on every refresh is what made a big library stutter. */
    private lateinit var libraryAdapter: LocalGridAdapter


    private val countValues by lazy { resources.getIntArray(R.array.count_values) }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { showLoginState() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        Cookies.enable()

        input = findViewById(R.id.input)
        statusView = findViewById(R.id.status)
        estimateView = findViewById(R.id.estimate)
        selectionView = findViewById(R.id.selectionCount)
        progress = findViewById(R.id.progress)
        countPick = findViewById(R.id.countPick)
        loadBtn = findViewById(R.id.loadBtn)
        downloadBtn = findViewById(R.id.downloadBtn)
        retryBtn = findViewById(R.id.retryBtn)
        selectBar = findViewById(R.id.selectBar)
        refresh = findViewById(R.id.refresh)
        grid = findViewById(R.id.grid)
        libraryList = findViewById(R.id.library)
        libraryPane = findViewById(R.id.libraryPane)
        libraryAdapter = LocalGridAdapter(
            emptyList(),
            flat = false,
            selected = collectionKeys,
            onOpen = { openOffline(it) },
            onMenu = { clip ->
                val profiles = libraryCache.map { it.author }.distinct()
                LocalMenu.show(this, clip, profiles,
                    onDeleted = { refreshLibrary() }, onMoved = { refreshLibrary() })
            },
            onToggled = { paintCollectionBar() }
        )
        libraryList.adapter = libraryAdapter
        findViewById<TextView>(R.id.librarySort).setOnClickListener { pickLibraryOrder() }
        // A collection is built by hand: name it once, tick the clips, add them together.
        findViewById<TextView>(R.id.libraryNew).setOnClickListener { newCollection() }
        findViewById<Button>(R.id.libraryAdd).setOnClickListener { addToCollection() }
        findViewById<Button>(R.id.libraryCancel).setOnClickListener { stopCollection() }
        settingsList = findViewById(R.id.settings)

        adapter = GridAdapter(onOpen = { openPlayer(it) }, onSelectionChanged = { showSelection() })
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        libraryList.layoutManager = GridLayoutManager(this, spanCount())
        settingsList.layoutManager = LinearLayoutManager(this)

        countPick.adapter = ArrayAdapter.createFromResource(
            this, R.array.counts, android.R.layout.simple_spinner_dropdown_item
        )
        countPick.setSelection(1)
        countPick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showEstimate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        adapter.setOrder(savedListOrder())
        paintListOrder()
        findViewById<TextView>(R.id.orderChip).apply {
            setOnClickListener { pickListOrder() }
            setOnLongClickListener {
                note("List order", lines(
                    "Latest — the order TikTok sends the clips in.",
                    "Popular — the same clips sorted by the play count on each cover.",
                    "",
                    "Tap the chip to switch. The status line under it shows the top count, so you",
                    "can check that the sort really happened."
                ))
                true
            }
        }

        findViewById<Button>(R.id.loginBtn).setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        loadBtn.setOnClickListener { loadList() }
        refresh.setOnRefreshListener { loadList() }
        findViewById<Button>(R.id.tickAll).setOnClickListener { adapter.tickAll(true) }
        findViewById<Button>(R.id.onlyNew).setOnClickListener {
            val picked = adapter.selectUnsaved()
            statusView.text = if (picked == 0) "Everything is already downloaded" else "$picked  new selected"
        }
        findViewById<Button>(R.id.exitSelect).setOnClickListener { adapter.exitSelect() }
        downloadBtn.setOnClickListener {
            if (Downloads.isRunning()) Downloads.stop() else startDownloads(adapter.selectedClips())
        }
        retryBtn.setOnClickListener { startDownloads(Downloads.failedClips) }

        findViewById<View>(R.id.tabList).setOnClickListener { showSection(LIST) }
        findViewById<View>(R.id.tabLibrary).setOnClickListener { showSection(LIBRARY) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { showSection(SETTINGS) }
        findViewById<View>(R.id.tabOffline).setOnClickListener {
            startActivity(Intent(this, OfflineActivity::class.java))
        }
        findViewById<View>(R.id.clipBtn).setOnClickListener { fromClipboard() }

        showLoginState()
        showEstimate()
        showSelection()
        showSection(LIBRARY)
        if (intent.getBooleanExtra(CLIP_EXTRA, false)) fromClipboard()

        // The number the Start-up row reports: process start → this screen actually drawn.
        window.decorView.post { Startup.firstFrameMs = Startup.now() - Startup.beganAt }

        // Android cannot ask for All files access at install time, and a fresh install has nothing
        // in the library yet — so the first launch is where the switch has to be explained.
        if (!Vault.granted(this) && !Ui.bool(this, "asked_access", false)) {
            Ui.setBool(this, "asked_access", true)
            statusView.post { askForAccess() }
        }
    }

    /**
     * Videos from the older builds sit in MediaStore, which deletes them with the app on uninstall.
     * They are copied into our own library folder once and the row is dropped, so a reinstall from
     * now on leaves the library exactly where it is.
     */
    private fun moveOldVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val moved = runCatching { Vault.migrateLegacy(this@MainActivity) }.getOrDefault(0)
            if (moved == 0) return@launch
            Backup.snapshot(this@MainActivity)
            withContext(Dispatchers.Main) {
                refreshLibrary()
                statusView.text = "Moved $moved videos into Download/TikTokGrab/${Vault.LIBRARY}"
            }
        }
    }

    private fun spanCount(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 120).toInt().coerceIn(3, 5)
    }

    private fun showLoginState() {
        findViewById<TextView>(R.id.loginStatus).setText(
            if (Cookies.hasLogin()) R.string.logged_in else R.string.not_logged_in
        )
    }

    /** 0 means "everything the account has": the listing keeps paging until TikTok runs out. */
    private fun wantedLimit(): Int {
        val picked = countPick.selectedItemPosition
        return if (picked in countValues.indices && countValues[picked] > 0) countValues[picked] else 0
    }

    private fun showEstimate() {
        val limit = wantedLimit()
        estimateView.text = if (limit <= 0) "everything · ~0.4 MB per 15 · loads live"
        else "list ~${(((limit + 14) / 15) * 0.4).format1()} MB · best quality"
    }

    private fun showSelection() {
        val bytes = adapter.selectedBytes()
        selectionView.text = buildString {
            append("${adapter.selectedCount()} / ${adapter.total()} select")
            if (bytes > 0) append(" · ~").append(GridAdapter.mb(bytes))
        }
        selectBar.visibility = if (adapter.selectMode) View.VISIBLE else View.GONE
        val running = Downloads.isRunning()
        val failed = Downloads.failedClips
        downloadBtn.text = if (running) getString(R.string.cancel) else getString(R.string.download)
        downloadBtn.isEnabled = running || adapter.selectedCount() > 0
        retryBtn.visibility = if (failed.isEmpty() || running) View.GONE else View.VISIBLE
        retryBtn.text = "Retry (${failed.size})"
    }

    private fun showSection(which: Int) {
        section = which
        grid.visibility = if (which == LIST) View.VISIBLE else View.GONE
        libraryPane.visibility = if (which == LIBRARY) View.VISIBLE else View.GONE
        settingsList.visibility = if (which == SETTINGS) View.VISIBLE else View.GONE
        refresh.isEnabled = which == LIST
        markTabs(which)
        if (which == LIBRARY) refreshLibrary()
        if (which == SETTINGS) showSettings()
    }

    private fun markTabs(which: Int) {
        tabLabel(R.id.tabLibraryLabel, which == LIBRARY)
        tabLabel(R.id.tabListLabel, which == LIST)
        tabLabel(R.id.tabSettingsLabel, which == SETTINGS)
        tabLabel(R.id.tabOfflineLabel, false)
    }

    private fun tabLabel(id: Int, on: Boolean) {
        findViewById<TextView>(id).setTextColor(getColor(if (on) R.color.accent else R.color.muted))
    }

    /** Every number a Settings row shows costs a folder read, so they are taken together off-main. */
    private data class Counts(val avatars: Int, val comments: Int, val backup: Int,
                              val frames: Boolean, val crash: String, val instagram: String)

    private fun readCounts() = Counts(
        Authors.cachedAvatars(applicationContext),
        Comments.stored(applicationContext),
        Backup.recordCount(applicationContext),
        FrameExport.folder(applicationContext).isDirectory,
        CrashRecorder.text(applicationContext),
        InstagramApi.state(applicationContext)
    )

    private fun showSettings() {
        lifecycleScope.launch {
            paintSettings(withContext(Dispatchers.IO) { readCounts() })
        }
    }

    /** TikTok-shaped list: grey section captions, then rows with the current value on the right. */
    private fun paintSettings(read: Counts) {
        // The folder was already read by the library refresh; scanning again here would be a disk
        // read on the thread that draws.
        val saved = libraryCache
        val size = GridAdapter.mb(saved.sumOf { it.bytes })
        settingsList.adapter = SettingsAdapter(listOf(
            SettingsAdapter.Row("Appearance", Theme.label(this), "Preferences") { pickTheme() },
            SettingsAdapter.Row("Reset For You order", "", "Feed") {
                LocalVideos.reshuffle(this)
                note("For You", "The order is rolled again next time you open the feed.")
            },
            SettingsAdapter.Row("Saved videos", "${saved.size}", "Storage") {
                note("Saved videos", lines("${saved.size} videos · $size", "",
                    "Download/TikTokGrab/${Vault.LIBRARY} · one folder, only this app plays them",
                    "They stay there even after this app is uninstalled."))
            },
            SettingsAdapter.Row("All files access", if (Vault.granted(this)) "Granted" else "Needed", "Storage") {
                if (Vault.granted(this)) {
                    val inLibrary = Vault.list(this)
                    note("All files access", lines("Granted — videos are saved as plain files in",
                        "Download/TikTokGrab/${Vault.LIBRARY} as *${Vault.EXT}",
                        "", "${inLibrary.size} files · ${GridAdapter.mb(inLibrary.sumOf { it.length() })}",
                        "A gallery will not show them and a reinstall will not take them."))
                } else {
                    askForAccess()
                }
            },
            SettingsAdapter.Row("Profile pictures", "${read.avatars} cached", "Storage") {
                val had = read.avatars
                MaterialAlertDialogBuilder(this)
                    .setTitle("Profile pictures")
                    .setMessage(lines("$had pictures are kept in Download/TikTokGrab/Backup/avatars.",
                        "", "Clear them? They are fetched again when you are online."))
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Clear") { _, _ ->
                        Authors.clearAvatars(this)
                        showSettings()
                        note("Profile pictures", "Cleared.")
                    }
                    .show()
            },
            SettingsAdapter.Row("Log in to TikTok", Session.label(this), "Account") {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            },
            SettingsAdapter.Row("Check the session now", Session.why(this), "Account") {
                statusView.text = "Checking the session…"
                lifecycleScope.launch {
                    val reason = withContext(Dispatchers.IO) { TikTokApi.probe(this@MainActivity) }
                    if (reason == null) Session.good(this@MainActivity) else Session.bad(this@MainActivity, reason)
                    showLoginState()
                    showSettings()
                    note("Session", if (reason == null) "TikTok still answers — the session is fine."
                    else lines("TikTok did not answer.", "", reason, "", "Use Log in to TikTok above."))
                }
            },
            SettingsAdapter.Row("Log out", "", "Account") {
                confirm("Log out", "This clears the TikTok session from this app.") {
                    Cookies.signOut()
                    showLoginState()
                    note("Logged out", "The TikTok session is cleared from this app.")
                }
            },
            // Instagram's login is not what downloads a post — that reader stays anonymous on
            // purpose. The session is only what lets a profile name more than its last twelve posts.
            SettingsAdapter.Row("Log in to Instagram", read.instagram, "Instagram") {
                loginLauncher.launch(Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.SITE, "instagram"))
            },
            SettingsAdapter.Row("Check the Instagram login now", "", "Instagram") {
                statusView.text = "Reading the Instagram profile the browser way…"
                // Built on the thread that owns views; the read itself happens off it.
                val web = igWeb ?: InstagramWeb(this).also { igWeb = it }
                lifecycleScope.launch {
                    val check = withContext(Dispatchers.IO) {
                        val links = runCatching { web.profileCodes("instagram", 40) }
                            .getOrDefault(emptyList())
                        // The state line reads prefs and the cookie jar, so it comes from here too.
                        links to InstagramApi.state(applicationContext)
                    }
                    showSettings()
                    note("Instagram browser read", lines(
                        if (check.first.isEmpty()) "The browser named no posts — Instagram is showing a " +
                            "wall, or this device is being asked to verify."
                        else "${check.first.size} post links read from instagram.com.",
                        "",
                        "Twelve or fewer means the login is not reaching the browser; well past twelve " +
                            "means it is, and a profile can be walked further.",
                        "",
                        check.second))
                }
            },
            SettingsAdapter.Row("Log out of Instagram", "", "Instagram") {
                confirm("Log out of Instagram", lines(
                    "Only the Instagram session is cleared — TikTok stays signed in.", "",
                    "Posts and reels still download without any login; this only affects how many " +
                        "posts a profile will list.")) {
                    Cookies.signOutInstagram()
                    showSettings()
                    note("Logged out", "The Instagram session is cleared from this app.")
                }
            },
            SettingsAdapter.Row("Clear cached profiles", "", "Account") {
                TikTokApi.clearCache(this)
                note("Cached profiles", "Cleared — the next list read refreshes them from TikTok.")
            },
            SettingsAdapter.Row("Saved in Downloads", "${read.backup} files", "Backup") {
                note("Backup folder", lines("Download/TikTokGrab/Backup", "",
                    "${read.comments} comment files",
                    "${read.avatars} profile pictures",
                    "1 records file"))
            },
            SettingsAdapter.Row("Fetch comments for saved videos",
                if (commentTask?.isActive == true) "running $commentDone/$commentTotal — tap to stop"
                else "${read.comments} of ${saved.size} clips", "Backup") { fetchComments(saved) },
            SettingsAdapter.Row("Back up now", "", "Backup") {
                Backup.snapshot(this)
                note("Backed up", lines("Records written to", "Download/TikTokGrab/Backup/meta"))
            },
            SettingsAdapter.Row("Restore from backup", "", "Backup") {
                confirm("Restore", "This replaces what is on the phone with the file in Download/TikTokGrab/Backup.") {
                    Backup.restore(this, force = true)
                    note("Restored", "Reopen a tab to see it.")
                }
            },
            SettingsAdapter.Row("Crash report",
                if (read.crash.isBlank()) "none" else "recorded", "About") {
                val log = read.crash
                if (log.isBlank()) note("Crash report", lines("Nothing has been recorded yet.",
                    "", "If the app stops again, open this row and read me what it says."))
                else MaterialAlertDialogBuilder(this)
                    .setTitle("Last crash")
                    .setMessage(log.take(4000))
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Clear") { _, _ ->
                        CrashRecorder.forget(this)
                        showSettings()
                    }
                    .show()
            },
            SettingsAdapter.Row("Export frames",
                if (read.frames) "on disk" else "none", "Training") {
                exportFrames()
            },
            SettingsAdapter.Row("Version", packageVersion(), "About") {
                note("TikTok Grab", lines("Version ${packageVersion()}", "Built for one person's own library."))
            },
            SettingsAdapter.Row("Start-up",
                if (Startup.firstFrameMs > 0) "${Startup.firstFrameMs} ms" else "measuring", "About") {
                note("Start-up", lines(
                    "First frame: ${Startup.firstFrameMs} ms after process start",
                    "Last library scan: ${Startup.scanMs} ms, on a background thread",
                    "",
                    "If the first number is large but the scan is small, the cost is before our",
                    "first screen — the system starting the app, not the folder."
                ))
            }
        ))
    }

    private fun lines(vararg parts: String) = parts.joinToString("\n")

    /**
     * One still frame per clip, into a folder named after its first tag, so the training set is
     * already labelled by the person who tagged it. Nothing leaves the phone; the folder is under
     * Downloads and can be copied to a PC with a cable or a zip.
     */
    private fun exportFrames() {
        if (!Vault.granted(this)) {
            askForAccess()
            return
        }
        if (libraryCache.isEmpty()) {
            // Settings can be opened before the first library scan finishes.
            lifecycleScope.launch {
                libraryCache = LocalVideos.scanOf(this@MainActivity)
                if (libraryCache.isEmpty()) {
                    note("Export frames", lines("The library is empty.", "", "Download something first."))
                } else {
                    startExport(libraryCache)
                }
            }
            return
        }
        startExport(libraryCache)
    }

    private fun startExport(videos: List<LocalVideo>) {
        val progress = TextView(this).apply {
            setPadding(52, 36, 52, 0)
            text = "Preparing…"
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Export frames")
            .setView(progress)
            .setNegativeButton("Stop", null)
            .create()
        dialog.setOnDismissListener { exportTask?.cancel() }
        dialog.show()
        exportTask = lifecycleScope.launch {
            val written = try {
                withContext(Dispatchers.IO) {
                    FrameExport.export(this@MainActivity, videos) { done, total, tag ->
                        runOnUiThread { progress.text = "$done / $total · $tag" }
                    }
                }
            } catch (e: CancellationException) {
                -2
            }
            exportTask = null
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            note("Frames", when {
                written < 0 -> lines("Stopped. Whatever was already written stays.",
                    "", "Download/TikTokGrab/Backup/frames")
                else -> lines("$written frames written to",
                    "Download/TikTokGrab/Backup/frames",
                    "",
                    "Each folder is a tag. Copy them to the PC to train a model.")
            })
        }
    }

    /**
     * The one thing no dialog can do for us: All files access is a switch on a system page. This
     * explains it in place, opens the page, and keeps a flag so the app asks once and then only
     * on demand — never as a wall at every launch.
     */
    private fun askForAccess(onGranted: () -> Unit = {}) {
        if (Vault.granted(this)) {
            onGranted()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("All files access is needed")
            .setMessage(lines(
                "Android shows no dialog for this one: the switch sits on a system page.",
                "",
                "If your phone asks for storage permission instead, that dialog comes next.",
                "",
                "Tap Open settings, find TikTok Grab in the list, turn the switch ON, then come back.",
                "",
                "If the page says no apps: Settings → Apps → Special app access → All files access",
                "→ TikTok Grab."
            ))
            .setPositiveButton("Open settings") { _, _ ->
                val opened = Vault.openAccessSettings(this)
                if (!opened) note("Could not open it", lines(
                    "Open Settings yourself, then: Apps → Special app access → All files access",
                    "→ TikTok Grab → Allow access to manage all files."))
            }
            .setNegativeButton("Later", null)
            .show()
    }


    private fun note(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Do it") { _, _ -> onYes() }
            .show()
    }

    private fun pickTheme() {
        val names = arrayOf("Dark", "Light", "Follow system")
        MaterialAlertDialogBuilder(this)
            .setTitle("Appearance")
            .setSingleChoiceItems(names, Theme.mode(this)) { dialog, which ->
                Theme.set(this, which)
                dialog.dismiss()
            }
            .show()
    }

    /**
     * One small request per clip, spaced out because the reader allows one per second — about 40 KB
     * and 1.2 seconds each, which is why it says so first. Tapping the row again stops it.
     */
    private fun fetchComments(saved: List<LocalVideo>) {
        if (commentTask?.isActive == true) {
            commentTask?.cancel()
            commentTask = null
            note("Comments", "Stopped at $commentDone of $commentTotal.")
            showSettings()
            return
        }
        lifecycleScope.launch {
            val missing = withContext(Dispatchers.IO) {
                // One folder check per saved clip, so the whole list is read off the frame thread.
                Comments.missingFor(applicationContext, saved)
            }
            if (missing.isEmpty()) {
                note("Comments", "Every saved video already has its comments.")
                return@launch
            }
            val megabytes = (missing.size * 4 + 99) / 100
            val minutes = (missing.size * 12 + 599) / 600
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Fetch ${missing.size} comment sets?")
                .setMessage(lines("About $megabytes MB and $minutes minutes: the reader answers one clip " +
                    "per second, and each set is assumed to hold up to 100 comments. Comment pictures " +
                    "can push that higher.", "", "Tap the same row again to stop."))
                .setNegativeButton("Not now", null)
                .setPositiveButton("Fetch") { _, _ -> pullComments(missing) }
                .show()
        }
    }

    private fun pullComments(missing: List<LocalVideo>) {
        commentDone = 0
        commentTotal = missing.size
        commentTask = lifecycleScope.launch {
            var empty = 0
            var failed = 0
            missing.forEach { video ->
                val got = withContext(Dispatchers.IO) {
                    runCatching {
                        Comments.fetch(this@MainActivity, video.clipId, video.isPhoto)
                    }.getOrNull()
                }
                commentDone++
                // Three outcomes, not two: the clip has none, the reader answered and had none, or the
                // reader never answered. Only the first two are facts about the clip.
                if (got == null || got.failed) {
                    failed++
                } else if (got.comments.isEmpty()) {
                    empty++
                }
                statusView.text = "Comments $commentDone/$commentTotal"
            }
            commentTask = null
            Backup.snapshot(this@MainActivity)
            showSettings()
            val savedCount = commentDone - empty - failed
            note("Comments", buildString {
                append("$savedCount of $commentTotal saved")
                if (empty > 0) append(", $empty have none")
                if (failed > 0) append(", $failed could not be read — tap this row again to retry")
            })
        }
    }

    private fun packageVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("1.0")

    /** The folder is the truth: whatever sits in Download/TikTokGrab is what this tab shows. */
    private fun refreshLibrary() {
        lifecycleScope.launch {
            val saved = sortedForLibrary(LocalVideos.scanOf(this@MainActivity))
            libraryCache = saved
            libraryAdapter.selectMode = collectionMode
            libraryAdapter.submit(saved)
            findViewById<TextView>(R.id.librarySort).text =
                "${libraryOrders[libraryOrder().coerceIn(0, libraryOrders.size - 1)]} ▾"
            if (!collectionMode) {
                statusView.text = if (saved.isEmpty()) getString(R.string.library_empty)
                else "${saved.size} saved · ${GridAdapter.mb(saved.sumOf { it.bytes })}"
            }
        }
    }

    // region collections

    /** "+ Collection": pick an existing one or name a new one, then tick clips in the grid. */
    private fun newCollection() {
        val known = ClipTags.known(this)
        if (known.isEmpty()) {
            askCollectionName()
            return
        }
        val labels = (known + "New collection…").toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Add to which collection?")
            .setItems(labels) { _, which ->
                if (which == labels.lastIndex) askCollectionName() else beginCollection(labels[which])
            }
            .show()
    }

    private fun askCollectionName() {
        val input = EditText(this).apply {
            hint = "e.g. School"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New collection")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) beginCollection(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun beginCollection(name: String) {
        collectionName = name
        collectionMode = true
        collectionKeys.clear()
        findViewById<View>(R.id.libraryBar).visibility = View.VISIBLE
        paintCollectionBar()
        showSection(LIBRARY)
        refreshLibrary()
    }

    private fun paintCollectionBar() {
        findViewById<TextView>(R.id.libraryBarText).text =
            "${collectionKeys.size} selected → $collectionName"
        findViewById<Button>(R.id.libraryAdd).isEnabled = collectionKeys.isNotEmpty()
    }

    private fun addToCollection() {
        if (collectionKeys.isEmpty()) return
        val count = collectionKeys.size
        val name = collectionName
        ClipTags.addAll(this, collectionKeys.toList(), name)
        stopCollection()
        note("Added to $name", lines(
            "$count clips are in \"$name\".",
            "",
            "Offline → Explore shows it as a chip; one clip can sit in several collections."
        ))
    }

    private fun stopCollection() {
        collectionMode = false
        collectionName = ""
        collectionKeys.clear()
        findViewById<View>(R.id.libraryBar).visibility = View.GONE
        refreshLibrary()
    }

    // endregion

    private val libraryOrders = arrayOf("Newest first", "Largest first", "Most played", "Random")

    /** Latest is what TikTok sends; Popular is the same page re-ordered by its own play count. */
    private fun savedListOrder(): GridAdapter.Order =
        if (getSharedPreferences("ui", MODE_PRIVATE).getString("list_order", "latest") == "popular")
            GridAdapter.Order.POPULAR else GridAdapter.Order.LATEST

    private fun paintListOrder() {
        findViewById<TextView>(R.id.orderChip).text =
            if (adapter.order == GridAdapter.Order.POPULAR) "Popular ▾" else "Latest ▾"
    }

    private fun pickListOrder() {
        // A dialog was the fragile part: setMessage and setSingleChoiceItems cannot be used together
        // (the message replaces the list, which is exactly what happened), so the chip switches on
        // its own tap and explains itself on a long press. Nothing to go wrong between tap and sort.
        applyListOrder(adapter.order != GridAdapter.Order.POPULAR)
    }

    private fun applyListOrder(popular: Boolean) {
        getSharedPreferences("ui", MODE_PRIVATE).edit()
            .putString("list_order", if (popular) "popular" else "latest").apply()
        adapter.setOrder(if (popular) GridAdapter.Order.POPULAR else GridAdapter.Order.LATEST)
        paintListOrder()
        // Say it out loud, with the number the first cover now holds: a silent re-order looked
        // exactly like a tap that did nothing.
        statusView.text = if (popular)
            "Popular · top ${GridAdapter.short(adapter.topPlays())} plays · ${adapter.count()} videos"
        else "Latest · newest first · ${adapter.count()} videos"
    }

    private fun libraryOrder() = Ui.int(this, "library_order", 0)

    /** Largest first answers "what do I delete", most played answers "what do I keep". */
    private fun sortedForLibrary(list: List<LocalVideo>): List<LocalVideo> = when (libraryOrder()) {
        1 -> list.sortedByDescending { it.bytes }
        2 -> list.sortedByDescending { ClipStats.of(this, it.clipId).plays }
        3 -> list.shuffled()
        else -> list.sortedByDescending { it.addedAt }
    }

    private fun pickLibraryOrder() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Library order")
            .setSingleChoiceItems(libraryOrders, libraryOrder()) { dialog, which ->
                Ui.setInt(this@MainActivity, "library_order", which)
                dialog.dismiss()
                refreshLibrary()
            }
            .show()
    }

    private fun openOffline(video: LocalVideo) {
        OfflineQueue.startUri = video.uri
        startActivity(Intent(this, OfflineActivity::class.java))
    }

    private fun fromClipboard() {
        val text = runCatching {
            getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: ""
        val link = Regex("https?://\\S+").find(text)?.value ?: ""
        if (link.isBlank()) {
            statusView.text = "No link in clipboard"
            return
        }
        showSection(LIST)
        input.setText(link)
        loadList()
    }

    private fun loadList() {
        val query = input.text.toString().trim()
        if (query.isBlank()) {
            statusView.text = "Type a username or link"
            return
        }
        // One button, one list: an Instagram link is read by the Instagram reader and comes back as
        // ordinary clips, so the tick, the queue, the library and the feed never learn a new shape.
        if (InstagramApi.isInstagram(query)) {
            loadInstagram(query)
            return
        }
        if (!Cookies.hasLogin()) {
            statusView.text = getString(R.string.need_login)
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            return
        }
        val web = tikWeb ?: TikTokWeb(this).also { tikWeb = it }

        loadBtn.isEnabled = false
        refresh.isRefreshing = true
        showSection(LIST)
        statusView.text = getString(R.string.loading)

        task = lifecycleScope.launch {
            try {
                var shown = false
                val result = TikTokApi.list(this@MainActivity, web, query, wantedLimit(),
                    onPage = { fresh ->
                        // The first page replaces the grid; every later one is appended in place.
                        runOnUiThread {
                            if (!shown) {
                                adapter.submit(fresh, Downloader.doneIds(this@MainActivity))
                                shown = true
                                refresh.isRefreshing = false
                            } else {
                                adapter.append(fresh)
                            }
                            statusView.text = "${adapter.count()} videos · still loading…"
                        }
                    }
                ) { count -> runOnUiThread { statusView.text = "Loading… $count loaded" } }
                val user = TikTokApi.username(result.source)
                val clips = result.clips
                // A reader-only answer says nothing about the TikTok login, so it must not be filed
                // as one: the session line only moves when TikTok itself replied.
                if (result.answered) Session.good(this@MainActivity)
                // A single-video answer never streams a page, so it is placed here.
                if (!shown) adapter.submit(clips, Downloader.doneIds(this@MainActivity))
                val already = clips.count { it.id in Downloader.doneIds(this@MainActivity) }
                val savedNote = if (already > 0) " · $already already downloaded" else ""

                if (result.match != null) {
                    input.setText("@$user")
                    statusView.text = "Found — downloading… @$user"
                    result.match?.let { startDownloads(listOf(it)) }
                } else {
                    val searched = Regex("/(?:video|photo)/(\\d+)").find(result.source) != null
                    statusView.text = "${clips.size} videos — @$user$savedNote" +
                            if (searched) " · video not found in ${result.searchedPages} pages" else ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Session.bad(this@MainActivity, e.message ?: "read failed")
                statusView.text = e.message ?: "Failed"
            } finally {
                loadBtn.isEnabled = true
                refresh.isRefreshing = false
                showSelection()
            }
        }
    }

    /**
     * Instagram through the same paste button: a post or a reel link comes back as one clip and is
     * downloaded at once, a profile link fills this list with the posts Instagram will name, and
     * ticking is the same tick it always was.
     *
     * There is no Instagram login in this app on purpose: the reader stays anonymous, because a
     * session attached to the crawler read makes Instagram take the media payload back off the page.
     */
    private fun loadInstagram(query: String) {
        val single = InstagramApi.code(query) != null
        // "everything" on Instagram costs about 0.86 MB of reading per post, so it is held to 120
        // instead of walking a ten-thousand-post account, and the status line says the price first.
        val cap = if (wantedLimit() <= 0) 120 else minOf(wantedLimit(), 120)
        loadBtn.isEnabled = false
        refresh.isRefreshing = true
        showSection(LIST)
        statusView.text = if (single) "Reading the Instagram post…"
        else "Instagram profile — up to $cap posts, ~${cap * 9 / 10} MB of reading before any of it " +
            "is saved"
        val web = igWeb ?: InstagramWeb(this).also { igWeb = it }

        task = lifecycleScope.launch {
            try {
                val clips = withContext(Dispatchers.IO) {
                    val found = InstagramApi.read(web, query, cap) { _, note ->
                        runOnUiThread { statusView.text = note }
                    }
                    // Remembered as the session's proof: a login that named twelve posts is worth
                    // knowing about before a big pull is paid for. The prefs write happens here, off
                    // the thread that draws.
                    InstagramApi.noteScan(applicationContext, found.size,
                        found.count { it.play.isNotBlank() })
                    found
                }
                adapter.submit(clips, Downloader.doneIds(this@MainActivity))
                refresh.isRefreshing = false
                val first = clips.firstOrNull()
                val already = clips.count { it.id in Downloader.doneIds(this@MainActivity) }
                if (single && first != null) {
                    statusView.text = "Instagram — " +
                        if (first.play.isBlank()) "${first.images.size} picture(s) · downloading…"
                        else "reel · downloading…"
                    startDownloads(listOf(first))
                } else if (first == null) {
                    statusView.text = "Instagram showed nothing for that link"
                } else {
                    val videos = clips.count { it.play.isNotBlank() }
                    statusView.text = "${clips.size} Instagram posts · $videos with video" +
                        (if (already > 0) " · $already already downloaded" else "") +
                        " · tick what you want"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing here says the TikTok session broke, because it did not: the two readers are
                // separate, and blaming the login would be a lie the user acts on.
                statusView.text = e.message ?: "Instagram read failed"
            } finally {
                loadBtn.isEnabled = true
                refresh.isRefreshing = false
                showSelection()
            }
        }
    }

    private fun openPlayer(position: Int) {
        val clip = adapter.clipAt(position) ?: return
        // A photo post previews exactly like a video does: tap opens it, download stays a choice.
        if (clip.isPhoto) {
            val first = clip.images.firstOrNull().orEmpty()
            if (first.isBlank()) {
                statusView.text = "This photo post has no picture address"
                return
            }
            ViewerQueue.urls = clip.images
            ViewerQueue.title = clip.title.ifBlank { "Photo post" }
            startActivity(Intent(this, ImageViewerActivity::class.java))
            return
        }
        if (clip.play.isBlank()) {
            statusView.text = "Not a video (maybe a photo post)"
            return
        }
        PlayerQueue.clips = adapter.clips()
        PlayerQueue.start = position
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun startDownloads(clips: List<Clip>) {
        if (clips.isEmpty()) return
        if (!Vault.granted(this)) {
            // Refuse out loud instead of letting the system keep a copy it will delete with the app.
            statusView.text = "All files access is needed before a download"
            askForAccess()
            return
        }
        showSelection()
        // The queue lives in its own scope: this screen may go away, the batch does not.
        Downloads.start(this, clips)
    }

    /**
     * The queue's report. It may arrive while this screen is alive, after it came back, or not at
     * all — none of that stops the download, it only decides what is painted.
     */
    private fun paintDownloads(state: Downloads.State) {
        progress.progress = when {
            state.running -> state.percent
            state.total > 0 -> 100
            else -> 0
        }
        downloadBtn.text = if (state.running) getString(R.string.cancel) else getString(R.string.download)
        downloadBtn.isEnabled = state.running || adapter.selectedCount() > 0
        loadBtn.isEnabled = !state.running
        retryBtn.visibility =
            if (!state.running && Downloads.failedClips.isNotEmpty()) View.VISIBLE else View.GONE
        retryBtn.text = "Retry (${Downloads.failedClips.size})"
        if (state.savedId.isNotBlank()) adapter.markSaved(state.savedId)
        if (state.running) {
            statusView.text = "${state.done + 1}/${state.total} · ${state.current} · ${state.percent}%"
        } else if (state.total > 0) {
            val trouble = if (state.failed == 0) ""
            // The reason, not just the count: on the phone there is no logcat to ask, and a silent
            // "1 failed" cannot be told apart from a batch that was only cancelled.
            else " · ${state.failed} failed" +
                if (state.lastError.isBlank()) "" else " · ${state.lastError}"
            statusView.text = getString(R.string.done, state.done - state.failed, state.total) + trouble
            if (downloadsWereRunning && section == LIBRARY) refreshLibrary()
        }
        downloadsWereRunning = state.running
    }

    override fun onResume() {
        super.onResume()
        showLoginState()
        if (section == LIBRARY) refreshLibrary()
        // The switch is granted on a system page, and we come back to this same screen, so the row
        // that says "Needed" is repainted without asking the user to tap anything.
        if (section == SETTINGS) showSettings()
        moveOldVideos()
        if (!Vault.granted(this)) {
            statusView.text = "All files access is still off — tap All files access in Settings"
        }
        // The queue may have kept working while this screen was away.
        Downloads.watch { paintDownloads(it) }
    }

    companion object {
        const val LIST = 0
        const val LIBRARY = 1
        const val SETTINGS = 2

        /** Set by the ➕ in the offline feed: land here and grab the clipboard link. */
        const val CLIP_EXTRA = "grab_from_clipboard"
    }

    override fun onPause() {
        // The queue keeps running after this; only the reporting stops.
        Downloads.watch(null)
        Backup.snapshot(this)
        super.onPause()
    }

    override fun onDestroy() {
        task?.cancel()
        tikWeb?.destroy()
        tikWeb = null
        igWeb?.destroy()
        igWeb = null
        super.onDestroy()
    }
}

private fun Double.format1(): String = "%.1f".format(this)
