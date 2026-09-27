#!/usr/bin/env python3
"""Draw the two images the Play listing needs, from the app's own palette.

The launcher icon is a vector drawable, so the store icon is the same design
rasterised: an indigo field, the darker hill behind it, and the white map pin
holding a clock. Drawing it here rather than exporting it from a design tool keeps
the two in step - the colours are read from the same values the app uses, and the
script is the only source of the PNGs, so they can be regenerated rather than
found.

The feature graphic is a design, not a screenshot: this app has not been run on a
screen, and a picture of a screen that does not exist would be a picture of
nothing. It carries the same palette, a pin and a timeline, and no text - Arabic
needs a shaping engine and a font, and a graphic with broken letter joining in it
would be worse than a graphic with no words.

    python3 tools/make_store_images.py

Requires Pillow. Writes into fastlane/metadata/android/<locale>/images/.
"""

import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCALES = ("ar", "en-US")

# The same three colours as values/colors.xml and the launcher drawable.
INDIGO = (26, 35, 126)
INDIGO_DEEP = (35, 44, 140)
INDIGO_DARK = (16, 22, 84)
WHITE = (255, 255, 255)
SAND = (255, 236, 179)

SUPERSAMPLE = 4


def _sin(radians):
    """Sine, without importing math for one call."""
    import math

    return math.sin(radians)


def teardrop(draw, cx, top, width, colour):
    """A map pin: a round head over a point, the way a pin is drawn on a map.

    The point is a triangle whose base sits inside the head, so the two shapes
    read as one: a triangle that only touched the circle would leave a waist, and
    a circle on its own is not a pin.
    """
    radius = width / 2
    centre_y = top + radius
    draw.ellipse(
        [cx - radius, centre_y - radius, cx + radius, centre_y + radius],
        fill=colour,
    )
    tip_y = centre_y + radius * 1.85
    draw.polygon(
        [
            (cx - radius * 0.92, centre_y + radius * 0.25),
            (cx + radius * 0.92, centre_y + radius * 0.25),
            (cx, tip_y),
        ],
        fill=colour,
    )


def clock(draw, centre, radius):
    """The indigo face with white hands, inside the pin."""
    x, y = centre
    draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=INDIGO)
    hand = max(2, int(radius * 0.16))
    draw.line([x, y, x, y - radius * 0.62], fill=WHITE, width=hand)
    draw.line([x, y, x + radius * 0.45, y + radius * 0.3], fill=WHITE, width=hand)


def hill(draw, width, height, colour):
    """The horizon the launcher background carries under the pin."""
    draw.polygon(
        [
            (0, height * 0.72),
            (width * 0.33, height * 0.55),
            (width * 0.66, height * 0.78),
            (width, height * 0.61),
            (width, height),
            (0, height),
        ],
        fill=colour,
    )


def icon(size=512):
    image = Image.new("RGB", (size * SUPERSAMPLE, size * SUPERSAMPLE), INDIGO)
    draw = ImageDraw.Draw(image)
    scaled = size * SUPERSAMPLE
    hill(draw, scaled, scaled, INDIGO_DEEP)
    pin_width = scaled * 0.46
    top = scaled * 0.18
    teardrop(draw, scaled / 2, top, pin_width, WHITE)
    clock(draw, (scaled / 2, top + pin_width * 0.5), pin_width * 0.21)
    return image.resize((size, size), Image.LANCZOS)


def contour(draw, width, height, offset, amplitude, wavelength, colour):
    """A smooth topographic line, so the graphic reads as a map without a map.

    Drawn as a polyline rather than with arcs: a sine sampled a few hundred times
    is smooth at any size, and it costs nothing to reason about.
    """
    points = []
    steps = 400
    for index in range(steps + 1):
        x = width * index / steps
        y = height * offset + amplitude * _sin(x / wavelength)
        points.append((x, y))
    draw.line(points, fill=colour, width=4, joint="curve")


def feature_graphic(width=1024, height=500):
    image = Image.new("RGB", (width * 2, height * 2), INDIGO)
    draw = ImageDraw.Draw(image)
    w, h = width * 2, height * 2

    # A gradient from indigo to the deeper blue the hill uses.
    for row in range(h):
        blend = row / h
        colour = tuple(
            int(INDIGO[index] + (INDIGO_DARK[index] - INDIGO[index]) * blend)
            for index in range(3)
        )
        draw.line([0, row, w, row], fill=colour)

    contour(draw, w, h, 0.20, h * 0.045, w * 0.16, (46, 56, 158))
    contour(draw, w, h, 0.30, h * 0.035, w * 0.11, (40, 50, 148))
    contour(draw, w, h, 0.78, h * 0.050, w * 0.14, (46, 56, 158))
    contour(draw, w, h, 0.88, h * 0.040, w * 0.19, (40, 50, 148))

    # The pin, and a timeline of moments running away from it.
    pin_width = w * 0.16
    top = h * 0.20
    teardrop(draw, w * 0.27, top, pin_width, WHITE)
    clock(draw, (w * 0.27, top + pin_width * 0.5), pin_width * 0.21)

    line_y = h * 0.52
    draw.line([w * 0.42, line_y, w * 0.9, line_y], fill=SAND, width=5)
    for index in range(5):
        x = w * (0.44 + index * 0.11)
        radius = 16 if index % 2 == 0 else 11
        draw.ellipse([x - radius, line_y - radius, x + radius, line_y + radius], fill=SAND)
    return image.resize((width, height), Image.LANCZOS)


def main() -> int:
    written = []
    for locale in LOCALES:
        target = ROOT / "fastlane/metadata/android" / locale / "images"
        target.mkdir(parents=True, exist_ok=True)
        icon(512).save(target / "icon.png", optimize=True)
        feature_graphic().save(target / "featureGraphic.png", optimize=True)
        written += [target / "icon.png", target / "featureGraphic.png"]
    for path in written:
        print(f"{path.relative_to(ROOT)} {path.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
