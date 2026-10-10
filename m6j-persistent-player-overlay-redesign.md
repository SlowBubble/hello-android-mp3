# m6j: Persistent Player with Home and Hidden Overlays

## Status
Design proposal only; no implementation changes are included in this document.

## Goal
Keep the player screen and its connection to `PlayerService` alive while the
user views the song list (Home) or Hidden tracks. Show those destinations as
layers over the player instead of navigating away and disposing the player
screen.

This targets the in-app Home navigation case. It is not a replacement for
keeping `PlayerService` correctly foregrounded, and it cannot prevent Android
from stopping the app process or interrupting playback for other reasons.

## Expected User-Facing and MediaSession Impact

- The goal is to preserve the existing appearance of the player, Home/song list,
  and Hidden screens. This is a lifecycle and presentation-structure change,
  not a visual redesign. Match the current controls, layout, and transitions;
  specifically verify Back behavior and overlay dismissal.
- Keeping the player host and its service binding alive during in-app screen
  changes should avoid unnecessarily destroying and recreating `PlayerService`
  and its MediaSession just to show Home or Hidden. The MediaSession is owned
  by the service, so it should remain the same instance while that service
  remains alive. Android or other lifecycle events can still destroy the
  service/process and require a new session.

## Why Consider This
The captured reproduction showed this sequence:

1. The in-player Home button was clicked.
2. `PlayerScreen` was disposed and unbound from `PlayerService`.
3. `PlayerService.onDestroy()` ran while ExoPlayer reported playback active.
4. Service teardown released ExoPlayer, stopping audio.

Foreground promotion had logged success immediately before navigation. The log
proved that service destruction stopped the player, but did not identify what
requested service destruction. Keeping the player screen mounted would avoid
the unbind caused by this navigation path, so it is a reasonable mitigation to
test. It does not explain or independently fix every possible reason the
service might be destroyed.

## Proposed Structure
Refactor the app shell so the selected track's player host is composed once
above or behind the navigable content:

- A persistent player host owns the single `PlayerScreen` instance and its
  service binding, player listeners, playback initialization, and progress
  saving.
- Home/song-list and Hidden content are rendered as overlay layers above that
  host. Showing or hiding an overlay changes visibility/state; it does not
  navigate away from or recreate the player host.
- The player host remains mounted while an overlay is visible, even if its
  controls are visually covered. Ensure covered controls cannot receive input.
- When no track is selected, retain the existing start/list experience without
  creating a player screen that has no song.
- Keep file picking and other destinations that are not part of this overlay
  redesign on their existing navigation flow unless implementation work
  identifies a reason to include them.

In the current implementation, `NavigationHost` uses separate `start`,
`songList`, and `player` destinations, and the player destination creates
`PlayerScreen`. The in-player Home action calls `onBack`, which pops that
destination. The redesign should move ownership of the player host out of a
destination that is popped, then represent Home and Hidden as overlay
visibility/state rather than destinations that dispose the player.

## State and Navigation Behavior

- Keep the selected song, playback state, and overlay selection in the existing
  `PlayerViewModel` where practical; avoid introducing a second source of truth.
- Define explicit actions for showing Home, showing Hidden, dismissing an
  overlay, and returning to the visible player controls.
- Preserve the current Home action's meaning (show the song list), and ensure
  selecting a song from an overlay updates the existing player rather than
  creating a second player or service binding.
- Define Android/system Back behavior deliberately: dismiss an open overlay
  first; otherwise preserve the app's existing back behavior.
- Ensure overlay transitions do not change the current media item, reset
  playback position, or trigger the player's load-and-play effect again for the
  already loaded URI.
- Keep progress persistence active when appropriate, but avoid duplicate
  periodic save loops or listeners when overlays appear and disappear.

## Lifecycle and Service Ownership

- There must be exactly one active binding to `PlayerService` for the persistent
  player host, not one binding per overlay.
- Navigation to Home or Hidden must not call `unbindService`, release
  ExoPlayer, or remove the player listener.
- Activity recreation and process death still require normal service rebinding
  and player-state restoration; a persistent Compose host only lasts as long as
  its Activity composition.
- Preserve the foreground-service behavior documented in
  `m6i-foreground-playback-mitigation.md`. The overlay design is not a substitute
  for foreground playback while the app is backgrounded.
- Continue logging service identity, binding/unbinding, player state, and
  Activity lifecycle during validation so service destruction can be
  distinguished from player pause or audio-focus loss.

## Risks and Questions for Implementation

- A fully mounted player UI behind an overlay may continue UI polling and
  recomposition. Keep work bounded and ensure hidden controls are inaccessible.
- A root overlay can change status-bar, back-stack, and transition behavior.
  Match the existing Home and Hidden navigation UX intentionally.
- Separate screen-specific effects (such as track-end auto-advance) from the
  persistent host carefully so they are not registered more than once.
- Do not conflate the app's in-player Home button with Android's system Home
  button. The reproduced log captured the in-app button; system Home testing is
  still needed for background playback.

## Acceptance Checks

1. Start a track, open the in-app Home/song list, and verify audio continues
   without `PlayerScreen` disposal, service unbind, or `PlayerService.onDestroy`.
2. Open and close Hidden while playback is active; verify the same player and
   position continue without duplicate bindings, listeners, or load operations.
3. Select another track from Home and verify the existing player switches
   tracks once and continues playback.
4. Exercise Back with each overlay open and closed and verify the intended
   navigation behavior.
5. Press Android system Home and test with the screen off. Verify the foreground
   service independently keeps playback alive; investigate any failure using
   the service and player logs.
