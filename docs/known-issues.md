# Known issues

## HyperOS 4 / Android 17: selecting a third-party screen saver can restart the UI

**Status:** open, platform-side. Reproduced twice on Xiaomi 17 (HyperOS OS4.0.6.0,
Android 17 / API 37, build CP2A.260605.016), 2026-11-16.

### Symptom

Selecting *Luma Standby* in Settings → Display → Screen saver, the phone appears to
restart (screen goes black, UI comes back after ~90 s). The kernel does not reboot:
`/proc/uptime` keeps counting, `sys.boot.reason` stays `shutdown,userrequested`,
and the recovery shows up as `sys.runtime.reboot.reason.last =
je#system_server#…unhandled`, i.e. a system_server runtime restart.

### Evidence

Two records in `system_server_crash` dropbox entries (18:53:29 and 18:54:56),
identical stack:

```
java.lang.NullPointerException: Attempt to read from field 'int com.android.server.wm.Task.mTaskId'
on a null object reference in method
'android.app.IAppTask com.android.server.wm.ActivityTaskManagerService.startDreamActivityLocked(
    android.content.Intent, int, int, com.android.server.wm.WindowProcessController,
    android.content.pm.ApplicationInfo)'
    at ActivityTaskManagerService.startDreamActivityLocked(ActivityTaskManagerService.java:1903)
    at ActivityTaskManagerService.startDreamActivityInternal(ActivityTaskManagerService.java:1954)
    at ActivityTaskManagerService$LocalService.startDreamActivity(ActivityTaskManagerService.java:7784)
    at DreamManagerService$BinderService.lambda$startDreamActivity$0(DreamManagerService.java:2353)
```

No entry from this app: the crash happens in system_server, in the *app-initiated*
dream path (`DreamManager.startDream(... appTask ...)`), which is what the Settings
screen-saver preview uses. Luma never calls `DreamManager`; it only declares a
`DreamService`. The activity/task that came in null belongs to the caller.

### Mitigation (already applied on the test device)

```bash
adb shell settings delete secure screensaver_components
adb shell settings put secure screensaver_enabled 0
```

That stops the dream from being started automatically, and the phone returns to a
normal state. The crash was observed while starting Luma through the screen-saver path. Its stack is in system_server, but that alone does not prove all other dreams fail or exclude an app-triggered platform interaction. Other apps were not tested.

### Follow-up test (2026-11-16 19:03-19:05, approved by the owner)

Triggered sleep with the screensaver selected and logcat streaming. Result:

* `system_server` pid unchanged (17488 before / after) — **no crash on the
  system-initiated path**.
* The dream never started: `mShowingDream=false` in both runs, even with
  `screensaver_activate_on_sleep=1`.
* `dumpsys dreams` explains why:

```
mCurrentDream=DreamRecord{ name=ComponentInfo{com.android.systemui/com.android.keyguard.doze.MiuiDozeService},
                           isPreview=false, canDoze=true, isDozing=true, dozeScreenState=1 }
mDreamsActivatedOnDockByDefault=true
mDreamsActivatedOnChargeByDefault=false
mDreamsActivatedOnPosturedByDefault=false
mIsDocked=false
```

The observed current dream was SystemUI's MiuiDozeService. The dump contains default settings, not proof that third-party dreams can only run when docked. Charging-trigger preferences exist, and dock is not equivalent to ordinary wireless charging. These sleep-key tests did not demonstrate Luma starting; explicit screen-off is not the same test as an idle timeout. The earlier universal claim was too strong. Do not use it as a compatibility conclusion.

### Conclusion

| Path | Result |
|---|---|
| Settings → Screen saver preview (app-initiated dream with app task) | system_server NPE, UI restart (twice) |
| Screen off while charging (sleep key experiment) | no crash observed; Luma did not start; not proof of universal blocking |
| Docked (system-initiated) | not testable without a physical dock; same unknown as above |

Treat third-party DreamService integration as **blocked on HyperOS 4** until Xiaomi
fixes the preview NPE. Do not select Luma as the screen saver on a dock/wireless
stand either: that path is untested and may hit the same crash. The in-app standby
surface is unaffected.

### Product impact

Do not advertise "works as a screen saver on HyperOS 4" yet. In-app standby
(`StandbyActivity`) is unaffected: it never touches the dream path.
## 2026-11-19 development boundary

DreamService remains unregistered and its preview shortcut was removed. No new dream/dock/AOD experiment was performed. The visual refresh fixes an API-35 View.setRequestedFrameRate call lacking a minSdk-31 guard. Display-rate and brightness observations remain system-reported snapshots, not panel-state or nits measurements; earlier 60 Hz and 0.10-cap observations cannot establish permanent OEM-wide limits.
