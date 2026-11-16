# Re-enabling the DreamService (other ROMs)

On HyperOS 4 the screen-saver path is blocked by a platform bug: the Settings
preview crashes system_server with an NPE inside
`ActivityTaskManagerService.startDreamActivityLocked`. Registration is therefore
commented out of `app/src/main/AndroidManifest.xml` in this tree. The
implementation is intact and correct against the public DreamService API.

## What is kept

* `app/src/main/java/app/luma/standby/dream/LumaDreamService.kt`
* `app/src/main/res/xml/luma_dream.xml` (settingsActivity pointer)
* `R.string.dream_label`

## How to re-enable

Paste this block back inside `<application>` in `app/src/main/AndroidManifest.xml`,
then rebuild:

```xml
<service
    android:name=".dream.LumaDreamService"
    android:exported="true"
    android:label="@string/dream_label"
    android:permission="android.permission.BIND_DREAM_SERVICE">

    <intent-filter>
        <action android:name="android.service.dreams.DreamService" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <meta-data
        android:name="android.service.dream"
        android:resource="@xml/luma_dream" />
</service>
```

## Verify before trusting it

1. `adb shell dumpsys package <app> | grep android.service.dreams.DreamService` must list it.
2. Select it in Settings > Display > Screen saver on a non-Xiaomi device, or a
   HyperOS build newer than CP2A.260605.016.
3. Watch `adb logcat -b crash` for `FATAL EXCEPTION IN SYSTEM PROCESS`.
4. Only then flip the README entry from blocked to verified.

Do this on a spare device if possible: the failure mode is a UI restart, not an
app crash.