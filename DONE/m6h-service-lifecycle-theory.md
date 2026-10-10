# m6h: Service Recreation Theory

## Status
This is a working theory supported by the logs, not proof that Android itself decided to stop the service. We observed the service being destroyed while its player reported that it was playing, followed shortly by a new service instance. The logs do not show exactly what requested that destruction.

## The Simple Version
The app screen and the audio player have different jobs:

- `MainActivity` shows the app screens.
- `PlayerScreen` connects to `PlayerService` while the player screen is visible.
- `PlayerService` owns ExoPlayer, which plays the audio, and a MediaSession, which connects playback to Android controls.

Leaving `PlayerScreen` disconnects that screen from the service. It does not, by itself, stop a service that was separately started. Android's `BIND_AUTO_CREATE` option creates a service only if there is no service instance to connect to.

The suspected problem is that the service instance may have been destroyed for another reason while its player and MediaSession were not released. The audio could then appear to continue even though Android no longer considered that service instance alive. When the app later bound to the player screen, Android could create a replacement service. The replacement tried to use Media3's default empty session ID while the earlier session was still registered, causing the duplicate-ID exception.

## Evidence We Have
One retained log sequence showed:

1. `PlayerService.onUnbind` while `isPlaying=true`.
2. `PlayerService.onDestroy` while `isPlaying=true`.
3. About two seconds later, another `PlayerService.onCreate`, followed by `onBind`.

The earlier implementation returned from `PlayerService.onDestroy()` when playback was active. That return did not cancel Android's destruction; it skipped the resource cleanup. This makes a leftover player/session a plausible explanation for how playback and a later duplicate session could overlap.

## What This Does Not Prove
- `onUnbind` alone does not destroy a started service. It only means the screen disconnected from it.
- A normal navigation from Player to the song list should not destroy `MainActivity` or stop `PlayerService`.
- The logs show that the service was destroyed and recreated, but do not identify who requested the destruction. Before the fix, `MainActivity.onDestroy()` called `stopService()`. The service could also stop itself when its notification was dismissed while playback was not active. The available log excerpt does not prove either event caused this particular destruction.
- Android can kill app processes, but a full process death normally clears in-process Media3 session registrations too. A duplicate ID is more consistent with another session still being registered in the same running process.
- The `isPlaying=true` value was logged as destruction began. By itself, it does not prove how long audible playback continued after that point.

## How the Current Fix Helps
- `MainActivity` no longer calls `stopService()` when the Activity is destroyed. The playback service can outlive the screen and continue audio in the background.
- `PlayerService.onDestroy()` no longer returns early during playback. If Android really destroys the service, the notification manager, MediaSession, and player are released instead of being deliberately left behind.
- If Media3 still reports the duplicate session ID, the service logs the exception and shows a warning toast rather than crashing during creation. It continues without a MediaSession token, so media-session controls may not work until the app is restarted. This is a fallback, not the normal fix.

## What To Check If It Happens Again
Capture logs from just before the failure through the next service creation. The useful events are `PlayerService.onUnbind`, `onDestroy`, `onCreate`, `onStartCommand`, `onBind`, notification cancellation, and Activity destruction. Include the process ID so we can tell whether this was a service restart in the same process or a full process restart. That should help identify what actually triggered the destruction.
