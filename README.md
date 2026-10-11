# Skis — Minecraft 26.2 Fabric 滑雪模组

三个物品 + 两套手臂/冲刺行为，后者分成两个分支。

## 物品

| id | 名称 | 槽位 | 说明 |
|---|---|---|---|
| `skis:modern_ski` | 现代滑雪板（双板） | 脚部（靴子） | 两块平行的板，两腿各向外平移 0.9 单位 |
| `skis:snowboard` | 现代滑雪单板 | 脚部（靴子） | 一只板（宽 9、长 24 单位），两脚**平行并列**站在板上；穿上后完全站定、身体不跟视角转，只有 A/D 能沿身体左右平移（见下「单板」） |
| `skis:ski_pole` | 滑雪杖 | 手 | 右键撑杖 → 一次摆臂 + 一段逐渐衰减的冲刺（`main` 分支）；手上是 3D 长杖，物品栏是标准 2D 图标 |

配方：双板 = 铁锭×2 + 木板×3（`I I` / `PPP`）；单板 = 木板×5 + 铁锭×1（`PPP` / `PIP`）；滑雪杖 = 铁粒×1 + 木棍×2（`I` / `S` / `S`，一次出 2 根）。

## 分支

| 分支 | 手臂 | 冲刺 |
|---|---|---|
| `main` | **平时不动**，只有右键撑杖才播放一次摆臂（从最高处往下摆到最后处，再收回最高处） | 右键一次：速度抬到「当前 + 0.35」，再在 60 tick（3 秒）内线性回到「右键当时的原速」 |
| `swing` | 滑行时**双臂持续同相划动**；滞空或落地不满 5 tick 时停在离地前最后一帧姿势 | 无（只有撑杖推进） |

两版共用物品注册、模型、贴图、配方、滑行物理；差异只在手臂与冲刺这几处文件：

- 只在 `main`：`SkiBoost.java`（冲刺尾焰）、`SwingHooks.java`（main→client 的动画钩子）
- 两版都有但内容不同：`SkiState.java`、`SkisClient.java`、`HumanoidModelMixin.java`、`SkiPoleItem.java`、`SkisMod.java`

## 构建与装机

```bash
export JAVA_HOME="G:/games/Minecraft/Java25/jdk-25.0.4.1+1"
sh gradlew build --no-daemon            # 产物 build/libs/skis-mod-1.0.0.jar
cp -p build/libs/skis-mod-1.0.0.jar \
  "G:/games/Minecraft/Minecraft/.minecraft/versions/26.2-Fabric 0.19.3/mods/skis-mod-1.0.0-push.jar"
```

- 实例目录：`G:/games/Minecraft/Minecraft/.minecraft/versions/26.2-Fabric 0.19.3`
- 同一个 mod id 的两个 jar **不能同时启用**：不用的那个改名成 `xxx.jar.disabled` 即可随时切换
- 只改数值常量时**编译通过即可**；改到 Mixin / 模型层注册 / 装备注册这类"只有启动才暴露"的东西才需要启动一次自检

## 贴图与模型生成

```bash
/d/hermes/hermes-agent/venv/Scripts/python tools/gen_assets.py
```

产物（全部在 `src/main/resources/assets/skis/`）：

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `textures/item/modern_ski.png` | 16×16 | 双板物品图标 |
| `textures/item/ski_pole.png` | 64×64 | 滑雪杖 3D 模型贴图（杖身/握把/挡雪盘三个 UV 分区） |
| `textures/item/ski_pole_icon.png` | 16×16 | 滑雪杖**物品栏图标**（`pole_icon()` 画的竖直杖，GUI 用） |
| `textures/item/snowboard.png` | 16×16 | 单板物品图标 |
| `textures/entity/ski_board.png` | 128×32 | 双板脚上模型（雪鞋 + 板身 + 板头） |
| `textures/entity/snowboard.png` | 128×32 | 单板脚上模型 |
| `textures/entity/equipment/humanoid/modern_ski.png` | 64×32 | 备用护甲层贴图 |
| `icon.png` | 128×128 | mod 图标 |

贴图用的是 `CubeListBuilder` 的**自动 UV 布局**，各面落在固定像素区（脚本注释里写了每个 box 占哪块）。
**改板子尺寸必须同步改脚本里对应的 `fill` 区域**，否则板子会渲染成透明/串色。

## 可调旋钮

