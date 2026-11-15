# 手感与数值（可调旋钮 / 物品属性 / 两块板的行为差异）

> 工程数值的唯一事实源。改手感先看这里；`README.md` 只面向使用者。

### 物品属性（都在 `registry/ModItems` 注册时写死）

| 项 | 值 |
|---|---|
| 堆叠 / 耐久 | `stacksTo(1)` / `durability(390)`（板与杖都不可堆叠） |
| 护甲 | `Attributes.ARMOR +2`（`EquipmentSlotGroup.FEET`，ADD_VALUE） |
| 移速 | `Attributes.MOVEMENT_SPEED +0.010`（ADD_VALUE；原版玩家基础 0.1 → 0.11，即穿上快约 10%） |
| 装备音效 / 装备资源 | `ARMOR_EQUIP_LEATHER` / `equipment/modern_ski.json`（`skis:modern_ski`） |
| 创造标签页 | 独立页「滑雪装备」（`itemGroup.skis`，不再塞原版工具页）：每款板各一条（`VARIANTS` 6 × 2）+ 1 根滑雪杖 |

## 可调旋钮

### 状态表按端拆分（单机双端共写 → 每端各一份）

单机（集成服务端与客户端同一个 JVM）里 `LocalPlayer` 与 `ServerPlayer` **共用同一个 entity id**，而「身体朝向 / 转向」这类**每 tick 加一个增量**的逻辑在 `SkisClient`（`END_CLIENT_TICK`）与 `SkiGlide`（`END_SERVER_TICK`）里**各跑一次**。拆表前两端写同一张静态表 → 同一份增量被加两次，**单机的转向 / carve 速率是专用服务器的 2 倍**（`SkiAir` 早就为同一个坑拆过 `CLIENT_STATES` / `SERVER_STATES`）。

现在 11 张表全部按端两份（`*_CLIENT` / `*_SERVER`，由 `SnowboardControl#table` / `AlpineSkiControl#table` 按 `entity.level().isClientSide()` 或显式 `boolean clientSide` 选表）：

| 类 | 表 | 写入端 |
|---|---|---|
| `SnowboardControl` | `BODY_YAW` / `BOARD_SIDE` / `CARVE_SPEED` / `CARVE_TICKS` / `TILT_VALUE` / `TILT_TARGET` / `HEAD_OFFSET`（7 张） | 全部两端各一份；实际 `CARVE_*` 只在服务端、`TILT_*` / `HEAD_OFFSET` 只在客户端 |
| `AlpineSkiControl` | `BODY_YAW` / `TURN_HEADING` / `TURN_YAW0` / `TURN_LAST_YAW`（4 张） | `BODY_YAW` 两端各一份；`TURN_*` 只在服务端（`alignTurnHeading`） |

**常量按「每端各跑一次」标定**：拆表后单机每 tick 只有一次写入，所以下面这些**累加型**的每 tick 步长 ×2，让单机手感与拆表前逐 tick 一致、专用服务器终于和单机一样快（`2 × clamp(x, lo, hi) ≡ clamp(2x, 2lo, 2hi)`，逐点相等）：

| 常量 | 现在 | 拆表前 | 为什么是累加型 |
|---|---|---|---|
| `TURN_RADIUS_BLOCKS` | **4.0 格** | 8.0 格 | `applyCarve` 每 tick 往 `BODY_YAW` 加一个角速度，半径减半＝角速度 ×2 |
| `TURN_MIN_DEGREES_PER_TICK` | **2.0 度/tick** | 1.0 度/tick | 同上（clamp 下限同步 ×2） |
| `TURN_MAX_DEGREES_PER_TICK` | **12.0 度/tick** | 6.0 度/tick | 同上（clamp 上限同步 ×2） |
| `VEER_SMOOTHING` | **0.58** | 0.35 | 比例项：拆表前两端各吃 0.35（复合 ＝ 0.5775），0.58 ≈ 1 − (1 − 0.35)² |
| `VEER_MAX_DEGREES` | **4.8 度/tick** | 2.4 度/tick | 饱和步长，两次复合 ＝ 一次 4.8 |
| `TURN_RATE_DEGREES`（`AlpineSkiControl`） | **4.0 度/tick** | 2.0 度/tick | `turnBody` 每 tick 减一个固定步长 |
| `VIEW_TURN_RATE_DEGREES`（`AlpineSkiControl`） | **4.8 度/tick** | 2.4 度/tick | 饱和步长（`clamp(delta, ±rate)`） |

**不翻倍的**（翻倍会直接改手感）：`TILT_LERP`、`HEAD_OFFSET_LERP` / `HEAD_OFFSET_MAX_STEP_DEGREES`（都只被客户端 `SkisClient` 调，服务端从不推进俯仰 / 头偏）；`BOARD_SIDE`（幂等写值，不是 `+=`）；`CARVE_SPEED` / `CARVE_TICKS` 与 `TURN_HEADING` / `TURN_YAW0` / `TURN_LAST_YAW`（只在服务端跑）；`ALIGN_MAX_DEGREES_PER_TICK`（作用在实体自己的速度方向上，每端每实体一次，见下面不变量表）；`AlpineSkiControl.ALIGN_DEGREES_PER_TICK` 9.0（只在服务端 `alignVelocity` 跑）。

**拆表后的不变量（单板 carve）**：

| 项 | 现在 | 拆表前 |
|---|---|---|
| 每 tick 最大身体转角 | 单机 2 × 6.0 = **12.0°** / 专用 6.0° | 单机 = 专用 = **12.0°** |
| `ALIGN_MAX_DEGREES_PER_TICK` | **12.5 度/tick** | 6.0 度/tick |
| 关系（回正步长必须**严格大于**身体转角） | 专用**刚好取等**（6.0 vs 6.0）、单机**已破**（6.0 vs 12.0 → 速度永久落后身体） | 12.5 &gt; 12.0，**余量 0.5°**（180° 偏角 15 tick ＝ 0.75 s 掰正） |

### 滑行物理 `SkiGlide.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEP_BLOCKS` | 0.5 | 每累计下降这么多格结算一次加速（**双板**；单板另有自己的 0.5 格步长，见下一行）。2026-10-17 起两板同粒度：双板单次 = 0.1 × 0.5 = **0.05**，每格总量 0.1 |
| `DESCENT_STEP_BLOCKS`（单板） | 0.5 | **单板的结算步长**（`SnowboardControl`）：每降 0.5 格结算一次，单次 = 0.15 × 0.5 = 0.075 —— 每格总量仍 0.15/格，下坡降没半格就有第一份（不用攒满一整格） |
| `FALL_GAIN_PER_BLOCK`（双板） | 0.1 | **双板**下落基数（`AlpineSkiControl`；0.12 → 0.08 → **0.1**（2026-10-17 用户回调））。每格 ≈ 7.2 km/h，每 0.5 格单次 0.05，从 0 到 0.85 极速需下落 ≈ 8.5 格 |
| `FALL_GAIN_PER_BLOCK`（单板） | 0.15 | **单板**同项（`SnowboardControl`；方向沿当前水平速度、不跟随身体朝向，基数比双板 0.08 大；0.11 → 0.13 → 0.15，用户 2026-10-13 两次「加 0.02」） |
| `FALL_GAIN_PER_TICK_MAX` | 两板均 0.3 | **单 tick 下落加速上限**：一次结算最多加这么多（防止从高处一步把速度给爆） |
| `FALL_GAIN_EXPONENT` | 1.0 | 两板共用的曲线指数。**当前 1.0 = 线性**（用户要求先去掉衰减试手感）；0.8 = 很缓的边际递减、0.5 = 开方 |
| `GRAVITY_MULTIPLIER`（双板） | 1.5 | **双板：贴地（`onGround`）时的重力倍数**（`AlpineSkiControl`）：原版 `Attributes.GRAVITY` 0.08 → **0.12** 格/tick²，**只在贴地滑行 / 落地压雪时生效**，腾空立刻换 `AIR_GRAVITY_MULTIPLIER`；脱板还原。终端速度 3.92 → **5.88** 格/tick，1 秒掉 13.3 → **19.9** 格，落 10 格 15 tick（0.75 s）。1.0 = 原版，越大贴地越沉 |
| `GRAVITY_MULTIPLIER`（单板） | 1.3 | **单板：贴地时的重力倍数**（`SnowboardControl`）：0.08 → **0.104** 格/tick²，终端 **5.10**，1 秒掉 **17.2** 格。两个类各一个常量，改一个不动另一个 |
| `AIR_GRAVITY_MULTIPLIER`（双板） | 0.9 | **双板：腾空（跳跃上升段 / 坠落）时的重力倍数**：0.08 → **0.072** 格/tick² —— 比原版轻一点，空中飘一点、滞空更长。终端速度 3.92 → **3.53** 格/tick（离散收敛 `49 × g`）。**满速跳高 1.50 格这张查找表就是按 0.072 反解的**（起跳首 tick 还是贴地档 0.12，见「跳跃与空翻」）；贴地的 1.5 不参与跳高 |
| `AIR_GRAVITY_MULTIPLIER`（单板） | 0.9 | **单板**同项：0.08 → **0.072** 格/tick²（终端 **3.53**）。与双板同值、各一个常量；满速跳高 1.50 格（按 0.072 反解，首 tick 贴地档 0.104） |
| `STEP_UP_PENALTY` | 0.04 格/tick | 滑上 1 格台阶扣掉的水平速度（动量一起扣，否则下一步被托回来） |
| `STEP_UP_MIN_HEIGHT` | 0.5 格 | 判定“上了一格台阶”的最小抬升高度 |
| `VEER_SMOOTHING` / `VEER_MAX_DEGREES` | 0.58 / 4.8° | 单板只用 W/S 时的平滑趋近系数与单 tick 上限（替代原“固定 2°/tick + 到目标死停”）。**两项都按「每端各跑一次」重新标定**（见「状态表按端拆分」）：0.58 ≈ 1 − (1 − 0.35)² ＝ 拆表前两端各 0.35 的复合；4.8°/tick ＝ 96°/s，转满 180° 需 37.5 tick ≈ 1.9 s（饱和步长 ×2 与两次复合逐 tick 等价） |
| `MAX_SPEED`（单板） | 0.55 格/tick | 单板速度上限 ≈ 11 m/s ≈ 40 km/h（参照：原版步行 0.216、疾跑 0.281、创造飞行 0.545 格/tick） |
| `MAX_SPEED`（双板） | 0.85 格/tick | 双板速度上限 ≈ 61 km/h（用户 2026-10-17 定：1.0 → **0.85**；撑杖冲刺也封顶在这里） |
| `MOMENTUM_DECAY` | 0.998 | 滑行动量每 tick 保留比例 ＝ 有效摩擦力（**双板**；历史 0.985 → 0.993 → 0.99 → 用户 2026-10-13 定 0.998。0.998 = 每 tick 掉 0.2%，1 秒后剩 96%，半衰期 17.3 s） |
| `ADD` / `MIN_PEAK` | 0.06 / 0.32 | 滑雪杖助推（原 0.1 / 0.45，用户要“慢一点”） |
| `MOMENTUM_DECAY` | 0.998 | **单板**（用户 2026-10-13 定 0.998，与双板同值）——**平时（直行 / 未压刃）的摩擦**；0.998 = 每 tick 掉 0.2%，1 秒 −4%，半衰期 17.3 s |
| `CARVE_MOMENTUM_DECAY` | **1.0** | **单板：转向期间（carve 生效，压着 W / S）专用的摩擦 —— 1.0 ＝ 转向零损失**（用户口径「转向只改变方向、不影响速度大小」：转向本来就是纯旋转 `alignCarveHeading`，不该再叠一层摩擦）。历史 0.999（1 秒 −2%）→ **1.0**。同一个常数还当**速度下限的衰减率**用：`SnowboardControl.guardCarveSpeed` 每 tick 拿 `入场速度 × 1.0^已持续 tick ＝ 入场速度` 作下限（撞方块那一 tick 不补），所以平地转向完全不掉速、撞墙被砍半之后下一个自由 tick 就回到入场速度。**不会失控增长**：上限仍有 `MAX_SPEED` 0.55 夹住（target 取 `min(..., MAX_SPEED)`）。**改成 0.998 ＝ 转向与直行同摩擦**，**0.999 ＝ 留一点点损耗** |
| （无刹车常量） | — | **单板：按 A/D 分“同向 / 反向”两种，但只在没压刃时算刹车**。① **反向按**（向左滑时按 D，且**没**按 W/S）＝刹车 —— 原版横移把人往右挪、速度自然抵消，代码**不覆盖玩家速度、也不托动量**（只把动量对齐当前速度），反着按才真的减得下来；② **同向按**（顺着滑行方向按）＝动量**托住**速度，下落加速攒出来的速度不会被地面摩擦（每 tick ×≈0.55）吃掉 —— 否则一直按住 A/D 下坡会“只有基础速度、叠加不上去”（用户报的正是这个）。③ **压着刃（W/S）时反向按侧压＝换刃转向，不再算刹车**：这也是转向，所以用 `CARVE_MOMENTUM_DECAY` 托住速度（旧行为下起弯那一下 0.5 → 0.165，掉 67% 且动量被清掉后整段弯回不来）。**S 与刹车无关** |
| `BRAKE_DECAY` / `BRAKE_MIN_ALIGN` | 0.92 / 0.3 | **双板刹车**：按住 S 且「还在往前滑」（速度与**身体前方**点积 > 0.3）时，动量与速度每 tick ×0.92（1 秒剩 ≈19%，约 1 秒刹停），方向**不翻**；低于 0.3（横滑 / 倒滑）按 S 才是真的往后走。单板没有这两个常量 |
| `MOMENTUM_DECAY_ON_HIT` | 0.5 | 横向撞到方块时的额外衰减，**两板口径不同**：**单板**要**连续撞墙 ≥ `MOMENTUM_HIT_TICKS_BEFORE_DECAY` 3 tick 才乘**（坡面上偶尔蹭一次墙不再被砍半，只有真的顶住墙才止损，断开一次就重新数）；**双板撞一下即砍**（`horizontalCollision` 为真就乘 0.5，不看 tick 数，与 0bbc10c 行为一致） |
| `MOMENTUM_HIT_TICKS_BEFORE_DECAY` | 3 tick | **单板**：**连续**撞墙多少 tick 之后才按 `MOMENTUM_DECAY_ON_HIT` 0.5 砍半（**双板不看这个门槛**）。计数在 `SkiGlide` 的 `HIT_TICKS`（实体 id → 连续撞墙 tick 数，撞一下 +1、不撞清零、脱板随 `forgetPhysics` 清），两板都记，只有单板拿它当门槛 |
| `MOMENTUM_MIN` | 0.02 | 动量低于它就丢掉 |
| `GLIDE_PUSH` / `REFRESH_INTERVAL_TICKS` | 0.055 / 10 | 雪/冰面上每 0.5 秒沿移动方向补一点推进 |

### 方向控制 `GlideSteering.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEER_RATE` | 0.2 | 每 tick 朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）；单板不参与方向；双板只在贴地时摆（空中速度方向一点都不转）。**落地后另有快的一条**：`AlpineSkiControl.ALIGN_DEGREES_PER_TICK` 9°/tick 直接把速度方向朝身体前方掰（见「零散常量」），这条 lerp 只是托底时的慢摆 |

