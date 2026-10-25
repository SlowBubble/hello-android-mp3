
# m9f
- Fixed deletion behavior: All X buttons (homepage, hidden page, and player page) now properly hide tracks from the UI immediately before deleting the file. This prevents "ghost tracks" that were deleted but still appeared in the UI, which was causing m9a and m9b to malfunction.
- Bookmarked tracks are preserved: When a track finishes naturally, bookmarked tracks are only hidden (not deleted) so they can be replayed later.
- Behavior summary:
  - **X button on homepage**: Hides track from UI, then deletes the file from disk
  - **X button on hidden page**: Hides track from UI, then deletes the file from disk
  - **X button on player page**: Hides track from UI, then deletes the file from disk, then advances to next track
  - **Track finishes naturally (bookmarked)**: Hides track from UI only (preserves file for replay)
  - **Track finishes naturally (not bookmarked)**: Hides track from UI, then deletes the file from disk, then advances to next track (or next folder per m9b)
  - **↺ button (hidden page)**: Restores track to homepage (unhides only, no deletion)
  - All deletions now ensure the track disappears from the UI immediately and won't reappear until the folder is re-scanned

# m9e
- Volume control redesign: Removed the +10% button and separate volume display. The volume button now cycles through preset levels (100% → 75% → 50% → 25%) and displays the current volume (🔊 XX%).
- Folder switcher in player: Added a folder switch button (📁 FolderName) that replaces the +10% button position. When clicked:
  - Stays on the player page (doesn't navigate to homepage)
  - Switches to the next folder with MP3 files
  - **Per-folder resumption**: Remembers the last playing track for each folder. When switching back to a folder you were in before (e.g., A → B → A), it resumes the exact track and position from before
  - If visiting a folder for the first time, plays the first track
  - The button text updates reactively to show the current folder name
- Technical implementation: Added per-folder last-track tracking via `setFolderLastTrack()` and `getFolderLastTrackSong()`. Each folder independently tracks which track was playing when you left it, combined with existing per-track position storage via `TrackProgress`

# m9d
- Remove the sort button in the player page and replace it with a random checkbox to decide if the Next button will follow the order set in the homepage or to randomly select the next track from the tracks that are not playing currently.

# m9c
- Sometimes, the track is paused/played from a different place instead of tapping on the player page, but the page doesn't switch from red to green (and vice versa). Propose options to fix this.

# m9b
- when the final non-hidden track of a folder is done (either from pressing x or next in the player page and the track reaches the end), switch to the next folder and play the first track from that folder.

# m9a
- for the x button in the home page and player page, let's just remove the file instead of moving it to hidden