### 滑行物理 `SkiGlide.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEP_BLOCKS` | 1.0 | 每累计下降这么多格结算一次加速 |
| `GAIN_PER_BLOCK` | 0.08 | 每下降 1 格加多少水平速度 |
| `MAX_GAIN_PER_TICK` | 0.3 | 单 tick 最多加多少（悬崖分几 tick 给） |
| `MOMENTUM_DECAY` | 0.985 | 滑行动量每 tick 保留比例（半衰期 ≈ 2.29 秒）＝ 有效摩擦力 |
| `MOMENTUM_DECAY_ON_HIT` | 0.5 | 横向撞到方块时的额外衰减 |
| `MOMENTUM_MIN` | 0.02 | 动量低于它就丢掉 |
| `GLIDE_PUSH` / `REFRESH_INTERVAL_TICKS` | 0.055 / 10 | 雪/冰面上每 0.5 秒沿移动方向补一点推进 |

### 方向控制 `SkiControl.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `MAX_GLIDE_SPEED` | 1.4 格/tick（≈100 km/h） | 滑行/冲刺速度上限（1 格 = 1 米，格/tick × 72 = km/h） |
| `STEER_MAX_HEIGHT` | 1.0 格 | 脚底离地不超过这个高度才允许转向，再高锁死方向 |
| `STEER_RATE` | 0.2 | 每 tick 朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）；单板不参与方向 |

### 单板：人板一体 `SnowboardControl.java`

穿上单板时**人板固定成一个朝向**，身体不跟视角转，只能整体左右平移：

| 操作 | 行为 |
|---|---|
| A / D | 沿**身体朝向**的左右平移 —— `ClientInputMixin` 把左右输入按 `(身体朝向 − 视角)` 旋转后交回原版，所以视角转了也仍往同一侧平移；身体朝向不会因此改变 |
| W | **前倾**：**整个人连同脚上的板**绕脚底向前倾 `TILT_DEGREES`（15°），**相机整块挂到倾斜后的头上**（位置前移 + 视线俯仰同角度），**不给任何推进**（不会自动往前滑） |
| S | **后倾**：同样整个人 + 板 + 相机一起向后仰 `TILT_DEGREES`，不给推进 |
| 空格 | 不能跳（`makeJump` 取消） |
| 鼠标（头 / 视角） | 可以随便转 |
| 身体朝向 | **不跟着视角转** —— 锁定在穿上板那一刻；客户端每 tick 写回 `yBodyRot` + `yBodyRotO`（渲染读的是 render state 的 `bodyRot`，`yBodyRotO` 一起写是为了不让插值把身体拉回视角方向） |
| 滑行物理 | 与双板**同款**：下落加速（每降 1 格 +0.08）、低摩擦动量、雪面推进 —— 只是方向不读按键转向，而是沿当前运动方向、静止时沿锁定的身体前方 |
| 点杖冲刺 | 单板时不给冲刺（`SkiBoost.burst` 直接 return） |
| 换朝向 | 把板脱下来再穿上（重新锁定） |

> 有动量之后松开 A / D **不会立刻停**，会像双板一样慢慢滑停（`SkiGlide.MOMENTUM_DECAY`，半衰期 ≈ 2.29 秒）。

双板不受影响（仍是原来的滑行 + 按键转向）。锁定表 = `SnowboardControl.BODY_YAW`（实体 id → 角度），条数超过 `MAX_ENTRIES` 整表清空。

### 冲刺 `SkiBoost.java`（仅 `main`）

| 常量 | 当前值 | 作用 |
|---|---|---|
| `ADD` | 0.1 | 每次右键在"当前速度"之上抬高多少 |
| `MIN_PEAK` | 0.45 | 起步速度下限（站着撑着也能冲出去） |
| `DURATION_TICKS` | 60 | 冲刺持续 3 秒后回到原速 |
| `CURVE` | 1.0 | 衰减曲线（1.0 = 线性匀速缓降，越大越"先猛后缓"） |
| `LIFT` | 0.12 | 右键那一下的向上分量 |

### 站姿与姿态

