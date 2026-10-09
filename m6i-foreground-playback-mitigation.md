# m6i: Keep Active Playback in the Foreground

## Status
Implemented and installed on the connected Pixel 9. The build succeeded with
`./gradlew installDebug`. Background playback has not yet been reproduced or
verified on-device with this change.

## The Change
`PlayerService` now keeps its foreground-service status aligned with active
playback:

- It treats playback as active when ExoPlayer has `playWhenReady` set and is
  neither idle nor ended. This includes buffering while playback is requested.
- When the playback notification is posted, the service enters foreground mode
  if the notification is ongoing or playback is active.
- If the notification is cancelled while playback is active, the service uses
  its last posted notification to restore foreground mode instead of
  intentionally continuing in the background without foreground status.
- When playback is not active and the notification is not ongoing, the service
  can leave foreground mode. If a cancelled notification finds playback
  inactive, the existing behavior stops the service.

The service's `mediaPlayback` foreground-service type and required manifest
permissions were already configured in `app/src/main/AndroidManifest.xml`.

## Limits and Follow-Up
This is a mitigation for Android background-service limits, not proof of the
cause of the reported audio stopping after about a minute. Foreground promotion
can still fail; `PlayerService` logs those failures. If notification
cancellation occurs during active playback before any notification has been
cached, the service logs that it cannot restore foreground mode.

Test by starting playback, switching to another app, and leaving the device
screen off and unplugged long enough to cover the reported failure window. If
playback stops, capture `PlayerService` and system foreground-service logs to
determine whether the service was stopped, foreground promotion failed, or the
player itself paused.
