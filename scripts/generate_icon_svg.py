#!/usr/bin/env python3
"""Generate the DayView master SVG used as the source for app icons.

The output deliberately keeps every meaningful shape inside a central safe area,
so it can later be cropped into Android adaptive icons and macOS app icons.
Only Python's standard library is required.

Two themes are available. ``dark`` (the default) is the original near-black
plate with a luminous mint ring. ``light`` is a flat warm off-white plate with a
deeper mint ring and amber marker, tuned to stay legible on a light launcher.
Each theme provides sensible defaults that individual ``--background`` and
similar options may still override.
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Theme:
    """A resolved set of colours for one icon variant."""

    background: str
    surface: str
    accent: str
    marker: str
    gradient_start: str
    gradient_end: str
    track_color: str
    track_opacity: float
    inner_disc: bool


THEMES: dict[str, Theme] = {
    # The original dark icon: a luminous mint ring on a near-black glow.
    "dark": Theme(
        background="#0B0D12",
        surface="#202731",
        accent="#78E6BD",
        marker="#FFB86B",
        gradient_start="#B9F7DE",
        gradient_end="#43BE93",
        track_color="#FFFFFF",
        track_opacity=0.10,
        inner_disc=True,
    ),
    # The light icon reuses the app's light widget palette (deeper mint and
    # amber) so the ring reads on a flat warm off-white plate.
    "light": Theme(
        background="#F4F1EA",
        surface="#F4F1EA",
        accent="#168866",
        marker="#B76218",
        gradient_start="#3FBF93",
        gradient_end="#0F6E52",
        track_color="#16211D",
        track_opacity=0.12,
        inner_disc=False,
    ),
}


def build_svg(size: int, theme: Theme) -> str:
    # macOS app icons are rendered inside a padded canvas. Keeping the artwork
    # close to 80% of the source canvas prevents the rounded square from looking
    # larger than system icons in the Dock, Finder and System Settings.
    artwork_size = size * 0.8046875
    artwork_origin = (size - artwork_size) / 2
    center = artwork_size / 2
    outer_radius = artwork_size * 0.328125
    ring_width = artwork_size * 0.0859375
    inner_radius = outer_radius - ring_width * 0.92
    marker_radius = ring_width * 0.34
    corner_radius = artwork_size * 0.22

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

    # A quiet inner disc improves recognition at notification-icon sizes on the
    # dark plate; the light plate is flat, so it is omitted there.
    if theme.inner_disc:
        inner_disc_block = (
            "\n  <!-- Quiet inner disc improves recognition at "
            "notification-icon sizes. -->\n"
            f'  <circle cx="{center:.2f}" cy="{center:.2f}" r="{inner_radius:.2f}"\n'
            f'          fill="{theme.background}" fill-opacity="0.50"/>\n\n'
        )
    else:
        inner_disc_block = "\n"

    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     width="{size}" height="{size}" viewBox="0 0 {size} {size}"
     role="img" aria-labelledby="title description">
  <title id="title">Icône DayView</title>
  <desc id="description">Un cercle lumineux partiellement consumé représentant le temps restant dans la journée.</desc>

  <defs>
    <radialGradient id="backgroundGlow" cx="38%" cy="28%" r="86%">
      <stop offset="0" stop-color="{theme.surface}"/>
      <stop offset="1" stop-color="{theme.background}"/>
    </radialGradient>
    <linearGradient id="timeGradient" x1="18%" y1="12%" x2="83%" y2="88%">
      <stop offset="0" stop-color="{theme.gradient_start}"/>
      <stop offset="0.48" stop-color="{theme.accent}"/>
      <stop offset="1" stop-color="{theme.gradient_end}"/>
    </linearGradient>
    <filter id="softGlow" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="{artwork_size * 0.018:.2f}" result="blur"/>
      <feMerge>
        <feMergeNode in="blur"/>
        <feMergeNode in="SourceGraphic"/>
      </feMerge>
    </filter>
    <clipPath id="appIconMask">
      <rect width="{artwork_size:.2f}" height="{artwork_size:.2f}" rx="{corner_radius:.2f}"/>
    </clipPath>
  </defs>

  <!-- The transparent outer canvas is the macOS app-icon safe margin. -->
  <g transform="translate({artwork_origin:.2f} {artwork_origin:.2f})"
     clip-path="url(#appIconMask)">
  <rect width="{artwork_size:.2f}" height="{artwork_size:.2f}" fill="url(#backgroundGlow)"/>
{inner_disc_block}  <!-- The complete day, shown as a subtle track. -->
  <circle cx="{center:.2f}" cy="{center:.2f}" r="{outer_radius:.2f}"
          fill="none" stroke="{theme.track_color}" stroke-opacity="{theme.track_opacity:.2f}"
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
          fill="{theme.marker}"/>
  </g>
</svg>
'''


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the scalable DayView reference icon."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="SVG output path (default depends on --theme)",
    )
    parser.add_argument(
        "--theme",
        choices=sorted(THEMES),
        default="dark",
        help="Colour variant to generate (default: dark)",
    )
    parser.add_argument("--size", type=int, default=1024, help="Canvas size in pixels")
    parser.add_argument("--background", default=None, help="Outer background color")
    parser.add_argument("--surface", default=None, help="Glow color")
    parser.add_argument("--accent", default=None, help="Remaining-time color")
    parser.add_argument("--marker", default=None, help="Current-time marker color")
    return parser.parse_args()


def default_output(theme_name: str) -> Path:
    suffix = "" if theme_name == "dark" else f"-{theme_name}"
    return Path(f"artwork/dayview-icon-reference{suffix}.svg")


def main() -> None:
    args = parse_args()
    if args.size < 128:
        raise SystemExit("--size must be at least 128")

    base = THEMES[args.theme]
    theme = Theme(
        background=args.background or base.background,
        surface=args.surface or base.surface,
        accent=args.accent or base.accent,
        marker=args.marker or base.marker,
        gradient_start=base.gradient_start,
        gradient_end=base.gradient_end,
        track_color=base.track_color,
        track_opacity=base.track_opacity,
        inner_disc=base.inner_disc,
    )

    output = args.output or default_output(args.theme)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(build_svg(args.size, theme), encoding="utf-8")
    print(f"Generated {output} ({args.size}x{args.size})")


if __name__ == "__main__":
    main()
