package com.amusic.airplay

/** The native AirPlay core: UxPlay plus the JNI bridge in src/main/cpp/airplay_jni.cpp. */
object AirPlayNative {
    init {
        System.loadLibrary("airplay")
    }

    /**
     * Starts the receiver and advertises it over mDNS. Returns its TCP port, or a negative error:
     * -1 bad arguments, -2 mDNS init failed, -3 server failed to start, -4 mDNS registration failed.
     */
    external fun start(
        callbacks: NativeCallbacks, name: String, mac: String, hostName: String, keyFile: String,
        width: Int, height: Int, fps: Int, h265: Boolean, hls: Boolean, pin: Boolean,
        lang: String, basePort: Int, logLevel: Int
    ): Int

    external fun stop()

    /** The AirPlay-video (HLS) player reached the end. */
    external fun hlsEnded()

    const val LOG_INFO = 6
    const val LOG_DEBUG = 7
}

/**
 * Everything the AirPlay core reports. Called on the core's own network threads,
 * so implementations must hand UI work over to the main thread and return quickly.
 */
interface NativeCallbacks {
    /** A device wants to connect. Return false to refuse it. */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean
    fun onConnections(count: Int)
    /** The client went silent (network trouble); the core resets the session. */
    fun onConnectionLost(reason: Int)

    /** An audio stream is set up. [ct]: 2 ALAC (music), 4 AAC-LC, 8 AAC-ELD (screen mirroring). */
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean)
    /**
     * One audio packet: for ct 2 already decoded 16-bit stereo PCM, otherwise one AAC frame.
     * [rtp] is its RTP timestamp (44.1 kHz), [delayUs] how long until the sender wants it heard
     * (Long.MIN_VALUE before the clocks are synced).
     */
    fun onAudio(ct: Int, data: ByteArray, rtp: Long, delayUs: Long)
    fun onAudioFlush()
    /** AirPlay volume in dB: -30..0, or -144 for mute. */
    fun onVolume(db: Float)
    /** DMAP-encoded "now playing": title, artist, album… */
    fun onMetadata(data: ByteArray)
    /** Cover art, JPEG or PNG. */
    fun onCoverArt(data: ByteArray)
    fun onCoverArtStop()
    /** RTP timestamps of the track's start, the current position and the end. */
    fun onProgress(start: Long, current: Long, end: Long)
    /** Lets us control the sender (play/pause, next…) over DACP. */
    fun onRemoteControl(dacpId: String, activeRemote: String)

    fun onVideoCodec(h265: Boolean, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int)
    /** One mirrored video frame, Annex-B, parameter sets prepended to key frames. */
    fun onVideo(data: ByteArray, h265: Boolean)
    fun onVideoFlush()
    fun onMirrorRunning(running: Boolean)
    /** See raop.h reset_type_t. */
    fun onVideoReset(type: Int)
    /** The core reset the session (after a client left or went silent): stop playing. */
    fun onSessionReset()
    /** PIN the user has to type on the iPhone. */
    fun onPin(pin: String)

    fun onHlsPlay(url: String, startSeconds: Float)
    fun onHlsScrub(seconds: Float)
    fun onHlsRate(rate: Float)
    fun onHlsStop()
    /** {duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull, ended}; null if unknown. */
    fun hlsPlaybackInfo(): DoubleArray?
    /** Pause and return the current position, in seconds. */
    fun onHlsPlaylistRemove(): Float
}
