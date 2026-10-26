import random, sys
from PIL import Image
random.seed(7)
img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
px = img.load()
def tile(ox, oy, pick):
    for y in range(16):
        for x in range(16):
            px[ox + x, oy + y] = pick(x, y)
# Wood: dark brown with vertical grain streaks
wood = [(74, 50, 32), (66, 44, 28), (82, 56, 36), (58, 39, 25), (90, 62, 40)]
grain = [random.randrange(len(wood)) for _ in range(16)]
tile(0, 0, lambda x, y: wood[(grain[x] + (1 if random.random() < 0.18 else 0)) % len(wood)] + (255,))
# Dark charcoal wood (crossbar, caps, tabs)
dark = [(44, 38, 36), (38, 33, 31), (50, 44, 41), (33, 29, 27)]
tile(16, 0, lambda x, y: dark[random.randrange(len(dark)) if random.random() < 0.35 else (x // 4 + y // 8) % 2] + (255,))
# Stone sides: medium grey, light top row, darker bottom
def stone(x, y):
    base = random.choice([(128, 128, 128), (120, 120, 122), (136, 136, 136), (112, 112, 114)])
    if y == 0:
        base = (168, 168, 168)
    elif y == 15:
        base = (88, 88, 90)
    return base + (255,)
tile(32, 0, stone)
# Gold sides: yellow with highlight and shade
def gold(x, y):
    c = random.choice([(250, 205, 40), (240, 190, 30), (255, 220, 70)])
    if y < 2:
        c = (255, 240, 130)
    elif y > 12:
        c = (205, 150, 20)
    return c + (255,)
tile(48, 0, gold)
# Stone top: lighter, bevelled edge
def stone_top(x, y):
    if x in (0, 15) or y in (0, 15):
        return (176, 176, 176, 255)
    return random.choice([(148, 148, 148), (140, 140, 142), (156, 156, 156)]) + (255,)
tile(0, 16, stone_top)
# Gold top
tile(16, 16, lambda x, y: ((255, 236, 110) if random.random() < 0.6 else (252, 214, 60)) + (255,))
img.save(sys.argv[1])