### 单板：人板一体 `SnowboardControl.java`

穿上单板时**人板固定成一个朝向**，身体不跟视角转，只能整体左右平移：

| 操作 | 行为 |
|---|---|
| A / D | 沿**身体朝向**的左右平移 —— `ClientInputMixin` 把左右输入按 `(身体朝向 − 视角)` 旋转后交回原版，所以视角转了也仍往同一侧平移；身体朝向不会因此改变。**速度不会被“拨”到侧向**：方向变化一律走 carve 的每 tick 旋转（与 W/S 同一套机制），只靠原版移动把位置走出来 |
| W | **前倾**（**渐进**到位，不瞬跳；W 与 S 同按判中立，避免快速切换时跳成前倾）：**整个人连同脚上的板**绕脚底向前倾 `TILT_DEGREES`（15°），**相机位置跟着倾斜后的头**（前移 眼高·sin15°），但**视线朝向不变**（仍正对原来的方向）；**不给任何推进** |
| S | **后倾**：同样整个人 + 板一起向后仰 `TILT_DEGREES`，相机位置跟着后移，视线不变 |
| **carve（切雪转弯）：W/S 只转向、不加力** | **前提是压刃**：必须按着 W 或 S，**光按 A/D 只是纯横移**。触发后身体朝向按**固定转弯半径**转：每 tick 转角 ＝ `clamp(toDegrees(vTravel / TURN_RADIUS_BLOCKS), TURN_MIN_DEGREES_PER_TICK, TURN_MAX_DEGREES_PER_TICK)` —— 半径 **4 格**（按「每端各跑一次」标定，见「状态表按端拆分」），满速 0.55 格/tick 时算出 **14.43**°/tick、被上限夹到 **12.0**°/tick（≈240°/s，半圈 15 tick ≈ 0.75 s；中速 0.30 格/tick 时 7.87°/tick），下限 2.0°/tick、上限 12.0°/tick（慢滑自然转得慢、快滑转得快，路线永远是同一个圆）。`vTravel` 是**还原过摩擦**的位移速度（`travelInAir` 末尾把速度乘过 `贴地 = 方块摩擦 × 0.91`，所以传进来的 `speed` 要先除回去，否则雪面上角速度只有 55%），**速度的方向跟着转同样的角度**（`rotateVelocity`，大小不变；整 tick 结束时再由下面的 `alignCarveHeading` 朝板轴限幅回正）—— 朝向转多少速度方向转多少，带惯性的路线慢慢弯过去，全程**不补任何推力**（W/S 只改方向）。**转向期间零掉速**：`CARVE_MOMENTUM_DECAY` **1.0**（转向只改方向、不吃任何摩擦；上限仍由 `MAX_SPEED` 0.55 夹住），而且此时按反向侧压是**换刃转向**、不再按刹车处理（`SnowboardControl.isCarving`）。**转向期间速度不衰减（`guardCarveSpeed`）**：`SkiGlide` 在动量结算之后对单板调一次 `SnowboardControl.guardCarveSpeed`，每 tick 算下限 `入场速度 × CARVE_MOMENTUM_DECAY^持续 tick`（现值 1.0 ⇒ **恒等于入场速度**），没撞到方块（`horizontalCollision` 为假）且当前水平速度低于它就缩放回去（方向不变、y 不动、`hurtMarked = true`），退出 carve 时清表 —— **只做下限、不封顶**（下坡加速自己涨上去的不会被拉回），撞墙那一 tick 不补，但下一个自由 tick 就回到这条曲线上。**速度方向自动回正到板轴（`alignCarveHeading`）**：单板是横着滑的，**板轴（身体左右轴）才是实际行进方向**，所以目标量就是板轴，**不再有「入弯基准」**（旧版那两张 `CARVE_HEADING` / `CARVE_YAW0` 表已删，`applyCarve` 也不再登记）。**每 tick 都掰，不要求正在 carve**：只要 ① 穿着单板、② 不在失控（`SkiAir.isStunned`）、③ 水平速度 > 1e-4、④ **近地**（`onGround()` **或** 脚底往下 **1.5 格**内有碰撞方块，走 `SkiGlide.groundDistance` 的碰撞射线），就取「板轴方向角 − 当前速度方向角」的最短角差（都按 `atan2(z, x)` 口径、用 `Mth.wrapDegrees`），差 ≤ **0.05°** 走死区（不写、不发包），否则把水平速度**按 `clamp(角差, ±ALIGN_MAX_DEGREES_PER_TICK 12.5°)` 旋转**过去（`rotateVelocity`，大小与 y 都不动、`hurtMarked = true`）—— 偏多少收多少，180° 的偏角最多 **15 tick（0.75 s）**就完全对上。`ALIGN_MAX_DEGREES_PER_TICK` 必须**严格大于**每 tick 最大身体转角（`TURN_MAX_DEGREES_PER_TICK` 12.0°），否则速度方向会永久落在身体后面；现值 12.5°、余量 0.5°（拆表前专用服务器上这条**刚好取等** 6.0 vs 6.0，单机里因为身体朝向被两端各写一次反而**是破的** 6.0 vs 12.0）。**往哪一侧掰由 `boardSide` 定，与头朝向（`headSide`）是同一个判定**（速度投在板轴上的分量 ≥ `HEAD_SIDE_MIN_SPEED` 0.05 就用它，否则看 A / D，都没有就锁存上一 tick），所以头朝哪边、速度就往哪边拉，不会互相矛盾。**调用点是整 tick 最后一个写速度的地方**（`SkiGlide.register` 里 `guardCarveSpeed` 之后，只在单板分支），收的就是同 tick 内别的写者（回落加速、动量托底、碰撞裁剪）留下的偏角。**撞墙也照拉**：`alignCarveHeading` 不再因 `horizontalCollision` 提前返回，`applyCarve` 里的 `rotateVelocity` 也无条件执行（身体转多少速度就转多少）—— 限幅 12.5°/tick 本身够温和，墙顶多让方向多歪一点，下一 tick 照旧被拉回来，不会出现「顶着墙滑、方向一直歪着」。**真腾空（脚底 1.5 格内没有方块）不掰**：速度方向由惯性保持，不在空中把路线掰弯。<br>转向方向：按 A/D 时由侧压决定，没按 A/D 时只要滑速 ≥ `CARVE_MIN_SPEED` 就前倾/后倾各走一边；**后倾（S）＝后刃**，转向与前倾相反。腾空也能转 |
| 视角范围 | 头只能相对身体朝向左右各 `VIEW_LIMIT_DEGREES`（135°），合计 270 —— 只剩背后 90° 是禁区。**限位只有一处权威状态**：`client/SkiViewSmoothing` 的 `headOffset`（视角相对身体朝向的偏角）。鼠标每帧转视角时（`SkiHeadLimitMixin` 拦 `Entity#turn` 的 HEAD/TAIL）只把这一帧转过的角度记进 `headOffset` 并夹一次、撤掉越界那点；每帧再由 `CameraViewSmoothingMixin`（`Camera#alignWithEntity` 的 HEAD）用「按 `partialTick` 插值后的身体朝向 + `headOffset`」写一次 yaw。没顶到限位时视角留在世界坐标里不动（只听鼠标），顶到之后头随身体平滑拖走；**往回转立刻响应，没有死区** |
| 空格 | **先起跳，而且起跳由本模组接管**（原版 `jumpFromGround` 在穿板时被 `mixin/LivingEntityJumpMixin` 在 HEAD 处 cancel，改由 `SkiAir.tryJump` 在**实体 tick 之内**发一次 —— 客户端注入 `LocalPlayer#aiStep` 的 HEAD（`travel()` 之前）、服务端在 `START_SERVER_TICK` 同 tick 起跳，不再依赖原版 `onGround` 分支）。贴地立即跳；离地 `COYOTE_TICKS`（6 tick）逗留窗内也跳（**只有这两条**：曾有第三条「离地 ≤ 1 格且 `vy ≤ 0` 就打向下射线补跳」，实机日志证实它在下落时被服务端每 tick 触发、把人一直弹起永远不落地，已删除）；按下沿有 `JUMP_BUFFER_TICKS`（5 tick）输入缓冲；`JUMP_COOLDOWN_TICKS`（10 tick）**在起跳成功时置满，落地沿也重新起算 —— 但只在这一趟腾空里起过跳（`jumpedThisAir`）或开过翻（`flipStarted`）时才禁** —— 跳过 / 翻过之后 10 tick（0.5 s）内不因「按住」起跳，所以**按住空格也不能在跳过 / 翻过之后落地即跳**，要等冷却走完（摔倒另叠 `CRASH_STUN_TICKS` 失控）；**只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳也没开翻的，落地不罚**；**落地沿只认真落地**（连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS` **3** tick 才算，坡面 `onGround` 1–2 tick 的逐 tick 抖动不算落地、不刷冷却）。起跳力度**随滑行速度放大**；**按住空格多久就转多少**（`holdTicks` 只在松手归零 —— 落地 / 起跳都不清）：前 4 tick 角度恒 0（静止段），第 5 tick 起每 tick 16°（22.5 tick 一圈 1.125 s），水平速度 ≥ 0.12 / 0.15 才转，空翻中不再补跳；松手立刻定格，**空中已经开翻过再按立刻续上**（`flipStarted`），只有从地面起跳那一下要数满 4 tick，落地角度不对会摔（见「跳跃与后空翻」） |
| 鼠标（头 / 视角） | 可以转，但相对身体朝向限在 ±`VIEW_LIMIT_DEGREES`（见上一行的单一限位实现） |
| 身体朝向 | **不跟着视角转** —— 锁定在穿上板那一刻；客户端每 tick 写回 `yBodyRot`（渲染读的是 render state 的 `bodyRot`）。**`yBodyRotO` 不再写** —— 原版 `baseTick` 每 tick 把它记成「上一 tick 的 yBodyRot」，留着它身体转动才是插值过去的（一起写成新值等于把插值掐掉，第三人称每 tick 跳 2°） |
| 滑行物理 | **只保留下落加速**：每降 1 格 +`FALL_GAIN_PER_BLOCK` 0.15（基数比双板 0.08 大；每 0.5 格结算一次）。方向**只沿当前水平速度**（`horizontal.normalize()`）—— **不投影到身体横轴、不跟随身体朝向**，所以空中转身之后这份加速只把速度加长、不会把速度方向拽向新朝向；**只有几乎没速度时**（`|v|² ≤ MIN_MOTION_SQR` 0.008）才按 A / D 的侧向（`sideLocked`），没速度也没按 A / D 就不给。空中也照样给。不做雪面推进、动量也不捕获玩家自己走出来的速度 —— 否则平地走路 / 站坡上都会“莫名往前” |
| 点杖冲刺 | 单板时不给冲刺（`SkiBoost.burst` 直接 return） |
| 换朝向 | 把板脱下来再穿上（重新锁定） |

> 单板的动量只在「下落加速」「按着 A / D 横移」和「转向（carve）」这三种情形下跟随，松开就靠原版摩擦停住；转向期间用的是 **`CARVE_MOMENTUM_DECAY` 1.0（零损失，转向只改方向、不吃摩擦）**（直行仍是 `MOMENTUM_DECAY` 0.998），**并且另有一道速度下限 `guardCarveSpeed`（入场速度 × 1.0^持续 tick ＝ 入场速度，撞方块那一 tick 不补，只做下限不封顶）—— 转向期间不会因为摩擦掉速，撞墙被削掉的速度撞完下一个自由 tick 就回到入场速度**；双板同为 `AlpineSkiControl.MOMENTUM_DECAY` 0.998。

双板现在也是「锁身体 + 按键转」，两块的锁表各自一份、**且每块按端再分两份**：单板 `SnowboardControl.BODY_YAW_CLIENT / _SERVER`、双板 `AlpineSkiControl.BODY_YAW_CLIENT / _SERVER`（实体 id → 角度；单机里两端共用 id，共用一张表会让每 tick 的转角被加两次 —— 见「状态表按端拆分」），条数超过 `util/IdMap` 的默认上限 64 就**按插入序淘汰最旧的一条**（所有这类 id 表都是 `IdMap`，超限自清；淘汰最旧而不是整表清空 —— 整表清空会让所有动量归零、`BODY_YAW` 被重锁、carve 失效）；双板的 A / D 每 tick 转 **2.0°**（`AlpineSkiControl.TURN_RATE_DEGREES`，≈40°/s，180° 需 90 tick ＝ 4.5 s），**按 W 时身体再朝视角方向补 2.4°/tick**（≈48°/s，180° 需 75 tick ＝ 3.75 s；`AlpineSkiControl.turnTowardView`）。**贴地转向期间速度方向由身体朝向推导**（`AlpineSkiControl.alignTurnHeading`，整 tick 最后一个写速度的点，单独一套 `TURN_HEADING` / `TURN_YAW0`，**前提是 `player.onGround()`**）：贴地时身体转多少、速度方向就转多少，不再依赖 `alignVelocity` 那条 9°/tick 的限幅（按 S 时它根本不跑）；**空中直接跳闸** —— 身体 / 视角照旧能转，速度方向一点不动（由惯性保持）；空中这一 tick 连同两份入弯记录一起清掉，否则落地那一 tick 会把空中累计的转角一次性算进速度方向、速度猛地一扭。**视角限位共用同一套实现、两块板各一个值**（`client/SkiViewSmoothing` 的唯一 `headOffset` + 两处入口：鼠标 `Entity#turn` 只记账、相机 `Camera#alignWithEntity` 每帧写一次）：双板限在身体左右各 **90°**，单板仍是 **135°**。

**越肩视角模式（Shoulder Surfing 打开时）**：**相机完全自由** —— `client/SkiViewSmoothing` 不再每帧写视角、`SkiHeadLimitMixin` 不再按 `VIEW_LIMIT_DEGREES` 限位（判定走反射 `IShoulderSurfing#isShoulderSurfing()`，见 `client/ShoulderSurfingCompat`；对方没装就与原来完全一致）；**头模型朝横移方向** —— 双板 = 身体朝向（正前方，偏移 0），单板 = 身体朝向 + **带符号的头偏角**：**朝哪一侧滑就朝哪一侧**（水平速度投到身体左轴定方向，侧向分量 < `HEAD_SIDE_MIN_SPEED` 0.05 时看 A / D，都没有就锁存上一次 —— 停下不会自己回正），幅度 **默认 `SNOWBOARD_HEAD_NEUTRAL_DEGREES` 75° / 按 W 前倾 `SNOWBOARD_HEAD_FORWARD_DEGREES` 90° / 按 S 后倾 `SNOWBOARD_HEAD_BACKWARD_DEGREES` 60°**；**左右翻转与幅度变化都平滑扫过**（`SnowboardControl.tickHead`：比例 0.45 + 单步上限 9°/tick —— 150° 翻转 25 tick ≈ 1.25 s、中途经过 0° 正前方；幅度变化约 10 tick ≈ 0.5 s；松手也是滑回当前方向的 75°）；**按 W 也不再让身体追视角**（客户端 `turnTowardView` 跳过）。退出越肩后限位与 W 追视角立刻按原规则恢复（状态在退出后第一帧按当时的视角重新起算）。

