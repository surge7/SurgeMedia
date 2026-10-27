#!/usr/bin/env python3
"""Author the SurgeMedia "Surge Wave" icon from a single geometric source.

The mark is a bold angular waveform that surges upward to the right, trailed
by a thin magenta echo line. Everything is derived from one polyline so the
macOS .icns and the Android VectorDrawable cannot drift apart.

Two glow treatments are produced from the same geometry:

  * "bloom"  - real SVG blur filters, used for the macOS master artwork.
  * "stepped" - layered translucent strokes with no filters, which is the
                only technique VectorDrawable supports.

Outputs
  design/icons/out/surge-wave.svg        macOS master (bloom)
  design/icons/out/surge-wave-fg.svg     artwork only, stepped (transparent)
  design/icons/out/surge-wave-mono.svg   artwork only, single colour
  design/icons/out/surge-wave.mvg        ImageMagick drawing commands
  composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml
  composeApp/src/androidMain/res/drawable/ic_launcher_background.xml
  composeApp/src/androidMain/res/drawable/ic_launcher_monochrome.xml
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent.parent
OUT = ROOT / "out"
ANDROID_RES = PROJECT / "composeApp/src/androidMain/res/drawable"

# --- brand palette (mirrors composeApp .../ui/theme/Color.kt) -------------
CYAN = "#00E5FF"
MAGENTA = "#FF3D81"
BACKGROUND = "#0B0E17"
BACKGROUND_INNER = "#141A2E"

# --- design geometry -----------------------------------------------------
# The waveform polyline. Valleys ride the baseline, peaks step upward and the
# last stroke ramps to the highest point: the surge.
WAVE = [
    (144, 753),
    (249, 753),
    (389, 453),
    (529, 753),
    (624, 753),
    (764, 373),
    (844, 243),
]

STROKE_CYAN = 92.0          # main waveform weight
STROKE_MAGENTA = 26.0       # thin trailing echo
MAGENTA_OFFSET = (58, 46)   # echo trails down-right

# Stepped glow for filter-less renderers: widest/softest first.
GLOW_STEPS = [
    (210.0, 0.05),
    (160.0, 0.08),
    (128.0, 0.12),
    (104.0, 0.18),
]

GRID = 1024.0

# --- platform placement --------------------------------------------------
# Android adaptive icons live on a 108dp canvas: the launcher masks a 72dp
# circle and guarantees a 66dp safe zone. Scale the artwork so its extremes
# sit just inside the 72dp mask.
ANDROID_VIEWPORT = 108.0
ANDROID_MASK_DIAMETER = 72.0
ANDROID_SAFE_DIAMETER = 66.0


def offset_points(points, dx, dy):
    return [(x + dx, y + dy) for x, y in points]


def path_data(points) -> str:
    head = f"M{points[0][0]:g},{points[0][1]:g}"
    return head + "".join(f"L{x:g},{y:g}" for x, y in points[1:])


def combined_bbox():
    """Bounding box of cyan core + magenta echo, in design units."""
    xs, ys = [], []
    for pts, stroke in ((WAVE, STROKE_CYAN), (offset_points(WAVE, *MAGENTA_OFFSET), STROKE_MAGENTA)):
        h = stroke / 2.0
        xs += [v for p in pts for v in (p[0] - h, p[0] + h)]
        ys += [v for p in pts for v in (p[1] - h, p[1] + h)]
    return min(xs), min(ys), max(xs), max(ys)


def centre_shift():
    """Translation that centres the combined artwork in the design grid."""
    x0, y0, x1, y1 = combined_bbox()
    return (GRID / 2.0 - (x0 + x1) / 2.0, GRID / 2.0 - (y0 + y1) / 2.0)


SHIFT_X, SHIFT_Y = centre_shift()
WAVE = [(x + SHIFT_X, y + SHIFT_Y) for x, y in WAVE]


def fmt(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def to_viewport(points, scale, vw):
    c = GRID / 2.0
    return [((x - c) * scale + vw / 2.0, (y - c) * scale + vw / 2.0) for x, y in points]


def android_scale() -> float:
    """Scale that puts the artwork's extremes just inside the 72dp mask."""
    x0, y0, x1, y1 = combined_bbox()
    half_diag = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5 / 2.0
    return (ANDROID_MASK_DIAMETER / 2.0 - 1.0) / half_diag


