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
    private var task: Job? = null
    private var commentTask: Job? = null
    private var commentDone = 0
    private var commentTotal = 0
    private var lastPercent = -1
    private var section = LIST
    private val failed = ArrayList<Clip>()


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
        findViewById<TextView>(R.id.librarySort).setOnClickListener { pickLibraryOrder() }
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
            if (task?.isActive == true) task?.cancel() else startDownloads(adapter.selectedClips())
        }
        retryBtn.setOnClickListener { startDownloads(ArrayList(failed)) }

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

        // Android cannot ask for All files access at install time, and a fresh install has nothing
        // in the library yet — so the first launch is where the switch has to be explained.
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (!Vault.granted(this) && !ui.getBoolean("asked_access", false)) {
            ui.edit().putBoolean("asked_access", true).apply()
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

    private fun wantedLimit(): Int {
        val picked = countPick.selectedItemPosition
        return if (picked < countValues.size && countValues[picked] > 0) countValues[picked] else TikTokApi.CAP
    }

    private fun showEstimate() {
        val pages = (wantedLimit() + 14) / 15
        estimateView.text = "list ~${(pages * 0.4).format1()} MB · best quality"
    }

    private fun showSelection() {
        val bytes = adapter.selectedBytes()
        selectionView.text = buildString {
            append("${adapter.selectedCount()} / ${adapter.total()} select")
            if (bytes > 0) append(" · ~").append(GridAdapter.mb(bytes))
        }
        selectBar.visibility = if (adapter.selectMode) View.VISIBLE else View.GONE
        val running = task?.isActive == true
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

    /** TikTok-shaped list: grey section captions, then rows with the current value on the right. */
    private fun showSettings() {
        val saved = LocalVideos.scan(this)
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
            SettingsAdapter.Row("Profile pictures", "${Authors.cachedAvatars(this)} cached", "Storage") {
                val had = Authors.cachedAvatars(this)
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
            SettingsAdapter.Row("Clear cached profiles", "", "Account") {
                TikTokApi.clearCache(this)
                note("Cached profiles", "Cleared — the next list read refreshes them from TikTok.")
            },
            SettingsAdapter.Row("Saved in Downloads", "${Backup.recordCount(this)} files", "Backup") {
                note("Backup folder", lines("Download/TikTokGrab/Backup", "",
                    "${Comments.stored(this)} comment files",
                    "${Authors.cachedAvatars(this)} profile pictures",
                    "1 records file"))
            },
            SettingsAdapter.Row("Fetch comments for saved videos",
                if (commentTask?.isActive == true) "running $commentDone/$commentTotal — tap to stop"
                else "${Comments.stored(this)} of ${saved.size} clips", "Backup") { fetchComments(saved) },
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
                if (CrashRecorder.text(this).isBlank()) "none" else "recorded", "About") {
                val log = CrashRecorder.text(this)
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
            SettingsAdapter.Row("Version", packageVersion(), "About") {
                note("TikTok Grab", lines("Version ${packageVersion()}", "Built for one person's own library."))
            }
        ))
    }

    private fun lines(vararg parts: String) = parts.joinToString("\n")

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
        val missing = Comments.missingFor(this, saved)
        if (missing.isEmpty()) {
            note("Comments", "Every saved video already has its comments.")
            return
        }
        val megabytes = (missing.size * 4 + 99) / 100
        val minutes = (missing.size * 12 + 599) / 600
        MaterialAlertDialogBuilder(this)
            .setTitle("Fetch ${missing.size} comment sets?")
            .setMessage(lines("About $megabytes MB and $minutes minutes: the reader answers one clip " +
                "per second, and each set is assumed to hold up to 100 comments. Comment pictures " +
                "can push that higher.", "", "Tap the same row again to stop."))
            .setNegativeButton("Not now", null)
            .setPositiveButton("Fetch") { _, _ -> pullComments(missing) }
            .show()
    }

    private fun pullComments(missing: List<LocalVideo>) {
        commentDone = 0
        commentTotal = missing.size
        commentTask = lifecycleScope.launch {
            var empty = 0
            var failed = 0
            missing.forEach { video ->
                val got = withContext(Dispatchers.IO) {
                    runCatching { Comments.fetch(this@MainActivity, video.clipId) }.getOrNull()
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
        val saved = sortedForLibrary(LocalVideos.scan(this))
        val profiles = saved.map { it.author }.distinct()
        libraryList.adapter = LocalGridAdapter(saved, onOpen = { openOffline(it) }, onMenu = { clip ->
            LocalMenu.show(this, clip, profiles,
                onDeleted = { refreshLibrary() }, onMoved = { refreshLibrary() })
        })
        findViewById<TextView>(R.id.librarySort).text =
            "${libraryOrders[libraryOrder().coerceIn(0, libraryOrders.size - 1)]} ▾"
        statusView.text = if (saved.isEmpty()) getString(R.string.library_empty)
        else "${saved.size} saved · ${GridAdapter.mb(saved.sumOf { it.bytes })}"
    }

    private val libraryOrders = arrayOf("Newest first", "Largest first", "Most played", "Random")

    private fun libraryOrder() =
        getSharedPreferences("ui", MODE_PRIVATE).getInt("library_order", 0)

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
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("library_order", which).apply()
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
                val result = TikTokApi.list(this@MainActivity, web, query, wantedLimit()) { count ->
                    runOnUiThread { statusView.text = "Loading… $count" }
                }
                val user = TikTokApi.username(result.source)
                val clips = result.clips
                Session.good(this@MainActivity)
                adapter.submit(clips, Downloader.doneIds(this@MainActivity))
                val already = clips.count { it.id in Downloader.doneIds(this@MainActivity) }
                val savedNote = if (already > 0) " · $already already downloaded" else ""

                if (result.match != null) {
                    input.setText("@$user")
                    statusView.text = "Found — downloading… @$user"
                    startDownloads(listOf(result.match!!))
                } else {
                    val searched = Regex("/video/(\\d+)").find(result.source) != null
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

    private fun openPlayer(position: Int) {
        val clip = adapter.clipAt(position) ?: return
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
        failed.clear()
        loadBtn.isEnabled = false
        lastPercent = -1
        // Pins the process and shows the bar outside the app; it can never break the loop itself.
        DownloadService.start(this, clips.size)

        task = lifecycleScope.launch {
            showSelection()
            var finished = 0
            try {
                for (clip in clips) {
                    try {
                        val result = Downloader.save(this@MainActivity, clip) { percent ->
                            val whole = percent.toInt()
                            if (whole != lastPercent) {
                                lastPercent = whole
                                runOnUiThread {
                                    progress.progress = whole
                                    statusView.text = "${finished + 1}/${clips.size} · $whole%"
                                    DownloadService.progress(this@MainActivity, finished, clips.size)
                                }
                            }
                        }
                        adapter.markSaved(result.clipId)
                        if (!result.skipped) {
                            progress.progress = 100
                            statusView.text = "Saved → ${result.path}"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.add(clip)
                        statusView.text = "Failed: ${e.message}"
                    }
                    finished++
                }
                statusView.text = getString(R.string.done, finished - failed.size, clips.size) +
                        if (failed.isEmpty()) "" else " · ${failed.size}"
            } catch (e: CancellationException) {
                statusView.text = "Stopped · $finished/${clips.size} done"
                throw e
            } finally {
                DownloadService.stop(this@MainActivity)
                loadBtn.isEnabled = true
                showSelection()
                if (section == LIBRARY) refreshLibrary()
            }
        }
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
    }

    companion object {
        const val LIST = 0
        const val LIBRARY = 1
        const val SETTINGS = 2

        /** Set by the ➕ in the offline feed: land here and grab the clipboard link. */
        const val CLIP_EXTRA = "grab_from_clipboard"
    }

    override fun onPause() {
        Backup.snapshot(this)
        super.onPause()
    }

    override fun onDestroy() {
        task?.cancel()
        tikWeb?.destroy()
        tikWeb = null
        super.onDestroy()
    }
}

private fun Double.format1(): String = "%.1f".format(this)