### 跳跃与后空翻 `SkiAir.java`（状态机共用，数值两块板各一套）

穿板时空格**由本模组接管起跳**（原版 `LivingEntity#jumpFromGround()` 在穿板时被 `mixin/LivingEntityJumpMixin` 在 HEAD 处 `ci.cancel()`，起跳只走 `SkiAir.tryJump`，而且**写在同一 tick 的实体 tick 之前**（客户端 `mixin/client/LocalPlayerJumpMixin` 注入 `LocalPlayer#aiStep` 的 HEAD，服务端 `START_SERVER_TICK` 同 tick 起跳）；**落地沿只在这一趟腾空里起过跳（`jumpedThisAir`）或开过翻（`flipStarted`）时才重新起算 `JUMP_COOLDOWN_TICKS` —— 纯粹从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳的落地不罚，按住空格落地后能立刻再跳**；落地沿只认「真落地」＝连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS`（**3**）tick，坡面贴面滑行时 `onGround` 的 1–2 tick 抖动不算落地、不刷冷却）：

- **起跳力度随水平滑行速度按对数形曲线升高**：`s = min(水平速度 / MAX_SPEED, 1)`（`MAX_SPEED` 就是该板的速度上限 0.55 / 0.85），力度 = 该板的 **11 点速度→起法力查找表** `JUMP_POWER_BY_SPEED_LUT`（s = 0.0 / 0.1 / … / 1.0 共 11 点，两点之间**线性插值**，取值在 `SkiAir.Tuning#jumpPower`）。表由**对数曲线** `h(s) = h₀ + (1.5 − h₀) × ln(1 + 9s) / ln(10)`（h = 跳高、h₀ = 0.34 单板 / 0.36 双板）在离散 tick 物理下（**第二 tick 起用腾空的 `AIR_GRAVITY_MULTIPLIER` 0.072**；**首 tick 仍是贴地档** 0.104 / 0.120 —— 跳跃写在同一 tick 的实体 tick 内，上一 tick 末尾 `SkiGlide.applyGravity` 按 `onGround()` 设的还是贴地档）反解而来 —— **前半段涨得快、后面趋平、末段增量趋近 0**，满速恰好卡在跳高上限 `JUMP_MAX_HEIGHT` **1.5 格**。**旧的两个线性常量 `JUMP_BASE_POWER` / `JUMP_SPEED_POWER_GAIN` 已删除**（同一个旋钮别再记在两行）。力度**只**由 `SkiAir.tryJump` 向这张表取：原版 `getJumpPower()` 那条路在穿板时已被 `LivingEntityJumpMixin` 取消，所以曾经那个「只改 `getJumpPower()` 返回值」的 mixin（`LivingEntityJumpPowerMixin`）没有任何消费者，已随死代码删除 —— **别再把它当成起跳力度的注入点**。
- **起跳由本模组接管（单一入口 `SkiAir.tryJump`）**：为什么必须接管 —— 原版起跳在 `LivingEntity#aiStep` 里，条件是「`this.jumping`（`LocalPlayer#tick` 由 `input.keyPresses.jump()` 写入）**且（`onGround()` 或在水里）**」。滑行时 `onGround` 在上坡 / 起伏 / 贴面走但没严格落地时经常是假，按空格就没反应；另外原版 `jumpFromGround()` 自己也会 `setDeltaMovement(x, max(power, vy), z)`，和补跳抢同一份 y。现在原版那条路在 `LivingEntity#jumpFromGround()` 的 HEAD 处被 `ci.cancel()`（`mixin/LivingEntityJumpMixin`），起跳只走 `SkiAir.tryJump`，**两端各一次、都放在实体 tick 之前**：客户端 `LocalPlayerJumpMixin` 注入 `LocalPlayer#aiStep` 的 HEAD（在 `super.aiStep()` / `travel()` 之前），服务端在 `START_SERVER_TICK` 用 `getLastClientInput().jump()` 起跳。**为什么服务端也要跳**：服务端只要 `hurtMarked` 就把整份速度经 `ClientboundSetEntityMotionPacket` 发给玩家本人，客户端 `Entity#lerpMotion` 是**整份覆盖** `deltaMovement`；滑行代码几乎每 tick 都 `hurtMarked`，服务端自己不跳就发的是下落速度，会把客户端写的冲量抹掉（原来挂在 `END_CLIENT_TICK` 时「穿板按空格完全没反应」就是这个）。两条触发条件**任一成立**即起跳：① `onGround`；② 离地未满 `COYOTE_TICKS`（**6 tick**）的逗留窗（土狼时间，`onGround` 在坡上抖动时不会漏按）。**曾经还有第三条「离地但 `vy ≤ 0` 且脚底到下方地面 ≤ 1.0 格（向下 1.0 格的碰撞射线）也跳」，已删除** —— 实机日志（`S jump! ... onGround=false groundBelow=0.635 / 0.621 / 0.874`，逐 tick 连发）证明它在**腾空下落**时被服务端反复命中：脚底离地本来就在 1 格左右，一落就被再弹起，**永远碰不到地**，于是落地沿 / 落地判定 / 禁跳 / 角度归零全都不触发（地面掉出 1 格射线范围反而能落地，表现为「相差一格以内就不判定」）；而服务端 `holdTicks` 恒为 0（它只在客户端 tick 累加），所以「空翻中不补跳」那道门在服务端拦不住它。它原本要兜的「坡面 `onGround` 抖动 → 按空格没反应」已由土狼窗覆盖（`COYOTE_TICKS` 6 tick，实测贴面抖动最多 3 tick）。**输入缓冲**：按下沿充值 `JUMP_BUFFER_TICKS`（**5 tick**），提前一点按下、条件成立那一 tick 自动发出。**防连发 / 落地禁跳**：`JUMP_COOLDOWN_TICKS`（**10 tick**）起跳成功时置满；**落地沿也在 `max()` 到满值 —— 但仅在「这一趟腾空里起过跳（`jumpedThisAir`，紧跟起跳成功那一行置位）或开过翻（`flipStarted`）」时**（`onGround` 由假变真，状态里 `wasOnGround` 做的边沿检测，默认 `true` 免得刚建状态那一 tick 被当成一次落地白锁）；**只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳也没开翻的，落地不罚**（否则滑下坡落一下地就白锁 0.5 s，按不出跳）。两个标志各有归属：`jumpedThisAir` 只由 `tryJump` 置与清（落地即清，下一趟腾空重新算），`flipStarted` 仍只由 `landClient` 落地时清 —— `tryJump` 的落地沿比 `landClient` 早（客户端 `aiStep` HEAD / 服务端 `START_SERVER_TICK`），所以落地那一 tick 读到的 `flipStarted` 还是 `true`。**落地沿要防抖**：滑行时 `onGround` 在上坡 / 起伏 / 贴面走但没严格落地时经常为假、还会逐 tick 抖动，纯边沿会在坡上每抖一次就刷满冷却（表现成「怎么按都跳不起来」），所以状态里另记 `airTicks`（本次连续腾空 tick 数：贴地归零、腾空累加并饱和），**只有连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS`（3）才算一次真落地**；1–2 tick 的贴面抖动不算落地、**不刷冷却**（真跳跃 / 空翻的腾空远长于 3 tick，闸门只为防刷，落地后的处罚不变）。所以**跳过 / 翻过之后按住空格也不能落地即跳**，落地后要等 0.5 s 冷却走完（摔倒另叠 `CRASH_STUN_TICKS`）；**纯滑落落地不受这条约束**。落地沿只看各自这一端的 `onGround`，两端每 tick 各调一次 `tryJump`，因此**双方自然得到同一个落地沿、不需要额外发包**，不会出现「客户端禁了、服务端还在跳」。写入方式只有一种：`deltaMovement` 的 y ＝ 起力、水平不变 ＋ `hurtMarked = true` ＋ `needsSync = true`（原版 `jumpFromGround` 同样两个标记），**不 `setPos`**。**起跳成功不再动 `holdTicks`**（计数从按下沿起算、只由松手归零），也**不再在起跳那一刻判定「能不能翻」**：能不能转是离地之后每 tick 实时算的。另外**空翻中不补跳**：`!onGround && holdTicks > FLIP_HOLD_DELAY_TICKS` 时 `tryJump` 直接返回 `false`（贴地起跳照旧，仍受起跳冷却约束）。**服务端读不到「这一 tick 有没有按住空格」**（`getLastClientInput()` 是上一份同步上来的输入），所以两端各自跑同一份 `tryJump`（**空中状态按端分开存：客户端 / 服务端各一张表**），不是同一状态机双写。**为什么要分表**：单机下两端共用同一 entity id，共用一张表会让计时器每 tick 递减两次（冷却 / 缓冲 / 逗留窗都只剩一半），且后调用的那端被先起跳那端写下的冷却拦下。
- **按住多久就转多少（前 4 tick 是静止段）**：空格先起跳（起跳走 `SkiAir.tryJump`，短按、长按都先起跳）。`holdTicks` ＝ **从按下那一刻起的连续按住 tick 数**，**只由松手归零**（`landClient` 落地、`tryJump` 起跳成功、失控**都不再清**），所以在地面上按着的那几 tick 会一直数进空中。空中**每 tick 实时判**（不再有「离地第一 tick 拍快照」的一次性阀门 `flipping`，那个字段已删除）：`holdTicks > FLIP_HOLD_DELAY_TICKS`（**4**，严格大于）**或本段已开翻**（`flipStarted`）＋ 没在失控里 ＋ 水平速度 ≥ `FLIP_MIN_SPEED` ＝ 这一 tick 转 `FLIP_DEGREES_PER_TICK`（**两板同值 16°＝22.5 tick 一圈 1.125 s**）。**只有从地面起跳那一下**因为判据是 `>`，**按住的前 4 个 tick 角度恒为 0** —— 这就是模型静止段；**短按（≤ 4 tick）＝纯跳跃、一点不转**；**松手立刻定格**（角度与模型停在那偏角）但计数归零；**空中已经开翻过就不必重数 —— 再按住下一 tick 立刻续上**（`flipStarted` 自上次落地起一直保持，只有 `landClient` 落地才清；不能用「角度 ≠ 0」代替，因为角度是 [0,360) 取模、翻满一圈那一 tick 会回到 ≈0）。**不在 360° 封顶**（可以按住转多圈），内部按 360 取模保存（[0,360)）避免数值膨胀。速度不够＝普通小跳、按住再久也不转；门槛每 tick 都看，滑着加速过线就立刻开始转。
- **落地判定按「与直立的最小角距」**：a ＝ 最终角度对 360 取模后映射到 (−180°, 180°]，`|a| ≤ FLIP_LANDING_TOLERANCE_DEGREES`（**±35°**）＝ **干净落地**（角度归零、速度不掉、无惩罚）—— **多翻了几圈只要停在直立附近也算净落**；否则＝**摔倒**：水平速度 ×`CRASH_SPEED_MULTIPLIER`、**向上（`vy > 0`）的速度归零**（`motion.y = min(motion.y, 0)`，把「落地那一 tick 的起跳」抢在禁跳之前弹起来的那一下吃掉）、进入 `CRASH_STUN_TICKS` 的失控 —— 失控期间两块板的**转向 / 压刃 / carve / 追视角**在各自入口一律跳过（`SnowboardControl.isCarving`、`AlpineSkiControl.turnBody / turnTowardView / shouldAlignOnGround`），且**禁止起跳**（`SkiAir.tryJump` 在 `stunTicks > 0` 时在计时推进之后、判条件之前直接返回 `false` —— 客户端 `landClient` 与服务端 `applyLanding` 都会置这个计时，而 `tryJump` 两端各调一次，所以一处闸门就是双侧禁跳；按住空格的人不会在摔倒的下一 tick 又从 0 开翻）。`CRASH_DAMAGE_MAX`（两板各一个，**现值 4.0**）> 0 时服务端按「与直立偏差 / 180 × 它」扣血：偏差 180° 扣 4 点（2 颗心）、90° 扣 2 点、±35° 边界约 0.78 点（35 / 180 × 4.0）。伤害类型是 **`damageSources().generic()`，不是 `fall()`** —— `SnowLanding` 的雪地免伤只取消 `DamageTypeTags.IS_FALL`，用 fall 的话**雪地上摔倒一点血都不掉**；`generic` 不在 `is_fall` tag 里所以拦不住，而且它在 `bypasses_armor` tag 里（护甲不会把这 4 点折小）。
- 状态机只有一份（`SkiAir`，每玩家状态用 `util/IdMap` 存：角度、上一 tick 角度、在空中、**连续按住计数**、失控计时；**「在翻」那个一次性字段 `flipping` 已删除** —— 能不能转是每 tick 实时算的）。**空翻角度与「有没有按住空格」只有客户端知道**（`LocalPlayer#input.keyPresses.jump()`，服务端读不到这个输入），所以**角度累积与观感都在客户端本地跑**（`SkiAir.tickClient`，只对本机玩家）；落地那一 tick（净落与摔倒**都**发）客户端发一个 C2S 包 `SkiFlip.Payload`（通道 `skis:flip_landing`，只传一个 float ＝ 最终角度；`PayloadTypeRegistry.serverboundPlay()` + `ServerPlayNetworking.registerGlobalReceiver` 都注册在**主源码集**里、双端共用同一张编解码表）把角度交给服务端。服务端 `SkiAir.tickServer` **只递减失控计时**（不再算角度），收到包后 `SkiAir.applyLanding` 用**同一个 `isCleanLanding`** 判定并施罚：水平速度 ×`CRASH_SPEED_MULTIPLIER` + **向上的速度归零**（`motion.y = min(motion.y, 0)`）+ `hurtMarked`，**只走 `deltaMovement`，不 `setPos`**；客户端本地同时按同一判定进失控（画观 / 输入门与服务器一致，降速仍只在服务端）。包里的角度有合法性校验：**非有限值 / 负值 / 超过 `MAX_NET_ANGLE_DEGREES`（3600°＝10 圈）直接丢弃**，不结算；此外还要求 **① `player.onGround()` 为真**（包是「落地那一刻」发的，人在空中说明这一份不是落地）**② 同一个 id 两次结算至少隔 `CRASH_STUN_TICKS`（20 tick ＝ 1 秒）** —— 否则只要角度合法的客户端就能无 cd 地自我扣血 + 降速。`isCleanLanding` 对非法输入返回 `true`（「不惩罚」）只是契约兜底，服务端在调用它之前就已把非法输入早退丢掉。
- 观感：渲染时**整个人 + 板**绕自身左右轴**向后**翻（`EntityTiltMixin` 的 poseStack 旋转，绕**腰部高度** 1.0 格、按 `partialTick` 插值）；落地那一帧状态机已把角度归零，所以插值两端都是 0、不留残余角度。**第一人称也跟着翻**：`CameraFlipMixin` 在 `Camera#alignWithEntity(F)` 的 TAIL 把本帧朝向重设成 `setRotation(yRot, xRot − flip)`（`flip = SkiAir.angleDegrees(id, partialTick)`）—— 用的是与模型**同一套符号**（`Axis.XP.rotationDegrees(flip)` 正角＝向后翻、相机 `xRot` 正＝俯视，所以是减）；只在第一人称 + 穿本模组的板 + 角度非 0 时写，叠加在 `CameraTiltMixin`（单板 W/S 的相机**位置**修正）之后，第三人称那条链与 `EntityTiltMixin` 的旋转量都不动。不碰 FOV / 鼠标灵敏度。

