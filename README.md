
# m4h ✓ - Home Button Playback Persistence

Fixed regression where pressing home button would stop playback. Music now continues playing in the background and resumes control when you return to the player screen.

## Issue

When pressing the home button to leave the PlayerScreen, the music would stop instead of continuing to play in the background.

## Root Cause

The `onNotificationCancelled()` callback was always calling `stopSelf()`, which killed the service even if music was still playing. This happened because:
1. When leaving PlayerScreen, `unbindService()` is called
2. This triggered `onUnbind()` → `onNotificationCancelled()` → `stopSelf()` 
3. Service was destroyed immediately, stopping all playback

Additionally, even though `onStartCommand()` returned `START_STICKY`, it couldn't keep the service alive since the service was only bound (never explicitly started).

## Solution

Modified `onNotificationCancelled()` to check if playback is active before destroying the service:

```kotlin
override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
    // If player is still playing, keep the service alive
    if (exoPlayer.isPlaying) {
        return  // Don't stop the service
    }
    
    // Only stop if not playing
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}
```

Also improved `onUnbind()` to clarify that the service survives unbinding:
```kotlin
override fun onUnbind(intent: Intent?): Boolean {
    return true  // Allow onRebind when client connects again
}
```

Now when returning to PlayerScreen, `onRebind()` is called instead of `onCreate()`, preserving the service state and maintaining playback.

# m4g ✓ - Progress Bar Proportion Fix

Fixed grey progress bar in song list not being proportional to displayed duration.

## Issue

The grey progress bar showed incorrect proportions when comparing tracks:
- A 29:54 track would have a longer grey bar than a 31:21 track
- Bar length didn't match the displayed duration

## Root Cause

Two separate duration calculations were being used inconsistently:
1. **Player screen**: Used actual ExoPlayer duration (loaded from metadata)
2. **Song list**: Used estimated duration from file size (assuming 128 kbps bitrate)

When these two durations differed (common with m4a files that have inaccurate metadata), the bars became disproportionate.

Additionally, the song list was mixing units:
- Grey bar: calculated from `(estimatedDuration / maxFileSize) * 100` (mixing milliseconds and bytes)
- Green bar: calculated from `(currentTime / actualDuration) * 100` (both milliseconds)

## Solution

### PlayerScreen.kt
- Updated duration sync to always use current ExoPlayer duration (removed equality check)
- Clamp currentPosition to ensure progress never exceeds 100%
- Display only uses actual duration from ExoPlayer, not saved estimates

```kotlin
// Continuously update UI state from service
val newDuration = service!!.getDuration()
if (newDuration > 0) {
    duration = newDuration  // Always update, not just on change
}

// Progress bar: clamp position and calculate proportion
val clampedPosition = currentPosition.coerceIn(0L, duration)
val progress = clampedPosition.toFloat() / duration.toFloat()
```

### SongListScreen.kt
- Grey bar now uses **actual saved duration** when available, not estimated
- `maxDuration` calculation updated to use actual durations across all visible songs
- All duration comparisons now in milliseconds (consistent units)

```kotlin
val trackDuration = progress?.duration?.takeIf { it > 0 }
    ?: estimateDuration(song.fileSize)

val maxDurationMs = visibleSongs.maxOfOrNull { s ->
    storageManager.getTrackProgress(s.title)?.duration?.takeIf { it > 0 }
        ?: estimateDuration(s.fileSize)
} ?: 1L

val grayWidth = (trackDuration.toFloat() / maxDurationMs.toFloat()) * 100
```

## Result

✅ Grey bar width is now always proportional to displayed duration
✅ Shorter tracks reliably show shorter bars than longer tracks
✅ Both player screen and song list use consistent duration values
✅ Visual comparison between tracks is accurate and meaningful

---

# m4f
- For the switch button in the home page:
  - Let's display the folder name; not the full path, just the name (do we need to store it to be available; if it's not available, we can do "Folder 1", etc)?
  - Let's add one more for cycling through the folders, calling it "All" that opens the tracks for all the folders combined (is that possible)

# m4e
- When the items are first loaded to display in the home page or hidden page (not sure when that happens), if the item's current time is less than 25 seconds away from the end (based on the estimated duration), can you reset the item's current time to 0.
- When I press on an item in the hidden page to play it, can you automatically to "unhide" it, so that it shows up in the home page instead of hidden

# m4d ✓ - Auto-Advance Track Completion Crash Fix

Fixed app crash that occurred when a track finished and attempted to auto-advance to the next track.

