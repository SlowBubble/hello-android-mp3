package com.example.myapp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class PlayerService : Service() {

    private val binder = LocalBinder()
    lateinit var exoPlayer: ExoPlayer
        private set

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()
    }

    override fun onDestroy() {
        exoPlayer.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun playUri(uri: String) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun loadUri(uri: String) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        // Don't call play() - just prepare for playback
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun play() {
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
}
