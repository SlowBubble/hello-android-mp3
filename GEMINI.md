# Project Rules & Instructions

## Post-Implementation Workflow
- **Always run `./gradlew installDebug` after implementing changes or fixes** to compile and deploy the updated application to the connected Android device or emulator.

## Debugging with Logcat
- When diagnosing on-device issues or unexpected behavior:
  1. Inspect error logs: `adb logcat -d *:E` or `adb logcat -d | grep "com.example.myapp"`.
  2. Verify installation state: ensure `./gradlew installDebug` succeeded.
  3. For clean reproduction: clear buffer with `adb logcat -c` before test run, then fetch logs with `adb logcat -d`.
