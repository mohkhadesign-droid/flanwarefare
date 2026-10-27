"""Generates the Flag Designer block textures (original pixel art, 16x16)."""
import random
from PIL import Image

OUT = "src/main/resources/assets/flanwarfareflags/textures/block/"
rnd = random.Random(7)
FRAME = [(58, 38, 22), (66, 44, 26), (50, 33, 19)]
PLANK = [(122, 86, 52), (132, 94, 57), (112, 79, 47), (138, 100, 62)]
GOLD = (222, 177, 48)
GOLD_D = (168, 124, 28)
RED = (156, 30, 34)
RED_D = (118, 20, 26)
CLOTH = (226, 220, 204)
CLOTH_D = (198, 190, 172)


def wood(img, x0, y0, x1, y1, palette, vertical=True):
    for y in range(y0, y1):
        for x in range(x0, x1):
            k = (x if vertical else y)
            seam = (k % 4 == 3)
            c = palette[(k // 4 + (y if vertical else x) // 7) % len(palette)]
            c = tuple(max(0, v - 18) for v in c) if seam else c
            j = rnd.randint(-5, 5)
            img.putpixel((x, y), tuple(max(0, min(255, v + j)) for v in c))


def frame(img):
    for i in range(16):
        for t in (0, 15):
            img.putpixel((i, t), FRAME[rnd.randint(0, 2)])
            img.putpixel((t, i), FRAME[rnd.randint(0, 2)])


# Side: planks, dark frame, a small hanging banner (red field, gold chevron) on a gold rod.
side = Image.new("RGB", (16, 16))
wood(side, 0, 0, 16, 16, PLANK)
frame(side)
for x in range(4, 12):
    side.putpixel((x, 3), GOLD if x not in (4, 11) else GOLD_D)
for y in range(4, 13):
    for x in range(5, 11):
        side.putpixel((x, y), RED if (x + y) % 5 else RED_D)
for x in range(5, 11):  # chevron
    d = abs(x - 7.5)
    yy = int(8 - d)
    side.putpixel((x, yy + 1), GOLD)
    side.putpixel((x, yy + 2), GOLD_D)
for x in (5, 7, 9):  # swallow-tail bottom
    side.putpixel((x, 12), RED_D)
    side.putpixel((x + 1, 12), side.getpixel((x + 1, 11)))
side.putpixel((5, 12), RED_D)
side.save(OUT + "flag_designer_side.png")

# Top: frame with a drafting cloth showing a banner layout grid and a dye corner.
top = Image.new("RGB", (16, 16))
wood(top, 0, 0, 16, 16, FRAME, vertical=False)
for y in range(2, 14):
    for x in range(2, 14):
        top.putpixel((x, y), CLOTH if (x * 3 + y) % 7 else CLOTH_D)
for y in range(4, 12):  # banner outline being designed
    for x in range(5, 9):
        top.putpixel((x, y), RED if not (x in (6, 7) and 6 <= y <= 8) else GOLD)
for i, c in enumerate([(176, 46, 38), (60, 68, 170), (94, 124, 22), (240, 240, 240)]):  # dye dots
    top.putpixel((11, 4 + i * 2), c)
    top.putpixel((12, 4 + i * 2), tuple(max(0, v - 30) for v in c))
for x in range(2, 14):
    top.putpixel((x, 13), CLOTH_D)
top.save(OUT + "flag_designer_top.png")

# Bottom: plain dark planks.
bottom = Image.new("RGB", (16, 16))
wood(bottom, 0, 0, 16, 16, FRAME)
bottom.save(OUT + "flag_designer_bottom.png")
print("ok")
