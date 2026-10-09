# m6i: Keep Active Playback in the Foreground

## Status
Implemented and installed on the connected Pixel 9. The build succeeded with
`./gradlew installDebug`. Background playback has not yet been reproduced or
verified on-device with the initial change. A later reproduction showed the
service being destroyed during in-app navigation while playback was active.

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
  can leave foreground mode. Notification cancellation no longer calls
  `stopSelf()`: cancellation can coincide with a transient playback-state
  change, and clearing the service's started state could make it get destroyed
  as soon as the player screen unbinds.

The service's `mediaPlayback` foreground-service type and required manifest
permissions were already configured in `app/src/main/AndroidManifest.xml`.

## Limits and Follow-Up
The reproduced log showed `startForeground` succeeding, followed by the player
screen unbinding and `PlayerService.onDestroy()` while ExoPlayer reported that
it was playing. The service teardown then released ExoPlayer. The log did not
identify who had cleared the service's started state; `stopSelf()` on
notification cancellation was a code path that could do so, and is now removed.
Foreground promotion can still fail; `PlayerService` logs those failures. If
notification cancellation occurs during active playback before any notification
has been cached, the service logs that it cannot restore foreground mode.

Test by starting playback, switching to another app, and leaving the device
screen off and unplugged long enough to cover the reported failure window. If
playback stops, capture service/player and Activity lifecycle logs to determine
whether the service was stopped, foreground promotion failed, or the player
itself paused. The diagnostic logs include process and service identity,
foreground status, player state transitions, and activity lifecycle events;
they intentionally omit the current track URI and title.

Capture a reproduction with:

```sh
adb logcat -c
# Reproduce the issue, then:
adb logcat -d -v threadtime | grep -E 'PlayerService|MainActivity|PlayerScreen|ActivityManager|ForegroundService|AudioFocus'
```
