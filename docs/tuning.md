# 手感与数值（可调旋钮 / 物品属性 / 两块板的行为差异）

> 工程数值的唯一事实源。改手感先看这里；`README.md` 只面向使用者。

### 物品属性（都在 `SkisMod` 注册时写死）

| 项 | 值 |
|---|---|
| 堆叠 / 耐久 | `stacksTo(1)` / `durability(390)`（板与杖都不可堆叠） |
| 护甲 | `Attributes.ARMOR +2`（`EquipmentSlotGroup.FEET`，ADD_VALUE） |
| 移速 | `Attributes.MOVEMENT_SPEED +0.010`（ADD_VALUE；原版玩家基础 0.1 → 0.11，即穿上快约 10%） |
| 装备音效 / 装备资源 | `ARMOR_EQUIP_LEATHER` / `equipment/modern_ski.json`（`skis:modern_ski`） |
| 创造标签页 | 独立页「滑雪装备」（`itemGroup.skis`，不再塞原版工具页）：每款板各一条（`VARIANTS` 6 × 2）+ 1 根滑雪杖 + 1 个滑雪头盔 |
| 滑雪头盔（`ski_helmet`） | **普通物品**：`stacksTo(1)`、无耐久、无属性修正，也不带 `EQUIPPABLE` —— 不可穿戴、不参与滑行物理；只有图标 / 显示名 / 配方 / 创造页条目 |

## 可调旋钮

### 滑行物理 `SkiGlide.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEP_BLOCKS` | 0.5 | 每累计下降这么多格结算一次加速（**双板**；单板另有自己的 0.5 格步长，见下一行）。2026-10-17 起两板同粒度：双板单次 = 0.1 × 0.5 = **0.05**，每格总量 0.1 |
| `DESCENT_STEP_BLOCKS`（单板） | 0.5 | **单板的结算步长**（`SnowboardControl`）：每降 0.5 格结算一次，单次 = 0.15 × 0.5 = 0.075 —— 每格总量仍 0.15/格，下坡降没半格就有第一份（不用攒满一整格） |
| `FALL_GAIN_PER_BLOCK`（双板） | 0.1 | **双板**下落基数（`SkisControl`；0.12 → 0.08 → **0.1**（2026-10-17 用户回调））。每格 ≈ 7.2 km/h，每 0.5 格单次 0.05，从 0 到 0.85 极速需下落 ≈ 8.5 格 |
| `FALL_GAIN_PER_BLOCK`（单板） | 0.15 | **单板**同项（`SnowboardControl`；只取左右分量、体感弱，所以基数比双板大；0.11 → 0.13 → 0.15，用户 2026-10-13 两次「加 0.02」） |
| `FALL_GAIN_PER_TICK_MAX` | 两板均 0.3 | **单 tick 下落加速上限**：一次结算最多加这么多（防止从高处一步把速度给爆） |
| `FALL_GAIN_EXPONENT` | 1.0 | 两板共用的曲线指数。**当前 1.0 = 线性**（用户要求先去掉衰减试手感）；0.8 = 很缓的边际递减、0.5 = 开方 |
| `GRAVITY_MULTIPLIER`（双板） | 1.5 | **双板**重力倍数（`SkisControl`）：原版 `Attributes.GRAVITY` 0.08 → **0.12** 格/tick²，全程生效（贴地滑行、跳跃、坠落都算），脱板还原。终端速度 3.92 → **5.88** 格/tick，1 秒掉 13.3 → **19.9** 格，落 10 格 15 tick（0.75 s）；代价是跳高 1.25 → **0.93** 格。1.0 = 原版，越大越沉越跳不高 |
| `GRAVITY_MULTIPLIER`（单板） | 1.3 | **单板**同项（`SnowboardControl`）：0.08 → **0.104** 格/tick²，终端 **5.10**，1 秒掉 **17.2** 格，跳高 **1.03** 格。两个类各一个常量，改一个不动另一个 |
| `STEP_UP_PENALTY` | 0.04 格/tick | 滑上 1 格台阶扣掉的水平速度（动量一起扣，否则下一步被托回来） |
| `STEP_UP_MIN_HEIGHT` | 0.5 格 | 判定“上了一格台阶”的最小抬升高度 |
| `VEER_SMOOTHING` / `VEER_MAX_DEGREES` | 0.35 / 4° | 单板只用 W/S 时的平滑趋近系数与单 tick 上限（替代原“固定 2°/tick + 到目标死停”） |
| `MAX_SPEED`（单板） | 0.55 格/tick | 单板速度上限 ≈ 11 m/s ≈ 40 km/h（参照：原版步行 0.216、疾跑 0.281、创造飞行 0.545 格/tick） |
| `MAX_SPEED`（双板） | 0.85 格/tick | 双板速度上限 ≈ 61 km/h（用户 2026-10-17 定：1.0 → **0.85**；撑杖冲刺也封顶在这里） |
| `MOMENTUM_DECAY` | 0.998 | 滑行动量每 tick 保留比例 ＝ 有效摩擦力（**双板**；历史 0.985 → 0.993 → 0.99 → 用户 2026-10-13 定 0.998。0.998 = 每 tick 掉 0.2%，1 秒后剩 96%，半衰期 17.3 s） |
| `ADD` / `MIN_PEAK` | 0.06 / 0.32 | 滑雪杖助推（原 0.1 / 0.45，用户要“慢一点”） |
| `MOMENTUM_DECAY` | 0.998 | **单板**（用户 2026-10-13 定 0.998，与双板同值）——**平时（直行 / 未压刃）的摩擦**；0.998 = 每 tick 掉 0.2%，1 秒 −4%，半衰期 17.3 s |
| `CARVE_MOMENTUM_DECAY` | 0.999 | **单板：转向期间（carve 生效，压着 W / S）专用的摩擦** —— 比平时的 0.998 再滑一点（0.999 = 每 tick 掉 0.1%，1 秒 −2%）。**改成 0.998 ＝ 转向与直行同摩擦**，改成 **1.0 ＝ 转向全程完全不掉速** |
| （无刹车常量） | — | **单板：按 A/D 分“同向 / 反向”两种，但只在没压刃时算刹车**。① **反向按**（向左滑时按 D，且**没**按 W/S）＝刹车 —— 原版横移把人往右挪、速度自然抵消，代码**不覆盖玩家速度、也不托动量**（只把动量对齐当前速度），反着按才真的减得下来；② **同向按**（顺着滑行方向按）＝动量**托住**速度，下落加速攒出来的速度不会被地面摩擦（每 tick ×≈0.55）吃掉 —— 否则一直按住 A/D 下坡会“只有基础速度、叠加不上去”（用户报的正是这个）。③ **压着刃（W/S）时反向按侧压＝换刃转向，不再算刹车**：这也是转向，所以用 `CARVE_MOMENTUM_DECAY` 托住速度（旧行为下起弯那一下 0.5 → 0.165，掉 67% 且动量被清掉后整段弯回不来）。**S 与刹车无关** |
| `BRAKE_DECAY` / `BRAKE_MIN_ALIGN` | 0.92 / 0.3 | **双板刹车**：按住 S 且「还在往前滑」（速度与**身体前方**点积 > 0.3）时，动量与速度每 tick ×0.92（1 秒剩 ≈19%，约 1 秒刹停），方向**不翻**；低于 0.3（横滑 / 倒滑）按 S 才是真的往后走。单板没有这两个常量 |
| `MOMENTUM_DECAY_ON_HIT` | 0.5 | 横向撞到方块时的额外衰减 |
| `MOMENTUM_MIN` | 0.02 | 动量低于它就丢掉 |
| `GLIDE_PUSH` / `REFRESH_INTERVAL_TICKS` | 0.055 / 10 | 雪/冰面上每 0.5 秒沿移动方向补一点推进 |

