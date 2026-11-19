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
  "background": "#25596A",
  "primary": "#FFCAD9",
  "secondary": "#E8E9E7",
  "accent": "#E9A8C0",
  "gradient": false,
  "gradientEnd": "#25596A",
  "font": "DISPLAY",
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
| `atlantic-rose` | #25596A | #FFCAD9 | default, ocean + sakura |
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

DISPLAY is bundled Anton under SIL OFL 1.1, not a system family alias. Shared tabular cells preserve narrow digits, circular colon dots and a tall silhouette. Six system alternatives remain. Labels use sans-serif medium. The license is shipped in the APK and [ANTON-OFL](ANTON-OFL.txt). This approximates the reference, not Apple's proprietary font.

## Gradient

`gradient = true` draws a top-left → bottom-right linear gradient from
`background` to `gradientEnd`. For OLED, a gradient is a compromise: it lights
more pixels than solid black. Keep gradients for Showcase.

## Palettes and typefaces (2026-11-17)

Colour and typography are separate choices, the way the reference UI splits
`颜色 / 字体`:

* **12 palettes** live in `PalettePresets`: Ocean Rose, Midnight Lavender, Forest
  Mist, Burgundy, Black Amber, Sage Ink, Ink Cream, Slate Blue, Terracotta, Plum
  Noir, Sand, Arctic. Two of them (Sage Ink, Arctic) are light surfaces with dark
  digits, which the old five-theme set had none of.
* **7 typefaces** live in `FontChoice`: DISPLAY (Anton), Rounded, Condensed (bold
  `sans-serif-condensed`, the tall narrow digits from the reference), Sans, Serif,
  Mono, Light.
* Picking a palette keeps the current font; picking a typeface keeps the current
  colours. Either action on a built-in theme forks it into a custom theme
  (`custom-<seed>`) so the built-ins stay immutable.
* The fine-grained editor (hex fields, gradient, per-theme seconds) is still there
  behind `复制并编辑`.

## Adding a theme

1. Append to `ThemePresets.all` (built-in) or add to `settings.customThemes`
   (user).
2. Keep `id` stable — it is the persistence key and the import/export handle.
3. Verify contrast: primary on background should clear ~4.5:1 for a *large*
   glyph, which is easy; secondary text at 0.045 × height needs ~3:1 minimum.

## Remaining work

Long-duration OLED drift observation, live weather cache verification, and final screenshot acceptance after USB disconnection. Theme clipboard/SAF editing/export exists; strict import validation still has gaps for unknown fields/types and is outside this visual refresh.