| 常量（单板 `SnowboardControl`） | 当前值 | 作用 |
|---|---|---|
| `JUMP_MAX_HEIGHT`（单板） | 1.5 格 | **跳高上限**：满速（s = 1.0）起跳在**腾空重力 0.072**（`AIR_GRAVITY_MULTIPLIER` 0.9 × 原版 0.08；首 tick 例外，贴地档 0.104）的离散物理下正好 1.50 格。下面那张表就是按它反解的；代码**不拿它反推力度**（表是唯一事实源），改上限要整张表重算 |
| `JUMP_CURVE_SAMPLES`（单板） | 11 点 | 速度→起法力查找表的长度（s = 0.0 / 0.1 / … / 1.0，插值步长 0.1）。静态块校验 `LUT.length == 它`，写错长度启动即抛 |
| `JUMP_POWER_BY_SPEED_LUT`（单板） | 11 点：0.208 / 0.302 / 0.348 / 0.377 / 0.400 / 0.417 / 0.430 / 0.442 / 0.452 / 0.461 / 0.470 | **速度 → 起跳力度**（格/tick），下标 = s × 10，两点之间线性插值。由对数曲线 `h(s) = 0.34 + (1.5 − 0.34) × ln(1 + 9s) / ln(10)` 在**首 tick 贴地 0.104、其后腾空 0.072** 下反解（旧 `JUMP_BASE_POWER` 0.22 / `JUMP_SPEED_POWER_GAIN` 0.83 已删；上一版按 2.0 格 / 0.064 反解 0.179 → 0.494） |
| `COYOTE_TICKS`（单板） | 6 tick | **离地逗留窗**（土狼时间）：离开地面后 6 tick（0.3 s）内按空格仍算「刚离地」，照样起跳 —— 滑行时 `onGround` 在坡上 / 起伏上会抖，这个窗口兜住「按了却不跳」 |
| `JUMP_BUFFER_TICKS`（单板） | 5 tick | **输入缓冲**：按下空格（按下沿）后 5 tick（0.25 s）内只要起跳条件成立就自动发出，不用重新按 |
| `JUMP_COOLDOWN_TICKS`（单板） | 10 tick | **起跳冷却（防连发）＋落地禁跳**：起跳成功时置满；**落地沿也重新起算 —— 但只在这一趟腾空里起过跳（`AirState#jumpedThisAir`）或开过翻（`flipStarted`）时**（`onGround` 由假变真，`AirState#wasOnGround` 边沿，默认 `true`）—— 跳过 / 翻过之后 10 tick（0.5 s）内不因「按住」起跳，所以**按住空格也不能在跳之后落地即跳**。**只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳也没开翻的落地不罚**；**落地沿另要防抖：只有连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS`（3）tick 的落地才算真落地**（`AirState#airTicks` 计数），坡面逐 tick 抖动（连续腾空 1–2 tick）不算落地、**不刷冷却**；`jumpedThisAir` 落地即清（下一趟腾空重新算），`flipStarted` 归 `landClient` 清；摔倒另叠 `CRASH_STUN_TICKS` 的失控（不转、降速、扣血、禁跳） |
| `FLIP_MIN_SPEED`（单板） | 0.12 格/tick | **空中每 tick 实时判**的水平速度门槛：低于它这一 tick 不转角（0.12 ≈ 单板上限 0.55 的 22%） |
| `FLIP_HOLD_DELAY_TICKS`（单板） | 4 tick | **连续按住多少 tick 之后才开始转**（4 tick ＝ 0.2 s，判据是 `holdTicks > 它`，严格大于）：按住的**前 4 个 tick 角度恒 0**（静止段），短按（≤ 4 tick）＝**纯跳跃、一点都不转**；计数从**按下那一刻**起算、**只在松手时归零**（落地 / 起跳成功都不清），所以站着按住再起跳＝一离地就接着转；**只有从地面起跳那一下**受它约束 —— 空中已开翻（`flipStarted`）后松手再按**立刻续翻、不再等**；**空翻中不补跳** |
| `FLIP_DEGREES_PER_TICK`（单板） | 16.0 度/tick | **按住空格**时每 tick 转多少（16 × 22.5 = 360°，一圈 **1.125 s**；**与双板同值**，各一个常量） |
| `FLIP_LANDING_TOLERANCE_DEGREES`（单板） | 35 度 | 落地时与直立（0° / 360° / 720°…）的角距在 **±35°** 内算净落，否则摔倒（多圈也只比最近的一圈） |
| `CRASH_SPEED_MULTIPLIER`（单板） | 0.35 | 摔倒时水平速度保留的比例（掉 65%）；同时**向上的速度归零**（`motion.y = min(motion.y, 0)`：把落地那一 tick 抢在禁跳之前发出的那次起跳吃掉，摔倒就是往下走） |
| `CRASH_STUN_TICKS`（单板） | 20 tick | 摔倒后失控多久（1 s）：转向 / 压刃 / carve / 追视角跳过，且**失控期间禁止起跳**（`tryJump` 在 `stunTicks > 0` 时直接返回 `false`，所以按住空格也不会立刻从 0 重新开翻）；`landClient`（客户端）与 `applyLanding`（服务端）都会置它 |
| `CRASH_DAMAGE_MAX`（单板） | 4.0 | 摔倒扣血上限（**4.0 ＝ 偏差 180° 扣 2 颗心、90° 扣 1 颗心**；0 ＝ 关闭）。服务端按「与直立偏差 / 180 × 它」比例扣，伤害类型是 **`damageSources().generic()`**（不是 `fall()`）：本模组 `SnowLanding` 会取消所有 `IS_FALL` 的伤害，用 `fall` 等于**雪地上摔倒不掉血**；`generic` 不在 `is_fall` tag 里（拦不到）、又在 `bypasses_armor` tag 里（护甲不折这 4 点）—— 所以雪地 / 石头地都生效、扣的就是标称值 |

| 常量（双板 `AlpineSkiControl`） | 当前值 | 作用 |
|---|---|---|
| `JUMP_MAX_HEIGHT`（双板） | 1.5 格 | **跳高上限**：满速（s = 1.0）在**腾空重力 0.072**（首 tick 贴地档 0.120）下正好 1.50 格。同单板：只作依据，代码不反推 |
| `JUMP_CURVE_SAMPLES`（双板） | 11 点 | 查找表长度（步长 0.1），静态块校验 |
| `JUMP_POWER_BY_SPEED_LUT`（双板） | 11 点：0.226 / 0.318 / 0.363 / 0.392 / 0.414 / 0.431 / 0.444 / 0.456 / 0.466 / 0.475 / 0.483 | 同单板，由 `h(s) = 0.36 + (1.5 − 0.36) × ln(1 + 9s) / ln(10)` 在**首 tick 贴地 0.120、其后腾空 0.072** 下反解（起跳点略高于单板，所以同一档力度略高；上一版按 2.0 格 / 0.064 反解 0.186 → 0.494） |
| `COYOTE_TICKS`（双板） | 6 tick | **离地逗留窗**（土狼时间）：离地后 6 tick 内按空格仍算「刚离地」可起跳；与单板同值、各一个常量 |
| `JUMP_BUFFER_TICKS`（双板） | 5 tick | **输入缓冲**：按下空格后 5 tick 内条件成立就自动发出起跳；与单板同值、各一个常量 |
| `JUMP_COOLDOWN_TICKS`（双板） | 10 tick | **起跳冷却（防连发）＋落地禁跳**：起跳成功时置满；**落地沿也重新起算 —— 但只在这一趟腾空里起过跳（`jumpedThisAir`）或开过翻（`flipStarted`）时**（跳过 / 翻过之后 10 tick（0.5 s）内不因「按住」起跳）；**纯滑落 / 走下台阶 / 掉到地上的落地不罚**；**落地沿与单板同一防抖判据：连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS`（3）tick 才算真落地**，坡面 1–2 tick 抖动不刷冷却（这个门槛两块板共用、不在各自 `Tuning` 里）；与单板同值、各一个常量 |
| `FLIP_MIN_SPEED`（双板） | 0.15 格/tick | **空中每 tick 实时判**的水平速度门槛：低于它这一 tick 不转角（0.15 ≈ 双板上限 0.85 的 18%） |
| `FLIP_HOLD_DELAY_TICKS`（双板） | 4 tick | **连续按住多少 tick 之后才开始转**（4 tick ＝ 0.2 s，判据 `holdTicks > 它`）。与单板同值、各一个常量：**从地面起跳那一下**的前 4 个按住 tick 角度恒 0（静止段），短按＝纯跳跃，计数只在松手时归零，**空中已开翻后松手再按立刻续翻**（不等这 4 tick），空翻中不补跳 |
| `FLIP_DEGREES_PER_TICK`（双板） | 16.0 度/tick | **按住空格**时每 tick 转多少（16 × 22.5 = 360°，一圈 **1.125 s**）；**与单板同值**，各一个常量：改一块不动另一块 |
| `FLIP_LANDING_TOLERANCE_DEGREES`（双板） | 35 度 | 与单板同值、各一个常量（±35°） |
| `CRASH_SPEED_MULTIPLIER`（双板） | 0.35 | 与单板同值、各一个常量 |
| `CRASH_STUN_TICKS`（双板） | 20 tick | 与单板同值、各一个常量 |
| `CRASH_DAMAGE_MAX`（双板） | 4.0 | 与单板同值、各一个常量（**4.0 ＝ 偏差 180° 扣 2 颗心**）。伤害类型同样是 `damageSources().generic()`：**雪地不会把它吃掉**（`SnowLanding` 只取消 `IS_FALL`），`generic` 又在 `bypasses_armor` 里，护甲不折 |

**两块板的差异就三处**：静止/满速两端的起法力（单板 0.208 → 0.470、双板 0.226 → 0.483，各自一张表）、能否开翻的门槛速度（**0.12 / 0.15**，占各自上限 **22% / 18%**），以及表的起跳点 h₀（0.34 / 0.36）；**两张表现在都按同一个腾空重力 0.072 反解**（`AIR_GRAVITY_MULTIPLIER` 0.9 × 原版 0.08），贴地的 1.3 / 1.5 只在起跳首 tick 参与（那时重力还没切到腾空档）。**按住空格时的转速两板同值：`FLIP_DEGREES_PER_TICK` 16.0°/tick ＝ 22.5 tick 一圈 1.125 s**（各一个常量，改一块不会带走另一块）。**「按住前 4 tick 只数不转」这个前置延迟 `FLIP_HOLD_DELAY_TICKS` 两板同值（4，判据 `>`；只对从地面起跳那一下生效，空中已开翻后再按立刻续翻）**，`COYOTE_TICKS` / `JUMP_BUFFER_TICKS` / `JUMP_COOLDOWN_TICKS` 也同值（6 / 5 / 10），净落容差、惩罚与失控时长两边同值但各自一份常量，改一块不会带走另一块。

**这套数值的后果**（离散模拟：起跳 `vy = power`，每 tick 先位移、再 `vy = (vy − g) × 0.98`，**第二 tick 起 g ＝ 腾空重力 0.072**，首 tick 仍是贴地档 0.104 / 0.120 —— 跳跃写在同一 tick 的实体 tick 内，重力属性下一个 tick 才切到 `AIR_GRAVITY_MULTIPLIER`）。

**跳高 —— 对数形的直观读数**（就是那 11 点查找表本身；`s` ＝ 水平速度 / 该板 `MAX_SPEED`，高度单位：格）：

| s | 0.0 | 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **单板** 起法力 | 0.208 | 0.302 | 0.348 | 0.377 | 0.400 | 0.417 | 0.430 | 0.442 | 0.452 | 0.461 | 0.470 |
| **单板** 跳高 | 0.34 | 0.66 | 0.86 | 1.00 | 1.11 | 1.20 | 1.28 | 1.34 | 1.40 | 1.45 | 1.50 |
| **双板** 起法力 | 0.226 | 0.318 | 0.363 | 0.392 | 0.414 | 0.431 | 0.444 | 0.456 | 0.466 | 0.475 | 0.483 |
| **双板** 跳高 | 0.36 | 0.68 | 0.87 | 1.01 | 1.12 | 1.20 | 1.28 | 1.34 | 1.40 | 1.45 | 1.50 |

（注：20% 速度已拿到 57%（单板）/ 58%（双板）的高度，这就是用户要的对数曲线形状；满速就卡在 1.50 格 ＝ `JUMP_MAX_HEIGHT`。腾空重力从 0.064 换到 0.072、上限从 2.0 压到 1.5 后**曲线形状不变**，只是整张表按新 g 重新反解，满速 1.50 格。）

**滞空只决定「最多能按住转多久」**（按住转速 `FLIP_DEGREES_PER_TICK`：两板同值 16°/tick → 22.5 tick ＝ 1.125 s 一圈；另外**从地面起跳那一下的前 4 个连续按住 tick 只计数不转**，见 `FLIP_HOLD_DELAY_TICKS`）：满速起跳的待机滞空（同一套离散物理，第二 tick 起 g ＝ 0.072；**滞空 tick ＝ 离地到落地前的 tick 数，落地那一 tick 不计** —— 上一版那张表把落地 tick 也数进去了，同口径下旧满速是 15 而不是表里的 16；「坡 α°」＝ 沿 α° 坡往下起跳、落点算在同一坡面上）：

| 地形 | 单板满速 p = 0.470 | 双板满速 p = 0.483 |
|---|---|---|
| 平地 | 12 tick / 0.60 s | 12 tick / 0.60 s |
| 10° 坡 | 15 tick / 0.75 s | 17 tick / 0.85 s |
| 20° 坡 | 19 tick / 0.95 s | 22 tick / 1.10 s |
| 30° 坡 | 22 tick / 1.10 s | 28 tick / 1.40 s |
| 45° 坡 | 31 tick / 1.55 s | 42 tick / 2.10 s |
| 60° 坡 | 47 tick / 2.35 s | 75 tick / 3.75 s |
| 75° 坡 | 123 tick / 6.15 s | 557 tick / 27.85 s |

（静止档 p = 0.208 / 0.226 平地 **6 tick**、s = 0.2（p = 0.348 / 0.363）**9 tick**、满速 **12 tick**；上一版 2.0 格 / g 0.064 时满速是 15 tick。）

→ 转多少完全看**按住多久**，上表给的是「最多能按住转多久」；而且**从地面起跳那一下的前 4 个按住 tick 只数不转**（`FLIP_HOLD_DELAY_TICKS` 4 之后的第 5 个 tick 起才加角度），所以可转 tick ＝ 滞空 tick − 4，已转角度 ＝ 16° × 可转 tick：

- **平地 12 tick**：两板可转 8 tick ＝ **128°**（距直立 128°，连半圈都不到）—— 平地按住到底也翻不满一圈，落地就是摔。要凑够一圈（22.5 个可转 tick，算上 ±35° 容差是 21–24 个）至少要 **25 tick 滞空**。
- **单板净落窗口是滞空 25–28 tick**（可转 21 / 22 / 23 / 24 tick ＝ 336° / 352° / 368° / 384°，偏差 −24° / −8° / +8° / +24°，都在 ±35° 内；再往上每 22.5 tick 一个窗口：**47–51 / 70–73 / 92–96 tick** 亦净落（±25° 时是 48–50 / 70–73 / 93–95，35° 把每个窗口两头各放宽 1 tick））。表里：10° 坡（15 tick）可转 11 tick ＝ 176°（距直立 176°，几乎倒着落）、20° 坡（19 tick）15 tick ＝ 240°（偏 −120°）、30° 坡（22 tick）18 tick ＝ 288°（偏 −72°）、45° 坡（31 tick）27 tick ＝ 432°（≡72°，偏 +72°）、60° 坡（47 tick）43 tick ＝ 688°（≡328°，偏 −32°，正好落进第二个窗口 → **净落**）、75° 坡（123 tick）119 tick ＝ 1904°（≡104°，偏 +104°）—— **表里只有 60° 坡这一档净落**（容差 25° 时它是偏 −32°、差 7° 进不去，所以那时一档都没有）。**单板想稳净落得把滞空凑进 25–28 tick** —— 这个窗口落在表里 30° 坡（22 tick）与 45° 坡（31 tick）之间；比这更陡的 75° 坡滞空早过了这两个窗口，转过 5 圈反而落不正。
- **双板**（同样 16°/tick，一圈要 22.5 个可转 tick，算上容差 21–24 个 ＝ 滞空 25–28 tick）：表里**只有 30° 坡（28 tick）净落** —— 可转 24 tick ＝ 384°（≡24°，偏差 +24°，在 ±35° 内、离边界还有 11°）；其余：10° 坡（17 tick）13 tick ＝ 208°（偏 −152°）、20° 坡（22 tick）18 tick ＝ 288°（偏 −72°）、45° 坡（42 tick）38 tick ＝ 608°（≡248°，偏 −112°）、60° 坡（75 tick）71 tick ＝ 1136°（≡56°，偏 +56°）、75° 坡（557 tick）553 tick ＝ 8848°（≡208°，偏 −152°），全是摔（双板这几档的滞空都不在 43–47 那个加宽的窗口里，容差放到 35° 也没多一档）。**双板想稳净落也得滞空 25–28 tick**（窗口落在 20° 坡 22 tick 与 30° 坡 28 tick 之间，比 30° 更陡的坡就冲出窗口了）。
- 75° 那种极端陡坡：单板 123 tick（6.15 s）可转 119 tick ＝ 1904°（≡104°，偏 +104°，摔）；双板 557 tick 已进「能转几十圈」的区间，不再当卖点写秒数；两板 80° **未测**。

晚了几 tick 就反而摔，所以「松手时机 ＋ 够陡的坡」就是这套机制的操作核心 —— 而且**按住时长是唯一的量**（落地、起跳都不再清计数，所以连跳时按住不松手会一路接着转，落地判定用当次落地那一刻的角度）。缓坡想净落只能把 `FLIP_DEGREES_PER_TICK` 调快（按住更少 tick 就凑满一圈）或把 `FLIP_HOLD_DELAY_TICKS` 调小（少吃掉几个 tick）。

**跳高随速度严格单调上升、但边际递减**（单板 0.34 → 0.66 → 0.86 → … → 1.50 格）：前 0.2 的速度就拿到一半以上的高度，每 0.1 速度的增量从 0.32 格一路收到 0.05 格，满速卡死在 `JUMP_MAX_HEIGHT` 1.5 格。想改跳高要改**整张表** `JUMP_POWER_BY_SPEED_LUT`（步长 0.1，按上面的对数公式在离散物理里反解，别手调单点）；想改按住时的转速只动 `FLIP_DEGREES_PER_TICK`（调高＝同样滞空能转更多圈）；想改「按住多久才开翻」只动 `FLIP_HOLD_DELAY_TICKS`；想改「多快才敢开翻」只动 `FLIP_MIN_SPEED`；想改「多接近直立才算净落」只动 `FLIP_LANDING_TOLERANCE_DEGREES`。

### 滑行雪粒子 `SnowSpray.java`（纯客户端观感）

穿着任一种板、在雪 / 冰面上滑行时扬起的雪，**纯客户端观感**（`particleEngine.createParticle` + `Particle#setParticleSpeed`）：服务端不参与，粒子多少不影响速度 —— 速度只决定粒子。

