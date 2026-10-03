Read README.md for implementation request and always run ./gradlew installDebug after implementing

## Debugging with Logcat
For proactive debugging when issues arise:
1. Run `adb logcat -c` to clear the buffer
2. Prompt user to trigger/test the feature
3. Wait for user to confirm they've completed testing
4. Fetch logs with `adb logcat -d` and analyze
5. This avoids timing issues and lets the user test at their own pace