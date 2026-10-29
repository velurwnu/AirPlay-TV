package com.amusic.airplay

import android.content.Context
import android.os.Build
import java.security.SecureRandom

/** The receiver's settings, kept in SharedPreferences. */
class Settings(private val context: Context) {

    private val p = context.getSharedPreferences("airplay", Context.MODE_PRIVATE)

    /** Name shown in the iPhone's AirPlay list. Defaults to the TV's own name. */
    var name: String
        get() = p.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(v) = p.edit().putString("name", v.trim()).apply()

    /** Ask for an on-screen code the first time a device connects (like Apple TV's "Require code"). */
    var pin: Boolean
        get() = p.getBoolean("pin", false)
        set(v) = p.edit().putBoolean("pin", v).apply()

    /** Keep the receiver running in the background and start it when the TV boots. */
    var background: Boolean
        get() = p.getBoolean("background", true)
        set(v) = p.edit().putBoolean("background", v).apply()

    var lyrics: Boolean
        get() = p.getBoolean("lyrics", true)
        set(v) = p.edit().putBoolean("lyrics", v).apply()

    /** Pronunciation in Latin letters under Korean, Japanese and Chinese lyrics. */
    var romanization: Boolean
        get() = p.getBoolean("romanization", true)
        set(v) = p.edit().putBoolean("romanization", v).apply()

    /** Screen mirroring in H.265 up to 4K (only offered when the TV has a hardware decoder). */
    var hevc: Boolean
        get() = p.getBoolean("hevc", false)
        set(v) = p.edit().putBoolean("hevc", v).apply()

    /** Video from apps (YouTube etc.) is played by the TV itself, not mirrored. */
    var hls: Boolean
        get() = p.getBoolean("hls", true)
        set(v) = p.edit().putBoolean("hls", v).apply()

    /** 0: any free ports. Otherwise TCP N, N+1 and UDP N..N+2, for firewalls and port forwarding. */
    var basePort: Int
        get() = p.getInt("base_port", 0)
        set(v) = p.edit().putInt("base_port", v).apply()

    /**
     * AirPlay identifies a receiver by a MAC-style device id. Android hides the real MAC,
     * so a random, locally administered one is made once and kept: the iPhone then
     * recognises the TV (and its pairing) across restarts.
     */
    val deviceId: String
        get() = p.getString("mac", null) ?: run {
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xfe) or 0x02).toByte()
            b.joinToString(":") { "%02x".format(it) }.also { p.edit().putString("mac", it).apply() }
        }

    private fun defaultName(): String {
        val tvName = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        // A build codename such as "sdk_google_atv64_arm64" is no name to show on an iPhone.
        fun readable(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !CODENAME.matches(it) }
        return readable(tvName) ?: readable(Build.MODEL) ?: "Android TV"
    }

    private companion object {
        val CODENAME = Regex("[a-z0-9]+(_[a-z0-9]+)+")
    }
}
