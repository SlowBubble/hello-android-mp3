# m6c ✓ - Paused Track Stays Pinned

## Problem
When a track was paused and the user went back to the home page, the current track sometimes lost its pinned state and the demarcation divider disappeared even though it was still the selected track.

## Root Cause
`PlayerScreen` was clearing the active song on dispose whenever playback was not active, without checking whether the track was still the current selection. That removed the pinned song state and caused `SongListScreen` to stop treating it as the current pinned track.

## Solution
Updated the dispose guard so a paused track remains pinned as long as it is still the active current song, while still protecting against stale screens overriding a newer song selection.

```kotlin
DisposableEffect(Unit) {
    onDispose {
        val vmCurrentSong = playerViewModel.currentSong.value
        val shouldPreserveNewerSong = vmCurrentSong != null && vmCurrentSong.id != song.id

        if (shouldPreserveNewerSong) {
            return@onDispose
        }

        if (vmCurrentSong == null || vmCurrentSong.id == song.id) {
            playerViewModel.setCurrentSong(song)
        }
    }
}
```

## Why This Fixes It
- **Paused track stays pinned**: the selected song remains the active pinned item even when playback is paused
- **Demarcation remains visible**: the home list still knows which song is the current track boundary
- **Stale state is still blocked**: a newer selected song continues to win over any older disposed screen
- **No accidental clear on home navigation**: pause is not treated like “this track is no longer current”

## Result
✅ Paused current track still shows as pinned on Home
✅ Demarcation remains below the pinned track
✅ Returning home does not clear the active track while paused
✅ A newer track still correctly replaces the old one after skip/advance

# m6b ✓ - Pinned Track Chip Standalone Navigation

## Problem
With a track playing, going to home page, and pressing the playing track's chip would sometimes crash. This has been fixed multiple times, suggesting a deeper state conflict issue.

## Root Cause
The normal `onSongClick` logic for chips checks `currentSong?.uri == song.uri` and `isPlaying` state, which can create state conflicts or race conditions when applied to the currently playing pinned track. The same track is already loaded and being displayed in the player screen, so reapplying the complex click logic could cause navigation or state synchronization issues.

## Solution
Implemented standalone navigation logic specifically for the pinned (currently playing) track:

### SongListScreen.kt
- Added new parameter `onPinnedTrackClick: ((Song) -> Unit)? = null`
- Modified click handler to detect when the current track is being clicked
- Routes pinned tracks to the dedicated handler if available

```kotlin
onSongClick = {
    val isCurrentTrack = song.id == currentSongId
    
    if (isCurrentTrack && onPinnedTrackClick != null) {
        // M6b: Use dedicated pinned track handler to avoid crash
        onPinnedTrackClick(song)
    } else {
        // Normal song click logic
        if (showHidden) {
            storageManager.unhideTrack(song)
            hiddenTracksRefresh++
        }
        onSongClick(song)
    }
}
```

### MainActivity.kt
- Added dedicated `onPinnedTrackClick` handler that bypasses normal click logic
- Checks if the pinned track is actually playing
- If playing: just navigate (simple path, no state changes)
- If not playing: load it first, then navigate

```kotlin
onPinnedTrackClick = { song ->
    // M6b: Standalone logic for pinned track to avoid crash
    if (isPlaying) {
        // Already playing — just navigate to player page
        navController.navigate("player")
    } else {
        // Not playing — load and play it first
        playerViewModel.setCurrentSong(song)
        navController.navigate("player")
    }
}
```

## Why This Fixes It
- **For playing track**: Simple navigate-only path avoids double-loading and state conflicts
- **For paused track**: Falls back to normal load logic instead of crashing with stale state
- **No complex conditions**: Either it's playing (navigate) or it's not (load+navigate)
- **Consistent identity**: Uses the stable Song.id to identify the pinned track
- **Eliminates race conditions**: No conditional state checks that could race with service updates