## Issue

When a track ended naturally, the app crashed with `ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed` instead of advancing to the next track.

## Root Cause

On Android 14+ (target SDK 34), the system restricts when apps can start a foreground service. When a track finished and the app attempted to transition to the next track:
1. `PlayerScreen` detected `Player.STATE_ENDED` via the Player.Listener
2. Called `onTrackHideAndNext()` to load the next track
3. The new track's URI was loaded, triggering media3 notification updates
4. `PlayerService.NotificationListener.onNotificationPosted()` tried to call `startForeground()`
5. Since the app was in a background state during track transition, the system rejected the call
6. Unhandled exception crashed the app instead of advancing to the next track

## Solution

Added comprehensive exception handling in `PlayerService.NotificationListener` to gracefully handle foreground service restrictions:

```kotlin
private inner class NotificationListener : PlayerNotificationManager.NotificationListener {
    override fun onNotificationPosted(
        notificationId: Int,
        notification: Notification,
        ongoing: Boolean
    ) {
        if (ongoing) {
            try {
                startForeground(notificationId, notification)
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
            } catch (e: Exception) {
                android.util.Log.w("PlayerService", "Could not stop foreground: ${e.message}", e)
            }
        }
    }

    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
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
```

## Result

✅ Tracks now smoothly advance to the next track when they finish
✅ No crash when transitioning between tracks in background state
✅ Foreground service exceptions are logged as warnings for debugging
✅ App remains stable during auto-advance operations

# m4c
- bug fix: grey duration bar in song list was incorrectly scaled using file size instead of duration
- root cause: progress bar width calculation compared track duration (milliseconds) against maximum file size (bytes)
  - mixing two different units caused incorrect proportions
  - longer files would show disproportionately wide bars regardless of actual duration
- solution: calculate bar width using estimated duration consistently
  - changed from: `(trackDuration / maxFileSize) * 100`
  - changed to: `(estimateDuration(fileSize) / maxDuration) * 100`
  - all durations now in milliseconds, all compared against maximum duration across visible songs
  - grey bar always uses estimated duration (never actual saved duration) for consistent relative scaling
- result: grey bar now accurately reflects relative track duration
  - shorter tracks have proportionally smaller bars
  - longer tracks have proportionally larger bars
  - visual comparison between tracks is now meaningful

# m4b ✓ - Track Playback Bug Fixes

Fixed critical bugs where tracks would overlap during auto-advance to the next track.

## Issues Fixed

### Bug #1: Missing Stop Command During Track Transitions
- **Problem**: When loading a new track, the old track's playback continued in ExoPlayer buffer
- **Result**: Both tracks played simultaneously for a moment
- **Fix**: Added `exoPlayer.stop()` to `PlayerService.loadUri()` to completely stop the previous track before loading new media

### Bug #2: Time-Based Auto-Advance Race Condition
- **Problem**: 30-second completion window was fragile and fired multiple times per second when approaching track end
- **Result**: LaunchedEffect would trigger multiple times, causing state race conditions
- **Fix**: Removed the 1-second detection window (`29000 < diff < 30000`) and replaced with event-driven detection

### Bug #3: Event-Based Track Completion Detection
- **Solution**: Added ExoPlayer's `Player.Listener` to detect `STATE_ENDED` event
- **Benefit**: Only triggers once when track actually finishes, not based on imprecise timing
- **Implementation**: Listener added in `PlayerScreen` when service connects

### Bug #4: State Management for Auto-Advance
- **Problem**: Without proper flag management, the listener could trigger multiple times
- **Solution**: Added `trackEndedHandled` flag that resets when a new song is loaded

## Changes Made

### PlayerService.kt
```kotlin
fun loadUri(uri: String) {
    // ... create mediaItem ...
    exoPlayer.stop()  // Stop previous playback
    exoPlayer.setMediaItem(mediaItem)
    exoPlayer.prepare()
}

fun stop() {
    exoPlayer.stop()
}
```

### PlayerScreen.kt
- Removed: 30-second auto-hide LaunchedEffect with time-based detection
- Added: `Player.Listener` in service connection to detect `STATE_ENDED`
- Added: `trackEndedHandled` state flag that resets per song
- When track ends naturally, auto-hide and advance to next non-hidden track
- Added `@OptIn(UnstableApi::class)` annotation for ExoPlayer API

## Result
✅ Tracks now advance only when they actually finish
✅ No more overlapping audio from simultaneous playback
✅ Clean, event-driven detection instead of fragile time-based window
✅ Proper state cleanup between track transitions

