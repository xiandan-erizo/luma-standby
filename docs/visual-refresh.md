# Visual refresh — iOS StandBy alignment (2026-11-19)

## Overview

`ClockView` and `FaceLayout` now support two switchable iOS StandBy-aligned face styles (`FaceStyle.FLOAT` and `FaceStyle.SPLIT`) on pure-black (`#000000`) OLED duo-tone palettes while preserving the strict 1-draw-per-minute standby budget:

1. **`FaceStyle.FLOAT` (Float 叠层大字)**:
   - Heavy, isotropic round-terminal vector numerals (`IosNumerals`) built directly in pixel space inset by half the stroke width (`Paint.Cap.ROUND` / `Paint.Join.ROUND`), eliminating non-uniform X/Y `canvas.scale` distortion.
   - Negative intra-pair kerning (`-0.145 * unitW`) with alternating `accent` / `primary` vertical gradients and a screen-blended (`PorterDuff.Mode.SCREEN`) overlap highlight on dark surfaces so intersecting strokes glow translucently.
   - Proportionate frosted colon circles (`radius = 0.49 * strokeWidth`, subtly tilted) and a compact top-right accent weekday + day header when no widgets are placed.

2. **`FaceStyle.SPLIT` (经典双栏组件)**:
   - Left column: bold rounded digital numerals centered inside a 60-tick rounded-squircle bezel (`IosNumerals.drawSquircleTickRing`) that lights ticks up to the current minute on each minute-boundary redraw (zero extra wakeups).
   - Right column without widgets: hero StandBy calendar lockup (accent-colored `WEEKDAY · MONTH` overline above an oversized rounded day numeral).
   - Right column with widgets: dark-glass squircle complication cards (`#16FFFFFF` fill, hairline stroke) dynamically distributed for 1, 2, or 3 placed tiles:
     - **Battery (`WidgetKind.BATTERY_RING`)**: circular progress arc (`#30D158` green / `#FFD60A` low / `#FF453A` critical, respecting Night Red tint) around a rounded phone silhouette + charging bolt, paired with tabular percentage and status.
     - **Weather (`WidgetKind.WEATHER_HERO`)**: sky-cyan (`#5AC8FA`) location header (`城市 ↗`), multi-tone vector weather icon, oversized temperature reading, and condition label.
     - **Alarm / Media / Notification**: tinted circular glyph badges with colored category/app overlines and primary titles.

3. **Palettes (`PalettePresets` / `ThemePresets`)**:
   - Group S introduces 6 pure-black `#000000` high-contrast duo-tone StandBy presets (`ios-float-blue` default, `ios-coral`, `ios-pink-cyan`, `ios-mint`, `ios-solar`, `ios-night-red`), alongside existing muted dark, near-black, and daylight groups (24 presets total, all legacy IDs preserved).
   - `PreviewScreen` and `ThemesScreen` expose a 2-chip `Float` / `Split` style toggle without altering the existing 4-tab settings shell.
