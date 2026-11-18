# M10b: Folder Cycling Improvements

## Overview
Improved folder cycling to skip folders with no visible MP3 files and enhanced folder display names.

## Changes Implemented

### 1. Skip Empty Folders When Cycling
Updated all folder cycling functions to skip folders that have no non-hidden MP3 files:

- **`switchToNextFolder()`**: When cycling through folders from the song list overlay, now filters out hidden tracks to check if a folder has any visible songs before selecting it.

- **`switchToNextFolderFromPlayer()`**: When switching folders from the player screen, now checks for visible songs and resumes the first visible track (or last played track if returning to a folder).

- **`removeCurrentFolderAndCycle()`**: When removing a folder, now finds the next folder with visible songs.

- **`onQueueEnded()`**: When auto-advancing to the next folder after queue completion, now finds folders with visible songs.

### 2. Enhanced Folder Display Names

Updated `StorageManager.getCurrentFolderDisplayName()` to:

- **Strip "Pod " prefix**: If a folder name starts with "Pod ", that prefix is removed from the display name.
  - Example: "Pod 001" → "001"
  - Example: "Pod My Audiobook" → "My Audiobook"

- **Add numeric index**: Displays a 1-indexed position like "1: ", "2: ", etc.
  - Index only counts folders that have non-hidden MP3 files
  - Folders with no visible songs are skipped in the count
  - Example: "1: My First Podcast", "2: My Second Podcast"

### Implementation Details

**Folder Visibility Check**:
```kotlin
val hiddenTracks = storageManager.getHiddenTracks().toSet()
val allSongs = scanner.scanFolderForMp3s(folderUri)
val visibleSongs = allSongs.filter { song -> !hiddenTracks.contains(song.title) }
```

**Display Name Logic**:
- Added `folderHasVisibleSongs()` helper method in StorageManager
- Counts only folders with visible songs when computing the display index
- Strips "Pod " prefix from folder names
- Shows format: "index: folder name" (e.g., "1: My Podcast")

## User Experience

### Before
- Cycling through folders would show folders with no visible MP3s, displaying "No MP3 files found"
- Folder names showed "Pod 001", "Pod 002", etc.
- Difficult to know position in the folder sequence

### After
- Cycling automatically skips folders with no visible MP3s
- Folder names show as "1: 001", "2: 002", etc.
- Clear indication of which folder you're on in the sequence
- Only folders with playable content are shown and counted

## Technical Notes

- The visibility check considers the hidden tracks list to determine if a folder has playable content
- Scanning folders to check visibility happens during cycling operations, not on every render
- The index computation scans folders sequentially but is acceptable since it only runs when displaying the button
- All songs (including hidden ones) are still loaded into the player for consistency with the hide/unhide feature


## Testing Checklist

### Test Scenario 1: Folder Cycling Skips Empty Folders
1. Add 3 folders: Folder A (has songs), Folder B (no songs), Folder C (has songs)
2. Start on Folder A
3. Click "Switch" button
4. **Expected**: Should jump directly to Folder C, skipping Folder B
5. Click "Switch" again
6. **Expected**: Should cycle back to Folder A

### Test Scenario 2: Folder Display Names
1. Add folders named "Pod 001", "Pod 002", "Pod My Audiobook"
2. View the "Switch" button text
3. **Expected**: Should show:
   - "1: 001" (for Pod 001)
   - "2: 002" (for Pod 002)  
   - "3: My Audiobook" (for Pod My Audiobook)

### Test Scenario 3: Index Counts Only Visible Folders
1. Add 3 folders with songs
2. Hide all songs in the middle folder (Folder 2)
3. View Folder 1: **Expected** display "1: [name]"
4. Cycle to next folder: **Expected** Folder 3 displays "2: [name]" (skipping hidden Folder 2)

### Test Scenario 4: Folder Cycling from Player
1. Play a song from Folder 1
2. Tap the Switch button on the player screen
3. **Expected**: Should switch to next folder with visible songs
4. **Expected**: Should start playing a song (resume last or play first)

### Test Scenario 5: Auto-Advance After Queue End
1. Play through all songs in a folder
2. **Expected**: When the last song ends, should automatically advance to next folder with visible songs
3. **Expected**: Should start playing the first visible song from the new folder

### Test Scenario 6: Remove Folder and Cycle
1. Have 3 folders (A with songs, B empty, C with songs)
2. Remove Folder A
3. **Expected**: Should automatically cycle to Folder C (skipping empty Folder B)
4. **Expected**: Current playback should continue uninterrupted