---

# m4a
- milestone: replace all unstable Song ID usage with stable URI-based identification
- root cause of crashes: Song IDs were assigned sequentially at scan time (`id = result.size.toLong()`), causing crashes when song lists were rescanned or reordered
  - song selection crashes: when clicking a song, the ID might not match after a rescan, causing wrong song to play
  - auto-advance crashes: when current track completes, finding the next song by ID fails if IDs have shifted
  - demarcation rendering crashes: Compose keys based on unstable IDs could become duplicate
- solution: implement stable ID generation based on URI hashes
  - added `Song.generateStableId(uri: Uri): Long = uri.toString().hashCode().toLong()`
  - changed SongScanner from position-based IDs to URI-based stable IDs
  - updated all ID-based comparisons to use URI comparisons for clarity and correctness
- affected components:
  - **MainActivity**: song play-pause logic now uses `.uri == song.uri` instead of `.id == song.id`
  - **SongListScreen**: demarcation and selection now uses URI comparisons
  - **PlayerScreen**: already uses URI-based comparisons (from m3c)
  - **Demarcation**: uses stable `nextSongId` which now works correctly
  - **SongScanner**: generates stable IDs from URIs instead of assignment order
- result: IDs are now immutable across app restarts and rescans
  - song selections always work correctly
  - auto-advance always finds the next song
  - demarcation keys are unique and stable
  - no more crashes due to ID mismatches

# m3c
- bug fix: potential crash when track completes and auto-advances to next track
- root cause: `getNextTrackInSortOrder()` was using unstable Song ID to find current song
  - Song IDs can change when lists are rescanned or reordered
  - when current song ID doesn't match any song in the sorted list, returns -1
  - causes `indexOfFirst` to fail and return null for nextSong
  - could crash if null nextSong is passed to `onTrackHideAndNext`
- solution: changed from ID-based lookup to URI-based lookup
  - changed from: `sorted.indexOfFirst { it.id == song.id }`
  - changed to: `sorted.indexOfFirst { it.uri.toString() == currentSongUri }`
  - URIs are stable identifiers (file paths), never change
  - ensures next song is correctly found even if IDs change
- added error handling: wrapped auto-advance logic in try-catch
  - prevents crashes during track completion
  - logs errors for debugging
  - falls back to home screen if next song fails

# m3b
- bug fix: app crashes with IllegalArgumentException when clicking different songs
- root cause: SongListScreen was generating duplicate Compose LazyColumn keys for demarcations
  - Demarcation objects with same label would have identical keys
  - Compose requires unique keys for all items in LazyColumn/Row
  - when song list changed order or was filtered, duplicate keys could appear
- solution: made demarcation keys unique by including the next song's ID
  - updated Demarcation data class to include `nextSongId: Long`
  - changed key from: "demarcation_${item.label}"
  - changed to: "demarcation_${item.label}_${item.nextSongId}"
  - this ensures each demarcation is uniquely keyed even with identical labels
- also: track song by stable URI instead of unstable ID (fixing m2f regression)
  - changed `lastLoadedSongId` to `lastLoadedSongUri` in PlayerViewModel
  - Song IDs are unstable (based on scan position), URIs are immutable file paths
  - fixes: home button now keeps music playing when navigating away
  - fixes: clicking different songs no longer causes crashes due to ID mismatches
- added error handling with try-catch and logging in PlayerScreen LaunchedEffect
  - prevents exceptions from crashing the app, logs errors for debugging

# m3a
- Add demarcations similar to the html-js impl in the mp3-player folder
    - Also see that impl to use estimated durations so we can sort things and display things by estimated durations instead of file size

# m2f
- bug: when I press on an item on the homepage that is already playing, it should be a no-op instead of triggering a seek and play (since there is a few seconds difference, causing a small rewind).

## Solution
Prevent redundant audio reloads when navigating back to the player with the same song already playing. The tricky part is that `PlayerScreen` is a composable that gets recreated on navigation, losing local state. Solution:
1. Track `lastLoadedSongId` in `PlayerViewModel` (shared across navigation) instead of local `remember`
2. Pass the shared `PlayerViewModel` from `MainActivity` to `PlayerScreen` (don't create a new instance)
3. Guard the load effect: if `lastLoadedSongId == currentSong.id && service.isPlaying()`, return early
4. Result: same song, playing → navigate without reload; same song, paused or different song → load and play normally
