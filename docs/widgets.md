# Widgets

## The problem with "a generic widget API"

Android has no single API that hands an app someone else's widget content. iOS looks
coherent because WidgetKit owns layout, typography and colour; Android gives you a
pile of independent services, each with its own permission model:

| Data | Android API | Permission | Reality |
|---|---|---|---|
| Battery | `BatteryManager` | none | works everywhere |
| Next alarm | `AlarmManager.getNextAlarmClock()` | none | null when no clock app set one |
| Weather | *none* | none if coordinates are hard-coded | needs a provider (we use Open-Meteo, no key) |
| Notifications | `NotificationListenerService` | user grants "notification access" | powerful and scary; easy to get wrong |
| Now playing | `MediaSessionManager` / notification extras | same grant | ROM-dependent |
| Calendar | `CalendarContract` | `READ_CALENDAR` runtime prompt | not wired yet |
| Location | `LocationManager` | `ACCESS_COARSE/FINE_LOCATION` | deliberately avoided: city is typed in |

So Luma defines **its own contract** and keeps every OEM difference behind it.

## The contract

```kotlin
interface LumaWidget {
    val id: String                       // persistence key
    val label: String                    // settings label
    val permission: WidgetPermission     // NONE | NOTIFICATION_ACCESS | CALENDAR | LOCATION
    fun render(data: WidgetData): WidgetContent?   // null = nothing to show
}
```

Three rules make it safe on a 1-frame-per-minute face:

1. **Null means silence.** A widget with no data returns null and draws nothing.
   There are no placeholders, no "loading" states, no fake dashes on the standby
   screen.
2. **Data is collected only for placed widgets.** `WidgetDataCollector` takes the
   set of enabled ids and never touches a source nobody asked for. No widget, no
   read, no wakeup.
3. **Rendering is primitive.** `WidgetContent` is text plus a glyph enum. The face
   stays one Canvas draw; no Compose, no images, no per-frame allocation beyond a
   couple of strings.

## Data flow

```
settings.widgetPlacements ─┐
                           ├─> WidgetRenderer.tiles(settings, data)
WidgetDataCollector.collect│           │
 (battery / alarm /        │           v
  notifications / weather) └─> ClockView.setWidgets(tiles)
                                        │
                              one draw per minute boundary
```

`refreshWidgets()` runs on the clock's own tick and when settings change - there is
no second timer and no polling loop.

## Placement

Fixed slots (`TOP_RIGHT`, `BOTTOM_RIGHT`, `BOTTOM_LEFT`) rather than absolute
coordinates: the clock keeps the visual centre, and a column of tiles reads as one
block because text is right-aligned against a shared margin.

## Weather

* Open-Meteo, no account, no API key, `current=temperature_2m,weather_code`.
* The user types a city name plus latitude/longitude, so **no location permission**
  is ever requested.
* Cached in `filesDir/weather.tsv` with the coordinates that produced it; a city
  change invalidates the cache immediately.
* Refreshed at most every **30 minutes**, and only while the weather widget is
  placed. The clock never blocks on the network: it reads the cache and a refresh
  lands for a later redraw.

## Notifications

`LumaNotificationListener` reads the app label and title of the newest
notifications, nothing else. It is declared in the manifest but the OS does not
start it until the user grants notification access; the widgets screen shows
"granted / not granted" and offers a shortcut to that setting.

## Deliberately not done yet

* Calendar: needs a runtime permission prompt and a sensible "next event" query;
  the widget exists but always returns null until the source lands.
* Media: approximated from notifications rather than `MediaSessionManager`, which
  needs the same grant and is inconsistent across ROMs.
* No absolute widget positioning, no custom widget sizes, no third-party widgets.
