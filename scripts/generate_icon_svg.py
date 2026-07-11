#!/usr/bin/env python3
"""Generate the DayView master SVG used as the source for app icons.

The output deliberately keeps every meaningful shape inside a central safe area,
so it can later be cropped into Android adaptive icons and macOS app icons.
Only Python's standard library is required.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path


def build_svg(
    size: int,
    background: str,
    surface: str,
    accent: str,
    marker: str,
) -> str:
    center = size / 2
    outer_radius = size * 0.328125
    ring_width = size * 0.0859375
    inner_radius = outer_radius - ring_width * 0.92
    marker_radius = ring_width * 0.34

    # The visible arc represents the time still available. Its open quarter is
    # the portion of the day that has already been consumed.
    circumference = 2 * 3.141592653589793 * outer_radius
    remaining = circumference * 0.735
    consumed = circumference - remaining
    elapsed_ratio = 1 - 0.735
    # Match the application: the remaining arc begins at "now" and continues
    # clockwise until the fixed end-of-day point at twelve o'clock.
    arc_start_degrees = -90 + 360 * elapsed_ratio
    arc_start_angle = math.radians(arc_start_degrees)
    marker_x = center + math.cos(arc_start_angle) * outer_radius
    marker_y = center + math.sin(arc_start_angle) * outer_radius

    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     width="{size}" height="{size}" viewBox="0 0 {size} {size}"
     role="img" aria-labelledby="title description">
  <title id="title">Icône DayView</title>
  <desc id="description">Un cercle lumineux partiellement consumé représentant le temps restant dans la journée.</desc>

  <defs>
    <radialGradient id="backgroundGlow" cx="38%" cy="28%" r="86%">
      <stop offset="0" stop-color="{surface}"/>
      <stop offset="1" stop-color="{background}"/>
    </radialGradient>
    <linearGradient id="timeGradient" x1="18%" y1="12%" x2="83%" y2="88%">
      <stop offset="0" stop-color="#B9F7DE"/>
      <stop offset="0.48" stop-color="{accent}"/>
      <stop offset="1" stop-color="#43BE93"/>
    </linearGradient>
    <filter id="softGlow" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="{size * 0.018:.2f}" result="blur"/>
      <feMerge>
        <feMergeNode in="blur"/>
        <feMergeNode in="SourceGraphic"/>
      </feMerge>
    </filter>
  </defs>

  <!-- Full-bleed base; platform tooling may apply its own rounded mask. -->
  <rect width="{size}" height="{size}" fill="url(#backgroundGlow)"/>

  <!-- Quiet inner disc improves recognition at notification-icon sizes. -->
  <circle cx="{center:.2f}" cy="{center:.2f}" r="{inner_radius:.2f}"
          fill="{background}" fill-opacity="0.50"/>

  <!-- The complete day, shown as a subtle track. -->
  <circle cx="{center:.2f}" cy="{center:.2f}" r="{outer_radius:.2f}"
          fill="none" stroke="#FFFFFF" stroke-opacity="0.10"
          stroke-width="{ring_width:.2f}"/>

  <!-- Remaining time. Rotation puts the start at twelve o'clock. -->
  <circle cx="{center:.2f}" cy="{center:.2f}" r="{outer_radius:.2f}"
          fill="none" stroke="url(#timeGradient)"
          stroke-width="{ring_width:.2f}" stroke-linecap="round"
          stroke-dasharray="{remaining:.2f} {consumed:.2f}"
          transform="rotate({arc_start_degrees:.2f} {center:.2f} {center:.2f})"
          filter="url(#softGlow)"/>

  <!-- A small warm marker identifies the start of the remaining arc: now. -->
  <circle cx="{marker_x:.2f}" cy="{marker_y:.2f}" r="{marker_radius:.2f}"
          fill="{marker}"/>
</svg>
'''


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the scalable DayView reference icon."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artwork/dayview-icon-reference.svg"),
        help="SVG output path (default: artwork/dayview-icon-reference.svg)",
    )
    parser.add_argument("--size", type=int, default=1024, help="Canvas size in pixels")
    parser.add_argument("--background", default="#0B0D12", help="Outer background color")
    parser.add_argument("--surface", default="#202731", help="Glow color")
    parser.add_argument("--accent", default="#78E6BD", help="Remaining-time color")
    parser.add_argument("--marker", default="#FFB86B", help="Current-time marker color")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.size < 128:
        raise SystemExit("--size must be at least 128")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        build_svg(args.size, args.background, args.surface, args.accent, args.marker),
        encoding="utf-8",
    )
    print(f"Generated {args.output} ({args.size}x{args.size})")


if __name__ == "__main__":
    main()
