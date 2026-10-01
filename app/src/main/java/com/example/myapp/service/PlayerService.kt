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
}
