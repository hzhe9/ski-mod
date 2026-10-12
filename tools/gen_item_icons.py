"""把 6 款板子 + 滑雪杖的**物品图标换成真 3D 模型**（不再用 16x16 平面图）。

思路：物品模型（`assets/<ns>/models/item/*.json`）与方块模型同一套格式——
用 `elements` 直接放几何体，贴图沿用**脚上那套 128x32 实体贴图**（UV 同一份像素区，不用另画图）。

两处关键换算：
1. **UV 是 0..16 的名义坐标**（`FaceBakery` 里 `uvs.minU()/16.0F`），不是像素 —— 128 宽贴图：
   `u_json = 像素 / 8`；32 高：`v_json = 像素 / 2`。
2. **每个面的 uv 矩形按方块模型的约定贴**（`FaceBakery.defaultFaceUV`）：
   UP u→+x v→+z；DOWN u→+x v→−z；NORTH u→−x v→−y；SOUTH u→+x v→−y；WEST u→+z v→−y；EAST u→−z v→−y。
   实体模型的自动布局 UV 已知（见 `gen_assets.py` 注释与 mcrender 模板），
   所以按「顶点 → 贴图值」把实体那套 UV 搬到方块空间，方向不会错。

另外：**模型的 GUI 窗口只有一格（16x16x16）**，超出就裁掉 —— 所以图标几何体要缩到 0..16 里，
居中在 (8,8,8)；这比调 `display.gui` 的 translation/scale 可靠（原先的滑雪杖就是这么做的）。

用法: python gen_item_icons.py   （默认写入仓库；--dry 只渲染预览不落盘）
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # 仓库根（本脚本在 tools/ 下）
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "skis")
MODELS = os.path.join(RES, "models", "item")

ENTITIES = os.path.join(RES, "textures", "entity")
TEX_W, TEX_H = 128, 32          # 脚上贴图尺寸
TEX_PER_UNIT_U = TEX_W / 16.0   # u：1 名义单位 = 8 像素
TEX_PER_UNIT_V = TEX_H / 16.0   # v：1 名义单位 = 2 像素

VARIANTS = ["wood", "graphite", "topo", "brushed", "neon", "leopard"]
BASE = "wood"

# 图标的显示变换（GUI 里那一眼的角度）—— 只旋转不缩放，缩放交给几何体自己
GUI_DISPLAY = {
    "modern_ski": {"rotation": [-90, 20, -10], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "snowboard": {"rotation": [-90, 20, -10], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}

# 图标里要不要带雪鞋：不带 = 只看板子（小尺寸下更清楚，与原来的 2D 图标观感一致）；
# 带 = 连两只雪鞋一起，更像脚上那一套。
WITH_BOOTS = False


# ----------------------------------------------------------------- 实体空间几何
# (texOffs_u, texOffs_v, mnx, mny, mnz, dx, dy, dz, [绕 y 的角度])
def ski_boxes(side):
    """双板：单侧 = 雪鞋 + 板身 + 板头。side = +1 / -1 决定横向位置。"""
    x = 2.8 * side
    return [
        (0, 0, x - 2.0, 18.0, -2.05, 4, 6, 4, 0.0),     # 雪鞋（z 0.05 躲共面）
        (20, 0, x - 1.5, 23.0, -12.0, 3, 1, 24, 0.0),   # 板身
        (20, 26, x - 1.5, 22.3, -14.0, 3, 1, 2, 0.0),   # 板头上翘
    ]


def snowboard_boxes(side):
    """单板：板组绕 y 转 90°（横在脚下），鞋在板组外、与腿同坐标。"""
    if side == 0:
        yaw = 90.0
        return [
            (20, 0, -2.88, 23.0, -11.0, 5.76, 1, 22, yaw),   # 板身（宽 5.76 = 36 cm）
            (90, 0, -2.88, 22.3, -13.0, 5.76, 1, 2, yaw),    # 板头
            (90, 4, -2.88, 22.3, 11.0, 5.76, 1, 2, yaw),     # 板尾
        ]
    x = 2.8 * side
    return [(0, 0, x - 2.0, 18.0, -2.05, 4, 6, 4, 0.0)]  # 雪鞋


def rot_y(box, deg):
    """绕 y 轴转 deg（ModelPart 的右手 Y 旋转：(x,z) → (x cosθ + z sinθ, −x sinθ + z cosθ)）。
    90° 的整数倍会保持轴对齐；这里只支持 90 的倍数。"""
    quarter = int(round(deg / 90.0)) % 4
    u, v, mnx, mny, mnz, dx, dy, dz, _ = box
    for _ in range(quarter):
        # 四个水平角点映射后取包围盒
        pts = [(mnx, mnz), (mnx + dx, mnz), (mnx + dx, mnz + dz), (mnx, mnz + dz)]
        rp = [(p[1], -p[0]) for p in pts]
        xs = [p[0] for p in rp]
        zs = [p[1] for p in rp]
        mnx, mnz, dx, dz = min(xs), min(zs), max(xs) - min(xs), max(zs) - min(zs)
    return (u, v, mnx, mny, mnz, dx, dy, dz, 0.0)


def collect(board):
    """返回该板的盒子列表（已应用组旋转，实体空间；按 WITH_BOOTS 决定要不要鞋）。"""
    out = []
    if board == "modern_ski":
        for side in (1, -1):
            out += ski_boxes(side)
    else:
        out += snowboard_boxes(0)
        for side in (1, -1):
            out += snowboard_boxes(side)
    if not WITH_BOOTS:
        out = [b for b in out if not (b[5] == 4 and b[6] == 6 and b[7] == 4)]
    return out


# ----------------------------------------------------------------- UV 映射
def entity_uv_faces(box):
    """实体模型自动布局：6 个面各自的「像素 UV 关于实体坐标」的线性函数。

    返回 {方块面名: (u(函数), v(函数))}，函数参数是实体坐标 (ex, ey, ez)。
    系数来自 ModelPart.Cube 的布局（min-y 面 u1..u2 等），斜率恒为 1 像素/单位。
    """
    u, v, mnx, mny, mnz, dx, dy, dz, _ = box
    u0, u1, u2 = u, u + dz, u + dz + dx
    u22, u3, u4 = u + 2 * dx + dz, u + dz + dx + dz, u + 2 * dz + 2 * dx
    v0, v1, v2 = v, v + dz, v + dz + dy
    return {
        # 方块空间 UP ← 实体 min-y（视觉顶面）
        "up": (lambda ex, ey, ez: u1 + (ex - mnx), lambda ex, ey, ez: v1 - (ez - mnz)),
        # 方块空间 DOWN ← 实体 max-y
        "down": (lambda ex, ey, ez: u2 + (ex - mnx), lambda ex, ey, ez: v1 - (ez - mnz)),
        # 方块空间 EAST(+x) ← 实体 min-x（因为实体渲染 x 被翻转过）
        "east": (lambda ex, ey, ez: u1 - (ez - mnz), lambda ex, ey, ez: v1 + (ey - mny)),
        # 方块空间 WEST(−x) ← 实体 max-x
        "west": (lambda ex, ey, ez: u2 + (ez - mnz), lambda ex, ey, ez: v1 + (ey - mny)),
        # 方块空间 NORTH(−z) ← 实体 min-z
        "north": (lambda ex, ey, ez: u1 + (ex - mnx), lambda ex, ey, ez: v1 + (ey - mny)),
        # 方块空间 SOUTH(+z) ← 实体 max-z
        "south": (lambda ex, ey, ez: u3 - (ex - mnx), lambda ex, ey, ez: v1 + (ey - mny)),
    }


# 方块模型每个面的 uv 方向（defauLtFaceUV 的语义）：(u 轴, u 符号, v 轴, v 符号)
FACE_AXES = {
    "up": ("x", +1, "z", +1),
    "down": ("x", +1, "z", -1),
    "north": ("x", -1, "y", -1),
    "south": ("x", +1, "y", -1),
    "west": ("z", +1, "y", -1),
    "east": ("z", -1, "y", -1),
}


def element_for_box(box, to_model):
    """把一个实体盒子转成方块模型的一个 element（含 6 个面的 uv）。to_model: 实体坐标 → 模型坐标。"""
    u, v, mnx, mny, mnz, dx, dy, dz, _ = box
    uv_f = entity_uv_faces(box)

    def pt(ex, ey, ez):
        return [round(c, 4) for c in to_model(ex, ey, ez)]

    corners_entity = {
        "x": {0: mnx, 1: mnx + dx}, "y": {0: mny, 1: mny + dy}, "z": {0: mnz, 1: mnz + dz},
    }
    faces = {}
    for name, (uax, usgn, vax, vsgn) in FACE_AXES.items():
        fu, fv = uv_f[name]
        fixed = ({"x", "y", "z"} - {uax, vax}).pop()

        def corner(u_idx, v_idx):
            c = {}
            c[uax] = corners_entity[uax][u_idx]
            c[vax] = corners_entity[vax][v_idx]
            c[fixed] = next(iter(corners_entity[fixed].values()))
            return c

        # uv 槽位：v0=(minU,minV) v1=(minU,maxV) v2=(maxU,maxV) v3=(maxU,minV)
        # 其中 minU 在 u 轴的符号端：sign>0 取小端，sign<0 取大端
        u_lo, u_hi = (0, 1) if usgn > 0 else (1, 0)
        v_lo, v_hi = (0, 1) if vsgn > 0 else (1, 0)
        c_min = corner(u_lo, v_lo)     # (minU, minV)
        c_max = corner(u_hi, v_hi)     # (maxU, maxV)
        px = lambda c, fn: fn(c["x"], c["y"], c["z"])

        def uvjson(c):
            return [round(px(c, fu) / TEX_PER_UNIT_U, 3), round(px(c, fv) / TEX_PER_UNIT_V, 3)]

        faces[name] = {"uv": uvjson(c_min) + uvjson(c_max), "texture": "#tex"}

    # 方块空间的 from/to（取 8 个角点的包围盒）
    pts = [pt(mnx + (dx if i & 1 else 0), mny + (dy if i & 2 else 0), mnz + (dz if i & 4 else 0))
           for i in range(8)]
    frm = [round(min(p[k] for p in pts), 4) for k in range(3)]
    to = [round(max(p[k] for p in pts), 4) for k in range(3)]
    return {"from": frm, "to": to, "faces": faces}


def rot_vec(rot, p):
    """按 MC 的 display 旋转顺序（X→Y→Z，见 ItemTransform + JOML rotationXYZ=qz*qy*qx）转一个向量。"""
    x, y, z = p
    a = math.radians(rot[0])
    y, z = y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)
    a = math.radians(rot[1])
    x, z = x * math.cos(a) + z * math.sin(a), -x * math.sin(a) + z * math.cos(a)
    a = math.radians(rot[2])
    x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
    return (x, y, z)


def build_board_model(board, variant):
    """生成一个款式的 3D 物品模型 json。

    几何流程：实体空间 → 方块空间（居中到原点）→ **按 display 旋转算投影**，据此定缩放到一格
    （11 单位，留出旋转余量）→ 平移回 (8,8,8)。旋转交给模型的 display，不在坐标里烘焙。
    """
    boxes = collect(board)
    to_block = lambda ex, ey, ez: (ex, 24.0 - ey, ez)   # 不做 x 镜像（与脚上视图的斜向一致）
    rot = GUI_DISPLAY[board]["rotation"]

    def corners_of(b):
        return [to_block(b[2] + (b[5] if i & 1 else 0), b[3] + (b[6] if i & 2 else 0),
                         b[4] + (b[7] if i & 4 else 0)) for i in range(8)]

    pts = [p for b in boxes for p in corners_of(b)]
    lo = [min(p[k] for p in pts) for k in range(3)]
    hi = [max(p[k] for p in pts) for k in range(3)]
    center = [(lo[k] + hi[k]) / 2.0 for k in range(3)]
    rel = [(p[0] - center[0], p[1] - center[1], p[2] - center[2]) for p in pts]
    spun = [rot_vec(rot, p) for p in rel]
    spans = [max(p[k] for p in spun) - min(p[k] for p in spun) for k in range(3)]
    scale = 11.0 / max(spans)
    # 旋转后的包围盒中心回到 (8,8,8)：把修正量放回「旋转前」的坐标里（offs 保持 8）
    pcen = [(max(p[k] for p in spun) + min(p[k] for p in spun)) / 2.0 for k in range(3)]
    fix = rot_vec([-rot[0], -rot[1], -rot[2]], (-pcen[0], -pcen[1], -pcen[2]))
    offs = [8.0, 8.0, 8.0]

    def to_model(ex, ey, ez):
        p = to_block(ex, ey, ez)
        return tuple((p[k] - center[k] + fix[k]) * scale + offs[k] for k in range(3))

    # 物品模型只能引用 textures/item/（entity/ 不进物品图集 → 图标紫黑缺失）
    tex = f"skis:item/{'ski_board' if board == 'modern_ski' else 'snowboard'}"
    if variant != BASE:
        tex += f"_{variant}"
    return {
        "parent": "minecraft:item/handheld",
        "display": {"gui": GUI_DISPLAY[board]},
        "textures": {"tex": tex, "particle": tex},
        "elements": [element_for_box(b, to_model) for b in boxes],
    }


# ----------------------------------------------------------------- 滑雪杖 GUI 模型
def build_pole_gui():
    """滑雪杖：把手上那根真 3D 杖缩进一格（uv 照抄原模型，只改几何坐标）。"""
    src = json.load(open(os.path.join(MODELS, "ski_pole.json"), encoding="utf-8"))
    els = src["elements"]
    ys = [c for e in els for c in (e["from"][1], e["to"][1])]
    zs = [c for e in els for c in (e["from"][2], e["to"][2])]
    xs = [c for e in els for c in (e["from"][0], e["to"][0])]
    span_y = max(ys) - min(ys)
    scale = 12.0 / span_y
    cx = 8.0 - (min(xs) + max(xs)) / 2.0 * scale
    cy = 8.0 - (min(ys) + max(ys)) / 2.0 * scale
    cz = 8.0 - (min(zs) + max(zs)) / 2.0 * scale
    out = []
    for e in els:
        out.append({
            "from": [round(e["from"][0] * scale + cx, 4), round(e["from"][1] * scale + cy, 4),
                     round(e["from"][2] * scale + cz, 4)],
            "to": [round(e["to"][0] * scale + cx, 4), round(e["to"][1] * scale + cy, 4),
                   round(e["to"][2] * scale + cz, 4)],
            "faces": e["faces"],
        })
    return {
        "textures": src["textures"],
        "display": {"gui": {"rotation": [6, 22, -8], "translation": [0, 0, 0], "scale": [1, 1, 1]}},
        "elements": out,
    }


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("written:", os.path.relpath(path, ROOT))


def main():
    dry = "--dry" in sys.argv
    for board in ("modern_ski", "snowboard"):
        for variant in VARIANTS:
            sfx = "" if variant == BASE else "_" + variant
            model = build_board_model(board, variant)
            if not dry:
                write_json(os.path.join(MODELS, f"{board}{sfx}.json"), model)
    if not dry:
        write_json(os.path.join(MODELS, "ski_pole_gui.json"), build_pole_gui())
    print("done", "(dry)" if dry else "")


if __name__ == "__main__":
    main()
