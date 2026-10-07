# m5g
- sorting seems broken (1 video with 0:10/24:54 comes before one with 0:00/15:09)

# m5f
Bugs:
- I don't see any hidden tracks even though I have a bunch of tracks I have press "X" on (either on home page or player page)
- Sometimes clicking on a track on the home page crashes after I do some other navigations
  - I thought the single source of truth should have avoided these issues
- Sometimes, tracks that are done or from pressing "X" are still in the homepage is that of hidden.

## Root cause
The hidden-state bug was caused by split identity logic, not by a single canonical queue.
- `X` stored a hidden entry in `StorageManager`, but the visible list was rebuilt separately in `QueueUtils.buildVisibleQueue(...)` and in `SongListScreen`.
- Hidden matching was mixed across `song.id`, `song.uri`, `song.title`, and legacy numeric values instead of one consistent identity rule.
- The hidden-page list was also derived from an already-filtered queue, so it could hide the actual hidden set rather than showing the canonical hidden list.
- After navigation or recomposition, stale song objects and recomputed lists could disagree about whether a track was already hidden, so tracks marked hidden via `X` could still appear on Home.

## Fix
- Centralize hidden visibility checks in the same canonical queue helper used by the home and hidden screens.
- Match hidden entries using one consistent track identity rule when comparing persisted hidden keys to current songs.
- Derive the hidden list from the canonical unfiltered queue, then filter to hidden items; derive the home list from the canonical filtered queue.
- Keep legacy hidden keys compatible so older saved values still match correctly.

# m5e - Single-Source-of-Truth Cleanup

This is the follow-up cleanup we should do to prevent these bugs from recurring.

## Why it is still messy
The app still has multiple “current song” and “queue” sources floating around:
- `PlayerViewModel.currentSong`
- the `song` argument passed into `PlayerScreen`
- `currentSongId` in `SongListScreen`
- the locally recomputed sorted/filtered list in the UI
- `lastLoadedSongUri` used as a side-channel to avoid reload loops
- list state that is partially derived in `MainActivity`, partially in `PlayerScreen`, and partially in `SongListScreen`

That means some transitions are correct only because of side effects, delayed disposal, or a lucky recomposition timing.

## Target architecture
We want exactly one canonical source of truth for the active queue:
- one function that builds the visible queue from `songs + sortMode + hiddenTracks`
- one canonical current-song id (or stable song id) as the pinned item
- all UI reads use that queue and id, never local ad hoc matching
- player navigation and list navigation both operate on the same queue output

## Proposed cleanup
1. Centralize queue construction in a single helper, e.g. `buildVisibleQueue(songs, sortMode, hiddenTracks, currentSongId)`.
2. Use `Song.id` as the single identity source for queue state, not a mix of `id`, `uri`, and title comparisons.
3. Store only the active song id in the ViewModel, not a mutable copy of the full song object everywhere.
4. Make `SongListScreen` derive the pinned boundary from the canonical queue, not from `prevSong` comparisons or stale object instances.
5. Make `PlayerScreen` ask the queue for “next” instead of re-deriving its own sorting logic.
6. Remove stale “fix-up” logic in `onDispose` whenever the canonical state already moved forward.

## Benefits
- No stale screen can overwrite the newer song
- The list divider logic is deterministic and tied to the same queue as the player
- Hidden/home page logic uses the same source of truth for visibility, sorting, and pinning
- Bugs become easier to reason about because the UI is no longer re-inventing queue state in multiple places

## Guiding rule
If a state value can be derived from the canonical queue, it should be derived there — not duplicated in each screen.


# m5d ✓ - Demarcation and Hidden Page Controls

## Problem
Two regressions were still showing up in the home/hidden list flow:
- After pressing Next and then returning Home, the divider between the pinned current track and the first track in the sorted list disappeared.
- On the hidden page, when there were zero hidden tracks, the navigation controls disappeared, leaving the page with no way back to Home.

## Root cause
The bug was caused by two parallel state issues:
1. The old `PlayerScreen` could still dispose after a Next action and re-assert the previous song as the active current song, even though the ViewModel had already advanced.
2. The demarcation logic was still relying on stale song-object matching instead of the current visible pinned position in the queue.
3. The hidden-page empty state replaced the normal list UI entirely, so the nav controls were never rendered when the list was empty.

## Fix
- Added a stale-state guard in `PlayerScreen` disposal so an older song cannot overwrite the newer current song after a Next/skip transition.
- Updated the divider check to anchor the boundary to the current visible pinned track in the displayed queue instead of only comparing stale object identity.
- Kept the Home/Hidden navigation controls rendered before the empty-state text, so the hidden page still shows navigation even with zero items.

## Result
✅ The divider remains after Next + Home
✅ Hidden page keeps nav controls even when empty
✅ The stale-screen overwrite is prevented during rapid transitions

# m5c
- Make the rewind and forward 45s buttons 1.5x in width
  - this may also affect the top row of buttons, which is okay (i.e. the X button will be wider also)
