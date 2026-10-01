package com.example.myapp.viewmodel

import androidx.lifecycle.ViewModel
import com.example.myapp.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerViewModel : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun setSongs(newSongs: List<Song>) {
        _songs.value = newSongs
    }

    fun setCurrentSong(song: Song) {
        _currentSong.value = song
        _isPlaying.value = true
    }

    fun setIsPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
    }
}
