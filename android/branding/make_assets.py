#!/usr/bin/env python3
"""Generates the app icon and launch-screen artwork.

Run `python3 make_assets.py` from this folder (needs `rsvg-convert` and the
Noto Sans Bold font). It writes the SVG sources next to this script and the
PNGs under ../app/src/main/res. The generated files are committed, so building
the app does not need this script; it is here to change the artwork later.

Artwork: a ball-and-stick glucose molecule (pyranose ring with its ring oxygen,
four OH groups and a CH2OH tail; carbon white, oxygen coral) above "TMS".
Everything sits inside the 66dp centre circle of the 108dp adaptive-icon canvas
so no launcher mask can clip it.
"""
import math
import pathlib
import subprocess

HERE = pathlib.Path(__file__).parent
RES = HERE.parent / "app" / "src" / "main" / "res"
# The one app version, shared with the web page and the Gradle build.
VERSION = (HERE.parent.parent / "resources" / "public" / "version.txt").read_text().strip()

WHITE, CORAL = "#ffffff", "#ff8a80"
FONT = "Noto Sans, sans-serif"

# --- Molecule geometry (108 x 108 canvas) -----------------------------------
CX, CY, RING = 54.0, 41.0, 10.0     # ring centre and bond length
ARM = 7.0                            # substituent bond length
C_R, O_R, RING_O_R = 2.6, 3.0, 3.4   # ball radii


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def molecule(carbon, oxygen, bond):
    """SVG elements for the molecule; colours are parameters so one drawing
    serves the full-colour and the single-colour (themed icon) versions."""
    verts = {d: pt(CX, CY, RING, d) for d in (-120, -60, 0, 60, 120, 180)}
    ring_o = verts[-120]
    ring_order = [-120, -60, 0, 60, 120, 180]
    lines, balls = [], []
    # Ring bonds.
    for a, b in zip(ring_order, ring_order[1:] + ring_order[:1]):
        lines.append((verts[a], verts[b]))
    # OH groups on C1..C4 point straight out from the ring.
    for d in (-60, 0, 60, 120):
        end = pt(CX, CY, RING + ARM, d)
        lines.append((verts[d], end))
        balls.append((end, O_R, oxygen))
    # CH2OH on C5 (left): out to C6, then bend down to the hydroxyl oxygen.
    c6 = pt(CX, CY, RING + ARM, 180)
    lines.append((verts[180], c6))
    balls.append((c6, C_R, carbon))
    o6 = (c6[0] - ARM * math.cos(math.radians(30)), c6[1] + ARM * math.sin(math.radians(30)))
    lines.append((c6, o6))
    balls.append((o6, O_R, oxygen))
    # Ring carbons and the ring oxygen.
    for d in ring_order:
        if d == -120:
            balls.append((ring_o, RING_O_R, oxygen))
        else:
            balls.append((verts[d], C_R, carbon))
    out = [f'<line x1="{a[0]:.2f}" y1="{a[1]:.2f}" x2="{b[0]:.2f}" y2="{b[1]:.2f}" '
           f'stroke="{bond}" stroke-width="2" stroke-linecap="round"/>' for a, b in lines]
    out += [f'<circle cx="{p[0]:.2f}" cy="{p[1]:.2f}" r="{r}" fill="{c}"/>' for p, r, c in balls]
    return "\n  ".join(out), [(p, r) for p, r, _ in balls]


def check_safe_zone(balls):
    """Every ball must lie inside the 66dp-diameter safe circle."""
    for (x, y), r in balls:
        d = math.hypot(x - 54, y - 54) + r
        assert d <= 34, f"ball at ({x:.1f},{y:.1f}) pokes out of the safe zone ({d:.1f})"


def tms_text(fill):
    return (f'<text x="54" y="81" text-anchor="middle" font-family="{FONT}" font-weight="800" '
            f'font-size="17" letter-spacing="1.2" fill="{fill}">TMS</text>')


def icon_svg(carbon, oxygen, bond, text):
    body, balls = molecule(carbon, oxygen, bond)
    check_safe_zone(balls)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'
            f'  {body}\n  {tms_text(text)}\n</svg>\n')


def wordmark_svg():
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 80" width="200" height="80">\n'
            f'  <text x="100" y="36" text-anchor="middle" font-family="{FONT}" font-weight="800" '
            f'font-size="17" fill="{WHITE}">TMS<tspan font-weight="400" dx="5">-</tspan><tspan dx="5">TrackMySugar</tspan></text>\n'
            f'  <text x="100" y="56" text-anchor="middle" font-family="{FONT}" font-weight="400" '
            f'font-size="11" fill="{WHITE}" fill-opacity="0.85">Version {VERSION}</text>\n'
            '</svg>\n')


def render(svg_path, out_path, width, height):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["rsvg-convert", "-w", str(width), "-h", str(height), "-o", str(out_path), str(svg_path)], check=True)


def main():
    fg = HERE / "icon_foreground.svg"
    mono = HERE / "icon_monochrome.svg"
    word = HERE / "wordmark.svg"
    fg.write_text(icon_svg(WHITE, CORAL, WHITE, WHITE))
    mono.write_text(icon_svg("#000", "#000", "#000", "#000"))
    word.write_text(wordmark_svg())

    # Adaptive icon layers: 108dp canvas at each density.
    for name, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        px = int(108 * scale)
        render(fg, RES / f"mipmap-{name}" / "ic_launcher_foreground.png", px, px)
        render(mono, RES / f"mipmap-{name}" / "ic_launcher_monochrome.png", px, px)
    # Splash icon: 288dp canvas (the artwork stays inside its 192dp centre circle).
    for name, scale in (("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        px = int(288 * scale)
        render(fg, RES / f"drawable-{name}" / "splash_icon.png", px, px)
    # Branding image under the splash icon: 200 x 80dp.
    for name, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        render(word, RES / f"drawable-{name}" / "splash_branding.png", int(200 * scale), int(80 * scale))
    print("Generated icon and splash artwork")


if __name__ == "__main__":
    main()
