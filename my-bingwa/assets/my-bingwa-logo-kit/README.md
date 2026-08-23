# My Bingwa Logo Asset Kit

This pack is generated from the approved source logo without regenerating or
redrawing its symbol.

## Important source correction

The approved PNG is the full My Bingwa lock-up on a white canvas. The production
assets retain the original black outline, green type, orange accents and soft
edge shading, while removing only the white canvas where Android needs a
transparent foreground.

The approved source is a raster PNG, not a true vector file. The 1254px source
is more than sufficient for all Android, Play Store and web sizes in this pack,
but it cannot provide unlimited print-scale enlargement. Do not label a
raster-in-SVG wrapper as a vector logo. If large-format printing is required
later, redraw or professionally trace the approved symbol into genuine vector
paths.

## Folder contents

### `source`

- `new-logo.png` — untouched approved source artwork.

### `brand`

- Transparent full-colour marks at 64, 128, 256, 512 and 1024px.
- Black monochrome 1024px mark.
- White monochrome 1024px mark.

Use the transparent mark inside the app and for the header lock-up. The approved
artwork already contains the My Bingwa wordmark, so do not add separate text to
it.

### `android`

- `my-bingwa-launcher-master-1024.png` — legacy launcher master.
- `my-bingwa-play-store-512.png` — Google Play listing icon.
- `my-bingwa-splash-mark-512.png` — Android splash-screen mark.
- `mipmap-*` — legacy launcher and round-launcher PNGs:
  - mdpi: 48×48
  - hdpi: 72×72
  - xhdpi: 96×96
  - xxhdpi: 144×144
  - xxxhdpi: 192×192
- `drawable-*` — monochrome Android notification icons:
  - mdpi: 24×24
  - hdpi: 36×36
  - xhdpi: 48×48
  - xxhdpi: 72×72
  - xxxhdpi: 96×96
- `adaptive`:
  - 432×432 transparent foreground.
  - 432×432 white background.
  - 432×432 monochrome layer.
  - Adaptive icon XML templates.

The adaptive foreground keeps all meaningful artwork inside the central safe
zone so Android launcher masks do not crop the figure or orbit.

### `web`

- Favicons: 16×16, 32×32 and 48×48 PNG.
- `favicon.ico` containing 16, 32 and 48px versions.
- Apple touch icon: 180×180.
- Android Chrome icons: 192×192 and 512×512.
- Microsoft tile: 150×150.

### `preview`

- Visual comparison of the launcher icon and transparent mark on light and dark
  surfaces.

## Android integration

When the Android project exists:

1. Place the legacy PNGs in their matching `mipmap-*` directories.
2. Place notification PNGs in their matching `drawable-*` directories.
3. Convert or place the adaptive foreground/background/monochrome layers in
   `drawable`.
4. Place `ic_launcher.xml` and `ic_launcher_round.xml` in
   `mipmap-anydpi-v26`.
5. Reference `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` from the
   manifest.
6. Use `ic_stat_my_bingwa` as the notification small icon.

## Restrictions

- Do not stretch the mark.
- Do not recolour individual parts.
- Do not add text inside the launcher icon.
- Do not place the coloured mark directly on visually busy imagery.
- Do not use the full-colour logo as an Android notification small icon.
- Do not rebuild the adaptive icon from the original black-cornered source.
