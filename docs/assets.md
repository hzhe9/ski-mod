# 资源管线（贴图 / 模型 / 图标 / 款式）

> 生成器怎么跑、产物有哪些、几何与 UV 的约定。

## 物品图标（板与杖：真 3D）

**板与杖**的图标不是 16x16 平面图，而是**方块模型格式的 3D `elements` 模型**，贴图用 `textures/item/ski_board|snowboard[_<款>].png`
—— 脚上那套 `textures/entity/*.png` 的**同名副本**（`gen_assets.py` 一次写两份，6 款配色自动跟着走）。
⚠️ **物品模型不能引用 `textures/entity/`**：物品贴图必须织进物品图集，只有 `textures/item/` 下的才行 ——
引用 `entity/` 的症状是**图标紫黑缺失**（日志 `Missing textures in model skis:item/…`）；脚上模型走实体渲染器直接读文件、不受影响，
所以会表现为「脚上正常、图标坏」。生成：

```bash
python tools/gen_item_icons.py
```

它把 `AlpineSkiModel` / `SnowboardModel` 的 addBox 几何按「实体空间 → 方块空间 → 按 display 旋转算投影 →
缩放居中到一格 (8,8,8)」生成模型，并逐面算出 uv（方块模型的 `uv` 是 0..16 名义坐标、不是像素；
每面方向按 `FaceBakery.defaultFaceUV`）。

| 物品 | `display.gui.rotation` | 说明 |
|---|---|---|
| `modern_ski` | `[-90, 20, 170]` | 竖起来 + 一点侧倾（与单板同角度）。**作用顺序 = 先 Z 后 Y 后 X**（v′=Rx·Ry·Rz，见下方关键坑）：rx=−90 定上下（板头朝下＝用户认可的摆放）、rz=170 把花纹面翻朝镜头（rz 若是 −10 就露出底面）。实机三轮定案 |
| `snowboard` | `[-90, 20, 170]` | 竖起来 + 一点侧倾（同上） |
| `ski_pole_gui` | `[6, 22, -8]` | 一点倾斜，露出两个面 |

**关键坑**：方块模型 y 朝上 ⇒ `up` 面在 **max y**，与实体模型（y 向下）相反；
`display` 旋转的**作用顺序是先 Z、再 Y、最后 X**（矩阵 v′ = Rx·Ry·Rz·v）—— 依据：原版 `ItemTransform.apply`
依次 `mulPose(XP/YP/ZP)` 而 PoseStack 是**后乘**（代码顺序 X,Y,Z ⇒ 向量上 Z 最先），Blockbench 显示预览
（three.js Euler `'XYZ'` → Rx·Ry·Rz）同构，且本工程实机三轮反馈全部吻合。⚠ 别把代码顺序当成作用顺序
（旧文档写「X→Y→Z」，连错三轮）。细长扁平的物品要把长边摆斜必须用 **rz**，只改 rx 只会把宽度转进来（板子仍是一条线）。
改角度前先跑一次「脚上模型 vs 图标模型同相机对照」自证朝向。

### 手上 / 地面模型（`*_hand.json`）

拿在手上的**不是图标几何**，而是**脚上模型的原尺寸几何**（去雪鞋、不缩放：双板全长 26 单位 ≈ 162 cm，
单板 26 单位含翘头；`build_hand_model()` 只做实体→方块空间换算 + 居中），由 `gen_item_icons.py` 一并生成
12 个 `<id>[_<款>]_hand.json`。**GUI 与手上的分流**照滑雪杖的模式，写在 `items/` 定义里（`display_context` select）：

```
gui → skis:item/<id>（图标模型）        fallback（1/3 人称、地面、展示框…）→ skis:item/<id>_hand
```

手上 display：**1 人称竖持** `[90, 0, 0]`（板面朝镜头 + 板竖起来）；
**3 人称 = 现实夹板拿法** `[90, 0, ±90]`（板面转到侧向贴体、夹在手臂与身体之间），
`translation [-2, 1, 0.5]`（x=朝手臂内侧——实测 +2 落外侧故取反，左手由 `applyLeftHandFix` 自动镜像、两侧同语义；y=板心抬到腋下高度；z=收回体前），
缩放 3 人称 0.85 / 1 人称 0.75（`translation [1.13,-4,1.13]`，照滑雪杖换算）。
（常量与轴向依据见 `gen_item_icons.py` 的 `HAND_DISPLAY` 注释。）

## 贴图与模型生成

```bash
python tools/gen_assets.py      # 贴图（脚上 entity/ + 图标 item/ 副本，一次写两份）
python tools/gen_item_icons.py  # 物品栏的 3D 图标模型（贴图用 item/ 那份）
```

两个生成器都要在**项目自带 venv** 下跑（依赖固定写在 `requirements.txt`）——建环境两行命令见
[`dev-setup.md`](dev-setup.md)「准备 Python 环境」。同一套 venv 也用来跑 `tools/check_docs.py`。

**物品栏图标不是贴图**：6 款板 + 杖的图标都是 `elements` 形式的 3D 物品模型（见上节），贴图用下表实体贴图的 `textures/item/` 同名副本 ——
所以换配色只跑 `gen_assets.py`（两份同写）、图标跟着变。