| 常量 | 位置 | 当前值 | 作用 |
|---|---|---|---|
| `LEG_SPREAD` | `SkiStance` | 0.9 | 双板：单侧腿向外平移（两板间隔随之变化） |
| `BOARD_YAW_DEGREES` | `SkiStance` | 90 | 单板**外观**：板组（板身 + 板头）绕 Y 转的角度 —— 90 = 板横在脚下（与视线垂直）；-90 = 板头朝另一侧 |
| `BOARD_LEG_SPREAD` | `SkiStance` | 2.0 | 单板：两腿（以及鞋）在**模型坐标**里的横向偏移 —— 平行并列、向外岔开；腿与鞋必须同值（4.0 被用户嫌“间隔太宽”） |
| `TILT_DEGREES` | `SnowboardControl` | 15.0 度 | 单板 W / S 时**整个人 + 板**的倾斜角（`EntityTiltMixin` 转模型 poseStack；`CameraTiltMixin` 用同一个角度把相机位置沿旋转后的眼高向量前移） |
| `ROW_CENTER` / `ROW_AMPLITUDE` | `HumanoidModelMixin` | -0.35 / 0.95 | 手臂划动中心角与幅度（最高处 = 中心 − 幅度） |
| `DOWN_FRACTION` | `HumanoidModelMixin` | 0.6 | 一次摆臂里"下摆"占的比例（其余是收回） |
| `GLIDE_KNEE_BEND` / `GLIDE_LEAN` | `HumanoidModelMixin` | 0.08 / 0.05 | 屈膝角度 / 身体前倾 |
| `ARM_OUTWARD_ROLL` | `HumanoidModelMixin` | 0.10 | 持杖那只手的向外滚转（保证杖是平行线） |
| `FREE_ARM_XROT` / `FREE_ARM_ROLL` | `HumanoidModelMixin` | -0.25 / 0.15 | 单板滑行时**空着的那只手**的固定前抬角 / 外张角（不复用会走原版走路摆臂） |
| `SWING_TICKS` | `SkiState` | 12 | 一次摆臂动画时长（0.6 秒） |

### 模型几何

- 双板 `SkiBoardModel`：板宽 3、厚 1、长 22（前 12.5 / 后 9.5 ≈ 前 57%，贴近现实固定器位置）、板头 box(3,1,2)
- 单板 `SnowboardModel`：板宽 9、厚 1、长 24（前 14 / 后 10）、板头 box(9,1,2)，整组绕 Y 转 90° 横在脚下；两只鞋 box(4,6,4) **建在模型根节点下**（与腿同一坐标系、同一横向偏移 `BOARD_LEG_SPREAD`）—— 放进转了 90° 的板组里会让鞋与腿朝向差 90°，看起来就是“脚歪了”
- 滑雪杖 `models/item/ski_pole.json`：杖身总长 24.75 单位（= 初版 15 单位 ×1.65）、挡雪盘随杖尖下移。**GUI 只显示模型 y 0~16 这段**，所以：
  - 物品栏走 `ski_pole_gui.json`（标准 **2D 图标**：`parent: item/generated` + `layer0: skis:item/ski_pole_icon`），由 `items/ski_pole.json` 的 `minecraft:select`（`property: display_context`，`when: "gui"` 单值写法）分流；
  - 长模型自己的 `display.gui` 另给了 `scale 0.55 + translation.y 1.0` 兜底 —— 万一某个上下文没命中 case 落到 fallback，整根杖也能完整落在框内，不会只剩上半截。

## 架构速览

| 文件 | 职责 |
|---|---|
| `SkisMod.java` | 物品注册、创造栏、装备资源；`isModernSki` / `isSkiFeet` 判定 |
| `item/SkiPoleItem.java` | 滑雪杖右键行为（客户端摆臂 + 服务端冲刺） |
| `SkiBoost.java` | 冲刺：冲量 + 按 tick 衰减的速度曲线 |
| `SkiGlide.java` | 每 tick 滑行物理：下落加速、低摩擦动量、雪面推进 |
| `SkiControl.java` | 方向控制：读玩家真实按键、离地高度判定、速度上限（单板不参与方向） |
| `SnowboardControl.java` | 单板状态：锁住身体朝向（`yBodyRot` 每 tick 写回）；输入改写与「站定」由 `ClientInputMixin` + `SkisClient` 的 tick 负责 |
| `mixin/client/ClientInputMixin.java` | 穿单板时把 A/D 换算到身体左右轴、丢掉前后分量、取消 `makeJump` |
| `client/SkiState.java` | 客户端动画计时（摆臂倒计时） |
| `client/SkiStance.java` | 站姿几何常量（腿/板的横向与前后位置、外撇角） |
| `client/HumanoidModelMixin.java` | 模型姿态：腿的站姿 + 手臂撑杖姿势 |
| `client/SkiBoardModel.java` / `SnowboardModel.java` | 脚上板的外观（层定义 + 贴图）；单板整组绕 Y 转 90° 横在脚下 |
| `client/BoardArmorRenderer.java` | Fabric `ArmorRenderer`：把板模型渲染到脚部 |
| `mixin/client/EntityTiltMixin.java` | 单板 W / S 时在 `LivingEntityRenderer#setupRotations` 的 TAIL 转整个 poseStack —— 人与板一起倾（只改模型部件的话板不跟） |
| `mixin/client/CameraTiltMixin.java` | 单板 W / S 时把相机整块挂到倾斜后的头上：`alignWithEntity` 里 `setPosition`(**两个重载都要拦**) 加上绕脚底旋转后的眼高偏移、`setRotation` 的 xRot 加同一个倾角 |
| `client/SkisClient.java` | 客户端入口：注册模型层、渲染器、tick 回调 |
| `SwingHooks.java` | main→client 的动画钩子（源集方向限制，见下） |