### 方向控制 `SkiControl.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEER_MAX_HEIGHT` | 1.0 格 | **只服务单板**（双板只在地面上转向）；脚底离地不超过这个高度才允许转向
| `STEER_RATE` | 0.2 | 每 tick 朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）；单板不参与方向；双板只在落地时摆（空中速度方向一点都不转） |

### 单板：人板一体 `SnowboardControl.java`

穿上单板时**人板固定成一个朝向**，身体不跟视角转，只能整体左右平移：

| 操作 | 行为 |
|---|---|
| A / D | 沿**身体朝向**的左右平移 —— `ClientInputMixin` 把左右输入按 `(身体朝向 − 视角)` 旋转后交回原版，所以视角转了也仍往同一侧平移；身体朝向不会因此改变。**速度不会被“拨”到侧向**：方向变化一律走 carve 的每 tick 旋转（与 W/S 同一套机制），只靠原版移动把位置走出来 |
| W | **前倾**（**渐进**到位，不瞬跳；W 与 S 同按判中立，避免快速切换时跳成前倾）：**整个人连同脚上的板**绕脚底向前倾 `TILT_DEGREES`（15°），**相机位置跟着倾斜后的头**（前移 眼高·sin15°），但**视线朝向不变**（仍正对原来的方向）；**不给任何推进** |
| S | **后倾**：同样整个人 + 板一起向后仰 `TILT_DEGREES`，相机位置跟着后移，视线不变 |
| **carve（切雪转弯）：W/S 只转向、不加力** | **前提是压刃**：必须按着 W 或 S，**光按 A/D 只是纯横移**。触发后身体朝向每 tick 转 `TURN_RATE_DEGREES`（2°/tick ＝ 180°/s），**速度的方向跟着转同样的角度**（`rotateVelocity`，大小不变）—— 朝向转多少速度方向转多少，带惯性的路线慢慢弯过去，全程**不补任何推力**（W/S 只改方向）。**转向期间的摩擦比直行更小**：用 `CARVE_MOMENTUM_DECAY` 0.999 托住速度（1 秒 −2%），而且此时按反向侧压是**换刃转向**、不再按刹车处理（`SnowboardControl.isCarving`）。<br>转向方向：按 A/D 时由侧压决定，没按 A/D 时只要滑速 ≥ `CARVE_MIN_SPEED` 就前倾/后倾各走一边；**后倾（S）＝后刃**，转向与前倾相反。腾空也能转 |
| 视角范围 | 头只能相对身体朝向左右各 `VIEW_LIMIT_DEGREES`（135°），合计 270 —— 只剩背后 90° 是禁区。**限位只有一处权威状态**：`client/SkiViewSmoothing` 的 `headOffset`（视角相对身体朝向的偏角）。鼠标每帧转视角时（`SkiHeadLimitMixin` 拦 `Entity#turn` 的 HEAD/TAIL）只把这一帧转过的角度记进 `headOffset` 并夹一次、撤掉越界那点；每帧再由 `CameraViewSmoothingMixin`（`Camera#alignWithEntity` 的 HEAD）用「按 `partialTick` 插值后的身体朝向 + `headOffset`」写一次 yaw。没顶到限位时视角留在世界坐标里不动（只听鼠标），顶到之后头随身体平滑拖走；**往回转立刻响应，没有死区** |
| 空格 | **能跳** —— 原版跳跃（2026-10-15 起 `makeJump` 不再被取消；单板以前是禁止跳的） |
| 鼠标（头 / 视角） | 可以转，但相对身体朝向限在 ±`VIEW_LIMIT_DEGREES`（见上一行的单一限位实现） |
| 身体朝向 | **不跟着视角转** —— 锁定在穿上板那一刻；客户端每 tick 写回 `yBodyRot`（渲染读的是 render state 的 `bodyRot`）。**`yBodyRotO` 不再写** —— 原版 `baseTick` 每 tick 把它记成「上一 tick 的 yBodyRot」，留着它身体转动才是插值过去的（一起写成新值等于把插值掐掉，第三人称每 tick 跳 2°） |
| 滑行物理 | **只保留下落加速**：每降 1 格 +`FALL_GAIN_PER_BLOCK` 0.15（基数比双板 0.08 大；每 0.5 格结算一次）。方向按 **单板只有左右**：把当前移动方向**投影到身体横轴**，只取左右分量（绝不给“往前”的加速），没左右分量又不按 A/D 就不加。空中也照样给。不做雪面推进、动量也不捕获玩家自己走出来的速度 —— 否则平地走路 / 站坡上都会“莫名往前” |
| 点杖冲刺 | 单板时不给冲刺（`SkiBoost.burst` 直接 return） |
| 换朝向 | 把板脱下来再穿上（重新锁定） |

