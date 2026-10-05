package com.example.myapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
    private val notificationId = 1001
    private val channelId = "audio_playback_channel"

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("PlayerService", "onCreate called")
        exoPlayer = ExoPlayer.Builder(this).build()
        
        // Create notification channel for Android O and above
        createNotificationChannel()
        
        // Create MediaSession
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(MediaSessionCallback())
            .build()
        
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
                setMediaSessionToken(mediaSession!!.sessionCompatToken)
                setUseRewindAction(true)
                setUseFastForwardAction(true)
                setUseRewindActionInCompactView(true)
                setUseFastForwardActionInCompactView(true)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("PlayerService", "onStartCommand called with intent=$intent flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onDestroy() {
        android.util.Log.d("PlayerService", "onDestroy called")
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
        android.util.Log.d("PlayerService", "onBind called with intent=$intent")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.d("PlayerService", "onUnbind called with intent=$intent")
        return true // allow onRebind
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        android.util.Log.d("PlayerService", "onRebind called with intent=$intent")
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
            android.util.Log.d("PlayerService", "onNotificationPosted: id=$notificationId ongoing=$ongoing")
            if (ongoing) {
                try {
                    startForeground(notificationId, notification)
                    android.util.Log.d("PlayerService", "startForeground succeeded")
                } catch (e: Exception) {
                    // Handle ForegroundServiceStartNotAllowedException (API 31+)
                    // This can happen when transitioning between tracks in the background
                    android.util.Log.w("PlayerService", "Could not start foreground service: ${e.message}", e)
                }
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                    android.util.Log.d("PlayerService", "stopForeground detach succeeded")
                } catch (e: Exception) {
                    android.util.Log.w("PlayerService", "Could not stop foreground: ${e.message}", e)
                }
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            android.util.Log.d("PlayerService", "onNotificationCancelled: id=$notificationId dismissedByUser=$dismissedByUser")
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
            stopSelf()
        }
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