- Make the top row of buttons on the player page 1.5x in height

# m5b ✓ - Next Track Control Using Displayed Queue
Add a Next button to the right of  the home button on the bottom, that skips to the next track (respecting the current sorting) without hiding the current track.

## Problem
The app had multiple competing notions of “what comes next”:
- the raw sorted song list
- a hidden-track filtered list
- a stale current-song state
- the pinned current item in the UI
- stale save callbacks that could overwrite the previous song’s persisted progress/duration after the service had already switched to the next track

That caused the next-track action to fail or fall back to the home screen because it wasn’t using the same ordering as the actual displayed list. The final bug was especially subtle: a delayed save from the old song could still run after the service had already loaded the next item, so the previous track inherited the next track’s duration.

## Fix
We unified the next-track logic around the displayed queue, which is the source of truth:
- current song stays pinned at the top of the effective queue
- the remaining queue is the current sort order with hidden tracks removed
- manual Next advances to the next item in that queue
- X follows the same queue logic, but hides the current item before moving on
- the ViewModel current song is updated immediately after the move so the next lookup starts from the new pinned track
- progress is saved for the exact previous song ID before switching media, and stale saves are ignored if they do not match the currently active song ID
- persisted progress/duration are now scoped to the canonical song ID so the old song keeps its own duration instead of inheriting the next track’s metadata

## Result
✅ Next respects the current sort mode
✅ Next does not hide the current track
✅ X and Next use the same queue behavior
✅ The app stays aligned with the visible list instead of drifting across stale state
✅ Saved track durations remain tied to the correct song after skipping forward
✅ Verified after restart and manual testing: Next advances correctly without overwriting the previous track’s progress or duration

# m5a ✓ - Canonical Track Identity Cleanup
Design things more cleanly to try fixing bugs that keep recurring.

## Root cause
The app was using multiple identity sources for the same track at the same time:
- `Song.id` (stable ID)
- `song.uri` (stable file identity)
- `song.title` (display label, not identity)
- transient `currentSong` state in the ViewModel
- persisted hidden/progress keys mixed across these values

That meant one track could appear as "different" depending on which layer was checking it, especially when navigating away and back, auto-advancing, or restoring state after restart.

## Fix
We standardized around one canonical identity: `Song.id`.
- `Song.id` is generated from the song's URI so the same file keeps the same identity across scans and app restarts.
- Persistence and navigation now prefer `song.id` for hidden-track checks, progress lookups, and last-active-track restoration.
- Compatibility fallback reads still accept older URI/title-based values for legacy saved data, but the app treats `Song.id` as the source of truth.

## The recurring bugs this addresses
- Sometimes, when a track is done, it fails to mark itself hidden and go to the next track: fixed by ensuring the current track and the next track are compared using the same canonical identity, rather than a stale title or temporary state.
- When a track is playing and I click homepage, then click the same item again to return to the player: fixed by removing stale identity mismatches and treating a same-URI same-song navigation as a no-op when already playing.
- Restart and restore behavior: last active track restoration now resolves against stable song identity instead of a fragile title string.

## Result
✅ No more drift between playing, hidden, and restored states
✅ Safe same-track re-entry while already playing
✅ Stable auto-advance and hidden-track transitions
✅ Legacy saved data still reads correctly during migration

# m4h ✓ - Home Button Playback Persistence

Fixed regression where pressing home button would stop playback. Music now continues playing in the background and resumes control when you return to the player screen.

## Issue

When pressing the home button to leave the PlayerScreen, the music would stop instead of continuing to play in the background.

## Root Cause

Two related issues:
1. `onNotificationCancelled()` was always calling `stopSelf()`, destroying the service when unbinding
2. `onDestroy()` was releasing the player even while music was playing

## Solution

### PlayerService.kt

Modified `onDestroy()` to check if playback is active before cleaning up:
```kotlin
override fun onDestroy() {
    if (exoPlayer.isPlaying) {
        return  // Don't destroy resources while playing
    }
    // Normal cleanup...
}
```

Modified `onNotificationCancelled()` to keep service alive when music is playing:
```kotlin
override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
    if (exoPlayer.isPlaying) {
        return  // Keep service running
    }
    stopForeground()
    stopSelf()
}
```

Improved `onUnbind()` to clarify the service survives unbinding:
```kotlin
override fun onUnbind(intent: Intent?): Boolean {
    return true  // Allow onRebind when client connects again
}
```

### MainActivity.kt

Added `onDestroy()` to provide explicit app shutdown control:
```kotlin
override fun onDestroy() {
    super.onDestroy()
    stopService(Intent(this, PlayerService::class.java))
}
```

This ensures:
- **Home button**: Service stays alive, `onRebind()` called on return
- **Force-close app**: Service is explicitly stopped via `MainActivity.onDestroy()`

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

# m5g ✓ - Sort Order Fix

