#!/usr/bin/env python3
"""Emit SVG path data for the shapes used by the SurgeMedia icon pipeline.

Pure-stdlib, no Pillow. Outputs an SVG 'd' attribute string on stdout.

shapes:
  squircle  superellipse |x/a|^n + |y/a|^n = 1  (macOS continuous corner)
  circle    plain circle
  rrect     plain rounded rectangle
"""
import argparse
import math


def superellipse_path(size: int, n: float = 5.0, samples: int = 512) -> str:
    """Continuous-curvature squircle, centred in a size x size box."""
    a = size / 2.0
    cx = cy = size / 2.0
    pts = []
    for i in range(samples):
        t = 2.0 * math.pi * i / samples
        ct, st = math.cos(t), math.sin(t)
        # signed power form of the superellipse
        x = cx + a * math.copysign(abs(ct) ** (2.0 / n), ct)
        y = cy + a * math.copysign(abs(st) ** (2.0 / n), st)
        pts.append((x, y))
    head = f"M{pts[0][0]:.3f},{pts[0][1]:.3f}"
    body = "".join(f"L{x:.3f},{y:.3f}" for x, y in pts[1:])
    return f"{head}{body}Z"


def circle_path(size: int, samples: int = 256) -> str:
    a = size / 2.0
    cx = cy = size / 2.0
    pts = [
        (
            cx + a * math.cos(2.0 * math.pi * i / samples),
            cy + a * math.sin(2.0 * math.pi * i / samples),
        )
        for i in range(samples)
    ]
    head = f"M{pts[0][0]:.3f},{pts[0][1]:.3f}"
    body = "".join(f"L{x:.3f},{y:.3f}" for x, y in pts[1:])
    return f"{head}{body}Z"


def rrect_path(size: int, radius: float, samples: int = 64) -> str:
    r = min(radius, size / 2.0)
    pts = []
    corners = [
        (size - r, r, -90.0, 0.0),
        (size - r, size - r, 0.0, 90.0),
        (r, size - r, 90.0, 180.0),
        (r, r, 180.0, 270.0),
    ]
    for ox, oy, a0, a1 in corners:
        for i in range(samples + 1):
            ang = math.radians(a0 + (a1 - a0) * i / samples)
            pts.append((ox + r * math.cos(ang), oy + r * math.sin(ang)))
    head = f"M{pts[0][0]:.3f},{pts[0][1]:.3f}"
    body = "".join(f"L{x:.3f},{y:.3f}" for x, y in pts[1:])
    return f"{head}{body}Z"


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("shape", choices=["squircle", "circle", "rrect"])
    p.add_argument("--size", type=int, default=1024)
    p.add_argument("--radius", type=float, default=0.2237)
    p.add_argument("--exponent", type=float, default=5.0)
    a = p.parse_args()

    if a.shape == "squircle":
        print(superellipse_path(a.size, a.exponent))
    elif a.shape == "circle":
        print(circle_path(a.size))
    else:
        print(rrect_path(a.size, a.radius * a.size))


if __name__ == "__main__":
    main()
