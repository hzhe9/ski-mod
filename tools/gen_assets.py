"""生成 skis 模组的全部贴图（6 种款式）。

产物（`<v>` = 款式 key：wood / graphite / topo / brushed / neon / leopard；
       **wood 是基础款，文件不带后缀**）：
  assets/skis/textures/entity/ski_board[_<v>].png      双板脚上模型贴图（128x32）
  assets/skis/textures/entity/snowboard[_<v>].png      单板脚上模型贴图（128x32）
  assets/skis/textures/item/ski_board[_<v>].png        上两者的同名副本 —— 物品图标模型专用（entity/ 不进物品图集）
  assets/skis/textures/item/snowboard[_<v>].png        同上（单板）
  assets/skis/textures/item/ski_pole.png               滑雪杖 3D 模型贴图（64x64）
  assets/skis/textures/item/ski_helmet.png             滑雪头盔图标（16x16 平面图，普通物品 → item/generated）
  assets/skis/textures/entity/equipment/humanoid/modern_ski.png 备用护甲层（旧方案）
  assets/skis/icon.png                                 mod 图标（底板上贴一块石墨款板子图形）

物品栏图标**不在本脚本里**：6 款板 + 杖的图标都是真 3D 模型（`elements` 复用上面的实体贴图），
生成器是 `tools/gen_item_icons.py`；旧的 16x16 平面图标方案已废弃、连文件一起删掉了。

UV 约定（CubeListBuilder 自动布局；⚠ 板面 v 轴与板的前后方向**相反**，v=0 在板尾，
所以「板头那一段」画在 v 的高端）：
  双板 板身 box(3,1,24) @texOffs(20,0) -> 占 (20,0)-(74,25)：板面 up=(44,0)3x24、板底 down=(47,0)3x24、侧面=第 24 行
       板头 box(3,1,2) @texOffs(20,26)  -> 占 (20,26)-(30,30)
       雪鞋 box(4,6,4) @texOffs(0,0)    -> 占 (0,0)-(16,10)
  单板 板身 box(4,1,22) @texOffs(20,0) -> 占 (20,0)-(72,23)：板面 up=(42,0)4x22、板底 down=(46,0)4x22、侧面=第 22 行
       板头 box(4,1,2) @texOffs(90,0)  -> (90,0)-(102,3)｜板尾 box(4,1,2) @texOffs(90,4) -> (90,4)-(102,7)
  ⚠ 改板身尺寸（长/宽）会整体挪动上面所有 u 数字，必须同步改；小件的新区域不能压到「侧面那一行」。

滑雪杖贴图 64x64：杖身 u0-4 / 握把 u6-10 / 挡雪盘 u12-16（一个 UV 单位 = 4 像素）。
备用护甲层以原版 iron.png 的靴子像素为模板（只保留靴子用到的 texel 再重上色）。

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

VANILLA_JAR = os.path.join(
    os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", "26.2", "minecraft-client.jar"
)

# 与款式无关的固定配色（杖、备用护甲层）
SHAFT = (198, 208, 216, 255)
SHAFT_HI = (235, 242, 249, 255)
GRIP = (36, 40, 45, 255)
GRIP_HI = (86, 94, 104, 255)
BASKET = (255, 138, 40, 255)
BASKET_DK = (196, 90, 16, 255)
ARMOR_DARK = (30, 44, 62, 255)
ARMOR_MID = (46, 74, 104, 255)
ARMOR_BOARD = (226, 238, 248, 255)
ARMOR_ACCENT = (41, 196, 255, 255)

# ---------------------------------------------------------------- 滑雪头盔
# 滑雪头盔是**普通物品**（不可穿戴），图标用 item/generated 的 16x16 平面图 ——
# 不做 3D elements（那是板与杖的图标管线的活）。配色与 graphite 款板同色系：
# 深色壳 + 青色装饰条 + 白色镜框 + 橙色镜片。字符表驱动：一行 = 一像素行，'.' = 透明。
HELMET_PALETTE = {
    "S": (38, 54, 76, 255),      # 壳
    "D": (22, 32, 46, 255),      # 壳（轮廓 / 阴影）
    "A": (41, 196, 255, 255),    # 装饰条（与板同款青）
    "F": (238, 244, 250, 255),   # 护目镜框
    "L": (255, 168, 48, 255),    # 护目镜镜片
}
HELMET_ART = [
    "................",
    ".....DDDDDD.....",
    "...DDSSSSSSSD...",
    "..DSSSAAAAASSD..",
    ".DSSSAAAAAAASSD.",
    ".DSSSSSSSSSSSSD.",
    "DSSSSSSSSSSSSSSD",
    "DSFFFFFFFFFFFFSD",
    "DSFLLLLLLLLLLFSD",
    "DSFLLLLLLLLLLFSD",
    "DSFFFFFFFFFFFFSD",
    "DSSSSSSSSSSSSSSD",
    ".DSSSSSSSSSSSSD.",
    ".DDSSSSSSSSSSDD.",
    "..DDDDDDDDDDDD..",
    "................",
]

# ---------------------------------------------------------------- 款式表
# ops = [(l0, l1, w0, w1, color)]：l=1 是板头侧、w=0/1 是板的两侧；按顺序覆盖，后者优先。
# 靴子跟着款式走：boot = 鞋身、boot_hi = 鞋口/脚踝、buckle = 卡扣。
VARIANTS = [
    dict(key="wood", zh="木纹", en="Wood Grain",
         top=(150, 102, 58),
         ops=[(0.0, 1.0, 0.24, 0.38, (104, 68, 36)), (0.0, 1.0, 0.64, 0.78, (104, 68, 36)),
              (0.80, 0.86, 0.0, 1.0, (240, 240, 240)), (0.86, 1.0, 0.0, 1.0, (30, 30, 32))],
         base=(54, 40, 28), side=(120, 82, 46), nose_side=(30, 30, 32),
         tip=(30, 30, 32), tip_up=(150, 102, 58),
         boot=(78, 54, 32), boot_hi=(150, 102, 58), buckle=(240, 240, 240),
         icon=dict(main=(150, 102, 58), accent=(104, 68, 36), tip=(30, 30, 32), binding=(240, 240, 240))),
    dict(key="graphite", zh="石墨", en="Graphite",
         top=(32, 40, 48),
         ops=[(0.0, 0.74, 0.34, 0.66, (41, 196, 255)), (0.74, 1.0, 0.0, 1.0, (240, 248, 255))],
         base=(228, 236, 244), side=(228, 236, 244), nose_side=(240, 248, 255),
         tip=(240, 248, 255), tip_up=(41, 196, 255),
         boot=(26, 38, 54), boot_hi=(58, 88, 120), buckle=(206, 214, 222),
         icon=dict(main=(32, 40, 48), accent=(41, 196, 255), tip=(240, 248, 255), binding=(206, 214, 222))),
    dict(key="topo", zh="等高线", en="Topo",
         top=(216, 222, 216),
         ops=[(0.0, 1.0, 0.45, 0.55, (150, 164, 156)),
              (0.16, 0.21, 0.0, 1.0, (110, 124, 118)), (0.40, 0.45, 0.0, 1.0, (110, 124, 118)),
              (0.62, 0.67, 0.0, 1.0, (110, 124, 118)), (0.86, 0.94, 0.0, 1.0, (232, 110, 40))],
         base=(54, 60, 64), side=(54, 60, 64), nose_side=(232, 110, 40),
         tip=(232, 110, 40), tip_up=(54, 60, 64),
         boot=(58, 66, 64), boot_hi=(108, 124, 118), buckle=(232, 110, 40),
         icon=dict(main=(216, 222, 216), accent=(110, 124, 118), tip=(232, 110, 40), binding=(54, 60, 64))),
    dict(key="brushed", zh="拉丝", en="Brushed",
         top=(196, 204, 212),
         ops=[(0.0, 1.0, 0.30, 0.70, (30, 58, 102)),
              (0.78, 0.80, 0.0, 1.0, (216, 60, 60)), (0.80, 1.0, 0.0, 1.0, (30, 58, 102))],
         base=(30, 58, 102), side=(168, 178, 188), nose_side=(216, 60, 60),
         tip=(30, 58, 102), tip_up=(216, 60, 60),
         boot=(44, 56, 70), boot_hi=(168, 178, 188), buckle=(216, 60, 60),
         icon=dict(main=(196, 204, 212), accent=(30, 58, 102), tip=(216, 60, 60), binding=(168, 178, 188))),
    dict(key="neon", zh="霓虹", en="Neon",
         top=(86, 44, 140),
         ops=[(0.04, 0.18, 0.0, 1.0, (232, 60, 140)), (0.24, 0.36, 0.0, 1.0, (255, 138, 40)),
              (0.44, 0.56, 0.0, 1.0, (190, 230, 60)), (0.62, 0.74, 0.0, 1.0, (41, 196, 255)),
              (0.84, 1.0, 0.0, 1.0, (255, 240, 80))],
         base=(26, 20, 40), side=(232, 60, 140), nose_side=(255, 240, 80),
         tip=(255, 240, 80), tip_up=(232, 60, 140),
         boot=(60, 32, 84), boot_hi=(232, 60, 140), buckle=(190, 230, 60),
         icon=dict(main=(86, 44, 140), accent=(232, 60, 140), tip=(255, 240, 80), binding=(190, 230, 60))),
    dict(key="leopard", zh="豹纹", en="Leopard",
         top=(226, 206, 166),
         ops=[(0.10, 0.20, 0.0, 0.55, (40, 36, 32)), (0.26, 0.36, 0.45, 1.0, (40, 36, 32)),
              (0.42, 0.52, 0.0, 0.55, (40, 36, 32)), (0.58, 0.68, 0.45, 1.0, (40, 36, 32)),
              (0.74, 0.84, 0.0, 0.55, (40, 36, 32)), (0.90, 1.0, 0.45, 1.0, (40, 36, 32))],
         base=(40, 36, 32), side=(40, 36, 32), nose_side=(226, 206, 166),
         tip=(226, 206, 166), tip_up=(40, 36, 32),
         boot=(48, 44, 40), boot_hi=(226, 206, 166), buckle=(40, 36, 32),
         icon=dict(main=(226, 206, 166), accent=(40, 36, 32), tip=(226, 206, 166), binding=(40, 36, 32))),
]
BASE_KEY = "wood"      # 基础款：注册 id 的默认模型 / 贴图不带后缀


def blank(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def put(img, x, y, color):
    if len(color) == 3:
        color = color + (255,)
    if 0 <= x < img.size[0] and 0 <= y < img.size[1]:
        img.putpixel((x, y), color)


def fill(img, x0, y0, x1, y1, color):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(img, x, y, color)


def paint_top(img, rect, variant, twin):
    """按款式的 ops 画板面。l=1 在板头（v 高端）；twin=True 时 l 取对称值（单板两头镜像）。"""
    x0, y0, x1, y1 = rect
    wpx, hpx = x1 - x0, y1 - y0
    for y in range(y0, y1):
        l = (y - y0) / max(1, hpx - 1)
        if twin:
            l = abs(2.0 * l - 1.0)
        for x in range(x0, x1):
            w = (x - x0) / max(1, wpx - 1)
            color = variant["top"]
            for (l0, l1, w0, w1, c) in variant["ops"]:
                if l0 <= l <= l1 and w0 <= w <= w1:
                    color = c
            put(img, x, y, color)


def draw_boot(img, v):
    """雪鞋：鞋身用该款深色、鞋口/脚踝用亮色、卡扣用点缀色。"""
    fill(img, 0, 0, 16, 10, v["boot"])
    fill(img, 0, 1, 16, 3, v["boot_hi"])
    fill(img, 0, 7, 16, 9, v["boot_hi"])
    for x in range(1, 16, 5):
        put(img, x, 4, v["buckle"])
        put(img, x + 1, 4, v["buckle"])


def ski_board(v):
    """双板脚上模型贴图（128x32）：雪鞋 + 板身 box(3,1,24) + 板头 box(3,1,2)。"""
    img = blank(128, 32)
    draw_boot(img, v)
    paint_top(img, (44, 0, 47, 24), v, twin=False)     # 板面
    fill(img, 47, 0, 50, 24, v["base"])                # 板底
    fill(img, 20, 24, 74, 25, v["side"])               # 板侧一圈
    fill(img, 39, 24, 44, 25, v["nose_side"])          # 西长边靠板头（它的 u 高端＝板头侧）
    fill(img, 47, 24, 52, 25, v["nose_side"])          # 东长边（它的 u 低端是板头）
    fill(img, 20, 26, 30, 30, v["tip"])                # 板头上翘
    fill(img, 22, 26, 25, 28, v["tip_up"])
    return img


def snowboard_sheet(v):
    """单板脚上模型贴图（128x32）：雪鞋 + 板身 box(5.76,1,22) + 板头/板尾 box(5.76,1,2) 各一（twin-tip）。"""
    img = blank(128, 32)
    draw_boot(img, v)
    fill(img, 47, 0, 54, 22, v["base"])                # 板底：u47.76..53.52（先画底）
    paint_top(img, (42, 0, 48, 22), v, twin=True)      # 板面（两头镜像）：宽 5.76 → u42..47.76，画到 48；后画，盖掉与板底重叠的 0.24px
    fill(img, 20, 22, 76, 23, v["side"])               # 板侧一圈（2d+2w = 55.52 → 画到 76）
    for y0 in (0, 4):                                  # 板头 / 板尾同款
        fill(img, 90, y0, 106, y0 + 3, v["tip"])       # 翘头侧带 2d+2w = 15.52 → 画到 106
        fill(img, 92, y0, 98, y0 + 2, v["tip_up"])     # 翘头板面 d..d+w = 2..7.76 → 画到 98
    return img


def ski_icon(v):
    """16x16 的高山滑雪板图形：**只给 mod 图标用**（`mod_icon()` 把它放大后贴在底板上）。
    物品栏图标是真 3D 模型，不走这里。"""
    c = v["icon"]
    img = blank()
    length = 11
    for offset in (0, 3):
        x0, y0 = 2 + offset, 14
        for t in range(length):
            x, y = x0 + t, y0 - t
            color = c["tip"] if t >= length - 2 else c["main"]
            put(img, x, y, color)
            put(img, x, y + 1, color)
            if t in (2, 6) and color == c["main"]:
                put(img, x, y, c["accent"])
        put(img, x0 + 5, y0 - 5, c["binding"])
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


def helmet_texture():
    """滑雪头盔图标（16x16）：按 HELMET_ART 的字符表落像素。"""
    img = blank(16, 16)
    for y, row in enumerate(HELMET_ART):
        assert len(row) == 16, f"HELMET_ART 第 {y} 行宽 {len(row)}（应为 16）"
        for x, ch in enumerate(row):
            if ch != ".":
                put(img, x, y, HELMET_PALETTE[ch])
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
    """128x128 mod 图标：深色渐变底 + 石墨款滑雪板。"""
    size = 128
    icon = Image.new("RGBA", (size, size), (18, 28, 40, 255))
    for y in range(size):
        shade = 18 + int(28 * (y / size))
        for x in range(size):
            icon.putpixel((x, y), (shade // 2, shade, min(255, shade + 40), 255))
    graphite = next(v for v in VARIANTS if v["key"] == "graphite")
    icon.alpha_composite(ski_icon(graphite).resize((96, 96), Image.NEAREST), (16, 16))
    return icon


def main():
    for d in (TEX_ITEM, TEX_ENTITY, TEX_ARMOR):
        os.makedirs(d, exist_ok=True)
    outputs = {
        os.path.join(TEX_ITEM, "ski_pole.png"): pole_texture(),
        os.path.join(TEX_ITEM, "ski_helmet.png"): helmet_texture(),
        os.path.join(TEX_ARMOR, "modern_ski.png"): armor_layer(),
        ICON: mod_icon(),
    }
    for v in VARIANTS:
        sfx = "" if v["key"] == BASE_KEY else "_" + v["key"]
        outputs[os.path.join(TEX_ENTITY, f"ski_board{sfx}.png")] = ski_board(v)
        outputs[os.path.join(TEX_ENTITY, f"snowboard{sfx}.png")] = snowboard_sheet(v)
        # 同名副本进 textures/item/：**物品模型只能引用织进物品图集的贴图**（textures/entity/ 不进图集，
        # 引用它 = 图标紫黑缺失；脚上模型走实体渲染器直接读文件，不受影响）。两份内容始终一致。
        outputs[os.path.join(TEX_ITEM, f"ski_board{sfx}.png")] = ski_board(v)
        outputs[os.path.join(TEX_ITEM, f"snowboard{sfx}.png")] = snowboard_sheet(v)
    for path, image in outputs.items():
        image.save(path)
        print("written:", os.path.relpath(path, ROOT), image.size)


if __name__ == "__main__":
    main()
