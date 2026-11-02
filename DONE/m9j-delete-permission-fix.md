# m9j: X Button Delete Permission Fix

## Problem
The X button on the homepage was not actually deleting video files. The track would disappear from the UI (appearing as if deleted), but the file remained on disk. Interestingly, the X button on the player page seemed to work, but only after re-opening the folder.

## Root Cause
The app was using Storage Access Framework (SAF) to access files in user-selected folders. When persisting folder access permissions, it was only requesting **READ** permission, not **WRITE** permission.

```kotlin
// OLD CODE (FilePickerScreen.kt, MainActivity.kt)
context.contentResolver.takePersistableUriPermission(
    it,
    Intent.FLAG_GRANT_READ_URI_PERMISSION  // ❌ READ-only
)
```

When attempting to delete files via `DocumentsContract.deleteDocument()`, the call would fail silently because the app lacked **WRITE** permission on that URI.

### Why It Appeared to Work
The deletion code sequence is:
1. `hideTrack()` — adds song to hidden tracks list in SharedPreferences
2. `deleteSong()` → `deleteTrack()` — attempts to delete file via `DocumentsContract.deleteDocument()` ❌ (fails due to no WRITE permission)
3. `hiddenTracksRefresh++` — triggers UI rebuild

The song disappeared from the UI because it was marked as hidden in the app's data, masking the fact that the file deletion had failed. The exception from `deleteDocument()` was caught and silently logged, never reaching the UI.

### Why Re-opening Folder Made Delete Work
When you re-selected the folder:
1. File picker launched again
2. `takePersistableUriPermission()` was called again on the same URI
3. Android granted fresh permissions (possibly with additional system prompts or context)
4. The permission state changed, allowing writes to succeed

This was essentially a workaround that reset the permission context without actually fixing the underlying issue.

## Solution
Add the **WRITE** flag when taking persistent URI permissions:

### Changes Made

**FilePickerScreen.kt (line 38):**
```kotlin
// OLD
Intent.FLAG_GRANT_READ_URI_PERMISSION

// NEW
Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
```

**MainActivity.kt (line 54):**
```kotlin
// OLD
android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION

// NEW
android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
```

## How It Works Now
1. User selects folder via file picker
2. App takes **both READ and WRITE** persistent permissions on that URI
3. `DocumentsContract.deleteDocument()` now has the necessary permissions
4. Files are actually deleted from disk (not just hidden in UI)
5. No need to re-open folder after deletion

## Affected Code Paths
All X button deletions use the same underlying mechanism:
- **X button on homepage** (SongListScreen.kt) → `onDeleteClick` → `hideTrack()` + `deleteSong()`
- **X button on hidden page** (SongListScreen.kt) → `onDeleteClick` → `hideTrack()` + `deleteSong()`
- **X button on player page** (PlayerScreen.kt) → `moveToNextTrack(hideCurrent=true)` → `hideTrack()` + `deleteSong()`
- **Delete All button on hidden page** (SongListScreen.kt) → `deleteHiddenTracks()` → `deleteTrack()`

All now have proper WRITE permissions.

## User Action Required
The next time you select a folder in the app, it will persist with WRITE permission. Existing folders selected before this fix will retain their READ-only permission state. To update an existing folder:
- Navigate to the folder picker
- Re-select your music folder
- The app will now take READ+WRITE permissions

After that, deletions will work immediately without needing to re-open the folder.
