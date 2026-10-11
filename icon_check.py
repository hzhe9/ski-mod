
from PIL import Image
p = r"D:/hermes/item/game/git/ski-mod-push/src/main/resources/assets/skis/textures/item/ski_pole_icon.png"
img = Image.open(p).convert("RGBA")
big = img.resize((128, 128), Image.NEAREST)
out = r"%OUT%"
bg = Image.new("RGBA", (128, 128), (40, 40, 40, 255))
bg.alpha_composite(big)
bg.save(out)
print("grid (X = opaque):")
a = img.load()
for y in range(16):
    print("".join("X" if a[x, y][3] > 0 else "." for x in range(16)))
print("saved", out)