> 单板的动量只在「下落加速」「按着 A / D 横移」和「转向（carve）」这三种情形下跟随，松开就靠原版摩擦停住；转向期间用的是更小的摩擦 `CARVE_MOMENTUM_DECAY` 0.999（直行仍是 `MOMENTUM_DECAY` 0.998）；双板同为 `SkisControl.MOMENTUM_DECAY` 0.998。

双板现在也是「锁身体 + 按键转」，两块的锁表各自一份：单板 `SnowboardControl.BODY_YAW`、双板 `SkisControl.BODY_YAW`（实体 id → 角度），条数超过 `MAX_ENTRIES` 整表清空；双板的 A / D 每 tick 转 8°（`SkisControl.TURN_RATE_DEGREES`），**按 W 时身体再朝视角方向补 4°/tick**（`SkisControl.turnTowardView`）。**视角限位共用同一套实现、两块板各一个值**（`client/SkiViewSmoothing` 的唯一 `headOffset` + 两处入口：鼠标 `Entity#turn` 只记账、相机 `Camera#alignWithEntity` 每帧写一次）：双板限在身体左右各 **90°**，单板仍是 **135°**。

### 滑行雪粒子 `SnowSpray.java`（纯客户端观感）

穿着任一种板、在雪 / 冰面上滑行时扬起的雪，**纯客户端观感**（`addParticle`）：服务端不参与，粒子多少不影响速度 —— 速度只决定粒子。

**只用一种粒子**：`SNOW` = `ParticleTypes.ITEM_SNOWBALL`（雪球崩碎的白色碎屑，重力 1.0、会落回雪面）。模组里**不用任何雾 / 烟 / 云类粒子** —— `SNOWFLAKE`、`POOF`、`CLOUD` 这些看着像一团白雾，用户明确不要。（原先撑杖时脚下的 8 颗 `SNOWFLAKE` 已整段删掉。）

