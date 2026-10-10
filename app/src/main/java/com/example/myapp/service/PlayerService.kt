package com.example.myapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager

@UnstableApi
class PlayerService : Service() {

    private val binder = LocalBinder()
    lateinit var exoPlayer: ExoPlayer
        private set
    private var mediaSession: MediaSession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var playbackNotification: Notification? = null
    private var isForeground = false
    private var relativeVolumePercent = 100
    private val notificationId = 1001
    private val channelId = "audio_playback_channel"
    private val playbackLogger = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            logPlaybackState("playbackStateChanged state=$playbackState")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            logPlaybackState("playWhenReadyChanged value=$playWhenReady reason=$reason")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            logPlaybackState("isPlayingChanged value=$isPlaying")
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            logPlaybackState("playerError error=${error.errorCodeName}: ${error.message}")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("PlayerService", "onCreate pid=${android.os.Process.myPid()} service=${System.identityHashCode(this)}")
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.addListener(playbackLogger)
        
        // Create notification channel for Android O and above
        createNotificationChannel()
        
        // Create MediaSession
        mediaSession = try {
            MediaSession.Builder(this, exoPlayer)
                .setCallback(MediaSessionCallback())
                .build()
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("Session ID must be unique.") != true) {
                throw e
            }

            android.util.Log.e("PlayerService", "MediaSession ID is already active", e)
            Toast.makeText(
                this,
                "Player controls couldn't start because a media session is already active. Restart the app if controls stop responding.",
                Toast.LENGTH_LONG
            ).show()
            null
        }
        
        // Create PlayerNotificationManager
        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            notificationId,
            channelId
        )
            .setMediaDescriptionAdapter(DescriptionAdapter())
            .setNotificationListener(NotificationListener())
            .setSmallIconResourceId(android.R.drawable.ic_media_play)
            .build()
            .apply {
                setPlayer(exoPlayer)
                mediaSession?.let { session ->
                    setMediaSessionToken(session.sessionCompatToken)
                }
                setUseRewindAction(true)
                setUseFastForwardAction(true)
                setUseRewindActionInCompactView(true)
                setUseFastForwardActionInCompactView(true)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logPlaybackState("onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logPlaybackState("onTaskRemoved action=${rootIntent?.action}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        logPlaybackState("onDestroy")

        exoPlayer.removeListener(playbackLogger)
        playerNotificationManager?.setPlayer(null)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        logPlaybackState("onBind action=${intent?.action}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        logPlaybackState("onUnbind action=${intent?.action}")
        // Return true to receive onRebind call when client binds again
        // The service will continue playing music in the background via the notification
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        logPlaybackState("onRebind action=${intent?.action}")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private inner class DescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return player.currentMediaItem?.mediaMetadata?.title ?: "Playing Audio"
        }

        override fun createCurrentContentIntent(player: Player): android.app.PendingIntent? {
            return null
        }

        override fun getCurrentContentText(player: Player): CharSequence {
            return player.currentMediaItem?.mediaMetadata?.artist ?: "Audio Player"
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): android.graphics.Bitmap? {
            return null
        }
    }
    
    private inner class NotificationListener : PlayerNotificationManager.NotificationListener {
        override fun onNotificationPosted(
            notificationId: Int,
            notification: Notification,
            ongoing: Boolean
        ) {
            logPlaybackState("onNotificationPosted id=$notificationId ongoing=$ongoing")
            playbackNotification = notification
            if (ongoing || isPlaybackActive()) {
                promoteToForeground(notification)
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                    isForeground = false
                    android.util.Log.d("PlayerService", "stopForeground detach succeeded")
                } catch (e: Exception) {
                    android.util.Log.w("PlayerService", "Could not stop foreground: ${e.message}", e)
                }
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            logPlaybackState("onNotificationCancelled id=$notificationId dismissedByUser=$dismissedByUser")
            
            if (isPlaybackActive()) {
                android.util.Log.d("PlayerService", "Playback is active; restoring foreground notification")
                val notification = playbackNotification
                if (notification != null) {
                    promoteToForeground(notification)
                } else {
                    android.util.Log.e("PlayerService", "Cannot restore foreground playback: notification is unavailable")
                }
                return
            }
            
            // A notification can be cancelled during teardown or a transient
            // playback-state change. Removing foreground status is safe here,
            // but stopping the service can make it die as soon as the screen
            // unbinds, even if playback resumes before then.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Could not stop foreground on notification cancelled: ${e.message}", e)
            }
            isForeground = false
        }
    }

    private fun isPlaybackActive(): Boolean {
        return exoPlayer.playWhenReady &&
            exoPlayer.playbackState != Player.STATE_IDLE &&
            exoPlayer.playbackState != Player.STATE_ENDED
    }

    private fun promoteToForeground(notification: Notification) {
        try {
            startForeground(notificationId, notification)
            isForeground = true
            android.util.Log.d("PlayerService", "startForeground succeeded")
        } catch (e: Exception) {
            android.util.Log.w("PlayerService", "Could not start foreground service: ${e.message}", e)
        }
    }

    private fun logPlaybackState(event: String) {
        val playerState = if (::exoPlayer.isInitialized) {
            "playbackState=${exoPlayer.playbackState} playWhenReady=${exoPlayer.playWhenReady} " +
                "isPlaying=${exoPlayer.isPlaying} suppression=${exoPlayer.playbackSuppressionReason} " +
                "items=${exoPlayer.mediaItemCount} currentIndex=${exoPlayer.currentMediaItemIndex}"
        } else {
            "player=uninitialized"
        }
        android.util.Log.d(
            "PlayerService",
            "$event pid=${android.os.Process.myPid()} service=${System.identityHashCode(this)} " +
                "foreground=$isForeground $playerState"
        )
    }
    
    private inner class MediaSessionCallback : MediaSession.Callback {
        // The base callback already handles play/pause through the MediaSession player
        // We can add custom commands if needed later
    }

    fun playUri(uri: String) {
        android.util.Log.d("PlayerService", "playUri: $uri")
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(uri.substringAfterLast("/").substringBeforeLast("."))
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun loadUri(uri: String) {
        android.util.Log.d("PlayerService", "loadUri: $uri")
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(uri.substringAfterLast("/").substringBeforeLast("."))
                    .build()
            )
            .build()
        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        // Don't call play() - just prepare for playback
    }

    fun stop() {
        android.util.Log.d("PlayerService", "stop() called")
        exoPlayer.stop()
    }

    fun pause() {
        android.util.Log.d("PlayerService", "pause() called")
        exoPlayer.pause()
    }

    fun play() {
        android.util.Log.d("PlayerService", "play() called")
        exoPlayer.play()
    }

    fun adjustRelativeVolume(changePercent: Int): Int {
        relativeVolumePercent = (relativeVolumePercent + changePercent).coerceIn(0, 100)
        exoPlayer.volume = relativeVolumePercent / 100f
        return relativeVolumePercent
    }

    fun getRelativeVolumePercent(): Int = relativeVolumePercent

    fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer.currentPosition
    }

    fun getDuration(): Long {
        return exoPlayer.duration
    }

    // M2: Playback rate control
    fun setPlaybackRate(rate: Float) {
        exoPlayer.setPlaybackSpeed(rate)
    }

    fun getPlaybackRate(): Float {
        return exoPlayer.playbackParameters.speed
    }

    // M2: Chapter navigation
    fun nextChapter() {
        val duration = exoPlayer.duration
        if (duration <= 0) return
        
        val chapterSize = duration / 10
        val currentChapter = (exoPlayer.currentPosition / chapterSize).toInt()
        val nextPosition = minOf(duration, (currentChapter + 1) * chapterSize)
        
        exoPlayer.seekTo(nextPosition)
    }

    fun previousChapter() {
        val duration = exoPlayer.duration
        if (duration <= 0) return
        
        val chapterSize = duration / 10
        val currentChapter = (exoPlayer.currentPosition / chapterSize).toInt()
        val chapterStart = currentChapter * chapterSize
        
        // If more than 2 seconds into current chapter, go to its start
        val targetPosition = if (exoPlayer.currentPosition - chapterStart > 2000L && currentChapter > 0) {
            chapterStart
        } else {
            maxOf(0, (currentChapter - 1) * chapterSize)
        }
        
        exoPlayer.seekTo(targetPosition)
    }

    fun jumpToChapter(chapter: Int) {
        val duration = exoPlayer.duration
        if (duration <= 0 || chapter < 0 || chapter >= 10) return
        
        val chapterSize = duration / 10
        val targetPosition = chapter * chapterSize
        
        exoPlayer.seekTo(targetPosition)
    }

    fun getCurrentChapter(): Int {
        val duration = exoPlayer.duration
        if (duration <= 0) return 0
        
        val chapterSize = duration / 10
        return minOf(9, (exoPlayer.currentPosition / chapterSize).toInt())
    }

    fun isPlaying(): Boolean {
        return exoPlayer.isPlaying
    }
}
