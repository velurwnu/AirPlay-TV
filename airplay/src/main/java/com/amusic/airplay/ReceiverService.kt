package com.amusic.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/**
 * Keeps the AirPlay receiver running while the TV is on, like an Apple TV: a foreground service
 * that holds the Wi-Fi multicast lock (mDNS discovery), restarts the receiver when the TV's
 * address changes, and publishes a MediaSession so the remote's media keys and the TV's
 * "now playing" reach the iPhone even from the home screen.
 */
class ReceiverService : Service(), AirPlayReceiver.Listener {

    private lateinit var receiver: AirPlayReceiver
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var address: String? = null
    private var shownTitle: String? = null
    private var shownCover: Any? = null

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            val a = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress ?: return
            main.post {
                if (a == address) return@post
                val first = address == null
                address = a
                Log.i(TAG, "TV address $a")
                receiver.onAddressChanged(a)
                // The core advertises the address it saw at start: tell the iPhones about the new one.
                if (first) receiver.start() else receiver.restart()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("AirPlay mDNS")?.apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirPlay stream")?.apply { setReferenceCounted(false); acquire() }

        receiver = AirPlayReceiver.get(this)
        receiver.addListener(this)
        setupSession()
        // Started once the network callback reports the TV's address (at once if it is online);
        // without a network after a few seconds, start anyway so the screen can say what is wrong.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        main.postDelayed({ if (address == null) receiver.start() }, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        receiver.removeListener(this)
        receiver.stop()
        session.release()
        multicastLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- notification ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Готов к AirPlay")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }

    // ---------- media session ----------

    private fun setupSession() {
        session = MediaSession(this, "AirPlay").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = receiver.remote.play()
                override fun onPause() = receiver.remote.pause()
                override fun onSkipToNext() = receiver.remote.next()
                override fun onSkipToPrevious() = receiver.remote.previous()
                override fun onStop() = receiver.remote.pause()
            })
        }
        onReceiverChanged()
    }

    override fun onReceiverChanged() {
        val t = receiver.track
        val audio = receiver.mode == AirPlayReceiver.Mode.AUDIO && t != null
        session.isActive = audio
        if (!audio) return
        if (t!!.title != shownTitle || receiver.cover !== shownCover) {
            shownTitle = t.title
            shownCover = receiver.cover
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, receiver.durationMs)
                .apply { receiver.cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build())
        }
        val playing = receiver.isPlaying
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                receiver.positionMs(), if (playing) 1f else 0f)
            .build())
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
