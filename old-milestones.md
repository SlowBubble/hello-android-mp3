

# m1j ✓

- Add a button on the upper right (similar to the 1x button), that is an "X", it should move the current track to hidden and play the next track in the sorted list
- When a track finishes playing, it should move the current track to hidden and play the next track in the sorted list


# m1i ✓
- Let's have the rewind and forward 7s button go to above the progress bar but still inside the buttons container

# m1h ✓
- let's remove the prev and next chapter and the chapter 1/10 thing.
- Move the home button to just below the progress bar above all the other buttons as a wide button
- Move the 1x speed button to where the home button was before
- Finally, add the rewind and forward 45s buttons to also span the left and right side of the panel above the progress bar

# m1g ✓
- Let's move the hidden button to the bottom after the items, so folder and sort buttons have more space
# m1g
- Let's move the hidden button to the bottom after the items, so folder and sort buttons have more space

# m1f ✓
For the hidden page:
- no need to have folder and sort buttons
- Show All should say Home instead
- For each item, let's have an X button to the right of the restore that actually delete the file from the phone

# m1e
Fix these

- Currently, pressing X doesn't hide the track from the page
- Same for hidden, pressing the button doesn't restore the track to the home page

# m1d
- put sort button on the right and the hidden button in the middle and add a left button to reselect folder

# m1c
- migrate html-app to android (see features.md)

# m1b
- Implement a simple mp3 player that allows selecting a folder and loads all the mp3 files in it to the page as a list of itmes
- When you click on an item, it goes to a player page that starts playing the item
  - Have a play/pause button
  - a fastforward/rewind 9seconds and 45 deconds buttons

# m1a
- Hello world

# MP3 Player - UI/UX Features

**Context**: The HTML/JS MP3 player in #[[file:html-app/mp3-player/]] is complete and fully functional. This document defines the features we want to replicate when building the native Android/Kotlin version of the app.

**Reference Implementation**: #[[file:html-app/mp3-player/index.html]] | #[[file:html-app/mp3-player/main.js]]

When in doubt about feature details or current behavior, refer to the HTML and JavaScript files above—they are the source of truth for how the app currently works and what we're implementing in Kotlin.


## m2 - Session Persistence & Advanced Playback
Track progress persistence, remembering where users left off, and advanced playback controls.

### Session Persistence
- **Resume Position**: Automatically restore playback position when re-opening a track
  - Save current time every 5 seconds while playing
  - When returning to a track, resume within 5 seconds of the last saved position
  - Skip final 30 seconds to mark track as "completed"
- **Last Played Track**: Remember which track was last active and restore it on app load
- **Hidden/Completed Tracks**: Persist list of hidden tracks across sessions
- **Sort Preference**: Remember user's last selected sort mode (size, date)

### Listening Statistics
- **Play Count**: Track how many times each track has been played
- **First Listened Date**: Record when a user first started playing a track
- **Last Played Date**: Show when a track was last played
- **Total Listening Time**: Accumulate total time spent listening to each track
- **Stats Display**: Show abbreviated listening stats on playlist (e.g., "3x • 45m total")

### Advanced Playback Controls
- **Playback Rate Cycling**: Cycle through rates (1x → 1.15x → 1.25x → 1.35x → 1x)
  - Display current rate on player interface
  - Persist rate choice across sessions
- **Chapter Navigation**: Divide track into 10 equal chapters (0/10, 1/10, ..., 9/10)
  - Prev Chapter: Jump to current chapter start or previous chapter boundary
  - Next Chapter: Jump to next chapter boundary
  - Handle boundaries gracefully (can't go before start or past end)

### Media Session Integration
- **Native Controls**: Integrate with browser's Media Session API for hardware controls
- **Lock Screen Display**: Show track title, artist, and artwork on lock screen
- **Remote Playback Control**: Support hardware buttons (play/pause, skip, seek) on supported devices
- **Background Playback**: Allow audio to continue playing when app is backgrounded

---

## m1 - Core Player Experience
Essential playlist and playback functionality.

### Home Page
- **Folder Selection**: 
  - Single button to trigger native file picker for folder selection
  - Accept only MP3 files from selected folder
  - Display user-friendly error if no MP3 files found
  - Update UI after successful folder load

- **Playlist Display**:
  - Show all loaded tracks as clickable list items
  - Display track name prominently
  - Show file size or duration for each track
  - Render proportional progress bars based on file size (longest file = 100% width)
  - Highlight currently active track with visual distinction (green highlight/border)
  - Smooth hover/active states for interactivity

- **Track Organization**:
  - **Sorting Modes**: Cycle between sort options
    - Sort by Duration (Shortest)
    - Sort by Duration (Longest)
    - Sort by Date Modified (Newest)
    - Sort by Date Modified (Oldest)
  - **Active Track Pinning**: Always show currently playing track at top of sorted list
  - **Smart Grouping**: Divide tracks into sections with visual dividers
    - Duration-based sorting: Group by duration ranges (0-6min, 6-11min, 11-21min, etc.)
    - Date-based sorting: Group by date + time of day (e.g., "Mon, Oct 03 AM")

- **Track Management**:
  - **Hide/Remove Tracks**: Button to hide completed tracks from list
  - **Restore Hidden Tracks**: Toggle view to show all hidden tracks with restore button
  - **Auto-Hide on Completion**: Automatically hide tracks when they finish playing
  - **Manual Load More**: "Load more" button to paginate through large libraries

### Player Page
- **Now Playing Display**:
  - Large, centered track title
  - Clear indication of playback status

- **Playback Controls**:
  - **Play/Pause Button**: Central control with large touch target
    - Single tap anywhere on track display area toggles play/pause
  - **Seek Controls**:
    - 7-second rewind button
    - 7-second forward button
    - Interactive progress bar (click to seek)
  - **Extended Skip Controls** (visible below progress):
    - 45-second rewind
    - 45-second forward
  - **Time Display**: Show current time and total duration (HH:MM:SS format)

- **Gesture Controls**:
  - **Single Tap**: Toggle play/pause
  - **Double Tap Left Half**: Rewind 45 seconds
  - **Double Tap Right Half**: Forward 45 seconds
  - **Progress Bar Click**: Seek to position

- **Navigation**:
  - **Back Button**: Return to home page (fixed header)
  - **Hide & Next**: Auto-hide current track and advance to next non-hidden track
  - **Preserve Playback**: Audio continues playing even when navigating back to home

- **Visual Design**:
  - **Color Transitions**: Background color changes based on playback state
    - Purple gradient when idle
    - Red gradient when player active
    - Green gradient when actively playing
  - **Large Touch Targets**: All buttons sized for comfortable mobile use
  - **Fixed Controls**: Bottom control bar remains accessible when scrolling

---

## Design Principles

### Mobile-First
- Design optimized for touch interaction on mobile devices
- Generous button sizes (48px minimum)
- Vertical scrolling as primary navigation
- No hover states on touch devices (use active states instead)

### Accessibility
- High contrast text on colored backgrounds
- Clear visual feedback for all interactions
- Descriptive labels and tooltips
- Semantic HTML structure

### Performance
- Lazy load playlist items as user scrolls
- Efficient progress bar rendering (updates throttled to ~1/sec)
- Smooth animations and transitions
- Preload metadata for quick interactions

### Discoverability
- Consistent icon usage across controls
- Intuitive button labels and tooltips
- Clear visual hierarchy guiding user through workflow
- Progressive disclosure of advanced features (sort, chapters, playback rate)
