

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

# m2e
- Let's also add home button to the top of the player page where "Now Playing" (remove this text also) is sitting

# m2d
- Given that the buttons are no longer sticky, I think it's better to move the folder/hidden button row to the top above the switch/sort button row

# m2c
- when I select a folder, remember that as 1 of the previously opened folder
- impl the switch button to cycle thru the previously opened folder and display the tracks
  - remember what is the currently selected folder so next time the app re-opens, it will keep using this selected folder

# m2b
- Let's remove the MP3 Player title to save space
- Let's move the folder button to the lower part next to the left of the hidden button
- Add a Switch button where the current Folder button is and have it be no-op for now
- Can you make the top rows of buttons non-sticky also?

# m2a
- is it possible to show play/pause in the lock screen if our player is/has been recently active?
