# Visual refresh — iOS 26/27 StandBy & Liquid Glass (2026-11-20)

## Overview

`ClockView`, `FaceLayout`, `LumaFonts`, and `WidgetIcons` have been redesigned around four switchable iOS 26/27 StandBy faces while keeping the strict 1-draw-per-minute Canvas budget (zero Compose or animation loop on the standby surface):

1. **`FaceStyle.ANALOG` (经典模拟表盘 + 对称双栏大卡)**:
   - Centered 50/50 symmetric dual-card landscape stage tailored to 2.18:1 OLED screens (`2656×1220`).
   - Left slot: sculpted dark circular watch dial with Liquid Glass specular bezel, 60 precision radial ticks, 12 optically centered `Nunito ExtraBold` numerals, sculpted rounded baton hour/minute hands with depth shadow, and Apple Watch-style orange center hub (second hand drawn only when seconds are explicitly enabled).
   - Right slot: flush-aligned square Calendar Hero card (oversized day numeral + accent weekday/month + full 7-column current-month calendar grid with today circled) when no widgets are placed, or 1 full-size Hero Square / 2 Medium / 3 Compact Liquid Glass widget cards.

2. **`FaceStyle.SPLIT` (双栏堆叠数字 + 对称大卡)**:
   - Left slot: dark Liquid Glass squircle card with stacked 2-row bold rounded numerals (`Nunito ExtraBold`, luminous white hours over vibrant accent minutes), matched pixel-for-pixel in size and alignment to the right slot.

3. **`FaceStyle.FLOAT` (全屏 Float 叠层大字)**:
   - Always edge-to-edge fullscreen landscape numerals using bundled `Fredoka Bold` (SIL OFL 1.1), replacing the former monoline stroke paths and fixing the `Paint.alpha` leak.
   - Organic alternating vertical stagger and micro-tilt, negative intra-pair overlap, rich duo-tone vertical gradients, subtle `SCREEN` overlap shimmer, and a sleek top bar with date + optional compact Liquid Glass widget pills.

4. **`FaceStyle.SOLAR` (iOS 26/27 液态玻璃 + Solar 氛围光)**:
   - Layered radial sunset/aurora horizon glow over OLED `#000000` black.
   - Oversized horizontal Liquid Glass numerals with frosted translucent body fill and a pure exterior specular refraction rim (interior punched out via `DST_OUT` to eliminate TTF sub-contour overlap artifacts).

5. **Widget Cards & Gestures**:
   - Weather cards feature condition- and time-aware iOS sky gradients, soft sun/moon glow, filled vector location arrows, and multi-tone SF-Symbols-inspired filled weather glyphs.
   - Battery cards feature thick sweep-gradient circular progress rings with centered phone silhouette and charging bolt.
   - Night Red mode (`BrightnessProfile.NIGHT`) unifies every face, glow, and card onto pure `#000000` black + deep crimson `#FF3B30`.
   - Vertical swipe up/down in `StandbyActivity` cycles all four faces live with a transient Liquid Glass indicator pill; double-tap exits cleanly.

## Bundled Fonts & Licenses

- `res/font/nunito_extrabold.ttf` & `res/font/nunito_bold.ttf` — SIL OFL 1.1 (`docs/NUNITO-OFL.txt`, `res/raw/nunito_ofl.txt`)
- `res/font/fredoka_bold.ttf` — SIL OFL 1.1 (`docs/FREDOKA-OFL.txt`, `res/raw/fredoka_ofl.txt`)
- `res/font/oswald.ttf` — SIL OFL 1.1 (`docs/OSWALD-OFL.txt`, `res/raw/oswald_ofl.txt`)
