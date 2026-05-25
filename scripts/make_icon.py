"""LitematicaFolia Modrinth icon generator — isometric grass block wrapped in the
iconic Litematica cyan placement ghost. 512x512 PNG, dark slate background."""

import math
from PIL import Image, ImageDraw, ImageFilter

W = H = 512
SQRT3_2 = math.sqrt(3) / 2

# ─── background ───────────────────────────────────────────────────────────────
BG = (16, 22, 36, 255)          # dark slate, Modrinth-friendly
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
# Rounded-rect background to match Modrinth's avatar look
draw.rounded_rectangle((0, 0, W - 1, H - 1), radius=72, fill=BG)


# ─── grass block (the "real" placed cube) ─────────────────────────────────────
cx, cy = W // 2, H // 2 + 14
S = 130

def iso_verts(s):
    return {
        "top":    (cx,            cy - s),
        "tr":     (cx + s * SQRT3_2, cy - s / 2),
        "tl":     (cx - s * SQRT3_2, cy - s / 2),
        "front":  (cx,            cy),
        "right":  (cx + s * SQRT3_2, cy + s / 2),
        "left":   (cx - s * SQRT3_2, cy + s / 2),
        "bottom": (cx,            cy + s),
    }

V = iso_verts(S)

# Minecraft-ish colours — grass on top, dirt on sides
GRASS    = (124, 196,  82, 255)
GRASS_LO = ( 97, 162,  60, 255)   # darker on shadowed top edge band
DIRT_R   = (134, 100,  60, 255)
DIRT_L   = (108,  80,  47, 255)
LINE_TXT = ( 70,  52,  30, 255)   # texture micro-lines

# right face
draw.polygon([V["front"], V["tr"], V["right"], V["bottom"]], fill=DIRT_R)
# left face
draw.polygon([V["tl"], V["front"], V["bottom"], V["left"]],  fill=DIRT_L)
# top face (grass)
draw.polygon([V["top"], V["tr"], V["front"], V["tl"]],       fill=GRASS)
# subtle "grass band" on the upper few px of the side faces
band_h = 18
# right band: parallelogram along V[tr] -> V[front], thickness band_h going down
ax, ay = V["tr"]
bx, by = V["front"]
draw.polygon([
    (ax, ay), (bx, by),
    (bx, by + band_h), (ax, ay + band_h)
], fill=GRASS_LO)
# left band
ax, ay = V["tl"]
bx, by = V["front"]
draw.polygon([
    (ax, ay), (bx, by),
    (bx, by + band_h), (ax, ay + band_h)
], fill=GRASS_LO)


# Pixel-art texture (subtle dots) to evoke MC's pixely look
import random
random.seed(42)
for _ in range(120):
    # Random point inside the top rhombus
    t = random.random()
    u = random.random()
    if t + u > 1:  # restrict to triangle, then mirror to fill rhombus halves
        t, u = 1 - t, 1 - u
    # interpolate between corners
    p_top = V["top"]
    p_tr  = V["tr"]
    p_tl  = V["tl"]
    p_fr  = V["front"]
    if random.random() < 0.5:
        x = p_top[0] + t * (p_tr[0] - p_top[0]) + u * (p_fr[0] - p_top[0])
        y = p_top[1] + t * (p_tr[1] - p_top[1]) + u * (p_fr[1] - p_top[1])
    else:
        x = p_top[0] + t * (p_tl[0] - p_top[0]) + u * (p_fr[0] - p_top[0])
        y = p_top[1] + t * (p_tl[1] - p_top[1]) + u * (p_fr[1] - p_top[1])
    r = 2
    draw.ellipse((x - r, y - r, x + r, y + r), fill=GRASS_LO)


# ─── Litematica cyan placement ghost (the outline + translucent overlay) ──────
S2 = S + 38
V2 = iso_verts(S2)
CYAN_FILL = (84, 215, 255, 45)    # very translucent
CYAN_LINE = (96, 232, 255, 255)
LINE_W = 9

# Translucent overlay (the "ghost feel")
overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(overlay)
od.polygon([V2["top"], V2["tr"], V2["front"], V2["tl"]], fill=CYAN_FILL)
od.polygon([V2["front"], V2["tr"], V2["right"], V2["bottom"]], fill=CYAN_FILL)
od.polygon([V2["tl"], V2["front"], V2["bottom"], V2["left"]],  fill=CYAN_FILL)
img.alpha_composite(overlay)

# Edges: the 9 visible iso-cube edges
edges = [
    # top rhombus
    (V2["top"], V2["tr"]),
    (V2["tr"],  V2["front"]),
    (V2["front"], V2["tl"]),
    (V2["tl"],  V2["top"]),
    # vertical-ish bottom edges
    (V2["tr"],  V2["right"]),
    (V2["right"], V2["bottom"]),
    (V2["bottom"], V2["front"]),
    (V2["tl"],  V2["left"]),
    (V2["left"], V2["bottom"]),
]
for a, b in edges:
    draw.line([a, b], fill=CYAN_LINE, width=LINE_W)

# Glow halo at the 7 visible corners
GLOW = (96, 232, 255, 220)
for p in V2.values():
    x, y = p
    r = 7
    draw.ellipse((x - r, y - r, x + r, y + r), fill=GLOW)


# ─── tiny "transfer" dots near the top — hints at upload to server ────────────
DOT_C = (96, 232, 255, 255)
for i, dy in enumerate([-12, -32, -60]):
    r = 5 - i
    if r < 2:
        continue
    draw.ellipse((cx - r, cy - S2 + dy - r, cx + r, cy - S2 + dy + r), fill=DOT_C)


# ─── save ─────────────────────────────────────────────────────────────────────
img.save("/tmp/litematica-folia-icon.png")
print("OK 512x512 →", "/tmp/litematica-folia-icon.png")