三层，数量都随速度涨：

1. **贴地雪屑**：从**板的两侧**（与滑行方向垂直的左右两边）向外、略向上溅，数量随速度增加；0.32 格/tick 起每侧满 5 颗。
2. **扬起雪屑**：同样从板两侧起，但**按「顶点高度」调（单位：格）**—— 每颗自己抽一个 `FLURRY_APEX_MIN`–`FLURRY_APEX_MAX`（0.9–2.0 格 = 腰到头顶）的顶点，出生初速由 `riseFor` 用离散粒子物理**反解**出来（不是手调 vy）。按 `FLURRY_HIGH_FRACTION` 0.70 拆成**下半 / 上半两档**：下半抽 0.9–1.45（腰 / 膝）、上半抽 1.45–2.0（胸到头），混起来才有层次 —— 大部分走「上半」档，雪**飘到胸 / 头高度**，既不是贴着地面一层皮、也不是 4–9 格的雪柱。**转向时这一层更多、顶点再 +0.40 格**（封顶 2.2 格）。
3. **急减速雪屑**：一 tick 内水平速度掉了 `BURST_DROP` 以上（急刹车 / 撞墙 / 压刃刹车都是这个形状）就从脚下一圈炸开一大团雪屑，粒子数随掉落量增加，带 `BURST_COOLDOWN_TICKS` 冷却。阈值取 0.08 是为了**兜住双板按 S 的持续刹车**（动量每 tick ×`BRAKE_DECAY` 0.92，高速时每 tick 掉约 8%）—— 持续刹车会每隔几 tick 炸一团，不是只响一次。

**所有粒子出生在脚底那片地面**上（`groundY + 0.05 ± 0.05`，`groundY = player.getY() - SkiGlide.groundDistance(player, GROUND_RANGE)`，探不到地面或离地超过 `GROUND_RANGE` 时退回 `player.getY()`）—— **出生点不跟着人身子抬**，高度层次只看**顶点高度**（`FLURRY_APEX_MIN`–`FLURRY_APEX_MAX` 0.9–2.0 格，转向再 +`FLURRY_APEX_TURN_BONUS` 0.4、封顶 `FLURRY_APEX_CAP` 2.2；初速按 `PARTICLE_GRAVITY` / `PARTICLE_DRAG` 离散反解）。所以看着是雪从地面被卷起来、飘到身体高度，而不是从人身上冒出来；**别为了「飘得高」去抬出生点**（用户明确：出发点在地面）。

**触发层（前两层）不看 `onGround`，也不看 `player.blockPosition()`**，而是先用 `SkiGlide.groundDistance` 向下射线量出**离脚下那片地面**的距离 `gap`（`GROUND_RANGE` 1 格以内），再把 `gap` 反推成脚下那块**实心地面块** `BlockPos.containing(x, y − gap − 0.05, z)`，用 `isOnSnowOrIce(level, ground.above())` 判它是不是雪 / 冰。

> 早先这里是 `isOnSnowOrIce(level, player.blockPosition())` —— 那一格**是人的身体格**：人一跳起来（哪怕只离地 0.3 格）那里就是空气、`below()` 也可能是空气，于是整个滞空阶段一颗粒子都不出（急刹爆发层也被同一行堵住）。用户原话「离了地面就没有粒子」。改看地面块之后，**人在空中但离雪面 ≤1 格照样扬雪**，且仍然只在自己脚下是雪面时才扬。