# --- SVG builders --------------------------------------------------------

def stroke_el(points, width, colour, opacity=None, indent="  ") -> str:
    op = "" if opacity is None else f' stroke-opacity="{fmt(opacity)}"'
    return (
        f'{indent}<path d="{path_data(points)}" fill="none" stroke="{colour}" '
        f'stroke-width="{fmt(width)}" stroke-linecap="round" '
        f'stroke-linejoin="round"{op}/>\n'
    )


def build_master_svg() -> str:
    """macOS master: gradient ground, real bloom around both strokes."""
    mag = offset_points(WAVE, *MAGENTA_OFFSET)
    s = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(GRID)}" height="{int(GRID)}" '
        f'viewBox="0 0 {int(GRID)} {int(GRID)}">\n'
        '  <defs>\n'
        '    <radialGradient id="bg" cx="50%" cy="47%" r="76%">\n'
        f'      <stop offset="0%" stop-color="{BACKGROUND_INNER}"/>\n'
        f'      <stop offset="100%" stop-color="{BACKGROUND}"/>\n'
        '    </radialGradient>\n'
        '    <filter id="bloom" x="-60%" y="-60%" width="220%" height="220%" '
        'color-interpolation-filters="sRGB">\n'
        '      <feGaussianBlur stdDeviation="30" result="wide"/>\n'
        '      <feGaussianBlur stdDeviation="11" in="SourceGraphic" result="tight"/>\n'
        '      <feMerge>\n'
        '        <feMergeNode in="wide"/>\n'
        '        <feMergeNode in="tight"/>\n'
        '        <feMergeNode in="SourceGraphic"/>\n'
        '      </feMerge>\n'
        '    </filter>\n'
        '  </defs>\n'
        f'  <rect width="{int(GRID)}" height="{int(GRID)}" fill="url(#bg)"/>\n'
    )
    # Magenta echo first so the cyan core sits in front of it.
    s += '  <g filter="url(#bloom)">\n'
    s += stroke_el(mag, STROKE_MAGENTA, MAGENTA, indent="    ")
    s += "  </g>\n"
    s += '  <g filter="url(#bloom)">\n'
    s += stroke_el(WAVE, STROKE_CYAN, CYAN, indent="    ")
    s += "  </g>\n"
    s += "</svg>\n"
    return s


def build_art_svg(bloom: bool, colour_cyan: str, colour_magenta: str) -> str:
    """Artwork only on a transparent ground."""
    mag = offset_points(WAVE, *MAGENTA_OFFSET)
    s = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(GRID)}" height="{int(GRID)}" '
        f'viewBox="0 0 {int(GRID)} {int(GRID)}">\n'
    )
    if bloom:
        s += '  <defs>\n'
        s += (
            '    <filter id="bloom" x="-60%" y="-60%" width="220%" height="220%" '
            'color-interpolation-filters="sRGB">\n'
            '      <feGaussianBlur stdDeviation="30" result="wide"/>\n'
            '      <feGaussianBlur stdDeviation="11" in="SourceGraphic" result="tight"/>\n'
            '      <feMerge>\n'
            '        <feMergeNode in="wide"/>\n'
            '        <feMergeNode in="tight"/>\n'
            '        <feMergeNode in="SourceGraphic"/>\n'
            '      </feMerge>\n'
            '    </filter>\n'
            '  </defs>\n'
        )
        s += '  <g filter="url(#bloom)">\n'
        s += stroke_el(mag, STROKE_MAGENTA, colour_magenta, indent="    ")
        s += "  </g>\n"
        s += '  <g filter="url(#bloom)">\n'
        s += stroke_el(WAVE, STROKE_CYAN, colour_cyan, indent="    ")
        s += "  </g>\n"
    else:
        for width, opacity in GLOW_STEPS:
            s += stroke_el(WAVE, width, colour_cyan, opacity)
        s += stroke_el(mag, STROKE_MAGENTA, colour_magenta)
        for width, opacity in GLOW_STEPS:
            s += stroke_el(WAVE, width, colour_cyan, opacity)
        s += stroke_el(WAVE, STROKE_CYAN, colour_cyan)
    s += "</svg>\n"
    return s


