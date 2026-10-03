package com.example.myapp.viewmodel

import androidx.lifecycle.ViewModel
import com.example.myapp.data.Song
import com.example.myapp.data.TrackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerViewModel : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _currentTrackSession = MutableStateFlow<TrackSession?>(null)
    val currentTrackSession: StateFlow<TrackSession?> = _currentTrackSession.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // M2: Playback rate (1.0, 1.15, 1.25, 1.35)
    private val playbackRates = listOf(1.0f, 1.15f, 1.25f, 1.35f)
    private var currentRateIndex = 0
    
    private val _playbackRate = MutableStateFlow(1.0f)
    val playbackRate: StateFlow<Float> = _playbackRate.asStateFlow()

    // M2: Hidden/completed tracks
    private val _hiddenTracks = MutableStateFlow<Set<Long>>(emptySet())
    val hiddenTracks: StateFlow<Set<Long>> = _hiddenTracks.asStateFlow()

    // M2: Sort preference (0=shortest, 1=longest, 2=newest, 3=oldest)
    private val _sortMode = MutableStateFlow(0)
    val sortMode: StateFlow<Int> = _sortMode.asStateFlow()

    fun setSongs(newSongs: List<Song>) {
        _songs.value = newSongs
    }

    fun setCurrentSong(song: Song) {
        _currentSong.value = song
        _isPlaying.value = true
    }

    fun clearCurrentSong() {
        _currentSong.value = null
        _isPlaying.value = false
    }

    fun setIsPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun togglePlayPause() {
        _isPlaying.value = !_isPlaying.value
    }

    fun setCurrentPosition(position: Long) {
        _currentPosition.value = position
    }

    fun setDuration(duration: Long) {
        _duration.value = duration
    }

    // M2: Track session management
    fun setCurrentTrackSession(session: TrackSession) {
        _currentTrackSession.value = session
    }

    fun updateTrackSession(currentPosition: Long, duration: Long) {
        _currentTrackSession.value = _currentTrackSession.value?.copy(
            currentPosition = currentPosition,
            duration = duration
        )
    }

    // M2: Playback rate cycling
    fun cyclePlaybackRate() {
        currentRateIndex = (currentRateIndex + 1) % playbackRates.size
        _playbackRate.value = playbackRates[currentRateIndex]
    }

    fun setPlaybackRate(rate: Float) {
        _playbackRate.value = rate
        val index = playbackRates.indexOf(rate)
        if (index >= 0) {
            currentRateIndex = index
        }
    }

    // M2: Chapter navigation
    fun getChapterInfo(): Pair<Int, Int>? {
        val duration = _duration.value
        val position = _currentPosition.value
        
        if (duration <= 0) return null
        
        val chapterSize = duration / 10
        val currentChapter = (position / chapterSize).toInt()
        
        return Pair(currentChapter, 10)
    }

    fun jumpToChapter(chapter: Int) {
        val duration = _duration.value
        if (duration <= 0 || chapter < 0 || chapter >= 10) return
        
        val chapterSize = duration / 10
        val targetPosition = chapter * chapterSize
        setCurrentPosition(targetPosition)
    }

    fun previousChapter() {
        val chapterInfo = getChapterInfo() ?: return
        val (currentChapter, _) = chapterInfo
        val duration = _duration.value
        val chapterSize = duration / 10
        val chapterStart = currentChapter * chapterSize
        
        // If more than 2 seconds into current chapter, go to its start
        if (_currentPosition.value - chapterStart > 2000L && currentChapter > 0) {
            setCurrentPosition(chapterStart)
        } else {
            jumpToChapter(maxOf(0, currentChapter - 1))
        }
    }

    fun nextChapter() {
        val chapterInfo = getChapterInfo() ?: return
        val (currentChapter, _) = chapterInfo
        val duration = _duration.value
        val chapterSize = duration / 10
        
        jumpToChapter(minOf(9, currentChapter + 1))
    }

    // M2: Hidden tracks management
    fun setHiddenTracks(trackIds: Set<Long>) {
        _hiddenTracks.value = trackIds
    }

    fun hideTrack(trackId: Long) {
        val hidden = _hiddenTracks.value.toMutableSet()
        hidden.add(trackId)
        _hiddenTracks.value = hidden
    }

    fun unhideTrack(trackId: Long) {
        val hidden = _hiddenTracks.value.toMutableSet()
        hidden.remove(trackId)
        _hiddenTracks.value = hidden
    }

    // M2: Sort preference
    fun setSortMode(mode: Int) {
        _sortMode.value = mode
    }

    fun cycleSortMode() {
        _sortMode.value = (_sortMode.value + 1) % 4
    }
}


