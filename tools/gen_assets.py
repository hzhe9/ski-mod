"""Generates every texture and model texture for the skis mod.

Outputs:
  assets/skis/textures/item/modern_ski.png                        item icon (ski board, flat 16x16)
  assets/skis/textures/item/ski_pole.png                          ski pole 3D model texture (64x64, three UV regions)
  assets/skis/textures/entity/ski_board.png                       on-foot model texture: boot + board body + tip (128x32)
  assets/skis/textures/entity/equipment/humanoid/modern_ski.png   vanilla armor layer texture (fallback, the old 128x32 scheme)
  assets/skis/icon.png                                            mod icon

Model UV convention: uv values in the model JSON / CubeListBuilder are in units of 16 (same as
vanilla), so one UV unit on a 64x64 texture is 4 pixels. The three ski pole regions are:
  shaft  u 0-4   v 0-8    -> pixels (0,0)-(16,32)
  grip   u 6-10  v 0-4    -> pixels (24,0)-(40,16)
  basket u 12-16 v 0-4    -> pixels (48,0)-(64,16)

ski_board.png uses CubeListBuilder's automatic UV layout, so each face lands at a fixed position:
  boot  box(4,6,4) @texOffs(0,0)    -> occupies (0,0)-(16,10)
  board box(3,1,22) @texOffs(20,0)  -> occupies (20,0)-(70,23), where the board top = up(42,0,3x22), the base = down(45,0,3x22) and the sides = row 22
  tip   box(3,1,2) @texOffs(20,24)  -> occupies (20,24)-(30,28)

The foot armor layer texture uses the boot pixels of vanilla iron.png as a template
(keeping only the texels the boot actually uses, then recolouring them).

Usage: python tools/gen_assets.py
"""
import io
import os
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "skis")
TEX_ITEM = os.path.join(ASSETS, "textures", "item")
TEX_ENTITY = os.path.join(ASSETS, "textures", "entity")
TEX_ARMOR = os.path.join(TEX_ENTITY, "equipment", "humanoid")
ICON = os.path.join(ASSETS, "icon.png")

# Loom-cached 26.2 client jar — it contains the humanoid armor layer texture we use as a template
VANILLA_JAR = os.path.join(
    os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", "26.2", "minecraft-client.jar"
)

# Palette
BODY = (32, 40, 48, 255)          # graphite black
ACCENT = (41, 196, 255, 255)      # modern cyan
TIP = (240, 248, 255, 255)        # tip white
BINDING = (200, 210, 218, 255)
BASE = (228, 236, 244, 255)       # board base
SHAFT = (198, 208, 216, 255)      # aluminium shaft
SHAFT_HI = (235, 242, 249, 255)
GRIP = (36, 40, 45, 255)
GRIP_HI = (86, 94, 104, 255)
BASKET = (255, 138, 40, 255)
BASKET_DK = (196, 90, 16, 255)
BOOT = (26, 38, 54, 255)          # boot
BOOT_HI = (58, 88, 120, 255)
BOOT_BUCKLE = (206, 214, 222, 255)
ARMOR_DARK = (30, 44, 62, 255)
ARMOR_MID = (46, 74, 104, 255)
ARMOR_BOARD = (226, 238, 248, 255)
ARMOR_ACCENT = (41, 196, 255, 255)


