# Debugging Android Implementation Failures

## On-Device Implementation Failures

When the user states that a fix or implementation didn't work on their physical phone:

1. **Do not immediately rewrite code or ask for manually copied errors.**

2. **Immediately check recent error logs using:**
   ```bash
   adb logcat -d *:E
   ```

3. **Filter for app-specific logs or crashes:**
   ```bash
   adb logcat -d | grep "com.example.myapp"
   ```
   (Replace with your actual package name)

4. **Verify whether the app was properly recompiled and installed:**
   ```bash
   ./gradlew installDebug
   ```
   Check the output for successful installation.

5. **Diagnose the root cause using the retrieved terminal logs before offering the next fix.**

### Common Issues to Look For

- **ClassNotFoundException/NoSuchMethodError**: Build cache issues, try `./gradlew clean installDebug`
- **SecurityException**: Missing permissions in AndroidManifest.xml
- **NullPointerException**: State management issues, check if data is properly loaded before UI rendering
- **IllegalStateException**: Lifecycle-related issues, verify service/activity state transitions

### When Logs Are Not Clear

If the logs don't reveal the issue:
- Ask the user to reproduce the issue while logs are being captured
- Check for native crashes using `adb logcat | grep "DEBUG"`
- Verify ABI compatibility (arm64-v8a, x86, etc.) for the device