## Problem
The app’s shortest/longest sort order was inconsistent and could place a track with a longer displayed duration ahead of a shorter one.

Example reported in the UI:
- `0:10 / 24:54` appearing before `0:00 / 15:09`

## Root cause
The sort path still had a stale fallback in the queue logic:
- the canonical visible queue was using `effectiveDuration(...)` in some paths,
- but older comparisons and fallback branches could still end up evaluating raw file size instead of a track’s true duration,
- and that meant the sorting heuristic was not always comparing the same duration units.

This was effectively leftover “half-migrated” logic: the app had moved to duration-based sorting in principle, but not every comparator was using the same canonical duration source.

## Fix
- Centralized duration choice behind a single helper: `QueueUtils.effectiveDuration(song, savedDuration)`
- The helper prefers:
  1. saved duration from progress
  2. `song.duration`
  3. `estimateDuration(song.fileSize)` as a fallback estimate only
- Updated the visible queue sorting to use that shared duration helper for both shortest and longest orderings.
- Cleaned up duplicate old duration logic in the list UI so there was only one canonical duration interpretation.

## Result
✅ Shortest/longest sorts now compare the same effective duration across the app
✅ A track with a longer actual duration no longer jumps ahead of a shorter one
✅ The fallback estimate is still available when needed, but it is no longer the primary sort source

# m5h - Whole-App Cleanup

This is the broader cleanup we still need across the app, even after fixing the sort regression in m5g.

## Why this still matters
The app works in pieces, but it still has too many places that independently decide:
- what a song is
- whether a track is hidden
- what the active queue is
- what “current song” means
- what duration to use for sorting or display
- whether a screen should be treated as stale or authoritative

That duplication is why bugs keep reappearing in slightly different forms: hidden tracks, sort order, steals of stale state, and navigation races.

## The cleanup we want

### 1. One canonical queue builder
There should be exactly one place that builds the effective list shown on Home and Hidden.
- `buildVisibleQueue(...)` should be the canonical source of truth
- home page and hidden page should not each re-derive their own filtered/sorted list
- sorting, hidden filtering, and pinned current song should all be derived from the same queue output

### 2. One canonical song identity
The app should treat one stable identity as authoritative for all song matching.
- prefer `Song.id` for queue and persisted state
- use `song.uri` only as the stable underlying source when needed for migration or compatibility
  - Actually, don't worry about migration or compatibility; but id should be uri, so just use id unless you see another compelling reason to use uri
- stop mixing `id`, `uri`, `title`, and transient screen state when checking whether two items are the same track

### 3. One canonical current-song state
The app should not keep multiple copies of “what is playing/selected.”
- current song should be represented by a single canonical id or stable reference
- the player screen, list screen, view model, and service should all read from the same source
- stale screen-local state should never overwrite a newer canonical state after navigation or skip events

### 4. One canonical duration policy
Every part of the app should agree on how duration is chosen.
- real metadata duration first
- saved duration second
- file-size estimate only as a fallback when necessary
- no more ad hoc “sometimes use file size, sometimes use progress, sometimes use actual song.duration” logic

### 5. One canonical persistence boundary
Hidden tracks, progress, and resumed playback should all be keyed and read through a single consistent rule.
- keep backward-compatible migration reads for old keys
- write only the canonical format going forward
- never let a UI layer silently treat legacy keys as separate identity sources

### 6. One canonical navigation contract
Screen-to-screen transitions should be based on the same queue state, not local recomputation.
- “next” should be derived from the displayed queue
- “home” should not invent a second list
- “same song re-entry” should be a no-op when already playing the same canonical track instead of reloading media

### 7. One canonical service boundary
The player service should own playback state; UI should not keep competing state machines.
- service is responsible for loading media, playback position, and state changes
- UI reads service state and queue state, but should not hold separate copies that may drift
- time-based and stale callback logic should be replaced by event-driven state whenever possible

## What is still duplicated today
These are the main places where the app still has duplicated truth:
- `SongListScreen` builds part of the visible list and partially re-derives sort or hidden state
- `QueueUtils` builds the effective queue, but some callers still validate lists or matching separately
- `StorageManager` stores hidden/progress state using legacy compatibility logic, plus callers still re-check identities ad hoc
- `PlayerScreen` and `PlayerViewModel` both keep notions of the active song and navigation state
- duration logic is still visible in multiple places depending on whether the code path is list UI, queue helper, or player display

## Target design
The app should behave like this:
1. Build one canonical queue from `songs + sortMode + hiddenTracks + currentSongId`
2. Resolve all identity checks against the canonical song identity
3. Render Home and Hidden from that queue output only
4. Use the canonical queue to determine next/prev state and divider boundaries
5. Persist only canonical keys and migrate old values on read
6. Keep the player service as the single playback source of truth

## Guiding rule
If a value can be derived from the canonical queue or canonical song state, it should not be recreated in a screen, a view model, or a storage layer.

That is the real cleanup we still need across the whole app.