款式全在 `tools/gen_assets.py` 顶部的 `VARIANTS` 表里（`top` 板面底色、`ops` 板面花纹区间、`base/side/tip`
板身其他面、`boot/boot_hi/buckle` 雪鞋三色；`icon` 四色只给 `icon.png` 里那块板子用）。**`wood` 是基础款，产物文件名不带后缀**，
其余 5 款加 `_<款>`。加一款 = 表里加一条 + 补 `models/item/<id>_<款>.json` + 补 `models/item/<id>_<款>_hand.json`（两者都由 `gen_item_icons.py` 生成）+ 补 `items/item/<id>_<款>.json`（**select 分流**：gui→图标、fallback→hand；缺了图标=紫黑棋盘格）+ 一条衍生配方 + 两个 lang 键。
⚠ 板面 UV 的 v 轴与板的前后方向相反（v=0 是板尾），板头那一段要画在 v 的高端；改板身长宽会整体挪动所有 u 数字。

产物（全部在 `src/main/resources/assets/skis/`；`[_<款>]` = 6 个款式各一份，共 **27 个 png**）：

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `textures/entity/ski_board[_<款>].png` | 128×32 | 双板脚上模型（雪鞋 + 板身 + 板头） |
| `textures/entity/snowboard[_<款>].png` | 128×32 | 单板脚上模型 |
| `textures/item/ski_board[_<款>].png` | 128×32 | 上行的同名副本 —— **双板物品图标**用（`entity/` 不进物品图集，图标必须用这份） |
| `textures/item/snowboard[_<款>].png` | 128×32 | 同上（单板物品图标） |
| `textures/item/ski_pole.png` | 64×64 | 滑雪杖 3D 模型贴图（杖身/握把/挡雪盘三个 UV 分区），脚上手上与物品栏共用 |
| `textures/entity/equipment/humanoid/modern_ski.png` | 64×32 | 备用护甲层贴图（`equipment/modern_ski.json` 引用） |
| `icon.png` | 128×128 | mod 图标（`fabric.mod.json` 引用） |

贴图用的是 `CubeListBuilder` 的**自动 UV 布局**，各面落在固定像素区（脚本注释里写了每个 box 占哪块）。
**改板子尺寸必须同步改脚本里对应的 `fill` 区域**，否则板子会渲染成透明/串色。

### 模型几何

- 双板 `AlpineSkiModel`：板宽 3、厚 1、长 **24 单位（= 150 cm，落在成人全能板 155–175 cm 区间下沿；含翘头总长 26 单位 = 162.5 cm）**、靴子**居中（前 12 / 后 12，对应现实高山板的居中安装点）**、板头 box(3,1,2) `texOffs(20,26)`
  - 板腰宽为什么不按现实的 85–95 mm 压到 1.4 单位：**看的是比例**——现实「靴宽 12 cm : 板腰 9 cm ≈ 0.75」，我们的靴子是 4 单位宽，所以 3 单位才是同比例的现实值；压到 1.4 单位会比靴子窄 3 倍，MC 里细成一根线
- 单板 `SnowboardModel`：板宽 **5.76 单位 = 36 cm**（用户 2026-10-12 定，比 4 单位的旧值宽；板比靴宽 4 更宽、沿横向露出靴外）、厚 1、板身长 **22 单位（= 137.5 cm；前 11 / 后 11 —— 人物站在板的几何正中）** + **板头 / 板尾两块上翘 box(5.76,1,2)**（`texOffs(90,0)` / `(90,4)`）＝ **twin-tip：两头一样翘、前后左右都对称、板面花纹也是两端各一段白 + 中间青色中线**，含翘总长 26 单位 = 162.5 cm（现实 twin 板 145–165）；整组绕 Y 转 90° 横在脚下；两只鞋 box(4,6,4) **建在模型根节点下**（与腿同一坐标系、同一横向偏移 `BOARD_LEG_SPREAD`）—— 放进转了 90° 的板组里会让鞋与腿朝向差 90°，看起来就是“脚歪了”
  - ⚠️ 加/改这块的翘头时，新的 box 需要**自己一块不重叠的 UV 区**（板尾用 `(90,4)`，紧挨板头的 `(90,0)` 下方），并且别压到板身“侧面那一行”（板身 dz=22 时是第 22 行）
- 滑雪杖 `models/item/ski_pole.json`：杖身总长 **21.6 单位（握柄顶 16.5 → 杖尖 −5.1）= 135 cm**（现实成人杖 105–135，按身高 ≈ ×0.68；改长度时**保持握柄不动**、只收起下端，手的位置与 display 偏移都不用重调）、挡雪盘（雪轮）随杖尖上移。**GUI 只显示模型 y 0~16 这段**，所以：
  - 物品栏走 `ski_pole_gui.json` —— **一版独立的 3D 短模型**（杖身 y2.0~12.89 / 握把 y12.89~14.0 / 挡雪盘 y2.39~2.83，全部落在 0~16 内），由 `items/ski_pole.json` 的 `minecraft:select`（`property: display_context`，`when: "gui"` 单值写法）分流；
  - 长模型自己的 `display.gui` 另给了 `scale 0.55 + translation.y 1.0` 兜底 —— 万一某个上下文没命中 case 落到 fallback，整根杖也能完整落在框内，不会只剩上半截。
