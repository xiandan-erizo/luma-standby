# Themes

## Files

* `domain/model/LumaTheme.kt` — the serialisable data class + colour resolution.
* `domain/theme/ThemePresets.kt` — the five launch themes.
* `domain/theme/ThemeRepository.kt` — DataStore persistence and, later, import/export.

## JSON shape

```json
{
  "id": "atlantic-rose",
  "name": "Atlantic Rose",
  "background": "#31596B",
  "primary": "#F4C9D5",
  "secondary": "#E8E9E7",
  "accent": "#E9A8C0",
  "gradient": false,
  "gradientEnd": "#31596B",
  "font": "ROUNDED",
  "showSeconds": false,
  "brightnessProfile": "STANDARD",
  "builtIn": true
}
```

`gradientEnd`, `font`, `showSeconds`, `brightnessProfile`, `builtIn` are optional on
import: `ignoreUnknownKeys = true` and serialiser defaults mean an older or
hand-written file still loads.

## The five presets

| id | Background | Primary | Intent |
|---|---|---|---|
| `atlantic-rose` | #31596B | #F4C9D5 | default, ocean + sakura |
| `midnight-lavender` | #24283B | #D9CDED | quiet, techy |
| `forest-mist` | #354A40 | #E8E1CE | natural, warm |
| `burgundy` | #492E3B | #F0CDD2 | vintage, textured |
| `black-amber` | #050607 | #D9B97E | OLED-first night / power theme |

`black-amber` uses `#050607` rather than pure black so the face has a hair of
depth on an OLED; `isPureBlack` exists if a future "true black" toggle is added.

## Colour maths

Everything is sRGB hex. `Color.parseColor` accepts `#RRGGBB` and `#AARRGGBB`;
invalid strings fall back per-slot (`parseColorOr`), so one bad field cannot
brick the clock. Alpha is applied only for the exit hint
(`withAlpha(secondary, 0.45)`).

## Typography

* Families come from the platform: `sans-serif-rounded`, `sans-serif`, `serif`,
  `monospace`. No TTF is bundled, so no font licence ships with the app and no
  1–2 MB of glyphs sits in the APK.
* Time uses `fontFeatureSettings = "tnum"` (tabular figures) so the minute roll
  cannot nudge horizontal layout.
* Size is computed from view height and *then* clamped to 78% of width, so a
  Ultra-Low-Power 3-glyph face and a Showcase `HH:mm:ss` face both fit without a
  second layout pass.

## Gradient

`gradient = true` draws a top-left → bottom-right linear gradient from
`background` to `gradientEnd`. For OLED, a gradient is a compromise: it lights
more pixels than solid black. Keep gradients for Showcase.

## Adding a theme

1. Append to `ThemePresets.all` (built-in) or add to `settings.customThemes`
   (user).
2. Keep `id` stable — it is the persistence key and the import/export handle.
3. Verify contrast: primary on background should clear ~4.5:1 for a *large*
   glyph, which is easy; secondary text at 0.045 × height needs ~3:1 minimum.

## Not yet implemented

Editor UI for arbitrary colours/fonts, theme save/delete/rename, share as file,
auto day/night theme switching (`autoNight` exists in settings but is currently
only a stored flag — wire it in Phase 2).
