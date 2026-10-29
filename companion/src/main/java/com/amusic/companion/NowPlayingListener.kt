package com.amusic.companion

import android.service.notification.NotificationListenerService

/**
 * Does nothing by itself. Having notification access granted to this service is what lets
 * MediaSessionManager hand us Apple Music's media session (now playing + controls).
 */
class NowPlayingListener : NotificationListenerService()