| 常量 | 当前值 | 作用 |
|---|---|---|
| `MIN_SPEED` | 0.10 格/tick | 低于这个速度不持续扬雪（慢慢挪一步不该扬雪） |
| `GROUND_RANGE` | 1.0 格 | 离**脚下那片地面**不超过这个高度就持续扬雪（向下射线 `ClipContext.Block.COLLIDER` 实测；超过 = 真的飞起来了）；跳起后 ≤1 格的小滞空照样扬 |
| `SPRAY_PER_SPEED` / `SPRAY_MAX_PER_SIDE` | 16.0 / 5 | 贴地雪屑每侧数量 = 速度 × 系数，夹到上限 |
| `FLURRY_PER_SPEED` / `FLURRY_MAX_PER_SIDE` | 26.0 / 8 | 扬起雪屑每侧数量（涨得更快）—— **要「更多」先加这两个上限** |
| `FLURRY_APEX_MIN` / `FLURRY_APEX_MAX` | 0.9 / 2.0 | 扬起雪屑的**顶点高度区间**（格，从地面算）：0.9 = 腰 / 膝、2.0 = 头顶 —— 每颗粒子自己抽一个值，**改这两个就是「飘多高」**（出生初速由 `riseFor` 反解，不用手调 vy） |
| `FLURRY_HIGH_FRACTION` | 0.70 | 扬起层里走「上半」档的比例（顶点抽 1.45–2.0 格）；其余抽下半（0.9–1.45 格）：**大部分走胸到头，两档混着才有层次** |
| `FLURRY_APEX_TURN_BONUS` / `FLURRY_APEX_CAP` | 0.4 / 2.2 | 转向时顶点再 +0.4 格；任何情况下封顶 2.2 格（避免又变成雪柱）—— **转弯甩起来的雪更高** |
| `PARTICLE_GRAVITY` / `PARTICLE_DRAG` | 0.04 / 0.98 | 反解用的原版粒子物理：每 tick `yd -= 0.04 × gravity`（雪球 `gravity` = 1.0）、位移后再 ×0.98 阻尼 —— 顶点 0.9 / 1.17 / 1.45 / 2.0 / 2.2 格对应初速 ≈ 0.300 / 0.341 / 0.379 / 0.445 / 0.467 格/tick |
| `TURN_EXTRA_PER_SIDE` | 5 | 转向时扬起层每侧再多 5 颗（**转向时更多**；这一层原有上限 8，转向时每侧 13） |
| `TURN_DEGREES_PER_TICK` | 2.0 度 | 滑行方向一 tick 转 2° 以上就算「在转向」（两板通用，只看速度方向的变化，不读按键） |
| `SIDE_OFFSET` | 0.34 格 | 出生点离身体中线的横向距离（撒在脚的两侧，不是脚底下） |
| `OUTWARD` / `UPWARD` / `BACKWARD` | 0.18 / 0.20 / 0.16 | 贴地雪屑溅出速度的三个分量：向外、向上、向后（与滑行相反）—— `UPWARD` 0.20 才真的离地（0.12 时几乎贴着雪面滚） |
| `SPREAD` / `Y_OFFSET` / `Y_JITTER` | 0.16 / 0.05 / 0.05 格 | 出生点的水平随机散布 / 离地高度 / 高度抖动（`groundY + Y_OFFSET ± Y_JITTER`） |
| `BURST_DROP` / `BURST_MIN_SPEED` | 0.08 / 0.12 格/tick | 急减速的判定：一 tick 掉超过 `BURST_DROP`、且掉之前快过 `BURST_MIN_SPEED` 才炸 |
| `BURST_PER_DROP` / `BURST_MAX` | 450.0 / 160 | 一团雪屑的粒子数 = 掉落量 × 系数，夹到上限（掉 0.08 → 36 颗，掉 0.15 → 67 颗，0.36 以上 → 满 160 颗）—— **急刹车不够狠就加这两个** |
| `BURST_COOLDOWN_TICKS` | 3 | 两团之间的最短间隔（持续刹车时 = 每隔 3 tick 炸一团） |
| `BURST_UPWARD` / `BURST_RADIUS` | 0.35 / 0.75 格 | 炸开时的上升速度（≈1.5 格高）/ 随机散布半径 |

### 落到雪面免摔伤 `SnowLanding.java`

穿着任一种板**摔落在雪 / 冰面上不掉血**：走 Fabric 的 `ServerLivingEntityEvents.ALLOW_DAMAGE` 事件（在 `hurtServer` 上），三条齐了才免 —— ① 是玩家 ② 脚上是我们的板 ③ 伤害类型属于 `DamageTypeTags.IS_FALL`；落点用 `SkiGlide.isSnowLanding`。**判定比「能不能滑」宽**：脚底格、上一格、下两格任一是雪/冰就免 —— 石头上铺一层雪（0.125 格）也算雪面。非雪面照旧摔伤，别的伤害类型一律放行。命中时顺手 `resetFallDistance()`。**没有可调常量。**

### 滑行音效 `GlideSounds.java`（纯客户端）

雪面上滑行时只有**切雪声**：按速度调的间隔播原版雪地脚步 `SNOW_STEP`，音量与音高也随速度抬。触发条件与雪粒子同一条（穿着板 + 雪 / 冰面 + 有速度），但**声音这边的离地门槛是 `GlideSounds.GROUND_RANGE` 1.0 格**（雪粒子 `SnowSpray.GROUND_RANGE` 现在也是 1.0 格，不过雪粒子量的是到**脚下地面块**的距离、且雪面判定看地面块，声音仍看 `blockPosition()`；两者各一个常量，没有联动）。原版踏雪脚步/落地声仍是静音的（那几条 mixin 只管原版走路与摔落，不管这里直接播的音效）。

**没有风声** —— 原先那条循环的风（`ELYTRA_FLYING`）已按用户 2026-10-13 的要求删掉，相关常量（`WIND_PER_SPEED` / `WIND_MAX` / `WIND_START_VOLUME`）与那个 `WindSound` 内部类一起删了。

