# -*- coding: utf-8 -*-
"""Turn logo.png into the launcher icon set.

The logo is already a finished tile - rounded square, gold bezel, its own glow -
so nothing here draws artwork. It crops, masks and resamples:

  ic_launcher.webp       the whole logo, glow included, for pre-API-26 launchers
  ic_launcher_round.webp the tile art zoomed past its own bezel, circle-masked,
                         with a gold ring drawn back on so the rim stays even
  ic_launcher_fg.webp    the bare tile, full bleed; mipmap-anydpi-v26 wraps it in
                         <inset 14%>, which is what keeps the mask off the bezel

Output is lossless WebP, which the platform has read since API 18. On this art it
lands at 73% of the equivalent PNG and is bit-identical wherever alpha > 0 - lossy
WebP is not an option here, its 4:2:0 chroma smears the thin blue orbit line.

Run: python _mkicon.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
SRC = os.path.join(ROOT, 'logo.png')

# Work at 4x the largest output, then downsample once - small circles and the
# ring stay clean that way.
WORK = 1024
LEGACY = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
FOREGROUND = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}

# The tile body inside logo.png, found by alpha: the surrounding glow is faint,
# the tile is ~253. Square, so the circle mask has a centre to sit on.
TILE = (81, 81, 1174, 1174)
# The bezel is a ~20px gold band at 1093px wide. Zooming past it by this much
# leaves the circle sitting in the interior gradient instead of clipping the rim.
BEZEL_ZOOM = 1.09
RING_OUTER = (232, 147, 10)
RING_INNER = (255, 217, 121)


def solidify(img):
    """The source tile is drawn at ~99% alpha. A launcher icon should not show
    the wallpaper through it, so anything essentially opaque becomes opaque."""
    a = img.getchannel('A').point(lambda v: 255 if v >= 230 else v)
    img.putalpha(a)
    return img


def source():
    return solidify(Image.open(SRC).convert('RGBA'))


def tile():
    return source().crop(TILE)


def write(img, density, name, size):
    directory = os.path.join(RES, 'mipmap-' + density)
    os.makedirs(directory, exist_ok=True)
    out = os.path.join(directory, name + '.webp')
    img.resize((size, size), Image.LANCZOS).save(out, 'WEBP', lossless=True, method=6)
    # A leftover .png would collide with the .webp on the same resource name.
    stale = os.path.join(directory, name + '.png')
    if os.path.exists(stale):
        os.remove(stale)
        print('removed %s' % stale)
    print('wrote %s (%dpx, %.1f kB)' % (out, size, os.path.getsize(out) / 1024.0))


def legacy_square():
    """Whole logo, glow and all: the tile lands at ~87% of the canvas, which is
    the padding a pre-adaptive launcher expects to supply itself."""
    return source().resize((WORK, WORK), Image.LANCZOS)


def legacy_round():
    art = tile()
    zoom = int(WORK * BEZEL_ZOOM)
    art = art.resize((zoom, zoom), Image.LANCZOS)
    off = (zoom - WORK) // 2
    art = art.crop((off, off, off + WORK, off + WORK))

    # Circle mask at 4x so the edge downsamples smooth.
    scale = 4
    mask = Image.new('L', (WORK * scale, WORK * scale), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, WORK * scale - 1, WORK * scale - 1), fill=255)
    art.putalpha(mask.resize((WORK, WORK), Image.LANCZOS))

    # The bezel was zoomed out of frame, so draw an even one back on.
    ring = Image.new('RGBA', (WORK * scale, WORK * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(ring)
    outer = int(WORK * scale * 0.030)
    inner = int(WORK * scale * 0.010)
    box = (outer // 2, outer // 2, WORK * scale - 1 - outer // 2, WORK * scale - 1 - outer // 2)
    d.ellipse(box, outline=RING_OUTER + (255,), width=outer)
    box2 = (outer + inner, outer + inner,
            WORK * scale - 1 - outer - inner, WORK * scale - 1 - outer - inner)
    d.ellipse(box2, outline=RING_INNER + (215,), width=inner)
    art.alpha_composite(ring.resize((WORK, WORK), Image.LANCZOS))
    return art


def foreground():
    """Bare tile, full bleed. mipmap-anydpi-v26 insets it 14%, so the visible
    72dp window shows the tile at ~108% - the whole composition, barely cropped,
    and the bezel falls outside every launcher mask."""
    return tile().resize((WORK, WORK), Image.LANCZOS)


def main():
    square, round_, fg = legacy_square(), legacy_round(), foreground()
    for density, size in LEGACY.items():
        write(square, density, 'ic_launcher', size)
        write(round_, density, 'ic_launcher_round', size)
    for density, size in FOREGROUND.items():
        write(fg, density, 'ic_launcher_fg', size)
    print('icons done')


if __name__ == '__main__':
    main()