> **初速必须经 `ParticleEngine#createParticle` 拿回 `Particle` 再 `setParticleSpeed`**（`SnowSpray.spawn`）。`ITEM_SNOWBALL` 的 provider（`BreakingItemParticle$SnowballProvider`）在 `createParticle(...)` 里**根本不读** `xSpeed/ySpeed/zSpeed` 三个参数（字节码只读 x/y/z），直接 `new BreakingItemParticle(level, x, y, z, sprite)`，构造器把速度写成随机 ±0.1 —— 所以走 `ClientLevel#addParticle` 传初速**永远无效**：2026-10-17 之前所有「顶点高度 → 初速」的调参一颗都没接上，粒子只会飘十几厘米。另外原版粒子寿命是 `lifetime = (int)(4 / (rand*0.9 + 0.1))`＝**随机 4–40 tick、均值约 10**，顶点 3 格要 ≈25 tick 才走完，所以高飞档用 `FLURRY_HIGH_LIFETIME` 40 兜住（只抬不降）。

**只用一种粒子**：`SNOW` = `ParticleTypes.ITEM_SNOWBALL`（雪球崩碎的白色碎屑，重力 1.0、会落回雪面）。模组里**不用任何雾 / 烟 / 云类粒子** —— `SNOWFLAKE`、`POOF`、`CLOUD` 这些看着像一团白雾，用户明确不要。（原先撑杖时脚下的 8 颗 `SNOWFLAKE` 已整段删掉。）

三层，数量都随速度涨（**低速也保底每侧 1 颗**，只是贴着地面）：

1. **贴地雪屑**：从**板的两侧**（与滑行方向垂直的左右两边）向外、略向上溅，数量随速度增加；低速 1 颗起步，0.50 时每侧 6 颗、0.70 起夹满 8 颗。
2. **扬起雪屑**：同样从板两侧起，但**按「顶点高度」调（单位：格）**，而且**顶点随速度长** —— 每颗自己抽一个区间内的顶点，区间两端各是一条直线：下沿 `APEX_LOW_FROM`→`APEX_LOW_TO`、上沿 `APEX_HIGH_FROM`→`APEX_HIGH_TO`，速度从 `APEX_SPEED_LO`(0.10) 到 `APEX_SPEED_HI`(0.85) 之间线性插值。于是 **0.10 = 0.20–0.40 格（贴着地面）**、0.30 = 0.41–0.91（腰）、0.50 = 0.63–1.41（胸）、0.85 = 1.00–2.30（胸到头）。出生初速由 `riseFor` 用离散粒子物理**反解**（不是手调 vy）。按 `FLURRY_HIGH_FRACTION` 0.65 拆成**下半 / 上半两档**，混起来才有层次。高飞档寿命抬到 `FLURRY_HIGH_LIFETIME` 34 tick（默认均值 10 tick 会半空消失）。**转向时这一层更多、顶点再 +0.35 格**（封顶 2.80 格）—— 但**只在没有任何按键偏向时才铺到两侧**：双板按着 A / D、单板按着 W / S 且在转向时，这一份额外只落在**受力侧**（= 弯外侧）；单板压着刃但没在转向时两侧都不加，由板端那一簇自己吃（见下面的受力侧一段）。
3. **急减速雪屑**：一 tick 内水平速度掉了 `BURST_DROP` 以上（急刹车 / 撞墙 / 压刃刹车都是这个形状）就从脚下一圈炸开一大团雪屑，粒子数随掉落量增加，带 `BURST_COOLDOWN_TICKS` 冷却。**但必须先过三道闸门**：上一 tick 贴地（`LAST_GROUNDED`）+ 本 tick 贴地（脚底到雪面 ≤ `GROUND_TOUCH_TOLERANCE` 0.15 格）+ 垂直速度 `|vy| < FALL_TOLERANCE` 0.10。落地那一 tick 水平速度同样会突降（动量重置 + 落地摩擦），只看差值的话**从一两格高跳下来也会炸一大团** —— 用户 2026-10-17 明确不要。落地瞬时 vy 是 −0.38 / −0.52 / −0.65（1 / 2 / 3 格），是阈值的 4–7 倍；贴地急刹时碰撞把 y 压在 0 附近（vy ≈ 0），照旧每 `BURST_COOLDOWN_TICKS` 炸一团。阈值取 0.08 是为了**兜住双板按 S 的持续刹车**（动量每 tick ×`BRAKE_DECAY` 0.92，高速时每 tick 掉约 8%）—— 持续刹车会每隔几 tick 炸一团，不是只响一次。

**受力侧不对称（按按键，只有本地玩家拿得到输入）**：两组互斥 —— 
- **双板按 A / D ＝「弯外侧」**：`biasSide = sideSign(keys)`（A = +1、D = −1，与侧循环里 `side` 同一套符号；`side = +1` ＝ 沿速度方向的**右向量**那一侧，即身体**右侧**）。所以按 A（左转）雪加在身体**右侧**、按 D 加在**左侧**，那一侧的贴地层与扬起层数量 ×`BIAS_COUNT_MUL`、横向速度 ×`BIAS_OUTWARD_MUL`，该侧扬起层顶点再 +`BIAS_APEX_BONUS`（仍夹 `FLURRY_APEX_CAP`）；弯里侧保持原公式。**符号已经核对**：MC 水平朝向 = `(−sin yaw, cos yaw)`，yaw = 0 朝 +Z（南），面朝南时右手边是 −X（西）—— 而 `(−dirZ, dirX)` 在 dir = (0, +1) 时正好是 (−1, 0)，所以它是**右**向量（旧版把它叫「左向量」，两侧全反了）。
- **单板按 W / S ＝「板端」+「弯外侧」（同一套旋钮）**：W 前倾 → 人物**背后**（`biasEnd = −1`）、S 后倾 → 人物**正前方**（`+1`），W 与 S 同按或都没按 → 0。命中时在「身体位置 + 身体前方 × `biasEnd` × `END_OFFSET` 格」处**额外**加一簇粒子（横向落点：**不转向时** ±`END_LATERAL`，**转向时只朝弯外侧单侧 0.08–0.55 格**；沿身体前后轴抖动 ±`END_SPREAD`），顶点走 `randomApex(...)` 再 +`BIAS_APEX_BONUS`。**前后轴用身体朝向（`SnowboardControl.frontLocked`）、不用行进方向**：单板是横着滑的（A / D 沿身体左右平移），行进方向几乎与身体正面垂直，拿它当轴那簇雪会拍到侧面去。<br>**另外**：按着 W / S **且本 tick 速度方向确实转过了 `TURN_DEGREES_PER_TICK`** 时，受力侧 = 这一 tick 转弯的**外侧**，与双板共用 `BIAS_COUNT_MUL`（数量 ×2）与 `BIAS_APEX_BONUS`（顶点 +0.80 格，仍夹 `FLURRY_APEX_CAP`），横向速度也 ×`BIAS_OUTWARD_MUL` 1.5；**没在转向时两侧完全对称**；板端那一簇照旧保留，但**转向时它也只朝弯外侧单侧喷**（横向 0.08–0.55 格、速度同向，见 `END_LATERAL` 那一行）。**真在转向时弯内侧归零**（`SINGLE_SIDE_SPRAY`，见下）：那侧的贴地层与扬起层数量都置 0，所以单板切弯只有**弯外侧一条雪线**（不再是左右两条）；想改回两侧都喷，把 `SINGLE_SIDE_SPRAY` 置 `false`。**符号**：`dir = atan2(dz, dx)`、侧循环的 +1 是右向量 `(−dirZ, dirX)`；速度方向角 θ 增大 ⇒ 朝右向量偏 ⇒ 右转 ⇒ 弯心在右 ⇒ **外侧是左 = `side −1`**，所以 `delta > 0 → biasSide = −1`、`delta < 0 → +1`（已用 θ = 0°/90°/−45° 各取 ±2.5° 的轨迹三点定曲率圆心数值核对：圆心恒在 `right` 向量的 delta 侧）。
- 其它玩家（`SnowSpray.tick` 里 `player == client.player` 才取 `client.player.input.keyPresses`，否则 null）两个偏向都是 0 ＝ 左右 / 前后**完全对称**，与改前一致。<br>**转向的对称加成 `TURN_EXTRA_PER_SIDE` / `FLURRY_APEX_TURN_BONUS` 只在「没有任何按键偏向」时铺到两侧**（`symmetricTurn = turning && biasSide == 0 && biasEnd == 0`）：双板按着 A / D、单板按着 W / S 且在转向时，这两条只落在受力侧（弯外侧）、弯里侧保持基础值（单板转向时弯里侧被 `SINGLE_SIDE_SPRAY` 归零、连基础值都没有）；**单板压着刃但没在转向时两侧都不加**这一份，这 +4 颗 / +0.35 格改由板端那一簇自己吃。否则按着键时两侧同时 +4 颗 / +0.35 格（双板按住 A/D 落地对齐每 tick 也转过 `TURN_DEGREES_PER_TICK` 1°），看着就是「两边一起变多」、偏向被冲淡。

**横向是抽出来的，不是两条线**，而且**两层各一套参数**（互不共享，改一层不会带走另一层的手感）：

- **贴地层**（`FLECK_*`）：横向落点 `FLECK_LATERAL_MIN`–`FLECK_LATERAL_MAX` 0.20–1.10 格（0.90 格宽、均值 0.65、标准差 0.26），向外速度 0.22–0.58 格/tick 再叠 `FLECK_OUTWARD_JITTER` 的 **`[0, 0.10]` 只朝外**抖动，出生点散布 `FLECK_SPREAD` 0.22（正负各半），纵向初速倍率 `FLECK_UPWARD_MIN`–`FLECK_UPWARD_MAX` 0.30–1.00（乘 `UPWARD`，顶点约 0.06–0.90 格）。**低速时视野里几乎全是这一层**（扬起层低速只有 1 颗、压在 0.2–0.4 格），原来那套 0.10–0.80 窄区间抽出来是一撮挤在板边 —— 这是 2026-10-17「低速时贴地层散开明显一点」改的那四个常量；外抛基准 0.22–0.58 与「抖动只朝外」是后来「板左侧往左、右侧往右」那次改的。受力侧再 ×`BIAS_OUTWARD_MUL` 1.5。
- **扬起层**（`LATERAL_*` / `OUTWARD_*` / `SPREAD`）：横向落点 0.10–0.80 格（0.70 格宽、均值 0.45、标准差 0.20），向外速度 0.10–0.42 再叠 **`[0, 0.06]` 只朝外**的抖动（受力侧再 ×`BIAS_OUTWARD_MUL` 1.5），出生散布 0.16；高速再按速度把横向速度扇开（`fan` = 1 + 1.6 × 归一化速度，顶速约 2.6 倍）。
- **两侧都只朝外**：横向速度 = `side × (基值 + jitter)`，`jitter ≥ 0`，所以左侧的雪一定朝左、右侧一定朝右，落点（`lateral`）与横向速度恒同号；沿行进方向的分量（`ALONG_JITTER` / `BACKWARD`）不变。旧写法 `randomSign × jitter` 会让一部分粒子朝内飞，正好抵消掉一截外抛感。