**暂停会立刻收声**（用户 2026-10-13 报「按 Esc 后雪声还在响」）：暂停时 `Minecraft#tick()` 照跑、本类照被调用，但声音引擎拿到的参数是「已暂停」——它只推进音乐，**不再推进游戏内声音**，于是切雪声会一直按间隔继续播。所以 `tick` 里先看 `client.isPaused()`，命中就 `silence(client)`：`SoundManager#stop(SNOW_SOUND.location(), PLAYERS)` 把已经在播的那一声一起掐掉、并清掉计时表，解暂停后重新起算。

| 常量 | 当前值 | 作用 |
|---|---|---|
| `SNOW_INTERVAL_BASE` / `SNOW_INTERVAL_PER_SPEED` / `SNOW_INTERVAL_MIN` | 8 / 5.0 / 4 tick | 切雪声的间隔 = 基础 − 速度 × 系数，夹到下限（0.1 格/tick → 8 tick 一声，0.55 以上 → 每 4 tick 一声；**别再调密** —— 2 tick 一声时用户反馈「太密集」） |
| `SNOW_VOLUME_BASE` / `SNOW_VOLUME_PER_SPEED` / `SNOW_VOLUME_MAX` | 0.12 / 0.5 / 0.7 | 切雪声音量 = 基础 + 速度 × 系数，夹到上限 |
| `SNOW_PITCH_BASE` / `SNOW_PITCH_PER_SPEED` | 0.95 / 0.25 | 切雪声音高（越快越尖） |
| `MIN_SPEED` / `GROUND_RANGE` | 0.10 格/tick / 1.0 格 | 出声的门槛：太慢不出声 / 离地超过一格（真飞起来了）不出声 |

### 冲刺 `SkiBoost.java`（仅 `main`）

| 常量 | 当前值 | 作用 |
|---|---|---|
| `ADD` | 0.06 | 每次右键在"当前速度"之上抬高多少 |
| `MIN_PEAK` | 0.32 | 起步速度下限（站着撑着也能冲出去） |
| `DURATION_TICKS` | 60 | 冲刺持续 3 秒后回到原速 |
| `CURVE` | 1.0 | 衰减曲线（1.0 = 线性匀速缓降，越大越"先猛后缓"） |

**撑杖只给水平推力**：起冲那一 tick 直接写 `(方向 × peak, 垂直分量原样保留, …)` —— 原来的 `LIFT`（向上 0.12）会让人时不时蹦一下，用户 2026-10-13 要求去掉，已删。

### 站姿与姿态