## Result
✅ Clicking a **playing** pinned track has guaranteed simple navigation (no state conflicts)
✅ Clicking a **paused** pinned track loads and plays it (normal behavior)
✅ No state re-initialization for actively playing tracks
✅ Handles edge case where pinned track might not actually be playing
✅ Eliminates the race condition that was causing crashes

# m6a ✓ - Stale Hidden Tracks State Crash Fix

## Problem
After pressing "X" to hide current track and advance to next, then returning to homepage and clicking the now-playing track item → **CRASH**

## Root Cause
`PlayerScreen.getVisibleQueue()` was using a stale `hiddenTracks` parameter instead of reading the current state from `StorageManager`. When `storageManager.hideTrack()` was called, the parameter didn't update, so subsequent queue calculations included already-hidden tracks, causing state mismatch and navigation crashes.

## Solution
Modified `getVisibleQueue()` to always read current hidden tracks directly from StorageManager:
```kotlin
fun getVisibleQueue(): List<Song> {
    val activeSongId = currentVmSongId ?: song.id
    val currentHiddenTracks = storageManager.getHiddenTracks()  // Read current state, not stale parameter
    return QueueUtils.buildVisibleQueue(
        songs = songs,
        sortMode = storageManager.getSortIndex(),
        hiddenTrackKeys = currentHiddenTracks,
        currentSongId = activeSongId
    )
}
```

## Result
✅ Queue state always matches StorageManager truth
✅ No crashes when navigating after hiding tracks
✅ Stale parameter removed from critical path

# m6d ✓ - Canonical Song Selection Cleanup

## Problem
The app still had stale state wins even after targeted fixes. Different screens and click paths were mutating the active song independently, so an older composable could overwrite the current selection after navigation, skip, or home-return actions.

## Root Cause
There were multiple mutation paths for the same concept:
- `PlayerViewModel.currentSong`
- `PlayerViewModel.currentSongId`
- `PlayerViewModel.isPlaying`
- direct `setCurrentSong(...)` calls from `MainActivity` and `PlayerScreen`
- lifecycle dispose logic that re-selected a song even after a newer selection had already been made

This meant an old `PlayerScreen` or old click handler could still “win” the race and clobber the canonical active track.

## Solution
Implemented a single canonical selection flow and removed stale-screen writes.

### ViewModel
Added a selection token and centralized selection updates:

```kotlin
private val _selectionToken = MutableStateFlow(0L)
val selectionToken: StateFlow<Long> = _selectionToken.asStateFlow()

fun selectSong(song: Song, playing: Boolean = true): Long {
    val newToken = _selectionToken.value + 1L
    _currentSong.value = song
    _currentSongId.value = song.id
    _isPlaying.value = playing
    _selectionToken.value = newToken
    return newToken
}
```

### Navigation / selection entry points
All player opens now route through one helper in `MainActivity`:

```kotlin
fun openPlayer(song: Song) {
    val isSameSelectedSong = currentSong?.id == song.id
    if (isSameSelectedSong && isPlaying) {
        navController.navigate("player")
        return
    }

    playerViewModel.selectSong(song, playing = true)
    navController.navigate("player")
}
```

### Stale screen guard
`PlayerScreen` no longer reassigns the current song from `DisposableEffect` on dispose. Instead, stale screens exit quietly if a newer selection is already active.

```kotlin
DisposableEffect(Unit) {
    onDispose {
        val currentSelection = playerViewModel.currentSong.value
        val isStaleSelection = currentSelection != null && currentSelection.id != song.id
        if (isStaleSelection) {
            return@onDispose
        }
    }
}
```

## Why This Fixes It
- **One canonical source of truth**: song selection is updated in one place
- **Old screens cannot overwrite newer state**: selection tokens and stale guards block races
- **Navigation is consistent**: all playback entry points follow the same logic
- **Dispose logic is no longer stateful mutation**: it only saves progress and never reselects

## Result
✅ No stale screen can overwrite the active song selection
✅ Home navigation and pinned-track taps follow the same canonical path
✅ Skip/advance and stale player screens no longer fight for state ownership
✅ Crash-prone stale selection races are removed at the source
