# SurgeMedia icon — "Surge Wave"

The app icon is a bold angular waveform that surges upward to the right, trailed
by a thin magenta echo line. It is cyberpunk-neon on the app's own dark palette
(`#00E5FF` cyan, `#FF3D81` magenta, `#0B0E17` background — the same values as
`composeApp/.../ui/theme/Color.kt`).

Everything is authored from **one polyline** in `build_icon.py`, so the macOS
`.icns` and the Android VectorDrawable cannot drift apart.

## Layout

```
concepts/            six AI-generated directions explored before the final mark
  concept-1-surge-wave.jpg   <- the direction that won
  ...
build_icon.py        the single source of truth: geometry -> SVG + VectorDrawable
shapes.py            superellipse / circle / rounded-rect path data
render_macos.sh      SVG -> macOS .icns (Apple grid, native render per size)
render_android.sh    SVG -> Android adaptive previews (circle / squircle / themed)
mockups.sh           renders every concept with real platform masks
review-sheet.sh      side-by-side sheet of all concepts at dock sizes
final-sheet.sh       verification sheet for the finished mark
```

## Rebuilding

```bash
cd design/icons
python3 build_icon.py        # SVG sources + Android VectorDrawables
./render_macos.sh            # out/SurgeMedia.icns
./render_android.sh          # out/android/*.png previews
./final-sheet.sh             # final-sheet.png
```

`render_macos.sh` renders every `.icns` size natively through Chrome, so the
neon bloom is not destroyed by downscaling. The generated `.icns` is committed
to `composeApp/src/desktopMain/resources/icons/SurgeMedia.icns`; the `out/`
folder is a build artefact.

## Platform rules this artwork respects

**macOS** — the squircle occupies 824/1024 of the canvas (Apple's grid) with the
artwork at 92% of the squircle width. The shape is a true superellipse
(`n = 5`), not a rounded rectangle.

**Android** — the adaptive icon lives on a 108dp canvas. The launcher masks a
72dp circle and guarantees a 66dp safe zone, so the artwork is scaled to fit
just inside the 72dp mask (`build_icon.py` computes this from the real
bounding box). `ic_launcher_foreground.xml` draws the foreground,
`ic_launcher_background.xml` the gradient ground, and
`ic_launcher_monochrome.xml` the single-colour silhouette that Android 13+
themed icons need.

## Why the art is filter-free

VectorDrawable cannot do blur. The neon bloom is therefore faked with layered
translucent strokes of decreasing width (`GLOW_STEPS`), which renders
identically on Android. The macOS master SVG *does* use a real `feGaussianBlur`
filter, because there is no such restriction there.

## Rejected directions

`concepts/` keeps the five alternatives that were explored: hex portal, S
ribbon, torrent swirl, pulse diamond, glitch stack. They are kept as a record
of the direction, not as live assets.