| 常量 | 位置 | 当前值 | 作用 |
|---|---|---|---|
| `BASE_LEG_X` | `SkiStance` | 1.9 | 双板腿的**基础**横向位置；`legOffsetX()` = `BASE_LEG_X` + `LEG_SPREAD` = 2.8 |
| `LEG_SPREAD` | `SkiStance` | 0.9 | 双板：单侧腿向外平移（两板间隔随之变化） |
| `BOARD_YAW_DEGREES` | `SkiStance` | 90 | 单板**外观**：板组（板身 + 板头）绕 Y 转的角度 —— 90 = 板横在脚下（与视线垂直）；-90 = 板头朝另一侧 |
| `BOARD_LEG_SPREAD` | `SkiStance` | 2.8 | 单板：两腿（以及鞋）在**模型坐标**里的横向偏移 —— 平行并列、向外岔开；腿与鞋必须同值（4.0 被用户嫌“间隔太宽”） |
| `TILT_LERP` | `SnowboardControl` | 0.25 | 俯仰姿态每 tick 向目标靠拢的比例（≈10 tick 到位）—— 倾斜是渐进的 |
| `TILT_DEGREES` | `SnowboardControl` | 15.0 度 | 单板 W / S 时**整个人 + 板**的倾斜角（`EntityTiltMixin` 转模型 poseStack；`CameraTiltMixin` 用同一角度把相机位置沿旋转后的眼高向量前移，视线不跟着转） |
| `VIEW_LIMIT_DEGREES` | `SnowboardControl` | 135.0 度 | **单板**：视角相对锁定身体朝向的最大偏转，左右各一份（合计 270°；留 90° 背后视线禁区）。限位状态只有 `client/SkiViewSmoothing.headOffset` 一处 |
| `VIEW_LIMIT_DEGREES` | `SkisControl` | 90.0 度 | **双板**：同一项，**各自一份常量**（改这里不动单板）。左右各 90°、合计 180° —— 刚好看到正侧方。限的是视角、不是 A/D 的 8°/tick 转向；按 W 时身体会朝视角追上 4°/tick（`VIEW_TURN_RATE_DEGREES`） |
| `TURN_RATE_DEGREES` | `SnowboardControl` | 2.0 度/tick | carve 时身体朝向每 tick 转多少（180°/s ＝ 一直按着画圈） |
| `CARVE_MIN_SPEED` | `SnowboardControl` | 0.05 格/tick | 没按 A/D 时，光靠前/后倾触发 carve 所需的最低滑速 |
| `ROW_CENTER` / `ROW_AMPLITUDE` | `HumanoidModelMixin` | -0.35 / 0.95 | 手臂划动中心角与幅度（最高处 = 中心 − 幅度） |
| `DOWN_FRACTION` | `HumanoidModelMixin` | 0.6 | 一次摆臂里"下摆"占的比例（其余是收回） |
| `BOARD_LEG_TILT` / `GLIDE_LEAN` | `HumanoidModelMixin` | -0.15 / 0.05 | **单板**腿位：腿（下半身）往后倾的角度（与双板的 `SKI_LEG_TILT` 同值 —— 用户 2026-10-13 要求「单板站姿改成跟双板一样、身体稍微向后斜」；原来的屈膝 `GLIDE_KNEE_BEND` 0.08 已删）/ 上身微前倾 |
| `SKI_LEG_TILT` | `HumanoidModelMixin` | -0.15 | **双板**：腿（身体下半部分）往后倾出去的角度，配合前倾的上身 |
| `ARM_REST` / `RAISE_FRACTION` | `HumanoidModelMixin` | -0.55 / 0.25 | 持杖静止时手的位置（**不举到最高**）/ 右键动画里“先举到最高”所占比例 |
| `DELAY_TICKS` | `SkiBoost` | 5 | **按右键到手杖真正发力之间的延迟**：动画总长 12 tick，这里等 5 tick 再发力，动作与冲劲才对得上 |
| `ARM_OUTWARD_ROLL` | `HumanoidModelMixin` | 0.10 | 持杖那只手的向外滚转（保证杖是平行线） |
| `FREE_ARM_XROT` / `FREE_ARM_ROLL` | `HumanoidModelMixin` | 0.0 / 0.28 | 单板滑行时**两只手**的姿势：`xRot`=0（**不前抬**）、`zRot`=0.28 rad ≈ 16°（**向两侧微微抬起**，左右对称）。不复用会走原版走路摆臂 |
| `SKI_FREE_ARM_XROT` / `SKI_FREE_ARM_ROLL` | `HumanoidModelMixin` | -0.25 / 0.28 | **双板空手**（那只手没拿滑雪杖）时两臂的姿势：`xRot`=-0.25 rad ≈ 14°（**向前微抬**，负值＝向前）、`zRot`=±0.28 rad ≈ 16°（**向两侧微抬**）。拿杖的那只手仍走撑杖姿势 |
| `SWING_TICKS` | `SkiState` | 12 | 一次摆臂动画时长（0.6 秒） |

### 零散常量（各处，也常被问到）

| 常量 | 位置 | 当前值 | 作用 |
|---|---|---|---|
| `MIN_MOTION_SQR` | `SkiGlide` | 0.008 | 水平速度的平方低于它就算「没在动」：不沿当前方向兜底加下落加速、也不接管动量 |
| `TURN_RATE_DEGREES` | `SkisControl` | 8.0 度/tick | **双板 A / D 转身体**每 tick 多少（≈160°/s；180° 约 1.1 s）—— **觉得转太慢/太快先改它**；A 让身体 yaw 减小（左）、D 让 yaw 增大（右）。**只转身体**；视角另有 ±90° 限位（见 `VIEW_LIMIT_DEGREES`，不是跟着 A/D 转）；按 tick 写，服务器与客户端各跑一步，不做按帧均摊 |
| `VIEW_TURN_RATE_DEGREES` | `SkisControl` | 4.0 度/tick | **双板按 W 时身体朝视角方向转**每 tick 多少（≈80°/s；残余 90° 约 23 tick ≈ 1.15 s 对齐）。步长限幅、不越过目标；残余 < `VIEW_TURN_MIN_DEGREES` 0.5° 就停（防抖）。只在 **W** 时触发，S 不触发；与 A / D 同向时叠加（8+4=12°/tick）。**转的仍是身体**，视角在世界坐标里不动 |
| `BODY_YAW` | `SkisControl` | 实体 id → 身体朝向（度） | **双板的身体朝向锁表**：穿上时 `ensureLocked` 记下当时的 yaw，之后只由 `turnBody`（A / D）改；脱板 `forgetBody` 清掉。视角（`yRot`）与它彻底分开 |
| `BRAKE_DECAY` | `SkisControl` | 0.92 | 双板按住 S 刹车时每 tick 保留的比例（1 秒剩 ≈19%；0.90 更利、0.96 更轻） |
| `BRAKE_MIN_ALIGN` | `SkisControl` | 0.3 | 速度方向与**身体前方**点积超过它才算「还在往前滑」（此时按 S 是刹车）；低于它按 S 就是往后走 |
| `TILT_EPSILON` | `SnowboardControl` | 0.01 | 俯仰角与目标差小于它就视为到位，停止补间 |
| `MAX_ENTRIES` | `SkiGlide` / `SnowboardControl` / `SkiBoost` / `SnowSpray` / `GlideSounds` | 64 | 静态表（动量 / 锁定朝向 / 冲刺 / 雪粒子的「上一 tick 速度」与雪雾冷却 / 切雪声计时）条数上限，超了**整表清空**，防地图残留状态泄漏 |
| `COOLDOWN_TICKS` | `item/SkiPoleItem` | 10（0.5 s） | 两次撑杖之间的冷却；冷却中右键直接 `FAIL`（所以连撑最快 0.5 s 一次） |
| `BOARD_TOP` | `SkiBoardModel` / `SnowboardModel` | 23.0 | 板身与翘头在模型空间里的 y 值（板顶面高度） |

