package com.amusic.companion

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

/**
 * Link to the Apple Music app (com.apple.android.music).
 *
 * Its media session gives us "now playing" and the controls. We reach it two ways:
 *  - MediaSessionManager (needs notification access for NowPlayingListener), and
 *  - a MediaBrowser connection to Apple Music's player service (no permission, if it accepts us).
 * Everything here runs on the main thread.
 */
class AppleMusic(private val context: Context, private val onController: (MediaController?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerName = ComponentName(context, NowPlayingListener::class.java)
    private var browser: MediaBrowser? = null
    private var fromSessions: MediaController? = null
    private var fromBrowser: MediaController? = null
    private var current: MediaController? = null
    private var pendingShuffle = false

    val hasNotificationAccess: Boolean
        get() = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(listenerName.flattenToString()) == true

    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        fromSessions = list?.firstOrNull { it.packageName == PKG }
        publish()
    }

    fun start() {
        if (hasNotificationAccess) runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerName, main)
            fromSessions = sessions.getActiveSessions(listenerName).firstOrNull { it.packageName == PKG }
        }
        connectBrowser()
        publish()
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        browser?.disconnect()
        browser = null
    }

    private fun connectBrowser() {
        if (browser != null || !isInstalled) return
        val b = MediaBrowser(context, ComponentName(PKG, SERVICE), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                fromBrowser = MediaController(context, b.sessionToken)
                publish()
            }
            override fun onConnectionFailed() { Log.i(TAG, "Apple Music refused the browser connection"); browser = null }
            override fun onConnectionSuspended() { fromBrowser = null; browser = null; publish() }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { browser = null }
    }

    private fun publish() {
        val c = fromSessions ?: fromBrowser
        if (c?.sessionToken != current?.sessionToken || (c == null) != (current == null)) {
            current = c
            onController(c)
        }
        if (c != null && pendingShuffle) { pendingShuffle = false; shuffleWith(c) }
    }

    // ---------- shuffle the whole library ----------

    /**
     * Restarts the whole library shuffled. Best way first:
     *  1. through Apple Music's media browser: find "Songs" in the library (or a "Shuffle all" item)
     *     and play it with shuffle on;
     *  2. otherwise ask the player to "play music" (empty search) with shuffle on.
     * If Apple Music isn't running yet, it is started and we finish once its session appears.
     */
    fun shuffleAll(report: (String) -> Unit) {
        val c = current
        if (c == null) {
            if (!isInstalled) return report("Apple Music не установлена")
            pendingShuffle = true
            report("Запускаю Apple Music…")
            launchPlayFromSearch()
            connectBrowser()
            return
        }
        report("Перемешиваю медиатеку…")
        shuffleWith(c)
    }

    private fun shuffleWith(c: MediaController) {
        val b = browser
        if (b != null && b.isConnected) {
            findLibrarySongs(b) { id ->
                setShuffle(c)
                if (id != null) {
                    Log.i(TAG, "shuffle: playing library item")
                    c.transportControls.playFromMediaId(id, Bundle())
                } else {
                    Log.i(TAG, "shuffle: no library item found, using play-from-search")
                    c.transportControls.playFromSearch("", Bundle())
                }
                main.postDelayed({ setShuffle(c) }, 1500)   // some players reset the mode on a new queue
            }
        } else {
            setShuffle(c)
            c.transportControls.playFromSearch("", Bundle())
            main.postDelayed({ setShuffle(c) }, 1500)
        }
    }

    private fun setShuffle(c: MediaController) {
        // Apple Music uses the AndroidX media session; this is its "set shuffle mode" command.
        c.transportControls.sendCustomAction(
            "android.support.v4.media.session.action.SET_SHUFFLE_MODE",
            Bundle().apply { putInt("android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE", SHUFFLE_ALL) }
        )
    }

    private fun launchPlayFromSearch() {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(PKG)
            .putExtra(SearchManager.QUERY, "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            context.packageManager.getLaunchIntentForPackage(PKG)?.let { context.startActivity(it) }
        }
    }

    /**
     * Walks Apple Music's browse tree (three levels at most) looking for a playable
     * "Shuffle all" item, or for "Songs" inside the library. Titles are matched in English
     * and Russian. Calls back once with the media id, or null.
     */
    private fun findLibrarySongs(b: MediaBrowser, done: (String?) -> Unit) {
        var finished = false
        var pending = 0
        var songs: String? = null
        fun finish(id: String?) { if (!finished) { finished = true; done(id) } }
        main.postDelayed({ finish(songs) }, 6000)

        fun visit(parentId: String, depth: Int, inLibrary: Boolean) {
            pending++
            b.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(id: String, children: MutableList<MediaBrowser.MediaItem>) {
                    b.unsubscribe(id)
                    for (item in children) {
                        val title = item.description.title?.toString().orEmpty()
                        val mediaId = item.mediaId ?: continue
                        Log.i(TAG, "browse ${"  ".repeat(depth)}$title [${if (item.isPlayable) "play" else ""}${if (item.isBrowsable) "dir" else ""}]")
                        if (item.isPlayable && SHUFFLE_ALL_TITLE.containsMatchIn(title)) return finish(mediaId)
                        if (inLibrary && SONGS_TITLE.matches(title) && songs == null) songs = mediaId
                        if (item.isBrowsable && depth < 2) visit(mediaId, depth + 1, inLibrary || LIBRARY_TITLE.containsMatchIn(title))
                    }
                    if (--pending == 0) finish(songs)
                }
                override fun onError(id: String) { if (--pending == 0) finish(songs) }
            })
        }
        visit(b.root, 0, false)
    }

    companion object {
        const val PKG = "com.apple.android.music"
        private const val SERVICE = "com.apple.android.music.player.MediaPlaybackService"
        private const val TAG = "AMusicCompanion"
        private const val SHUFFLE_ALL = 1   // PlaybackStateCompat.SHUFFLE_MODE_ALL
        private val SHUFFLE_ALL_TITLE = Regex("shuffle all|перемешать вс", RegexOption.IGNORE_CASE)
        private val LIBRARY_TITLE = Regex("library|медиатек", RegexOption.IGNORE_CASE)
        private val SONGS_TITLE = Regex("^(songs|песни)$", RegexOption.IGNORE_CASE)
    }
}