两层沿滑行方向都有 `ALONG_JITTER` ±0.10 的前后抖动。固定侧向偏移会变成两条平行轨迹，抽样之后才是一片。

**所有粒子出生在脚底那片雪面**上（`surface + 0.05 ± 0.05`；`surface` 由 `snowSurface` 的方块扫描给出 = `方块 y + 该方块形状最高点`，满方块 1.0、1 层雪 0.125）—— **出生点不跟着人身子抬**，高度层次只看**顶点高度**（初速按 `PARTICLE_GRAVITY` / `PARTICLE_DRAG` 离散反解）。所以看着是雪从地面被卷起来、抛上去，而不是从人身上冒出来；**别为了「飘得高」去抬出生点**（用户明确：出发点在地面）。

**触发层（前两层）的离地门槛是「距离 ≤ `AIR_RANGE` 1.5 格」**，由 `snowSurface` 算：从脚底那一列往下扫 `SCAN_DEPTH`（3）格，看每一格**本身**是不是雪 / 冰（`BlockTags.SNOW` / `BlockTags.ICE`）；中心列空手时再扫 `SCAN_RADIUS`（1）格的 3×3 邻列（坡上 / 崖边一跳，雪面常在斜下方一格）；每列的候选高度用 `方块 y + 形状最高点` 算真正的**上表面**，距离 = 脚底 y − 上表面，只有 ≤ 1.5 才认（脚底低于雪面 `EMBED_TOLERANCE` 0.35 格内也算）。**不再看 `onGround`、不再看 `blockPosition()` 落在哪一格、不再用碰撞射线。**

> **为什么不用碰撞射线当门槛**：`minecraft:snow` 的 1–8 层雪，**碰撞形状按层数算** —— 1 层根本没有碰撞盒、2 层才 0.125 格。`level.clip(..., Block.COLLIDER, ...)` 会**穿透雪层直接命到下面的石头**，于是站在一层雪上量出来的 gap 就已经是 1 格，再一跳就超 1.5 格。**为什么不按「往下数几格」判**：那是方块下标，不是距离 —— 1 层雪只有 0.125 格高，算成 1 格就会把「站着」也当成「离地」。**前几版真正漏掉的不是门槛**：`p.above()` + 深度 2 的扫描在平地上能容忍到雪面上方 ≈2.9 格，所以原地一跳本来就该扬雪；漏的是**数量公式没有保底** —— 旧写法 `(速度 − 0.10) × 系数`、下限夹 0，速度 0.10–0.14 时每侧 0 颗，而离地后客户端水平速度按原版空气摩擦每 tick ×0.91 掉（0.20 起跳第 8 tick 就 0.094 < 0.10），整个滞空段正好「一颗粒子也没有」，落地后动量托底把速度补回又「有粒子」。所以现在两层都**保底 1 颗**、`MIN_SPEED` 降到 0.06。
>
> 门槛/速度/生成点到底断在哪一环，用一个**默认关闭**的开关定位：`SnowSpray.DEBUG_LOG = true` 后每 20 个客户端 tick 打一行 `[skis] snowspray feetY=.. surface=.. gap=.. speed=.. turning=.. range=..`（走 `SkisMod.LOGGER`，即 `latest.log`）。`surface=NaN` = 门槛没过；`speed` 太小 = 速度门槛；`gap` 正常却没粒子 = 生成点 / 可见性。

| 常量 | 当前值 | 作用 |
|---|---|---|
| `MIN_SPEED` | 0.06 格/tick | 低于这个速度不持续扬雪（站着不动不该扬雪；0.06 让「慢慢挪一步」也扬 1 颗） |
| `AIR_RANGE` | 1.5 格 | **离地阈值**：脚底到雪面上表面的**距离** ≤ 1.5 格就扬雪 —— 按距离算，不按「往下数几格」算 |
| `SCAN_DEPTH` | 3 格 | 每列往下扫的方块数（1.5 格 + 取整余量：脚底在格子最下面时 1.5 格已落到下两格）。**这是扫描深度，不是门槛** |
| `SCAN_RADIUS` | 1 格 | 中心列空手时改扫 3×3 邻列（坡上 / 崖边一跳雪面在斜下方时兜底） |
| `EMBED_TOLERANCE` | 0.35 格 | 脚底低于雪面这么多（站在雪层里）仍算「在雪面上」 |
| `SPRAY_MIN_PER_SIDE` / `SPRAY_PER_SPEED` / `SPRAY_MAX_PER_SIDE` | 1 / 12.0 / 8 | 贴地雪屑每侧 = `1 + (速度 − MIN_SPEED) × 12`，夹到 `[1, 8]`：0.1 → 1、0.2 → 2、0.3 → 3、0.5 → 6、0.7 → 8、0.85 → 8。**保底 1 颗**：低速也扬，只是贴地 |
| `FLURRY_MIN_PER_SIDE` / `FLURRY_PER_SPEED` / `FLURRY_MAX_PER_SIDE` | 1 / 20.0 / 14 | 扬起雪屑每侧，同一公式（涨得更快）：0.1 → 1、0.2 → 3、0.3 → 5、0.5 → 9、0.7 → 13、0.85 → 14。**要「更多」先加这两个上限** |
| `APEX_SPEED_LO` / `APEX_SPEED_HI` | 0.10 / 0.85 | **顶点随速度**的两个锚点（格/tick）：0.10 = 贴地、0.85 = 顶速；两点之间线性插值 |
| `APEX_LOW_FROM` / `APEX_LOW_TO` | 0.20 / 1.00 | 顶点区间**下沿**：低速 0.20 格 → 顶速 1.00 格（每颗抽在下半区） |
| `APEX_HIGH_FROM` / `APEX_HIGH_TO` | 0.40 / 2.30 | 顶点区间**上沿**：低速 0.40 格（贴地）→ 顶速 2.30 格（头上一截）。区间均值 0.10 → 0.32、0.30 → 0.70、0.50 → 1.08、0.85 → 1.75 格 |
| `FLURRY_HIGH_FRACTION` | 0.65 | 扬起层里走「上半」档的比例（顶点抽区间上半）；其余抽下半：两档混着才有层次 |
| `FLURRY_APEX_TURN_BONUS` / `FLURRY_APEX_CAP` | 0.35 / 2.80 | 转向时顶点再 +0.35 格；**只在没有任何按键偏向时才铺到两侧** —— 双板按 A / D、单板按 W / S 且在转向时只落在受力侧（弯外侧）；单板压着刃但没转向时两侧不给、由板端那一簇自己吃。任何情况下封顶 2.80 格（避免又变成雪柱） |
| `FLURRY_HIGH_LIFETIME` | 34 tick | 高飞档雪屑的最低寿命（只抬不降）。原版寿命 `(int)(4 / (rand*0.9 + 0.1))`＝随机 4–40 tick、均值 ≈10，而顶点 2.3 格要 ≈26 tick —— 不抬这一档，粒子会在半空凭空消失 |
| `PARTICLE_GRAVITY` / `PARTICLE_DRAG` | 0.04 / 0.98 | 反解用的原版粒子物理：每 tick `yd -= 0.04 × gravity`（雪球 `gravity` = 1.0）、位移后再 ×0.98 阻尼 —— 顶点 0.20 / 0.40 / 0.70 / 1.00 / 1.75 / 2.30 格对应初速 ≈ 0.15 / 0.20 / 0.26 / 0.31 / 0.42 / 0.47 格/tick |
| `TURN_EXTRA_PER_SIDE` | 4 | 转向时扬起层每侧再多 4 颗（**加在夹上限之后**，所以转向时每侧可到 18 颗）。**只在 `turning && biasSide == 0 && biasEnd == 0` 时才铺到两侧**：双板按 A / D、单板按 W / S 且在转向时只加受力侧（弯外侧）；单板压着刃但没转向时两侧不加、这 4 颗改由板端簇吃（`END_EXTRA_PER_TICK` 8 → 12）。例（双板按 A，turning）：速度 0.5 基础 9 → 受力侧 9×2+4 = 22、非受力侧 9；速度 0.2 基础 3 → **两侧 3/3，不加也不偏**（0.2 < `TURN_MIN_SPEED` 0.25，见下一行）。单板按 W 且在转向时同样：0.5 → 弯外侧 22 / **弯里侧 0**（`SINGLE_SIDE_SPRAY` 归零）+ 板端那一簇 12 颗；单板按 W 但没转向（速度方向没转）时两侧 9/9，只有板端那一簇吃 4 颗。**速度 < `TURN_MIN_SPEED` 0.25 时这些整段不生效**（`turning` 为假、两个偏向归零）：0.2 时两侧都是 3/3、板端簇不喷 |
| `TURN_DEGREES_PER_TICK` | 1.0 度 | 滑行方向一 tick 转 1.0° 以上就算「在转向」（两板通用，只看速度方向的变化，不读按键）。**阈值必须低于两板最小的每 tick 转角（当前单板 A / D 下限 `TURN_MIN_DEGREES_PER_TICK` 2.0°）** —— 否则单板低速纯 carve 不算「在转向」，弯外侧偏向与板端簇加成全部失效。这也是单板角速度**封底 2.0** 的唯一理由（半径 4 格下 vTravel 低于 ≈0.14 格/tick 时算出来的角速度会掉到 2.0 以下）。现状四档：单板 A / D = `toDegrees(vTravel / 4)` 夹在 2.0–12.0°/tick（满速夹到 12.0°）、双板 A / D = `AlpineSkiControl.TURN_RATE_DEGREES` 4.0°/tick、两板按 W 的最大偏转 4.8°/tick（`VEER_MAX_DEGREES` / `VIEW_TURN_RATE_DEGREES`），都 ≥ 1.0°（判据带 1e-9 容差，等于 1.0 也算），`turning` 生效率 100%；而走位那种 0.5° / 0.8°/tick 的低幅方向修正仍在阈值以下，不会误判成转向。判据带 1e-9 弧度容差（`≥ 阈值 − 1e-9`），避免浮点末位噪声把纯匀速转向的 tick 误判成「没在转向」（否则弯外侧偏向与板端簇的转向加成会闪） |
| `TURN_MIN_SPEED` | 0.25 格/tick | **「算在转向」的最低滑速**（两板共用）：速度低于它就**一颗「因转向而生」的雪都没有** —— `turning = speed >= TURN_MIN_SPEED && |转角| > TURN_DEGREES_PER_TICK`，且 `spray()` 里在算出偏向之后把 `biasSide` / `biasEnd` 置 0，于是弯外侧 ×`BIAS_COUNT_MUL` / +`BIAS_APEX_BONUS` 格 / ×`BIAS_OUTWARD_MUL` 外抛 / 单边喷射（`SINGLE_SIDE_SPRAY`）/ 板端那一簇（`END_EXTRA_PER_TICK`）**全都不出**，两侧雪完全对称；≥ 0.25 一切照旧。**改这个值就是改「低速转向的起点」**：调低 → 更早出弯外侧 / 板端偏向，调高 → 要滑更快才出。取 0.25 ≈ 单板上限 0.55 的 45%、双板上限 0.85 的 29%。不受影响：持续扬雪仍看 `MIN_SPEED` 0.06（0.06–0.25 照样有对称的贴地 + 扬起雪）、急减速 `burst`（`BURST_MIN_SPEED` 0.12）、其它玩家 / 拿不到输入（本来两个偏向就是 0） |
| `SINGLE_SIDE_SPRAY` | `true` | **单板转向时只喷弯外侧**（开关）：为真时若「单板＋按着 W / S＋本 tick 真在转向」（`biasSide` 非零），侧循环里**弯内侧的贴地 `fleckCount` 与扬起 `flurryCount` 都置零** —— 只剩弯外侧一条雪线，不再是左右两条。双板按 A / D 不受影响（弯外侧加强、内侧保留基础值）；没在转向 / 拿不到按键 / 其它玩家不受影响。**板端那一簇也归这个开关管**：单板转向时它的横向落点/速度也只朝弯外侧（见 `END_LATERAL` 那一行）。置 `false` ＝ 一步回到「两侧都喷」的旧行为 |
| `BIAS_COUNT_MUL` | 2.0 | **弯外侧**那一侧的贴地层与扬起层数量 × 这个值（弯里侧原公式）：双板 = `biasSide = sideSign(keys)`（按 A ＝身体右侧 / 按 D ＝左侧）；**单板 = 按着 W / S 且本 tick 在转向时，取这一 tick 转弯的外侧**（`delta > 0` 右转 ⇒ 外侧在左 ＝ `side -1`，`delta < 0` 反之；没转向就 0 ＝ 对称）。**单板的弯里侧不走「原公式」而是直接归零**（`SINGLE_SIDE_SPRAY`）。调「弯外侧比内侧厚多少」：1.0 ＝ 双板退回对称 |
| `BIAS_APEX_BONUS` | 0.80 格 | **受力侧**（双板弯外侧 / 单板弯外侧与板端那一簇）扬起层顶点再 + 这个值，最后仍夹 `FLURRY_APEX_CAP`。调「受力侧的雪抛多高」 |
| `BIAS_OUTWARD_MUL` | 1.5 | **受力侧的横向（向外）速度倍率**：`biasSide` 那一侧（双板弯外侧 / 单板本 tick 转弯外侧）的**贴地层与扬起层**横向速度再 ×1.5 —— 与 `BIAS_COUNT_MUL`（数量）、`BIAS_APEX_BONUS`（顶点）成套。只乘横向分量，沿行进方向的分量不动；两侧抖动都只朝外，所以不会互相抵消 |
| `END_OFFSET` / `END_SPREAD` / `END_LATERAL` | 0.85 / 0.40 / 0.55 格 | **单板板端那一簇**的出生点：沿**身体朝向**偏移 `biasEnd × END_OFFSET`（W → 人物背后、S → 人物正前方；不用行进方向 —— 单板横着滑，行进方向几乎与身体正面垂直），落点沿身体前后轴 ±`END_SPREAD` 摊开、沿身体左右轴摊开成一小片（不排成一条线）：**不转向**时左右各 ±`END_LATERAL` 0.55（对称、围绕身体中线）；**单板转向时**（`SINGLE_SIDE_SPRAY` 且 `biasSide != 0`）只朝**弯外侧单侧** `biasSide × [0.15, 1.0] × END_LATERAL` ＝ 0.08–0.55 格，横向速度同向（`biasSide × (基值 + [0, OUTWARD_JITTER])`，不带随机符号）—— 起点仍是板那一端，但不再向两边开花 |
| `END_EXTRA_PER_TICK` | 8 | **单板按 W / S 时每 tick 在板的那一端额外加的粒子数**（扬起级，不占 `SPRAY_MAX_PER_SIDE` / `FLURRY_MAX_PER_SIDE` 配额）。**转向时这一簇自己再吃 `TURN_EXTRA_PER_SIDE`（8 → 12）**；单板没转向时两侧的对称转向加成被收走（改由这一簇吃），在转向时则只落在弯外侧。调「板端扬雪有多猛」 |
| `LATERAL_MIN` / `LATERAL_MAX` | 0.10 / 0.80 格 | **扬起层的横向落点区间**：每颗自己抽（均匀分布，0.70 格宽、均值 0.45、标准差 0.20），乘左右符号 —— 不再是固定一点，所以不会排成两条线。**贴地层不用它**（见下面 `FLECK_LATERAL_*`） |
| `OUTWARD_MIN` / `OUTWARD_MAX` / `OUTWARD_JITTER` | 0.10 / 0.42 / 0.06 | **扬起层**的横向速度区间（格/tick）+ **只朝外**的抖动 `[0, 0.06]`（`out = side × (基值 + jitter)`，不再有朝内那一半）。受力侧再 ×`BIAS_OUTWARD_MUL`。贴地层用 `FLECK_OUTWARD_*` |
| `FLECK_LATERAL_MIN` / `FLECK_LATERAL_MAX` | 0.20 / 1.10 格 | **贴地层的横向落点区间**（只给 flecks，与扬起层那套无关）：0.90 格宽、均值 0.65、标准差 0.26 —— 比原来的 0.10–0.80 整体外移并加宽，低速（0.1–0.3）就看得出雪屑横向铺开 |
| `FLECK_OUTWARD_MIN` / `FLECK_OUTWARD_MAX` / `FLECK_OUTWARD_JITTER` | 0.22 / 0.58 / 0.10 | 贴地层的向外速度区间（格/tick）+ **只朝外**的抖动 `[0, 0.10]`（比扬起层大，雪屑才真往外跑而不是贴板边）；受力侧再 ×`BIAS_OUTWARD_MUL`。旧值是 0.12 / 0.45 + 正负 ±0.10 |
| `FLECK_SPREAD` / `FLECK_UPWARD_MIN` / `FLECK_UPWARD_MAX` | 0.22 / 0.30 / 1.00 | 贴地层出生点水平散布（`±FLECK_SPREAD/2`＝±0.11 格，原来 `SPREAD` 是 ±0.08）/ 纵向初速倍率区间（乘 `UPWARD` 0.30，vy 0.09–0.30 → 顶点约 0.06–0.90 格，仍在贴地范围） |
| `GROUND_TOUCH_TOLERANCE` | 0.15 格 | 急刹爆发的**贴地闸门**：脚底到雪面上表面 ≤ 0.15 格才算贴地，且**上一 tick 也必须贴地**（`LAST_GROUNDED` 静态表，脱板时清）。落地那一 tick 水平速度会突降，靠这条挡掉「从一两格高跳下来炸一团」 |
| `FALL_TOLERANCE` | 0.10 格/tick | 急刹爆发的**垂直速度闸门**：`|vy| < 0.10` 才算在地面滑。自由落体落地瞬时 vy 是 −0.38 / −0.52 / −0.65（1 / 2 / 3 格，重力 0.08 + 阻尼 0.98 的离散值），全被拒；贴地滑行/急刹时 vy ≈ 0，照常爆发 |
| `ALONG_JITTER` | 0.10 | 沿滑行方向的前后抖动（格/tick）：雪屑不排在同一条法线上 |
| `UPWARD` / `BACKWARD` | 0.30 / 0.16 | 贴地雪屑的向上 / 向后（与滑行相反）分量：`UPWARD × FLECK_UPWARD_MIN–FLECK_UPWARD_MAX`（0.30–1.00）→ vy 0.09–0.30、顶点约 0.06–0.90 格，仍然贴地 |
| `SPREAD` / `Y_OFFSET` / `Y_JITTER` | 0.16 / 0.05 / 0.05 格 | **扬起层**出生点的水平随机散布（贴地层用 `FLECK_SPREAD` 0.22）/ 离地高度 / 高度抖动（`surface + Y_OFFSET ± Y_JITTER`） |
| `BURST_DROP` / `BURST_MIN_SPEED` | 0.08 / 0.12 格/tick | 急减速的判定：一 tick 掉超过 `BURST_DROP`、且掉之前快过 `BURST_MIN_SPEED` 才炸 |
| `BURST_PER_DROP` / `BURST_MAX` | 450.0 / 160 | 一团雪屑的粒子数 = 掉落量 × 系数，夹到上限（掉 0.08 → 36 颗，掉 0.15 → 67 颗，0.36 以上 → 满 160 颗）—— **急刹车不够狠就加这两个** |
| `BURST_COOLDOWN_TICKS` | 3 | 两团之间的最短间隔（持续刹车时 = 每隔 3 tick 炸一团） |
| `BURST_UPWARD` / `BURST_RADIUS` | 0.5 / 0.75 格 | 炸开时的上升速度（≈2 格高，之前被 provider 吞掉、现在才真生效）/ 随机散布半径 |