def build_background_svg() -> str:
    """Ground only, so the art can be placed on its own platform grid."""
    s = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(GRID)}" height="{int(GRID)}" '
        f'viewBox="0 0 {int(GRID)} {int(GRID)}">\n'
        '  <defs>\n'
        '    <radialGradient id="bg" cx="50%" cy="47%" r="76%">\n'
        f'      <stop offset="0%" stop-color="{BACKGROUND_INNER}"/>\n'
        f'      <stop offset="100%" stop-color="{BACKGROUND}"/>\n'
        '    </radialGradient>\n'
        '  </defs>\n'
        f'  <rect width="{int(GRID)}" height="{int(GRID)}" fill="url(#bg)"/>\n'
        "</svg>\n"
    )
    return s


def build_mvg() -> str:
    """ImageMagick drawing commands (MSVG's gradient support is unreliable,
    so the .icns pipeline composites the ground separately)."""
    mag = offset_points(WAVE, *MAGENTA_OFFSET)
    s = f"stroke-linecap round stroke-linejoin round fill none\n"
    s += f"stroke '{MAGENTA}' stroke-width {fmt(STROKE_MAGENTA)}\n"
    s += f"path '{path_data(mag)}'\n"
    s += f"stroke '{CYAN}' stroke-width {fmt(STROKE_CYAN)}\n"
    s += f"path '{path_data(WAVE)}'\n"
    return s


# --- Android VectorDrawable ---------------------------------------------

def build_android_foreground() -> str:
    vw = ANDROID_VIEWPORT
    k = android_scale()
    wave = to_viewport(WAVE, k, vw)
    mag = to_viewport(offset_points(WAVE, *MAGENTA_OFFSET), k, vw)

    def d(points):
        return path_data([(round(x, 2), round(y, 2)) for x, y in points])

    s = '<?xml version="1.0" encoding="utf-8"?>\n'
    s += '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    s += '    android:width="108dp"\n    android:height="108dp"\n'
    s += f'    android:viewportWidth="{fmt(vw)}"\n    android:viewportHeight="{fmt(vw)}">\n'
    # Stepped glow for the cyan core, then the magenta echo, then the core.
    for width, opacity in GLOW_STEPS:
        s += (
            f'    <path android:pathData="{d(wave)}" android:strokeColor="{CYAN}"\n'
            f'        android:strokeWidth="{fmt(width * k)}" android:strokeAlpha="{fmt(opacity)}"\n'
            f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
        )
    s += (
        f'    <path android:pathData="{d(mag)}" android:strokeColor="{MAGENTA}"\n'
        f'        android:strokeWidth="{fmt(STROKE_MAGENTA * k)}"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
    )
    for width, opacity in GLOW_STEPS:
        s += (
            f'    <path android:pathData="{d(wave)}" android:strokeColor="{CYAN}"\n'
            f'        android:strokeWidth="{fmt(width * k)}" android:strokeAlpha="{fmt(opacity)}"\n'
            f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
        )
    s += (
        f'    <path android:pathData="{d(wave)}" android:strokeColor="{CYAN}"\n'
        f'        android:strokeWidth="{fmt(STROKE_CYAN * k)}"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
    )
    s += "</vector>\n"
    return s


def build_android_background() -> str:
    s = '<?xml version="1.0" encoding="utf-8"?>\n'
    s += '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    s += '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    s += '    android:width="108dp"\n    android:height="108dp"\n'
    s += '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
    s += '    <path android:pathData="M0,0h108v108h-108z">\n'
    s += '        <aapt:attr name="android:fillColor">\n'
    s += '            <gradient android:type="radial" android:centerX="54" android:centerY="49.7"\n'
    s += '                android:gradientRadius="84">\n'
    s += f'                <item android:offset="0" android:color="#FF{BACKGROUND_INNER.lstrip("#")}" />\n'
    s += f'                <item android:offset="1" android:color="#FF{BACKGROUND.lstrip("#")}" />\n'
    s += '            </gradient>\n'
    s += '        </aapt:attr>\n'
    s += '    </path>\n'
    s += "</vector>\n"
    return s


def build_android_monochrome() -> str:
    vw = ANDROID_VIEWPORT
    k = android_scale()
    wave = to_viewport(WAVE, k, vw)

    def d(points):
        return path_data([(round(x, 2), round(y, 2)) for x, y in points])

    s = '<?xml version="1.0" encoding="utf-8"?>\n'
    s += '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    s += '    android:width="108dp"\n    android:height="108dp"\n'
    s += f'    android:viewportWidth="{fmt(vw)}"\n    android:viewportHeight="{fmt(vw)}">\n'
    s += (
        f'    <path android:pathData="{d(wave)}" android:strokeColor="#FFFFFFFF"\n'
        f'        android:strokeWidth="{fmt(STROKE_CYAN * k)}"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n'
    )
    s += "</vector>\n"
    return s


