# Visual refresh — 2026-11-19

Reference direction: the owner's blue/pink StandBy screenshots, with tall heavy numerals and compact right-hand date/status information. Not a redistribution of Apple's proprietary font.

Default Atlantic Rose: background #25596A, digits #FFCAD9, secondary #F9D9E3, icon/accent #FFF1F4. These are explicit sRGB theme values, not a nits measurement. Colored surfaces remain optional; Black Amber remains available for OLED-focused use.

DISPLAY uses the real bundled Anton font under SIL OFL 1.1. The complete license is included both in APK raw resources and ANTON-OFL.txt. Numerals use uniform glyph scale inside fixed tabular cells, controlled vertical expansion, rounded path corners and separately drawn circular colon dots. A slender 1 must not be widened to fill its whole cell. Labels use a restrained Android sans-serif medium rather than the headline face. This is a licensed approximation of the reference style, not a pixel-identical Apple font.

FaceLayout defines separate clock/date/tile bounds. No external fake weather, demo notifications or fabricated media state appear in production. Seven other system font options remain for compatibility (including the bundled DISPLAY option).

Known limits: bundled-font rounding needs fresh visual review after the last device disconnect; it is built and JVM-tested, not yet screenshot-accepted. The main preview does not automatically collect remote or private widget data. Calendar and third-party widget hosting are out of scope.