### 落到雪面免摔伤 `SnowLanding.java`

穿着任一种板**落地前 60 tick 内踩过雪面，或落点本身就是雪 / 冰面，就不掉血**：走 Fabric 的 `ServerLivingEntityEvents.ALLOW_DAMAGE` 事件（在 `hurtServer` 上），公共前提三条 —— ① 是玩家 ② 脚上是我们的板 ③ 伤害类型属于 `DamageTypeTags.IS_FALL`；满足后两条落点判定**取或**：

- **落点本身是雪 / 冰**：用 `SkiGlide.isSnowLanding`，**判定比「能不能滑」宽** —— 脚底格、上一格、下两格任一是雪/冰就免，石头上铺一层雪（0.125 格）也算雪面（落地那一 tick 脚就踩在雪上，本身也属于「踩在雪地上」；也是从没被 tick 记到的玩家的兜底）。
- **落地前 `SNOW_GRACE_TICKS` 60 tick（3 秒）内踩过雪地**：每服务器 tick（`END_SERVER_TICK`）遍历在线玩家，**穿板 + `onGround()` + 脚下是雪/冰** 才把 `level().getGameTime()` 记进 `LAST_SNOW_TICK`（玩家 id → tick）；比对时算 `0 ≤ now - last ≤ 60`。所以「雪坡冲下来、腾空两秒、砸在石头上」免伤，「在石头地上站够 3 秒再摔」照旧掉血。脱板 / 断线 / **死亡重生** `remove`（重生点与死亡前无关，不能拿死前 3 秒的雪面记录免掉重生后的摔伤），超 `MAX_ENTRIES` 整表清空；退出 / 换维度残留的旧记录靠时间戳自然过期，不会错免。

非雪面且 60 tick 内没踩过雪照旧摔伤，别的伤害类型一律放行。命中时顺手 `resetFallDistance()`。

> **摔倒扣血不受这条影响**：`SkiAir.applyLanding` 用的是 `damageSources().generic()`（不在 `IS_FALL` tag 里），
> 所以空翻落地摔了以后，**雪地也照扣**（`CRASH_DAMAGE_MAX` 4.0，按偏差比例）。这条免伤只挡**坠落高度**造成的摔伤 ——
> 板当缓冲，从坡上飞出去砸在雪里不掉血；但自己空翻没落正，是要付账的。

| 常量 | 值 | 作用 |
|---|---|---|
| `SNOW_GRACE_TICKS` | 60 tick | 落地时距上次「穿板站上雪/冰面」多少 tick 内仍免摔落伤害（60 tick = 3 秒）；调大＝雪地缓冲更宽容，调小＝更要求贴着雪落地 |

### 滑行音效 `GlideSounds.java`（纯客户端）

雪面上滑行时只有**切雪声**：按速度调的间隔播原版雪地脚步 `SNOW_STEP`，音量与音高也随速度抬。触发条件与雪粒子同一条（穿着板 + 雪 / 冰面 + 有速度），但**声音这边的离地门槛是 `GlideSounds.GROUND_RANGE` 1.0 格**（雪粒子那边是 `SnowSpray.AIR_RANGE` 1.5 格的**方块扫描 + 形状高度算距离**，不看 `blockPosition()` 落在哪一格、也不看碰撞射线；声音仍看 `blockPosition()` 的射线离地距离）；两者各一个常量、没有联动 —— 所以跳起来平飞时，雪还在扬、声音可能已经停了）。原版踏雪脚步/落地声仍是静音的（那几条 mixin 只管原版走路与摔落，不管这里直接播的音效）。

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
| `BOARD_LEG_SPREAD` | `SkiStance` | 2.8 | 单板：两腿（以及鞋）在**模型坐标**里的横向偏移 —— 平行并列、向外岔开；腿与鞋必须同值（4.0 被用户嫌“间隔太宽”）。写成常量表达式 `BASE_LEG_X` 1.9 + `LEG_SPREAD` 0.9，动腿距时单板自动跟 |
| `SQUAT_LEG_TILT` | `SkiStance` | -0.20 | **穿板站姿的“角度微蹲”①：大腿绕靴口翻的角度**（弧度，负值＝膝往前顶）。**枢轴在靴口（模型 y=18）**：可见的腿只到上半段（腿局部 y=0…6 ＝ 模型 y=12…18，由 `PlayerModelThighMixin` 加的大腿子部件画；原版整条腿用 `skipDraw` 藏掉），而**雪鞋就是竖直的下半段**（`AlpineSkiModel` / `SnowboardModel` 的 boot 是 `addBox(-2,18,-2,4,6,4)`，与腿下半段同形同位），所以只有膝被顶出去，脚底 / 板面 / 鞋都不动。两块板共用这一个值（旧的双板 `SKI_LEG_TILT` / 单板 `BOARD_LEG_TILT` 两个 -0.15 的同值常量已删除，同一个旋钮不再记两处）。**不缩腿、不挪 y**：`yScale` 恒 1.0、`leg.y` 恒 12、`leg.xRot` 归零（「髋 → 靴口」保持竖直） |
| `SQUAT_BODY_LEAN` | `SkiStance` | 0.12 | **角度微蹲②：躯干前倾角**（弧度，正值＝向前压）。与 `SQUAT_LEG_TILT` 配对才有“屈身压刃”的样子（只做腿后倾会读成直挺挺往后倒）。原来 `HumanoidModelMixin` 里的 `GLIDE_LEAN` 0.05 已删除、统一用这一个 |
| `SQUAT_HEAD_PITCH` | `SkiStance` | 0.10 | **角度微蹲③：头随上身略前低**（弧度，0.10 rad ≈ 5.7°）。只在站姿生效的分支、且**非蹲行**时写 `head.xRot`；蹲行整段跳过（保持原版弯腰与头角，不干扰原版渲染） |
| `TILT_LERP` | `SnowboardControl` | 0.25 | 俯仰姿态每 tick 向目标靠拢的比例（≈10 tick 到位）—— 倾斜是渐进的 |
| `TILT_DEGREES` | `SnowboardControl` | 15.0 度 | 单板 W / S 时**整个人 + 板**的倾斜角（`EntityTiltMixin` 转模型 poseStack；`CameraTiltMixin` 用同一角度把相机位置沿旋转后的眼高向量前移，视线不跟着转） |
| `SNOWBOARD_HEAD_NEUTRAL_DEGREES` / `SNOWBOARD_HEAD_FORWARD_DEGREES` / `SNOWBOARD_HEAD_BACKWARD_DEGREES` | `SnowboardControl` | 75.0 / 90.0 / 60.0 度 | **越肩视角模式下单板的头**相对身体朝向的**偏角幅度**（恒正，符号看横移方向）：默认 75°（没按 W / S 或两者同按）、按 W 前倾 90°、按 S 后倾 60°。方向：**+ 朝身体右侧（yaw 增大，与 D 同向）、− 朝左侧（yaw 减小）**，由 `headSide` 判定（实际横移方向 → A / D → 锁存）。双板在该模式下是 0°（正前方，不设常量）。只在越肩开启时生效 |
| `HEAD_OFFSET_LERP` / `HEAD_OFFSET_MAX_STEP_DEGREES` / `HEAD_SIDE_MIN_SPEED` | `SnowboardControl` | 0.45 / 9.0 / 0.05 | 头偏角的**平滑**参数（`tickHead`）：每 tick 吃掉与目标差距的 45%，且单步不超过 9°/tick（**限幅说的是度数/tick**，不是速度）→ 左右翻转 150° 约 25 tick（≈1.25 s，中途经过 0° 正前方）、幅度变化 15° 约 10 tick（≈0.5 s）。`HEAD_SIDE_MIN_SPEED` = 判定「正在横移」的最小侧向速度（格/tick，< 0.05 改看 A / D）；**同一个判定也被速度回正用**（`boardSide`，`headSide` 只是它反号），头朝哪边与速度往哪边拉永远一致 |
| `VIEW_LIMIT_DEGREES` | `SnowboardControl` | 135.0 度 | **单板**：视角相对锁定身体朝向的最大偏转，左右各一份（合计 270°；留 90° 背后视线禁区）。限位状态只有 `client/SkiViewSmoothing.headOffset` 一处 |
| `VIEW_LIMIT_DEGREES` | `AlpineSkiControl` | 90.0 度 | **双板**：同一项，**各自一份常量**（改这里不动单板）。左右各 90°、合计 180° —— 刚好看到正侧方。限的是视角、不是 A/D 的 4.0°/tick 转向；按 W 时身体会朝视角追上 4.8°/tick（`VIEW_TURN_RATE_DEGREES`） |
| `TURN_RADIUS_BLOCKS` / `TURN_MIN_DEGREES_PER_TICK` / `TURN_MAX_DEGREES_PER_TICK` | `SnowboardControl` | 4.0 格 / 2.0 度/tick / 12.0 度/tick | **单板 carve 的固定转弯半径**：每 tick 身体转角 ＝ `clamp(toDegrees(vTravel / 4.0), 2.0, 12.0)` —— 满速 0.55 格/tick 时算出 **14.43°/tick、被夹到 12.0°/tick ≈ 240°/s**（半圈 15 tick ≈ 0.75 s；中速 0.30 时 7.87°/tick），半径恒定、慢滑自然更慢。原来的 `TURN_RATE_DEGREES` 1.2 已删（改为随速度）。**这三个值都按「每端各跑一次」×2 标定**（8.0→4.0、1.0→2.0、6.0→12.0，见「状态表按端拆分」）。**下限 2.0 不能删**：snow spray 判「在转向」的门槛是 `TURN_DEGREES_PER_TICK` 1.0°/tick，封底低于它弯外侧就不喷雪。上限 12.0 在满速附近封顶（算出 14.43°）。速度方向由 `alignCarveHeading` 每 tick 朝**板轴**限幅 12.5° 拉回（近地 1.5 格内就生效、撞墙也照拉），改这里模型与速度一起变 |
| `ALIGN_MAX_DEGREES_PER_TICK` / `ALIGN_DEAD_ZONE_DEGREES` / `ALIGN_GROUND_RANGE` | `SnowboardControl` | 12.5 度/tick / 0.05 度 / 1.5 格 | **速度方向自动回正到板轴**（`alignCarveHeading`）：每 tick 把水平速度方向朝**板轴**（身体左右轴 = 单板实际行进方向）**限幅旋转** ≤12.5°，不是一步写成目标角；与板轴角差 ≤ 0.05° 走死区（不写、不发包）；**只在近地时生效** —— `onGround()` 或脚底往下 1.5 格内有碰撞方块（`SkiGlide.groundDistance`），真腾空不掰。**生效条件**：穿单板 + 不在失控（`SkiAir.isStunned`）+ 水平速度 > 1e-4 + 近地。**撞墙 tick 也照拉**（不再跳过）。12.5 **必须严格大于每 tick 最大身体转角**（`TURN_MAX_DEGREES_PER_TICK` 12.0°，余量 0.5°），否则速度方向永久落后于身体；拆表前这条在专用服务器上**刚好取等**（6.0 vs 6.0）、单机里**根本没成立**（6.0 vs 12.0），本批一并修掉。180° 偏角最多 15 tick（0.75 s）掰正。往哪一侧由 `boardSide` 定（与头朝向 `headSide` 同一判定），不再有入弯基准表。**服务端与客户端各跑一遍**（和 `applyCarve` 同一套做法；客户端只对**本机玩家**跑），因为玩家位置是**客户端权威**，只靠服务端发包（`hurtMarked` → `ClientboundSetEntityMotionPacket`，仅误差 > 死区才发）在无误差时不会触发 —— 客户端撞墙把速度方向裁歪后服务端算不出误差、不发包，歪方向只能客户端自己掰回来 |
| `CARVE_MIN_SPEED` | `SnowboardControl` | 0.05 格/tick | 没按 A/D 时，光靠前/后倾触发 carve 所需的最低滑速 |
| `ROW_CENTER` / `ROW_AMPLITUDE` | `HumanoidModelMixin` | -0.35 / 0.95 | 手臂划动中心角与幅度（最高处 = 中心 − 幅度） |
| `DOWN_FRACTION` | `HumanoidModelMixin` | 0.6 | 一次摆臂里"下摆"占的比例（其余是收回） |
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
| `TURN_RATE_DEGREES` | `AlpineSkiControl` | 4.0 度/tick | **双板 A / D 转身体**每 tick 多少（≈80°/s；180° 需 45 tick ＝ 2.25 s）—— **觉得转太慢/太快先改它**（8.0 → 4.5 → 3.5 → 2.0 → **4.0**；4.0 是按「每端各跑一次」重新标定的值：拆表前单机里两端各减 2.0 ＝ 每 tick 4.0，见「状态表按端拆分」）。**速度方向由 `alignTurnHeading` 从身体推导，改它模型与速度一起变**；A 让身体 yaw 减小（左）、D 让 yaw 增大（右）。**只转身体**；视角另有 ±90° 限位（见 `VIEW_LIMIT_DEGREES`，不是跟着 A/D 转）；按 tick 写，服务器与客户端各跑一步，不做按帧均摊 |
| `VIEW_TURN_RATE_DEGREES` | `AlpineSkiControl` | 4.8 度/tick | **双板按 W 时身体朝视角方向转**每 tick 多少（≈96°/s；残余 90° 约 19 tick ≈ 0.95 s 对齐；转满 180° 需 37.5 tick ≈ 1.9 s）。步长限幅、不越过目标；残余 < `VIEW_TURN_MIN_DEGREES` 0.5° 就停（防抖）。只在 **W** 时触发，S 不触发；与 A / D 同向时叠加（4.0+4.8=8.8°/tick）。2.4 → **4.8** 同 `TURN_RATE_DEGREES` 的理由（每端各跑一次；饱和步长 ×2 与两次复合逐 tick 等价）。**转的仍是身体**，视角在世界坐标里不动 |
| `BODY_YAW` | `AlpineSkiControl` | 实体 id → 身体朝向（度） | **双板的身体朝向锁表**：穿上时 `ensureLocked` 记下当时的 yaw，之后只由 `turnBody`（A / D）改；脱板 `forgetBody` 清掉。视角（`yRot`）与它彻底分开 |
| `BRAKE_DECAY` | `AlpineSkiControl` | 0.92 | 双板按住 S 刹车时每 tick 保留的比例（1 秒剩 ≈19%；0.90 更利、0.96 更轻） |
| `BRAKE_MIN_ALIGN` | `AlpineSkiControl` | 0.3 | 速度方向与**身体前方**点积超过它才算「还在往前滑」（此时按 S 是刹车）；低于它按 S 就是往后走 |
| `ALIGN_DEGREES_PER_TICK` | `AlpineSkiControl` | 9.0 度/tick | **双板落地后速度方向快速朝身体前方对齐**（修「A/D 转完身落地方向不跟」）：`shouldAlignOnGround`（① `onGround()` ② 没按 S —— input 为 null 放行 ③ 水平速度平方 > 1.0E-4）通过才 `alignVelocity`，把速度**方向**朝身体前方转 min(残差, 9.0) 度（`Mth.wrapDegrees` 算残差，**只改方向、大小不变**，写回 `setDeltaMovement` + `hurtMarked`）。9.0°/tick ≈ 180°/s，落地后一两 tick 就掰到朝向上；对齐后重读 `motion / speed` 再往下走。只在双板分支、按 S 那一 tick 不对齐 |
| `TILT_EPSILON` | `SnowboardControl` | 0.01 | 俯仰角与目标差小于它就视为到位，停止补间 |
| `MAX_ENTRIES` | `SkiBoost` / `SnowLanding` | 64 | 这三张表的条数上限（冲刺 `ACTIVE` **与 `PENDING`** / 落地免伤的「上次踩雪」），超了**整表清空**，防地图残留状态泄漏（两张小表都只在「玩家在线」时维护，另有断线清理，清空是泄漏兜底而非常态） |
| `DEFAULT_CAPACITY` | `IdMap` | 64 | **其余所有「实体 id → 值」表**（动量、锁定朝向、雪粒子、切雪声、落地结算时间戳……）的容量：写入时超限**按插入序淘汰最旧的一条**（见 `util/IdMap`；两块板的 4 + 2 + 4 + 7 张都走它）。不是整表清空 —— 那会让所有动量归零、`BODY_YAW` 被重锁、carve 失效 |
| `COOLDOWN_TICKS` | `item/SkiPoleItem` | 10（0.5 s） | 两次撑杖之间的冷却；冷却中右键直接 `FAIL`（所以连撑最快 0.5 s 一次） |
| `BOARD_TOP` | `BoardModel`（两块板共用） | 23.0 | 板身与翘头在模型空间里的 y 值（板顶面高度）；abstract `client/BoardModel` 持有，`AlpineSkiModel` / `SnowboardModel` 继承使用 |