def blank(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def put(img, x, y, color):
    if 0 <= x < img.size[0] and 0 <= y < img.size[1]:
        img.putpixel((x, y), color)


def fill(img, x0, y0, x1, y1, color):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(img, x, y, color)


def modern_ski():
    """Item icon: a pair of parallel, diagonally placed ski boards, cyan stripes on the body, white tips."""
    img = blank()
    length = 11
    for offset in (0, 3):
        x0, y0 = 2 + offset, 14
        for t in range(length):
            x, y = x0 + t, y0 - t
            color = TIP if t >= length - 2 else BODY
            put(img, x, y, color)
            put(img, x, y + 1, color)
            if t in (2, 6) and color is BODY:
                put(img, x, y, ACCENT)
        put(img, x0 + 5, y0 - 5, BINDING)
    return img


def pole_texture():
    """Ski pole 3D model texture: 64x64, three UV regions for shaft / grip / basket."""
    img = blank(64, 64)
    fill(img, 0, 0, 16, 32, SHAFT)
    fill(img, 4, 0, 7, 32, SHAFT_HI)
    fill(img, 24, 0, 40, 16, GRIP)
    fill(img, 24, 4, 40, 7, GRIP_HI)
    fill(img, 24, 11, 40, 13, GRIP_HI)
    fill(img, 48, 0, 64, 16, BASKET)
    for y in range(0, 16, 3):
        for x in range(48, 64, 3):
            put(img, x + (y // 3) % 3, y, BASKET_DK)
    return img


def ski_board():
    """On-foot model texture (128x32): boot + board body + upturned tip, split by CubeListBuilder's automatic UVs."""
    img = blank(128, 32)

    # Boot: box(4,6,4) @texOffs(0,0) -> (0,0)-(16,10)
    fill(img, 0, 0, 16, 10, BOOT)
    fill(img, 0, 1, 16, 3, BOOT_HI)          # cuff ring
    fill(img, 0, 7, 16, 9, BOOT_HI)          # ankle area
    for x in range(1, 16, 5):                # buckles
        put(img, x, 4, BOOT_BUCKLE)
        put(img, x + 1, 4, BOOT_BUCKLE)

    # Board body: box(3,1,22) @texOffs(20,0) -> up(42,0,3x22) / down(45,0,3x22) / sides on row 22
    fill(img, 42, 0, 45, 22, BODY)           # board top (up)
    fill(img, 42, 0, 45, 5, TIP)             # front tip region
    fill(img, 43, 5, 44, 22, ACCENT)         # board centre line
    fill(img, 45, 0, 48, 22, BASE)           # board base (down)
    fill(img, 20, 22, 70, 23, BASE)          # board side ring
    fill(img, 20, 22, 42, 23, TIP)           # front side section

    # Upturned tip: box(3,1,2) @texOffs(20,24) -> (20,24)-(30,28)
    fill(img, 20, 24, 30, 28, TIP)
    fill(img, 22, 24, 25, 26, ACCENT)
    return img


def armor_layer():
    """Fallback for the old scheme: take the boot pixels of vanilla iron.png as a template and recolour them."""
    with zipfile.ZipFile(VANILLA_JAR) as jar:
        data = jar.read("assets/minecraft/textures/entity/equipment/humanoid/iron.png")
    vanilla = Image.open(io.BytesIO(data)).convert("RGBA")
    vpx = vanilla.load()

    img = blank(64, 32)
    for y in range(16, 32):
        for x in range(0, 16):
            if vpx[x, y][3] == 0:
                continue
            if y >= 31:
                color = ARMOR_BOARD
            elif y == 30:
                color = ARMOR_ACCENT
            elif y in (28, 29):
                color = ARMOR_MID
            else:
                color = ARMOR_DARK
            put(img, x, y, color)
    return img


def mod_icon():
    """128x128 mod icon: dark gradient background + an enlarged ski board."""
    size = 128
    icon = Image.new("RGBA", (size, size), (18, 28, 40, 255))
    for y in range(size):
        shade = 18 + int(28 * (y / size))
        for x in range(size):
            icon.putpixel((x, y), (shade // 2, shade, min(255, shade + 40), 255))
    icon.alpha_composite(modern_ski().resize((96, 96), Image.NEAREST), (16, 16))
    return icon


def main():
    for d in (TEX_ITEM, TEX_ENTITY, TEX_ARMOR):
        os.makedirs(d, exist_ok=True)
    outputs = {
        os.path.join(TEX_ITEM, "modern_ski.png"): modern_ski(),
        os.path.join(TEX_ITEM, "ski_pole.png"): pole_texture(),
        os.path.join(TEX_ENTITY, "ski_board.png"): ski_board(),
        os.path.join(TEX_ARMOR, "modern_ski.png"): armor_layer(),
        ICON: mod_icon(),
    }
    for path, image in outputs.items():
        image.save(path)
        print("written:", path, image.size)


if __name__ == "__main__":
    main()