## 双板 vs 单板（现行为）

| | 双板 | 单板 |
|---|---|---|
| 朝向 | **锁身体、不锁视角**：身体朝向锁在穿上板那一刻（`SkisControl.BODY_YAW`），由 A / D 改、**按 W 时还会朝视角追 4°/tick**；**视角由鼠标转、并限在身体左右各 ±90°**（与单板同一套实现、各自一个值，用户 2026-10-17 定 — 之前拿视角当朝向是错的） | 锁定（穿上板那一刻），视角同样限在 ±135° |
| 转向 | **A / D 只转身体**（`SkisControl.turnBody`，`TURN_RATE_DEGREES` 8°/tick ≈ 160°/s，180° 约 1.1 s；yaw 减小＝左、增大＝右）；**按 W 时身体再朝视角方向补 `VIEW_TURN_RATE_DEGREES` 4°/tick**（`turnTowardView`，限幅不越过，残余 90° 约 1.15 s 追上；S 不触发）。两种转法都**不碰视角**（只有顶到 ±90° 限位时视角才被身体拖着走）；客户端与服务端每 tick 各跑一步（先 A/D 再 W），客户端写 `yBodyRot / yBodyRotO`。速度方向由服务端 `SkiControl` 按**身体前方**摆过去：**地上跟着转、空中不跟**（空中身体随便转，速度方向一点都不动） | 压刃（W/S）＋侧压（A/D），`TURN_RATE_DEGREES` 2°/tick |
| 前进 | **W 前进**；**按住 S 且还在往前滑 = 刹车**（`BRAKE_DECAY` 0.92，只减速、不拨方向）；**静止 / 后退时才往后走**（速度与**身体前方**点积 ≤ `BRAKE_MIN_ALIGN` 0.3）。**W / S 一律沿身体前后**（`ClientInputMixin` 把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系），所以视角转到哪都不影响溜的方向 | W/S 只做前倾 / 后倾（**不加推力**） |
| 左右 | **A 左 / D 右 只转身体**（`SkisControl.turnBody`，`TURN_RATE_DEGREES` 8°/tick ≈ 160°/s），**视角不跟着动**（顶到 ±90° 限位才被拖）；横向输入被清掉（不横移），滑行方向在地上跟着身体转、空中不跟 | A / D 沿身体左右**平移** |
| 站姿 | 腿往后倾 `SKI_LEG_TILT` -0.15 | 腿往后倾 `BOARD_LEG_TILT` -0.15（**与双板同值**，用户 2026-10-13 定） |
| 视角限位 | **±`VIEW_LIMIT_DEGREES` 90°**（`SkisControl`）：鼠标能转，但相对锁定的身体朝向最多左右各 90°（合计 180°，刚好到正侧方）；没顶到限位时视角**留在世界坐标里不动**（身体在下面转），顶到之后头被身体拖着走。**按 W 时身体朝视角方向追 4°/tick**（`VIEW_TURN_RATE_DEGREES`）—— 追的是身体，视角在世界坐标里仍然不动 | **±`VIEW_LIMIT_DEGREES` 135°**（`SnowboardControl`）：同一套实现（`client/SkiViewSmoothing` 的唯一 `headOffset`，鼠标只记账、相机每帧写一次）、**各自一个常量** |
| 音效 / 粒子 | 同一套：滑行雪粒子 + 随速度变密的切雪声（暂停即停，无风声） | 同左 |
| 摩擦（动量每 tick 保留） | `SkisControl.MOMENTUM_DECAY` **0.998** | `SnowboardControl.MOMENTUM_DECAY` **0.998**（直行）/ **0.999**（carve 转向期间）—— 两板同值，用户 2026-10-13 定 |
| 重力 | 穿板时 `GRAVITY_MULTIPLIER` **1.5**（`Attributes.GRAVITY` 0.08 → 0.12 格/tick²，跳高 → 0.93 格），脱板还原 | 单板 **1.3**（0.104 格/tick²，跳高 → 1.03 格）；各自一个常量，改一个不动另一个 |
