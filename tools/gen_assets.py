"""生成 skis 模组的全部贴图与模型贴图。

产物：
  assets/skis/textures/item/modern_ski.png                      物品图标（滑雪板，平面 16x16）
  assets/skis/textures/item/ski_pole.png                        滑雪杖 3D 模型贴图（64x64，三个 UV 分区）
  assets/skis/textures/item/ski_pole_icon.png                   滑雪杖物品栏图标（16x16，GUI 用）
  assets/skis/textures/entity/ski_board.png                     脚上模型贴图：雪鞋 + 板身 + 板头（128x32）
  assets/skis/textures/entity/snowboard.png                     单板脚上模型贴图：雪鞋 + 单板板身 + 板头（128x32）
  assets/skis/textures/item/snowboard.png                       单板物品图标（16x16）
  assets/skis/textures/entity/equipment/humanoid/modern_ski.png 原版护甲层贴图（备用，128x32 的旧方案）
  assets/skis/icon.png                                          mod 图标

模型 UV 约定：模型 JSON / CubeListBuilder 里的 uv 以 16 为单位（与原版一致），
64x64 贴图上一个 UV 单位 = 4 像素。滑雪杖三个分区：
  杖身 u 0-4   v 0-8      -> 像素 (0,0)-(16,32)
  握把 u 6-10  v 0-4      -> 像素 (24,0)-(40,16)
  挡雪盘 u 12-16 v 0-4    -> 像素 (48,0)-(64,16)

ski_board.png 用的是 CubeListBuilder 的自动 UV 布局，所以各面落在固定位置：
  雪鞋 box(4,6,4) @texOffs(0,0)    -> 占 (0,0)-(16,10)
  板身 box(3,1,24) @texOffs(20,0)  -> 占 (20,0)-(76,25)，其中板面=up(44,0,3x24)、板底=down(47,0,3x24)、侧面=第 24 行
  板头 box(3,1,2) @texOffs(20,26)  -> 占 (20,26)-(30,30)
  ⚠ 板身长度一改，上面这几个数字全都要重算（宽 = 2×长 + 2×宽、板面列 = 20+长 …），
     并且板头区不能压到「侧面那一行」，否则两处抢同一批像素。

脚部护甲层贴图以原版 iron.png 的靴子像素为模板（只保留靴子用到的 texel 再重上色）。

用法: python tools/gen_assets.py
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

# loom 缓存的 26.2 客户端 jar —— 里面有人形护甲层贴图可作模板
VANILLA_JAR = os.path.join(
    os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", "26.2", "minecraft-client.jar"
)

# 配色
BODY = (32, 40, 48, 255)          # 石墨黑
ACCENT = (41, 196, 255, 255)      # 现代青
TIP = (240, 248, 255, 255)        # 板头白
BINDING = (200, 210, 218, 255)
BASE = (228, 236, 244, 255)       # 板底
SHAFT = (198, 208, 216, 255)      # 铝合金杖身
SHAFT_HI = (235, 242, 249, 255)
GRIP = (36, 40, 45, 255)
GRIP_HI = (86, 94, 104, 255)
BASKET = (255, 138, 40, 255)
BASKET_DK = (196, 90, 16, 255)
BOOT = (26, 38, 54, 255)          # 雪鞋
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
    """物品图标：一对平行斜放的滑雪板，板身青色条纹，板头白色。"""
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
    """滑雪杖 3D 模型贴图：64x64，杖身 / 握把 / 挡雪盘三个 UV 分区。"""
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
    """脚上模型贴图（128x32）：雪鞋 + 板身 + 上翘板头，按 CubeListBuilder 自动 UV 分区。"""
    img = blank(128, 32)

    # 雪鞋：box(4,6,4) @texOffs(0,0) -> (0,0)-(16,10)
    fill(img, 0, 0, 16, 10, BOOT)
    fill(img, 0, 1, 16, 3, BOOT_HI)          # 鞋口一圈
    fill(img, 0, 7, 16, 9, BOOT_HI)          # 脚踝处
    for x in range(1, 16, 5):                # 卡扣
        put(img, x, 4, BOOT_BUCKLE)
        put(img, x + 1, 4, BOOT_BUCKLE)

    # 板身：box(3,1,24) @texOffs(20,0) -> up(44,0,3x24) / down(47,0,3x24) / 侧面在第 24 行
    fill(img, 44, 0, 47, 24, BODY)           # 板面（up）
    fill(img, 44, 0, 47, 5, TIP)             # 前段板头区
    fill(img, 45, 5, 46, 24, ACCENT)         # 板面中线
    fill(img, 47, 0, 50, 24, BASE)           # 板底（down）
    fill(img, 20, 24, 76, 25, BASE)          # 板侧一圈
    fill(img, 20, 24, 44, 25, TIP)           # 前段侧面

    # 板头上翘：box(3,1,2) @texOffs(20,26) -> (20,26)-(30,30)
    fill(img, 20, 26, 30, 30, TIP)
    fill(img, 22, 26, 25, 28, ACCENT)
    return img


def pole_icon():
    """物品栏图标：一根竖直的滑雪杖（握把在上、杖身居中、挡雪盘在下）。"""
    img = blank()
    # 杖身（竖直，贯穿上下）
    fill(img, 7, 1, 9, 15, SHAFT)
    fill(img, 7, 1, 8, 15, SHAFT_HI)          # 左半高光
    outline = (120, 132, 144, 255)
    fill(img, 9, 1, 10, 15, outline)          # 右缘描边，让细杆更清楚
    # 握把（顶部，比杖身略粗、更深）
    fill(img, 6, 0, 10, 4, GRIP)
    fill(img, 6, 0, 10, 1, GRIP_HI)
    # 挡雪盘（靠近杖尖：距底 2 像素）
    fill(img, 4, 12, 12, 14, BASKET)
    fill(img, 4, 13, 12, 14, BASKET_DK)
    # 杖尖
    fill(img, 7, 14, 9, 16, SHAFT)
    return img


def snowboard_item():
    """物品图标：一块斜放的滑雪单板（twin-tip：两头一样白、比双板宽，两道固定器横带）。"""
    img = blank()
    length = 13
    x0, y0 = 1, 14
    for t in range(length):
        x, y = x0 + t, y0 - t
        color = TIP if (t < 2 or t >= length - 2) else BODY   # 两端都白（双翘对称）
        for w in range(2):                                    # 窄板：宽 ≈ 长的 1/6（现实板腰 25 : 板长 150）
            put(img, x + w, y, color)
            put(img, x + w, y + 1, color)
        if t in (4, 5, 8, 9):
            for w in range(2):
                put(img, x + w, y, BINDING)
    return img


def snowboard_sheet():
    """单板脚上模型贴图（128x32）：雪鞋 + 板身 box(4,1,22) + 板头/板尾两块上翘 box(4,1,2)。

    twin-tip：板身前后各 11、宽 4 单位（= 25 cm，现实板腰宽度），两端各接一块翘头，
    **板面花纹左右（前后）对称** —— 两端各一段白 + 中间石墨黑底 + 青中线。
    ⚠ 板身宽度一改，下面所有 u 数字都要重算（宽 = 2×长 + 2×宽）。

    CubeListBuilder 自动 UV 布局：
      雪鞋 box(4,6,4) @texOffs(0,0)   -> (0,0)-(16,10)
      板身 box(4,1,22) @texOffs(20,0) -> 占 (20,0)-(72,23)；板面 up=(42,0)-(46,22)、板底 down=(46,0)-(50,22)、侧面=第 22 行
      板头 box(4,1,2) @texOffs(90,0)  -> 占 (90,0)-(102,3)
      板尾 box(4,1,2) @texOffs(90,4)  -> 占 (90,4)-(102,7)
    """
    img = blank(128, 32)

    # 雪鞋（两只共用同一 UV 区）
    fill(img, 0, 0, 16, 10, BOOT)
    fill(img, 0, 1, 16, 3, BOOT_HI)
    fill(img, 0, 7, 16, 9, BOOT_HI)
    for x in range(1, 16, 5):
        put(img, x, 4, BOOT_BUCKLE)
        put(img, x + 1, 4, BOOT_BUCKLE)

    # 板面（twin-tip：两端白段对称，中间黑底 + 青色中线），v 轴两端各占 5
    fill(img, 42, 0, 46, 22, BODY)
    fill(img, 42, 0, 46, 5, TIP)
    fill(img, 42, 17, 46, 22, TIP)
    fill(img, 43, 5, 45, 17, ACCENT)
    # 板底
    fill(img, 46, 0, 50, 22, BASE)
    # 板侧一圈（整圈同色 —— 对称）
    fill(img, 20, 22, 72, 23, BASE)

    # 上翘板头 / 板尾：两块同款（白底 + 中间青条，青条落在各自的“板面”UV 区里）
    for y0 in (0, 4):
        fill(img, 90, y0, 102, y0 + 3, TIP)
        fill(img, 93, y0, 95, y0 + 2, ACCENT)
    return img


def armor_layer():
    """旧方案备用：取原版 iron.png 的靴子像素作模板，重上色。"""
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
    """128x128 mod 图标：深色渐变底 + 放大的滑雪板。"""
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
        os.path.join(TEX_ITEM, "ski_pole_icon.png"): pole_icon(),
        os.path.join(TEX_ITEM, "snowboard.png"): snowboard_item(),
        os.path.join(TEX_ENTITY, "ski_board.png"): ski_board(),
        os.path.join(TEX_ENTITY, "snowboard.png"): snowboard_sheet(),
        os.path.join(TEX_ARMOR, "modern_ski.png"): armor_layer(),
        ICON: mod_icon(),
    }
    for path, image in outputs.items():
        image.save(path)
        print("written:", path, image.size)


if __name__ == "__main__":
    main()
