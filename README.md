

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
