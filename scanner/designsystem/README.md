# :designsystem (v2, lane C1)

The Compose theme and the components C-1 … C-17 of `scanner/design/ui-v2/SPEC.md`, plus every UI string (G-8),
the shadows (G-5), the motion tokens (G-7), the concentric radii (G-13a) and the formatting helpers.

- **Generated, never copied (G-1).** `generateDesignSystem` (in `build.gradle.kts`) writes, at build time:
  - `Tokens.kt` from `design/ui-v2/tokens.json` (light and dark colors, shadows, lengths, numbers, durations,
    easing, fonts, and the expression tokens as functions of a `CssEnv`);
  - `Css.kt` from `design/ui-v2/components.css`: every declaration that resolves from literals and tokens alone,
    by selector and property (`Css.Row.padding`, `Css.Seg_Button.height`);
  - `Glyphs.kt` from `app.js` `ICONS` plus `icons/extra-icons.json` (the §3.4 category glyphs that ICONS lacks,
    the Filter checkbox mark and the tablet Partial chevron).

  `TokensTest` proves every `tokens.json` entry is generated with its value and that hand-written code has no hex,
  dp, sp or em literal (only `SpecValues.kt`, for the one SPEC value that neither CSS file carries, and the gallery).
- **Fonts.** Inter 4.1 variable (`res/font/inter_variable.ttf`, OFL, see `fonts/`), with `opsz` set to the size as
  browsers do; numbers use `tabular()`.
- **Shadows.** `Modifier.tokenShadow` uses Compose's blur-radius `dropShadow`, converting the CSS blur to the
  BlurMaskFilter radius so both have σ = blur / 2.
- **Gallery.** `gallery/Gallery.kt` mirrors the prototype's `#gallery` cell by cell. `GalleryScreenshotTest`
  captures every cell, light and dark, at 2.625× into `src/test/screenshots/` and compares against them
  (`mise run //scanner:designsystem-check`; `mise run //scanner:designsystem-record` rewrites them).
- **Prototype comparison.** `tools/prototype-shots.mjs` renders the prototype's `#gallery` in headless Chromium and
  crops each cell under the same file names, for side-by-side review.