## 双板 vs 单板（现行为）

| | 双板 | 单板 |
|---|---|---|
| 朝向 | **锁身体、不锁视角**：身体朝向锁在穿上板那一刻（`AlpineSkiControl.BODY_YAW`），由 A / D 改（4.0°/tick）、**按 W 时还会朝视角追 4.8°/tick**；**视角由鼠标转、并限在身体左右各 ±90°**（与单板同一套实现、各自一个值，用户 2026-10-17 定 — 之前拿视角当朝向是错的） | 锁定（穿上板那一刻），视角同样限在 ±135° |
| 转向 | **A / D 只转身体**（`AlpineSkiControl.turnBody`，`TURN_RATE_DEGREES` 4.0°/tick ≈ 80°/s，180° 需 45 tick ＝ 2.25 s；yaw 减小＝左、增大＝右）；**按 W 时身体再朝视角方向补 `VIEW_TURN_RATE_DEGREES` 4.8°/tick**（`turnTowardView`，限幅不越过，残余 90° 约 19 tick ≈ 0.95 s 追上；S 不触发）。两种转法都**不碰视角**（只有顶到 ±90° 限位时视角才被身体拖着走）；客户端与服务端每 tick 各跑一步（先 A/D 再 W），客户端写 `yBodyRot / yBodyRotO`。速度方向由服务端 `GlideSteering` 按**身体前方**摆过去：**地上跟着转、空中不跟**（空中身体随便转，速度方向一点都不动）；**落地后速度方向按 `ALIGN_DEGREES_PER_TICK` 9°/tick 快速朝身体前方对齐**（`AlpineSkiControl.shouldAlignOnGround` + `alignVelocity`，**按 S 时不对齐**）—— 转完身落地方向立刻跟上，不靠 `GlideSteering.steer` 慢慢摆；**真正把两条路钉在一起的是 `AlpineSkiControl.alignTurnHeading`**（`SkiGlide` 里双板分支、整 tick 最后一个写速度的点，**只在贴地 `onGround()` 时生效**）：只要这一 tick `turnBody` / `turnTowardView` 真的转过身体，就把水平速度方向直接写成「入弯方向角（`TURN_HEADING`，`atan2(-x, z)` 口径，与 `alignVelocity` 同一套）+ 身体累计转角（`bodyYaw 现在 − TURN_YAW0`）」—— **身体转多少速度就转多少**，只剩入弯那一刻固有的「身体−速度」偏角；这一 tick 没转身体就清表（`forgetBody` / 脱板路径一起清）。**空中直接跳闸**（身体 / 视角照旧转，速度方向由惯性保持，两份入弯记录一起清）；按 S 时 `alignVelocity` 不跑、这条照样锁 | 压刃（W/S）＋侧压（A/D），转角由固定半径 4 格算出（满速算出 14.43°/tick、夹到 `12.0`°/tick，下限 2.0°/tick）；速度方向每 tick 朝板轴自动回正 ≤12.5°（近地 1.5 格内就生效，撞墙也照拉） |
| 前进 | **W 前进**；**按住 S 且还在往前滑 = 刹车**（`BRAKE_DECAY` 0.92，只减速、不拨方向）；**静止 / 后退时才往后走**（速度与**身体前方**点积 ≤ `BRAKE_MIN_ALIGN` 0.3）。**W / S 一律沿身体前后**（`ClientInputMixin` 把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系），所以视角转到哪都不影响溜的方向 | W/S 只做前倾 / 后倾（**不加推力**） |
| 左右 | **A 左 / D 右 只转身体**（`AlpineSkiControl.turnBody`，`TURN_RATE_DEGREES` 4.0°/tick ≈ 80°/s），**视角不跟着动**（顶到 ±90° 限位才被拖）；横向输入被清掉（不横移），滑行方向在地上跟着身体转（落地后另有 `ALIGN_DEGREES_PER_TICK` 9°/tick 快速对齐）、空中不跟 | A / D 沿身体左右**平移** |
| 站姿 | 大腿绕靴口翻 `SQUAT_LEG_TILT` -0.20 ＋ 躯干前压 `SQUAT_BODY_LEAN` 0.12 ＋ 头略低 `SQUAT_HEAD_PITCH` 0.10（三个都是**角度**，`SkiStance` 里两板共用；**枢轴在靴口（模型 y=18）、可见腿只到上半段、雪鞋＝竖直下半段**，所以脚底 / 板面 / 鞋不动；**不缩腿**、`yScale` 恒 1.0、`leg.y` 恒 12）；蹲行时躯干 / 头保持原版 | 与双板**完全同一套角度**（同一个 `SQUAT_LEG_TILT` / `SQUAT_BODY_LEAN` / `SQUAT_HEAD_PITCH`，差别只在外观与脚位） |
| 视角限位 | **±`VIEW_LIMIT_DEGREES` 90°**（`AlpineSkiControl`）：鼠标能转，但相对锁定的身体朝向最多左右各 90°（合计 180°，刚好到正侧方）；没顶到限位时视角**留在世界坐标里不动**（身体在下面转），顶到之后头被身体拖着走。**按 W 时身体朝视角方向追 4.8°/tick**（`VIEW_TURN_RATE_DEGREES`）—— 追的是身体，视角在世界坐标里仍然不动 | **±`VIEW_LIMIT_DEGREES` 135°**（`SnowboardControl`）：同一套实现（`client/SkiViewSmoothing` 的唯一 `headOffset`，鼠标只记账、相机每帧写一次）、**各自一个常量** |
| 音效 / 粒子 | 同一套：滑行雪粒子 + 随速度变密的切雪声（暂停即停，无风声） | 同左 |
| 摩擦（动量每 tick 保留） | `AlpineSkiControl.MOMENTUM_DECAY` **0.998** | `SnowboardControl.MOMENTUM_DECAY` **0.998**（直行）/ **1.0**（carve 转向期间 ＝ 零损失，转向只改方向不吃摩擦）—— 直行 0.998 两板同值，用户 2026-10-13 定；单板 carve 另有速度下限 `guardCarveSpeed`（下限恒等于入场速度，转向期间实际不衰减） |
| 重力 | **贴地**（`onGround`）`GRAVITY_MULTIPLIER` **1.5**（`Attributes.GRAVITY` 0.08 → 0.12 格/tick²）；**腾空**（跳跃 / 坠落）`AIR_GRAVITY_MULTIPLIER` **0.9**（→ 0.072 格/tick²）；脱板还原 | 贴地 **1.3**（0.104 格/tick²）、腾空 **0.9**（0.072）；各自一份常量，改一个不动另一个。两板满速跳高都卡在 `JUMP_MAX_HEIGHT` 1.50 格（**按腾空 0.072 反解的表**，贴地的 1.5 / 1.3 只在起跳首 tick 参与） |
| 跳跃 / 空翻 | 起跳力 `JUMP_POWER_BY_SPEED_LUT` 11 点表（0.226 → 0.483，满速跳高 1.50 格）；门槛 `FLIP_MIN_SPEED` **0.15**；`holdTicks` **只在松手归零**（落地 / 起跳都不清），空中**每 tick 实时判** `holdTicks > FLIP_HOLD_DELAY_TICKS`(4) **或本段已开翻 `flipStarted`** 才转，转速 **16.0°/tick ＝ 22.5 tick 一圈 1.125 s**，**从地面起跳那一下**的前 4 tick 角度恒 0（静止段）、**空中已开翻后松手再按立刻续翻**、空翻中不补跳；**跳过 / 翻过之后落地禁跳 10 tick**（`JUMP_COOLDOWN_TICKS` 在落地沿重新起算 —— 只在这一趟腾空里起过跳 `jumpedThisAir` 或开过翻 `flipStarted` 时才禁，按住也不行；**纯滑落 / 走下台阶 / 掉到地上的落地不罚**；**落地沿只认真落地** —— 连续腾空 ≥ `LANDING_LOCK_MIN_AIR_TICKS` 3 tick，坡面 1–2 tick 的贴面抖动不刷冷却）；落地与直立差 ≤ ±35° 才是净落，否则水平速度 ×0.35 + 向上速度归零 + 20 tick 失控（失控期间禁止起跳） | `JUMP_POWER_BY_SPEED_LUT`（0.208 → 0.470，满速跳高 1.50 格）；门槛 **0.12**；同一套计数口径（只在松手归零、**只有地面起跳那一下**有前 4 tick 静止段、空中续翻不等、16.0°/tick ＝ 1.125 s 一圈，两板同值）；**跳过 / 翻过之后落地禁跳 10 tick**同双板（含「有跳或有翻才禁、纯滑落不罚」与「只认真落地、贴面抖动不刷冷却」两条门槛）；±35° 净落容差、×0.35 + 向上速度归零 + 20 tick 失控（失控期间禁止起跳）与双板同值，但各自一份常量 |