## 约定与坑（细节见 Hermes 技能）

完整经验记在 Hermes 技能 `minecraft-mod-support` 的 `references/fabric-mod-dev-26.2.md`，要点：

- 26.x **没有 yarn 映射**，核 API 签名要对官方 Mojang 映射 `javap`
- 物品注册必须 `Item.Properties.setId`；资源要 `items/`（物品定义）+ `models/`（模型）双层
- `src/main` 看不到 `src/client`（依赖方向 client → main），跨侧行为要走 `SwingHooks` 这类中转钩子
- 持续施加速度后要置 `player.hurtMarked = true`，否则服务端不同步、客户端会和你抢速度
- 肢体 `zRot` 符号：-x 侧肢体正 zRot = 末端向外；搞反会让垂在身侧的长物品内倾穿模
- 渲染状态每帧重建，**不要**把跨帧状态挂在它上面（用静态表 + 实体 id）
- **改完 Mixin 要自己跑一次启动自检**：注入目标写错＝启动即崩（本项目被 `Camera#setup` 这个不存在的方法名坑过一次）。用 PCL 导出的启动脚本 bat 拉真实客户端，看 `logs/latest.log` 出现 `[skis] … 已加载` + `Sound engine started`、且无 `MixinApplyError` 才算过。
- **相机要“整块挂到倾斜后的头上”＝位置 + 朝向都改，而且 `setPosition` 有两个重载**：普通（第一人称/第三人称）走 `setPosition(double, double, double)`，**只有骑矿车分支**才走 `setPosition(Vec3)` —— 只拦 Vec3 版等于完全没改，症状是“低头看见自己的后背”（First-person Model 渲染的身体已经倾了）。三个 double 参数各自 `@ModifyArg` 取 index 0/1/2，再加一个 Vec3 版兜底骑乘；朝向用 `setRotation(FF)` 的 index 1。
- **26.2 里相机算位置的方法叫 `alignWithEntity`（private，被 `update` 调用），没有 `setup`**：jump 到旧版本记忆里的方法名会直接崩在启动阶段。
- **「相机跟着头走」= 改相机的位置，不是朝向**：人绕脚底倾斜后眼睛从竖直的 `(0, 眼高, 0)` 转成 `(前移 眼高·sinθ, 眼高·cosθ)`，而原版相机的眼高永远竖直 —— 在 `Camera#setup` 里 `@ModifyArg` 它调 `setPosition(Vec3)` 的那个参数，加上旋转后的偏移即可（`Camera.entity` 是私有字段且无 getter，得 `@Shadow`）。
- **「整个人 + 脚上的板一起动」的姿态必须在渲染 poseStack 层做**：板是 Fabric `ArmorRenderer` 独立提交的模型，只改 `HumanoidModel` 的部件它不会跟。`LivingEntityRenderer#setupRotations` 的 TAIL 是安全注入点（poseStack 原点＝实体脚底、朝向已转好，模型与所有 RenderLayer 共用它）
- **GUI 里只显示模型 y 0~16**：长物品（杖/枪）在物品栏会被裁掉下半截；用 `display_context` 分流一个 2D 图标模型是正解，**长模型自己也留一份 GUI 缩放兜底**（select 没命中时不会只剩半截）
- 同一个 mod id 的 jar 不能共存，用 `.jar.disabled` 切换
