# Widgets: current implementation

Luma renders its own small information tiles alongside the clock. This is not Android launcher-widget hosting: Android does provide AppWidgetHost/AppWidgetManager for third-party RemoteViews widgets, but their external layouts and update policies cannot guarantee a consistent appearance or our redraw budget. Native Luma tiles use Android public data APIs with one controlled Canvas renderer.

## Visible features

- Battery and next alarm: public SDK sources, no runtime permission.
- Weather: Open-Meteo, city selected explicitly; offline presets or custom coordinates. Network access starts only when weather is placed and StandbyActivity is RESUMED. Provider terms/attribution: https://open-meteo.com/en/terms and https://open-meteo.com/. No location permission or key.
- Media: MediaSessionManager active sessions using the exact authorized NotificationListener component. Only PlaybackState.STATE_PLAYING with real metadata is shown. No guessing from application names.
- Notifications: explicitly enabled listener, newest sanitized title/app label. Private/unknown visibility reveals only the app name; secret, ongoing, group-summary and own notifications are omitted. No body text, no persistent notification cache. Both grant and keyguard state are checked before publishing.
- Calendar is not implemented and is not shown as a selectable option.

## Layout

A large clock occupies the left region, with date and up to three information tiles in a separate right rail. The optional bottom-left tile occupies a non-overlapping footer. Icons are hand-drawn vector paths; long text is ellipsized rather than shrunk unreadably or overlapped. All face elements share the slow OLED drift. Missing data produces no fake placeholder.

Settings offer one local preview, localized names, bounded placement chips, six offline city presets and an expandable custom location form. This preview intentionally uses local battery/alarm data only: no weather request or private notification content on settings pages.

## Weather cache / lifecycle

WeatherStore has no detached scope or periodic service. update is a child of the visible Activity lifecycle; pause/destroy cancels in-flight IO and disconnects the HTTP request. Snapshot getters never touch disk. Versioned, atomic disk cache is keyed by exact coordinates; changed coordinates cannot expose the previous city's reading. One refresh attempt per 30 minutes (including failed requests), with in-flight deduplication. Readings older than 30 minutes are marked cached and readings older than 24 hours are hidden. No requests in Ultra Low Power.

Notification snapshots are event-fed. One initial query on explicit opt-in or reconnection seeds the bounded memory cache; no repeated activeNotifications polling. SCREEN_OFF and Activity pause clear already-rendered sensitive tiles and notification memory. Media uses real sessions at redraw/data events. Revocation, missing service and SecurityException fail closed.

## Verified / pending (2026-11-19)

- JVM tests cover cache serialization, stale/backoff rules, numeric validation, notification filtering, media gating, and collision-free face geometry. No live notification text is committed to the repository.
- Xiaomi 17: revised icon/rail layout, real media title/artist and privacy-sanitized app-name notification rendering observed on screen. Bundled blue/pink display font and Chinese settings observed.
- Beijing selection path exercised; live weather response/cache success was not confirmed before device disconnected. It remains pending, not reported as working end-to-end.
- Latest uniform-glyph rounding and bounded landscape preview adjustment built/tested locally; fresh on-device screenshot remains pending after disconnection.
- 2026-11-19 second device pass (reconnected): blue/pink face, rail tiles, real media and sanitized notification tile confirmed on screen; notification tile no longer repeats the app label; date aligned to the rail's left edge; a private-notification and a live Beijing weather reading (3°, 晴) both rendered.
- Live weather end-to-end is now confirmed: selecting Beijing wrote `files/weather-v1.json` with `{"lat":39.9042,"lon":116.4074,"temperatureC":3,"condition":"CLEAR","city":"北京"}` and the tile shows `北京 · 晴`.
- Two real bugs found only by looking at the device: the preset wrote an empty city string, and a new city did not trigger an immediate fetch. Both fixed.
- Power savings and panel 1 Hz remain unproven. No DreamService preview/dock/AOD experiments were performed.