def build_android_svg() -> str:
    """Artwork on the 108dp adaptive grid - mirrors the VectorDrawable
    coordinates exactly, so it can be rasterised as a true preview."""
    vw = ANDROID_VIEWPORT
    k = android_scale()
    wave = to_viewport(WAVE, k, vw)
    mag = to_viewport(offset_points(WAVE, *MAGENTA_OFFSET), k, vw)

    s = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{fmt(vw)}" height="{fmt(vw)}" '
        f'viewBox="0 0 {fmt(vw)} {fmt(vw)}">\n'
    )
    for width, opacity in GLOW_STEPS:
        s += stroke_el(wave, width * k, CYAN, opacity)
    s += stroke_el(mag, STROKE_MAGENTA * k, MAGENTA)
    for width, opacity in GLOW_STEPS:
        s += stroke_el(wave, width * k, CYAN, opacity)
    s += stroke_el(wave, STROKE_CYAN * k, CYAN)
    s += "</svg>\n"
    return s


def build_android_bg_svg() -> str:
    s = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{fmt(ANDROID_VIEWPORT)}" '
        f'height="{fmt(ANDROID_VIEWPORT)}" viewBox="0 0 {fmt(ANDROID_VIEWPORT)} '
        f'{fmt(ANDROID_VIEWPORT)}">\n'
        '  <defs>\n'
        '    <radialGradient id="bg" cx="50%" cy="49.7%" r="78%">\n'
        f'      <stop offset="0%" stop-color="{BACKGROUND_INNER}"/>\n'
        f'      <stop offset="100%" stop-color="{BACKGROUND}"/>\n'
        '    </radialGradient>\n'
        '  </defs>\n'
        f'  <rect width="{fmt(ANDROID_VIEWPORT)}" height="{fmt(ANDROID_VIEWPORT)}" fill="url(#bg)"/>\n'
        "</svg>\n"
    )
    return s


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    ANDROID_RES.mkdir(parents=True, exist_ok=True)

    (OUT / "surge-wave.svg").write_text(build_master_svg())
    (OUT / "surge-wave-bg.svg").write_text(build_background_svg())
    (OUT / "surge-wave-art.svg").write_text(build_art_svg(True, CYAN, MAGENTA))
    (OUT / "surge-wave-fg.svg").write_text(build_art_svg(False, CYAN, MAGENTA))
    (OUT / "surge-wave-mono.svg").write_text(build_art_svg(False, "#FFFFFF", ""))
    (OUT / "surge-wave.mvg").write_text(build_mvg())
    (OUT / "surge-wave-android.svg").write_text(build_android_svg())
    (OUT / "surge-wave-android-bg.svg").write_text(build_android_bg_svg())

    (ANDROID_RES / "ic_launcher_foreground.xml").write_text(build_android_foreground())
    (ANDROID_RES / "ic_launcher_background.xml").write_text(build_android_background())
    (ANDROID_RES / "ic_launcher_monochrome.xml").write_text(build_android_monochrome())

    x0, y0, x1, y1 = combined_bbox()
    half_diag = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5 / 2.0
    k = android_scale()
    print(f"art bbox        : {x0:.1f},{y0:.1f} -> {x1:.1f},{y1:.1f}  ({x1-x0:.0f} x {y1-y0:.0f} of 1024)")
    print(f"centring shift  : ({SHIFT_X:+.2f}, {SHIFT_Y:+.2f})")
    print(f"android scale   : {k:.5f}  art = {(x1-x0)*k:.1f} x {(y1-y0)*k:.1f} dp of 108")
    print(f"android radius  : {half_diag*k:.1f}dp  (mask 36dp, safe 33dp)")
    print("written:")
    for p in sorted(OUT.glob("*")):
        print("  ", p.relative_to(PROJECT))
    for n in ("ic_launcher_foreground.xml", "ic_launcher_background.xml", "ic_launcher_monochrome.xml"):
        print("  ", (ANDROID_RES / n).relative_to(PROJECT))


if __name__ == "__main__":
    main()
