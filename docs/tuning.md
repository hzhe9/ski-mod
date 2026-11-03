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

### 滑行物理 `SkiGlide.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEP_BLOCKS` | 0.5 | 每累计下降这么多格结算一次加速（**双板**；单板另有自己的 0.5 格步长，见下一行）。2026-10-17 起两板同粒度：双板单次 = 0.1 × 0.5 = **0.05**，每格总量 0.1 |
| `DESCENT_STEP_BLOCKS`（单板） | 0.5 | **单板的结算步长**（`SnowboardControl`）：每降 0.5 格结算一次，单次 = 0.15 × 0.5 = 0.075 —— 每格总量仍 0.15/格，下坡降没半格就有第一份（不用攒满一整格） |
| `FALL_GAIN_PER_BLOCK`（双板） | 0.1 | **双板**下落基数（`SkisControl`；0.12 → 0.08 → **0.1**（2026-10-17 用户回调））。每格 ≈ 7.2 km/h，每 0.5 格单次 0.05，从 0 到 0.85 极速需下落 ≈ 8.5 格 |
| `FALL_GAIN_PER_BLOCK`（单板） | 0.15 | **单板**同项（`SnowboardControl`；只取左右分量、体感弱，所以基数比双板大；0.11 → 0.13 → 0.15，用户 2026-10-13 两次「加 0.02」） |
| `FALL_GAIN_PER_TICK_MAX` | 两板均 0.3 | **单 tick 下落加速上限**：一次结算最多加这么多（防止从高处一步把速度给爆） |
| `FALL_GAIN_EXPONENT` | 1.0 | 两板共用的曲线指数。**当前 1.0 = 线性**（用户要求先去掉衰减试手感）；0.8 = 很缓的边际递减、0.5 = 开方 |
| `GRAVITY_MULTIPLIER`（双板） | 1.5 | **双板：贴地（`onGround`）时的重力倍数**（`SkisControl`）：原版 `Attributes.GRAVITY` 0.08 → **0.12** 格/tick²，**只在贴地滑行 / 落地压雪时生效**，腾空立刻换 `AIR_GRAVITY_MULTIPLIER`；脱板还原。终端速度 3.92 → **5.88** 格/tick，1 秒掉 13.3 → **19.9** 格，落 10 格 15 tick（0.75 s）。1.0 = 原版，越大贴地越沉 |
| `GRAVITY_MULTIPLIER`（单板） | 1.3 | **单板：贴地时的重力倍数**（`SnowboardControl`）：0.08 → **0.104** 格/tick²，终端 **5.10**，1 秒掉 **17.2** 格。两个类各一个常量，改一个不动另一个 |
| `AIR_GRAVITY_MULTIPLIER`（双板） | 0.8 | **双板：腾空（跳跃上升段 / 坠落）时的重力倍数**：0.08 → **0.064** 格/tick² —— 比原版还轻，空中飘一点、滞空更长。终端速度 3.92 → **3.14** 格/tick。**满速跳高 2.00 格这张查找表就是按 0.064 反解的**（见「跳跃与空翻」）；贴地的 1.5 不参与跳高 |
| `AIR_GRAVITY_MULTIPLIER`（单板） | 0.8 | **单板**同项：0.08 → **0.064** 格/tick²（终端 **3.14**）。与双板同值、各一个常量；满速跳高 2.00 格（按 0.064 反解） |
| `STEP_UP_PENALTY` | 0.04 格/tick | 滑上 1 格台阶扣掉的水平速度（动量一起扣，否则下一步被托回来） |
| `STEP_UP_MIN_HEIGHT` | 0.5 格 | 判定“上了一格台阶”的最小抬升高度 |
| `VEER_SMOOTHING` / `VEER_MAX_DEGREES` | 0.35 / 2.4° | 单板只用 W/S 时的平滑趋近系数与单 tick 上限（替代原“固定 2°/tick + 到目标死停”）。4° → **2.4°**：2.4°/tick ＝ 48°/s，转满 180° 需 75 tick ＝ 3.75 s |
| `MAX_SPEED`（单板） | 0.55 格/tick | 单板速度上限 ≈ 11 m/s ≈ 40 km/h（参照：原版步行 0.216、疾跑 0.281、创造飞行 0.545 格/tick） |
| `MAX_SPEED`（双板） | 0.85 格/tick | 双板速度上限 ≈ 61 km/h（用户 2026-10-17 定：1.0 → **0.85**；撑杖冲刺也封顶在这里） |
| `MOMENTUM_DECAY` | 0.998 | 滑行动量每 tick 保留比例 ＝ 有效摩擦力（**双板**；历史 0.985 → 0.993 → 0.99 → 用户 2026-10-13 定 0.998。0.998 = 每 tick 掉 0.2%，1 秒后剩 96%，半衰期 17.3 s） |
| `ADD` / `MIN_PEAK` | 0.06 / 0.32 | 滑雪杖助推（原 0.1 / 0.45，用户要“慢一点”） |
| `MOMENTUM_DECAY` | 0.998 | **单板**（用户 2026-10-13 定 0.998，与双板同值）——**平时（直行 / 未压刃）的摩擦**；0.998 = 每 tick 掉 0.2%，1 秒 −4%，半衰期 17.3 s |
| `CARVE_MOMENTUM_DECAY` | 0.999 | **单板：转向期间（carve 生效，压着 W / S）专用的摩擦** —— 比平时的 0.998 再滑一点（0.999 = 每 tick 掉 0.1%，1 秒 −2%）。**改成 0.998 ＝ 转向与直行同摩擦**，改成 **1.0 ＝ 转向全程完全不掉速**。同一个常数还当**速度下限的衰减率**用：`SnowboardControl.guardCarveSpeed` 每 tick 拿 `入场速度 × 0.999^已持续 tick` 作下限（撞方块那一 tick 不补），所以平地转向的曲线由它决定，撞墙被砍半之后也能按这条曲线回来 |
| （无刹车常量） | — | **单板：按 A/D 分“同向 / 反向”两种，但只在没压刃时算刹车**。① **反向按**（向左滑时按 D，且**没**按 W/S）＝刹车 —— 原版横移把人往右挪、速度自然抵消，代码**不覆盖玩家速度、也不托动量**（只把动量对齐当前速度），反着按才真的减得下来；② **同向按**（顺着滑行方向按）＝动量**托住**速度，下落加速攒出来的速度不会被地面摩擦（每 tick ×≈0.55）吃掉 —— 否则一直按住 A/D 下坡会“只有基础速度、叠加不上去”（用户报的正是这个）。③ **压着刃（W/S）时反向按侧压＝换刃转向，不再算刹车**：这也是转向，所以用 `CARVE_MOMENTUM_DECAY` 托住速度（旧行为下起弯那一下 0.5 → 0.165，掉 67% 且动量被清掉后整段弯回不来）。**S 与刹车无关** |
| `BRAKE_DECAY` / `BRAKE_MIN_ALIGN` | 0.92 / 0.3 | **双板刹车**：按住 S 且「还在往前滑」（速度与**身体前方**点积 > 0.3）时，动量与速度每 tick ×0.92（1 秒剩 ≈19%，约 1 秒刹停），方向**不翻**；低于 0.3（横滑 / 倒滑）按 S 才是真的往后走。单板没有这两个常量 |
| `MOMENTUM_DECAY_ON_HIT` | 0.5 | 横向撞到方块时的额外衰减 |
| `MOMENTUM_MIN` | 0.02 | 动量低于它就丢掉 |
| `GLIDE_PUSH` / `REFRESH_INTERVAL_TICKS` | 0.055 / 10 | 雪/冰面上每 0.5 秒沿移动方向补一点推进 |

### 方向控制 `SkiControl.java`

| 常量 | 当前值 | 作用 |
|---|---|---|
| `STEER_MAX_HEIGHT` | 1.0 格 | **只服务单板**（双板只在地面上转向）；脚底离地不超过这个高度才允许转向
| `STEER_RATE` | 0.2 | 每 tick 朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）；单板不参与方向；双板只在落地时摆（空中速度方向一点都不转）。**落地后另有快的一条**：`SkisControl.ALIGN_DEGREES_PER_TICK` 9°/tick 直接把速度方向朝身体前方掰（见「零散常量」），这条 lerp 只是托底时的慢摆 |

### 单板：人板一体 `SnowboardControl.java`

穿上单板时**人板固定成一个朝向**，身体不跟视角转，只能整体左右平移：

| 操作 | 行为 |
|---|---|
| A / D | 沿**身体朝向**的左右平移 —— `ClientInputMixin` 把左右输入按 `(身体朝向 − 视角)` 旋转后交回原版，所以视角转了也仍往同一侧平移；身体朝向不会因此改变。**速度不会被“拨”到侧向**：方向变化一律走 carve 的每 tick 旋转（与 W/S 同一套机制），只靠原版移动把位置走出来 |
| W | **前倾**（**渐进**到位，不瞬跳；W 与 S 同按判中立，避免快速切换时跳成前倾）：**整个人连同脚上的板**绕脚底向前倾 `TILT_DEGREES`（15°），**相机位置跟着倾斜后的头**（前移 眼高·sin15°），但**视线朝向不变**（仍正对原来的方向）；**不给任何推进** |
| S | **后倾**：同样整个人 + 板一起向后仰 `TILT_DEGREES`，相机位置跟着后移，视线不变 |
| **carve（切雪转弯）：W/S 只转向、不加力** | **前提是压刃**：必须按着 W 或 S，**光按 A/D 只是纯横移**。触发后身体朝向每 tick 转 `TURN_RATE_DEGREES`（**1.2**°/tick ＝ 24°/s：转满 180° 需 150 tick ＝ 7.5 s），**速度的方向跟着转同样的角度**（`rotateVelocity`，大小不变；整 tick 结束时再由下面的 `alignCarveHeading` 按身体朝向直接重写）—— 朝向转多少速度方向转多少，带惯性的路线慢慢弯过去，全程**不补任何推力**（W/S 只改方向）。**转向期间的摩擦比直行更小**：用 `CARVE_MOMENTUM_DECAY` 0.999 托住速度（1 秒 −2%），而且此时按反向侧压是**换刃转向**、不再按刹车处理（`SnowboardControl.isCarving`）。**转向期间速度不衰减（`guardCarveSpeed`）**：`SkiGlide` 在动量结算之后对单板调一次 `SnowboardControl.guardCarveSpeed`，每 tick 算下限 `入场速度 × CARVE_MOMENTUM_DECAY^持续 tick`，没撞到方块（`horizontalCollision` 为假）且当前水平速度低于它就缩放回去（方向不变、y 不动、`hurtMarked = true`），退出 carve 时清表 —— **只做下限、不封顶**（下坡加速自己涨上去的不会被拉回），撞墙那一 tick 不补，但下一个自由 tick 就回到这条曲线上。**速度方向由身体朝向推导（`alignCarveHeading`）**：转向期间速度方向不再靠每 tick 转 `delta` 累加 —— 那样同一 tick 里回落加速（沿身体左右轴加一份）与动量托底会把方向拽回去，速度方向越滑越落后于身体朝向，而且 W 与 S 拽偏方向相反（W 落后、S 超前）。入弯那一 tick 记下 `CARVE_HEADING`（入弯速度方向角，`atan2(z, x)` 弧度）与 `CARVE_YAW0`（入弯 bodyYaw），之后每 tick 由 `alignCarveHeading` 把水平速度方向**直接写成** 「`CARVE_HEADING` + `wrapDegrees(bodyYaw现在 − CARVE_YAW0)`」（大小与 y 都不动，变化 > 1e-4 才写并 `hurtMarked = true`），退出 carve 清表。**调用点是整 tick 最后一个写速度的地方**（`SkiGlide.register` 里 `guardCarveSpeed` 之后，只在单板分支），所以同 tick 内别的写者造成的偏角不会累下去：身体转多少、速度方向就转多少。<br>转向方向：按 A/D 时由侧压决定，没按 A/D 时只要滑速 ≥ `CARVE_MIN_SPEED` 就前倾/后倾各走一边；**后倾（S）＝后刃**，转向与前倾相反。腾空也能转 |
| 视角范围 | 头只能相对身体朝向左右各 `VIEW_LIMIT_DEGREES`（135°），合计 270 —— 只剩背后 90° 是禁区。**限位只有一处权威状态**：`client/SkiViewSmoothing` 的 `headOffset`（视角相对身体朝向的偏角）。鼠标每帧转视角时（`SkiHeadLimitMixin` 拦 `Entity#turn` 的 HEAD/TAIL）只把这一帧转过的角度记进 `headOffset` 并夹一次、撤掉越界那点；每帧再由 `CameraViewSmoothingMixin`（`Camera#alignWithEntity` 的 HEAD）用「按 `partialTick` 插值后的身体朝向 + `headOffset`」写一次 yaw。没顶到限位时视角留在世界坐标里不动（只听鼠标），顶到之后头随身体平滑拖走；**往回转立刻响应，没有死区** |
| 空格 | **先起跳** —— 原版跳跃（2026-10-15 起 `makeJump` 不再被取消；单板以前是禁止跳的），短按 / 长按都先跳。起跳力度**随滑行速度放大**；滑得快时**连续按住超过 5 tick 才在空中后空翻**（前 5 tick 只数不转），松手定格、中途松手要重新数满 5 tick，落地角度不对会摔（见「跳跃与后空翻」） |
| 鼠标（头 / 视角） | 可以转，但相对身体朝向限在 ±`VIEW_LIMIT_DEGREES`（见上一行的单一限位实现） |
| 身体朝向 | **不跟着视角转** —— 锁定在穿上板那一刻；客户端每 tick 写回 `yBodyRot`（渲染读的是 render state 的 `bodyRot`）。**`yBodyRotO` 不再写** —— 原版 `baseTick` 每 tick 把它记成「上一 tick 的 yBodyRot」，留着它身体转动才是插值过去的（一起写成新值等于把插值掐掉，第三人称每 tick 跳 2°） |
| 滑行物理 | **只保留下落加速**：每降 1 格 +`FALL_GAIN_PER_BLOCK` 0.15（基数比双板 0.08 大；每 0.5 格结算一次）。方向按 **单板只有左右**：把当前移动方向**投影到身体横轴**，只取左右分量（绝不给“往前”的加速），没左右分量又不按 A/D 就不加。空中也照样给。不做雪面推进、动量也不捕获玩家自己走出来的速度 —— 否则平地走路 / 站坡上都会“莫名往前” |
| 点杖冲刺 | 单板时不给冲刺（`SkiBoost.burst` 直接 return） |
| 换朝向 | 把板脱下来再穿上（重新锁定） |

> 单板的动量只在「下落加速」「按着 A / D 横移」和「转向（carve）」这三种情形下跟随，松开就靠原版摩擦停住；转向期间用的是更小的摩擦 `CARVE_MOMENTUM_DECAY` 0.999（直行仍是 `MOMENTUM_DECAY` 0.998），**并且另有一道速度下限 `guardCarveSpeed`（入场速度 × 0.999^持续 tick，撞方块那一 tick 不补，只做下限不封顶）—— 转向期间不会因为摩擦 / 撞墙把速度吃掉，撞完下一个自由 tick 就回到曲线上**；双板同为 `SkisControl.MOMENTUM_DECAY` 0.998。

双板现在也是「锁身体 + 按键转」，两块的锁表各自一份：单板 `SnowboardControl.BODY_YAW`、双板 `SkisControl.BODY_YAW`（实体 id → 角度），条数超过 `util/IdMap` 的默认上限 64 就整表清空（所有这类 id 表都是 `IdMap`，超限自清）；双板的 A / D 每 tick 转 **2.0°**（`SkisControl.TURN_RATE_DEGREES`，≈40°/s，180° 需 90 tick ＝ 4.5 s），**按 W 时身体再朝视角方向补 2.4°/tick**（≈48°/s，180° 需 75 tick ＝ 3.75 s；`SkisControl.turnTowardView`）。**转向期间速度方向由身体朝向推导**（`SkisControl.alignTurnHeading`，整 tick 最后一个写速度的点，单独一套 `TURN_HEADING` / `TURN_YAW0`）：身体转多少、速度方向就转多少，不再依赖 `alignVelocity` 那条 9°/tick 的限幅（空中 / 按 S 时它根本不跑）。**视角限位共用同一套实现、两块板各一个值**（`client/SkiViewSmoothing` 的唯一 `headOffset` + 两处入口：鼠标 `Entity#turn` 只记账、相机 `Camera#alignWithEntity` 每帧写一次）：双板限在身体左右各 **90°**，单板仍是 **135°**。

**越肩视角模式（Shoulder Surfing 打开时）**：**相机完全自由** —— `client/SkiViewSmoothing` 不再每帧写视角、`SkiHeadLimitMixin` 不再按 `VIEW_LIMIT_DEGREES` 限位（判定走反射 `IShoulderSurfing#isShoulderSurfing()`，见 `client/ShoulderSurfingCompat`；对方没装就与原来完全一致）；**头模型朝横移方向** —— 双板 = 身体朝向（正前方，偏移 0），单板 = 身体朝向 + **带符号的头偏角**：**朝哪一侧滑就朝哪一侧**（水平速度投到身体左轴定方向，侧向分量 < `HEAD_SIDE_MIN_SPEED` 0.05 时看 A / D，都没有就锁存上一次 —— 停下不会自己回正），幅度 **默认 `SNOWBOARD_HEAD_NEUTRAL_DEGREES` 75° / 按 W 前倾 `SNOWBOARD_HEAD_FORWARD_DEGREES` 90° / 按 S 后倾 `SNOWBOARD_HEAD_BACKWARD_DEGREES` 60°**；**左右翻转与幅度变化都平滑扫过**（`SnowboardControl.tickHead`：比例 0.45 + 单步上限 9°/tick —— 150° 翻转 25 tick ≈ 1.25 s、中途经过 0° 正前方；幅度变化约 10 tick ≈ 0.5 s；松手也是滑回当前方向的 75°）；**按 W 也不再让身体追视角**（客户端 `turnTowardView` 跳过）。退出越肩后限位与 W 追视角立刻按原规则恢复（状态在退出后第一帧按当时的视角重新起算）。

### 跳跃与后空翻 `SkiAir.java`（状态机共用，数值两块板各一套）

穿板时空格仍然是**原版跳跃**（不新开按键、也不拦 `makeJump`；按住空格＝落地即再跳＝连跳）：

- **起跳力度随水平滑行速度按对数形曲线升高**：`s = min(水平速度 / MAX_SPEED, 1)`（`MAX_SPEED` 就是该板的速度上限 0.55 / 0.85），力度 = 该板的 **11 点速度→起法力查找表** `JUMP_POWER_BY_SPEED_LUT`（s = 0.0 / 0.1 / … / 1.0 共 11 点，两点之间**线性插值**，取值在 `SkiAir.Tuning#jumpPower`）。表由**对数曲线** `h(s) = h₀ + (2.0 − h₀) × ln(1 + 9s) / ln(10)`（h = 跳高、h₀ = 0.34 单板 / 0.36 双板）在离散 tick 物理下（**重力用腾空的 `AIR_GRAVITY_MULTIPLIER` 0.064**，不是贴地的 1.3 / 1.5）反解而来 —— **前半段涨得快、后面趋平、末段增量趋近 0**，满速恰好卡在跳高上限 `JUMP_MAX_HEIGHT` **2.0 格**。**旧的两个线性常量 `JUMP_BASE_POWER` / `JUMP_SPEED_POWER_GAIN` 已删除**（同一个旋钮别再记在两行）。注入点是原版 `LivingEntity#getJumpPower()`（`jumpFromGround` 里唯一一次取值，common mixin `LivingEntityJumpPowerMixin`），**没穿板 / 生物一律原版值**。
- **空格永远先起跳，后空翻要按住够久才转**：空格一直是**原版跳跃**（短按、长按都先起跳，5 tick 内松手就是**纯跳跃、一点不转**）。真正离地起跳（`onGround` 由真变假且 `vy > 0` —— 走下坡 / 掉悬崖不算起跳）时，起跳水平速度 ≥ `FLIP_MIN_SPEED` 且没在失控里，才**进入可翻转状态**；**起跳本身不自动开翻**（角度保持 0）。进入之后，从离地第一 tick 开始数「**连续按住跳跃**」的 tick：**连续按住满 `FLIP_HOLD_DELAY_TICKS`（5）tick 起**才每 tick 加 `FLIP_DEGREES_PER_TICK`（**单板 18°＝20 tick 一圈 1.00 s，双板 12°＝30 tick 一圈 1.50 s；两板不同速**）；**中途一松开，这个计数立刻归零**（角度继续定格在那一偏角，不清零），再次按住要**重新数满 5 tick** 才接着转。**不在 360° 封顶**（可以按住转多圈），内部按 360 取模保存（[0,360)）避免数值膨胀。速度不够＝普通小跳、按住再久也不转。
- **落地判定按「与直立的最小角距」**：a ＝ 最终角度对 360 取模后映射到 (−180°, 180°]，`|a| ≤ FLIP_LANDING_TOLERANCE_DEGREES`（**±25°**）＝ **干净落地**（角度归零、速度不掉、无惩罚）—— **多翻了几圈只要停在直立附近也算净落**；否则＝**摔倒**：水平速度 ×`CRASH_SPEED_MULTIPLIER`、进入 `CRASH_STUN_TICKS` 的失控 —— 失控期间两块板的**转向 / 压刃 / carve / 追视角**在各自入口一律跳过（`SnowboardControl.isCarving`、`SkisControl.turnBody / turnTowardView / shouldAlignOnGround`），且**不能再次起跳空翻**。`CRASH_DAMAGE_MAX > 0` 时服务端按「与直立偏差 / 180 × 它」`hurtServer` 扣血（**默认 0＝关闭**）。
- 状态机只有一份（`SkiAir`，每玩家状态用 `util/IdMap` 存：角度、上一 tick 角度、在空中、在翻、**连续按住计数**、失控计时）。**空翻角度与「有没有按住空格」只有客户端知道**（`LocalPlayer#input.keyPresses.jump()`，服务端读不到这个输入），所以**角度累积与观感都在客户端本地跑**（`SkiAir.tickClient`，只对本机玩家）；落地那一 tick（净落与摔倒**都**发）客户端发一个 C2S 包 `SkiFlip.Payload`（通道 `skis:flip_landing`，只传一个 float ＝ 最终角度；`PayloadTypeRegistry.serverboundPlay()` + `ServerPlayNetworking.registerGlobalReceiver` 都注册在**主源码集**里、双端共用同一张编解码表）把角度交给服务端。服务端 `SkiAir.tickServer` **只递减失控计时**（不再算角度），收到包后 `SkiAir.applyLanding` 用**同一个 `isCleanLanding`** 判定并施罚：水平速度 ×`CRASH_SPEED_MULTIPLIER` + `hurtMarked`，**只走 `deltaMovement`，不 `setPos`**；客户端本地同时按同一判定进失控（画观 / 输入门与服务器一致，降速仍只在服务端）。包里的角度有合法性校验：**非有限值 / 负值 / 超过 `MAX_NET_ANGLE_DEGREES`（3600°＝10 圈）直接丢弃**，不结算。
- 观感：渲染时**整个人 + 板**绕自身左右轴**向后**翻（`EntityTiltMixin` 的 poseStack 旋转，绕**腰部高度** 1.0 格、按 `partialTick` 插值）；落地那一帧状态机已把角度归零，所以插值两端都是 0、不留残余角度。**相机不跟着转**，第一人称不处理。

| 常量（单板 `SnowboardControl`） | 当前值 | 作用 |
|---|---|---|
| `JUMP_MAX_HEIGHT`（单板） | 2.0 格 | **跳高上限**：满速（s = 1.0）起跳在**腾空重力 0.064**（`AIR_GRAVITY_MULTIPLIER` 0.8 × 原版 0.08）的离散物理下正好 2.00 格。下面那张表就是按它反解的；代码**不拿它反推力度**（表是唯一事实源），改上限要整张表重算 |
| `JUMP_CURVE_SAMPLES`（单板） | 11 点 | 速度→起法力查找表的长度（s = 0.0 / 0.1 / … / 1.0，插值步长 0.1）。静态块校验 `LUT.length == 它`，写错长度启动即抛 |
| `JUMP_POWER_BY_SPEED_LUT`（单板） | 11 点：0.179 / 0.295 / 0.350 / 0.385 / 0.412 / 0.431 / 0.448 / 0.462 / 0.475 / 0.485 / 0.494 | **速度 → 起跳力度**（格/tick），下标 = s × 10，两点之间线性插值。由对数曲线 `h(s) = 0.34 + (2.0 − 0.34) × ln(1 + 9s) / ln(10)` 在**腾空重力 0.064** 下反解（旧 `JUMP_BASE_POWER` 0.22 / `JUMP_SPEED_POWER_GAIN` 0.83 已删；旧表按 0.104 反解，0.219 → 0.611） |
| `FLIP_MIN_SPEED`（单板） | 0.20 格/tick | 起跳水平速度低于它就不进入可翻转状态（0.20 ≈ 单板上限 0.55 的 36%） |
| `FLIP_HOLD_DELAY_TICKS`（单板） | 5 tick | **起跳后要连续按住空格满多少 tick 才开始翻**（5 tick ＝ 0.25 s）。空格永远先起跳；短按（< 5 tick）＝ **纯跳跃、一点都不转**；连续按住满 5 tick 起才每 tick 加 `FLIP_DEGREES_PER_TICK`；中途一松开计数**归零**（角度继续定格），再按住要重新数满 |
| `FLIP_DEGREES_PER_TICK`（单板） | 18.0 度/tick | **按住空格**时每 tick 转多少（18 × 20 = 360°，一圈正好 **1.00 s**；比双板快，**两板不同值**） |
| `FLIP_LANDING_TOLERANCE_DEGREES`（单板） | 25 度 | 落地时与直立（0° / 360° / 720°…）的角距在 **±25°** 内算净落，否则摔倒（多圈也只比最近的一圈） |
| `CRASH_SPEED_MULTIPLIER`（单板） | 0.35 | 摔倒时水平速度保留的比例（掉 65%） |
| `CRASH_STUN_TICKS`（单板） | 20 tick | 摔倒后失控多久（1 s）：转向 / 压刃 / carve / 追视角跳过、不能再起跳空翻 |
| `CRASH_DAMAGE_MAX`（单板） | 0.0 | 摔倒扣血上限（0＝关闭；>0 时服务端按「与直立偏差 / 180」比例扣） |

| 常量（双板 `SkisControl`） | 当前值 | 作用 |
|---|---|---|
| `JUMP_MAX_HEIGHT`（双板） | 2.0 格 | **跳高上限**：满速（s = 1.0）在**腾空重力 0.064** 下正好 2.00 格。同单板：只作依据，代码不反推 |
| `JUMP_CURVE_SAMPLES`（双板） | 11 点 | 查找表长度（步长 0.1），静态块校验 |
| `JUMP_POWER_BY_SPEED_LUT`（双板） | 11 点：0.186 / 0.298 / 0.352 / 0.387 / 0.413 / 0.432 / 0.448 / 0.463 / 0.475 / 0.485 / 0.494 | 同单板，由 `h(s) = 0.36 + (2.0 − 0.36) × ln(1 + 9s) / ln(10)` 在**腾空重力 0.064** 下反解（起跳点略高于单板，所以同一档力度略高；旧表按 0.12 反解 0.241 → 0.651） |
| `FLIP_MIN_SPEED`（双板） | 0.25 格/tick | 起跳水平速度低于它就不进入可翻转状态（0.25 ≈ 双板上限 0.85 的 29%） |
| `FLIP_HOLD_DELAY_TICKS`（双板） | 5 tick | **起跳后要连续按住空格满多少 tick 才开始翻**（5 tick ＝ 0.25 s）。与单板同值、各一个常量：短按＝纯跳跃，满 5 tick 才转，中途松手计数归零 |
| `FLIP_DEGREES_PER_TICK`（双板） | 12.0 度/tick | **按住空格**时每 tick 转多少（12 × 30 = 360°，一圈正好 **1.50 s**）；**与单板 18.0°/tick（1.00 s）不同值**，各一个常量：改一块不动另一块 |
| `FLIP_LANDING_TOLERANCE_DEGREES`（双板） | 25 度 | 与单板同值、各一个常量（±25°） |
| `CRASH_SPEED_MULTIPLIER`（双板） | 0.35 | 与单板同值、各一个常量 |
| `CRASH_STUN_TICKS`（双板） | 20 tick | 与单板同值、各一个常量 |
| `CRASH_DAMAGE_MAX`（双板） | 0.0 | 与单板同值、各一个常量（默认关闭） |

**两块板的差异就四处**：静止/满速两端的起法力（单板 0.179 → 0.494、双板 0.186 → 0.494，各自一张表）、能否开翻的门槛速度（0.20 / 0.25，占各自上限 36% / 29%）、**按住空格时的转速**（单板 18.0°/tick ＝ 20 tick 一圈 1.00 s，双板 12.0°/tick ＝ 30 tick 一圈 1.50 s），以及表的起跳点 h₀（0.34 / 0.36）；**两张表现在都按同一个腾空重力 0.064 反解**（`AIR_GRAVITY_MULTIPLIER` 0.8 × 原版 0.08），贴地的 1.3 / 1.5 不参与跳高。**「按住满 5 tick 才开翻」这个前置延迟 `FLIP_HOLD_DELAY_TICKS` 两板同值**，净落容差、惩罚与失控时长两边同值但各自一份常量，改一块不会带走另一块。

**这套数值的后果**（离散模拟：起跳 `vy = power`，每 tick 先位移、再 `vy = (vy − g) × 0.98`，**g ＝ 腾空重力 0.064** —— 起跳后一直离地，读的是 `AIR_GRAVITY_MULTIPLIER`，不是贴地的 0.104 / 0.12）。

**跳高 —— 对数形的直观读数**（就是那 11 点查找表本身；`s` ＝ 水平速度 / 该板 `MAX_SPEED`，高度单位：格）：

| s | 0.0 | 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **单板** 起法力 | 0.179 | 0.295 | 0.350 | 0.385 | 0.412 | 0.431 | 0.448 | 0.462 | 0.475 | 0.485 | 0.494 |
| **单板** 跳高 | 0.34 | 0.80 | 1.08 | 1.28 | 1.44 | 1.57 | 1.68 | 1.77 | 1.86 | 1.93 | 2.00 |
| **双板** 起法力 | 0.186 | 0.298 | 0.352 | 0.387 | 0.413 | 0.432 | 0.448 | 0.463 | 0.475 | 0.485 | 0.494 |
| **双板** 跳高 | 0.36 | 0.82 | 1.09 | 1.29 | 1.45 | 1.57 | 1.68 | 1.78 | 1.86 | 1.93 | 2.00 |

（注：20% 速度已拿到 54% 的高度，这就是用户要的对数曲线形状；满速就卡在 2.00 格 ＝ `JUMP_MAX_HEIGHT`。腾空重力从 0.104 / 0.12 换到 0.064 后**曲线形状不变**，只是整张表按新 g 重新反解，满速仍是 2.00 格。）

**滞空只决定「最多能按住转多久」**（按住转速 `FLIP_DEGREES_PER_TICK`：单板 18°/tick → 20 tick ＝ 1.00 s 一圈；双板 12°/tick → 30 tick ＝ 1.50 s 一圈；另外**前 5 个连续按住的 tick 只计数不转**，见 `FLIP_HOLD_DELAY_TICKS`）：满速起跳的待机滞空（同一套离散物理，g ＝ 0.064；「坡 α°」＝ 沿 α° 坡往下起跳、落点算在同一坡面上）：

| 地形 | 单板满速 p = 0.494 | 双板满速 p = 0.494 |
|---|---|---|
| 平地 | 16 tick / 0.80 s | 16 tick / 0.80 s |
| 10° 坡 | 20 tick / 1.00 s | 22 tick / 1.10 s |
| 20° 坡 | 23 tick / 1.15 s | 27 tick / 1.35 s |
| 30° 坡 | 28 tick / 1.40 s | 35 tick / 1.75 s |
| 45° 坡 | 37 tick / 1.85 s | 52 tick / 2.60 s |
| 60° 坡 | 57 tick / 2.85 s | 93 tick / 4.65 s |
| 75° 坡 | 162 tick / 8.10 s | —（未测） |

→ 转多少完全看**按住多久**，上表给的是「最多能按住转多久」；而且**前 4 个按住的 tick 只数不转**（`FLIP_HOLD_DELAY_TICKS` 5 的那一 tick 起才开始加角度），所以可转角度的 tick ＝ 滞空 tick − 4，已转角度 ＝ 转速 × 可转 tick：

- **平地 16 tick**：单板可转 12 tick ＝ 216°、双板 12 tick ＝ 144°，离一圈很远，按住到底也是摔倒（与直立差 ≫ ±25°）。平地光靠空翻转不满一圈 —— 这是「想翻就别在平地上翻」的设计口径。
- **单板 20° 坡（23 tick）**：可转 19 tick ＝ 342°，与直立差 18° ≤ 25° —— 按住到底松手就是净落。
- **单板 30° 坡（28 tick）**：可转 24 tick ＝ 432°，**第 24 tick（360°）松手净落**；**45° 坡 37 tick、可转 33 tick ＝ 594°**，第 23 / 24 tick 松手（342° / 360°）净落，再按住就沿 378° → 594° 一路偏下去，落到 720° 附近它在 37 tick 内够不到，所以 25 tick 之后任何时刻松手都是摔。
- **双板**（12°/tick，一圈要 30 个可转 tick，即滞空 ≥ 34 tick）：**30° 坡 35 tick** 刚好够 —— 第 32–35 tick 松手都在 ±25° 内；**45° 坡 52 tick** 余量最大，第 34 tick（360°）松手净落；10° / 20° 坡（22 / 27 tick）转不过一圈，只能摔。
- 75° 那种极端陡坡的单板 162 tick 已进「能转好几圈」的区间，不再当卖点写秒数；双板 75° 与两板 80° **未测**。

晚了几 tick 就反而摔，所以「松手时机 ＋ 够陡的坡」就是这套机制的操作核心。缓坡想净落只能把 `FLIP_DEGREES_PER_TICK` 调快（按住更少 tick 就凑满一圈）或把 `FLIP_HOLD_DELAY_TICKS` 调小（少吃掉几个 tick）。

**跳高随速度严格单调上升、但边际递减**（单板 0.34 → 0.80 → 1.08 → … → 2.00 格）：前 0.2 的速度就拿到一半以上的高度，越往后每 0.1 速度只多 0.07 → 0.03 格，满速卡死在 `JUMP_MAX_HEIGHT` 2.0 格。想改跳高要改**整张表** `JUMP_POWER_BY_SPEED_LUT`（步长 0.1，按上面的对数公式在离散物理里反解，别手调单点）；想改按住时的转速只动 `FLIP_DEGREES_PER_TICK`（调高＝同样滞空能转更多圈）；想改「按住多久才开翻」只动 `FLIP_HOLD_DELAY_TICKS`；想改「多快才敢开翻」只动 `FLIP_MIN_SPEED`；想改「多接近直立才算净落」只动 `FLIP_LANDING_TOLERANCE_DEGREES`。

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
| `TURN_DEGREES_PER_TICK` | 1.0 度 | 滑行方向一 tick 转 1.0° 以上就算「在转向」（两板通用，只看速度方向的变化，不读按键）。**阈值必须低于两板最小的每 tick 转角（当前单板 A / D 1.2°）** —— 否则单板纯 carve 不算「在转向」，弯外侧偏向与板端簇加成全部失效。现状四档：单板 A / D = `SnowboardControl.TURN_RATE_DEGREES` 1.2°/tick、双板 A / D = `SkisControl.TURN_RATE_DEGREES` 2.0°/tick、两板按 W 的最大偏转 2.4°/tick（`VEER_MAX_DEGREES` / `VIEW_TURN_RATE_DEGREES`），都 ≥ 1.2° > 1.0°，`turning` 生效率 100%；而走位那种 0.5° / 0.8°/tick 的低幅方向修正仍在阈值以下，不会误判成转向。判据带 1e-9 弧度容差（`≥ 阈值 − 1e-9`），避免浮点末位噪声把纯匀速转向的 tick 误判成「没在转向」（否则弯外侧偏向与板端簇的转向加成会闪） |
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
- **落地前 `SNOW_GRACE_TICKS` 60 tick（3 秒）内踩过雪地**：每服务器 tick（`END_SERVER_TICK`）遍历在线玩家，**穿板 + `onGround()` + 脚下是雪/冰** 才把 `level().getGameTime()` 记进 `LAST_SNOW_TICK`（玩家 id → tick）；比对时算 `0 ≤ now - last ≤ 60`。所以「雪坡冲下来、腾空两秒、砸在石头上」免伤，「在石头地上站够 3 秒再摔」照旧掉血。脱板 / 断线 `remove`，超 `MAX_ENTRIES` 整表清空；退出 / 换维度残留的旧记录靠时间戳自然过期，不会错免。

非雪面且 60 tick 内没踩过雪照旧摔伤，别的伤害类型一律放行。命中时顺手 `resetFallDistance()`。

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
| `BOARD_LEG_SPREAD` | `SkiStance` | 2.8 | 单板：两腿（以及鞋）在**模型坐标**里的横向偏移 —— 平行并列、向外岔开；腿与鞋必须同值（4.0 被用户嫌“间隔太宽”） |
| `TILT_LERP` | `SnowboardControl` | 0.25 | 俯仰姿态每 tick 向目标靠拢的比例（≈10 tick 到位）—— 倾斜是渐进的 |
| `TILT_DEGREES` | `SnowboardControl` | 15.0 度 | 单板 W / S 时**整个人 + 板**的倾斜角（`EntityTiltMixin` 转模型 poseStack；`CameraTiltMixin` 用同一角度把相机位置沿旋转后的眼高向量前移，视线不跟着转） |
| `SNOWBOARD_HEAD_NEUTRAL_DEGREES` / `SNOWBOARD_HEAD_FORWARD_DEGREES` / `SNOWBOARD_HEAD_BACKWARD_DEGREES` | `SnowboardControl` | 75.0 / 90.0 / 60.0 度 | **越肩视角模式下单板的头**相对身体朝向的**偏角幅度**（恒正，符号看横移方向）：默认 75°（没按 W / S 或两者同按）、按 W 前倾 90°、按 S 后倾 60°。方向：**+ 朝身体右侧（yaw 增大，与 D 同向）、− 朝左侧（yaw 减小）**，由 `headSide` 判定（实际横移方向 → A / D → 锁存）。双板在该模式下是 0°（正前方，不设常量）。只在越肩开启时生效 |
| `HEAD_OFFSET_LERP` / `HEAD_OFFSET_MAX_STEP_DEGREES` / `HEAD_SIDE_MIN_SPEED` | `SnowboardControl` | 0.45 / 9.0 / 0.05 | 头偏角的**平滑**参数（`tickHead`）：每 tick 吃掉与目标差距的 45%，且单步不超过 9°/tick（**限幅说的是度数/tick**，不是速度）→ 左右翻转 150° 约 25 tick（≈1.25 s，中途经过 0° 正前方）、幅度变化 15° 约 10 tick（≈0.5 s）。`HEAD_SIDE_MIN_SPEED` = 判定「正在横移」的最小侧向速度（格/tick，< 0.05 改看 A / D） |
| `VIEW_LIMIT_DEGREES` | `SnowboardControl` | 135.0 度 | **单板**：视角相对锁定身体朝向的最大偏转，左右各一份（合计 270°；留 90° 背后视线禁区）。限位状态只有 `client/SkiViewSmoothing.headOffset` 一处 |
| `VIEW_LIMIT_DEGREES` | `SkisControl` | 90.0 度 | **双板**：同一项，**各自一份常量**（改这里不动单板）。左右各 90°、合计 180° —— 刚好看到正侧方。限的是视角、不是 A/D 的 2.0°/tick 转向；按 W 时身体会朝视角追上 2.4°/tick（`VIEW_TURN_RATE_DEGREES`） |
| `TURN_RATE_DEGREES` | `SnowboardControl` | 1.2 度/tick | carve 时身体朝向每 tick 转多少（24°/s，转满 180° 需 150 tick ＝ 7.5 s；2.0 → **1.2**，用户 2026-10-31 再降一档）。速度方向由 `alignCarveHeading` 从身体推导，改它模型与速度一起变 |
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
| `TURN_RATE_DEGREES` | `SkisControl` | 2.0 度/tick | **双板 A / D 转身体**每 tick 多少（≈40°/s；180° 需 90 tick ＝ 4.5 s）—— **觉得转太慢/太快先改它**（8.0 → 4.5 → 3.5 → **2.0**，用户 2026-10-31 再降一档）。**速度方向由 `alignTurnHeading` 从身体推导，改它模型与速度一起变**；A 让身体 yaw 减小（左）、D 让 yaw 增大（右）。**只转身体**；视角另有 ±90° 限位（见 `VIEW_LIMIT_DEGREES`，不是跟着 A/D 转）；按 tick 写，服务器与客户端各跑一步，不做按帧均摊 |
| `VIEW_TURN_RATE_DEGREES` | `SkisControl` | 2.4 度/tick | **双板按 W 时身体朝视角方向转**每 tick 多少（≈48°/s；残余 90° 约 38 tick ≈ 1.9 s 对齐；转满 180° 需 75 tick ＝ 3.75 s）。步长限幅、不越过目标；残余 < `VIEW_TURN_MIN_DEGREES` 0.5° 就停（防抖）。只在 **W** 时触发，S 不触发；与 A / D 同向时叠加（2.0+2.4=4.4°/tick）。**转的仍是身体**，视角在世界坐标里不动 |
| `BODY_YAW` | `SkisControl` | 实体 id → 身体朝向（度） | **双板的身体朝向锁表**：穿上时 `ensureLocked` 记下当时的 yaw，之后只由 `turnBody`（A / D）改；脱板 `forgetBody` 清掉。视角（`yRot`）与它彻底分开 |
| `BRAKE_DECAY` | `SkisControl` | 0.92 | 双板按住 S 刹车时每 tick 保留的比例（1 秒剩 ≈19%；0.90 更利、0.96 更轻） |
| `BRAKE_MIN_ALIGN` | `SkisControl` | 0.3 | 速度方向与**身体前方**点积超过它才算「还在往前滑」（此时按 S 是刹车）；低于它按 S 就是往后走 |
| `ALIGN_DEGREES_PER_TICK` | `SkisControl` | 9.0 度/tick | **双板落地后速度方向快速朝身体前方对齐**（修「A/D 转完身落地方向不跟」）：`shouldAlignOnGround`（① `onGround()` ② 没按 S —— input 为 null 放行 ③ 水平速度平方 > 1.0E-4）通过才 `alignVelocity`，把速度**方向**朝身体前方转 min(残差, 9.0) 度（`Mth.wrapDegrees` 算残差，**只改方向、大小不变**，写回 `setDeltaMovement` + `hurtMarked`）。9.0°/tick ≈ 180°/s，落地后一两 tick 就掰到朝向上；对齐后重读 `motion / speed` 再往下走。只在双板分支、按 S 那一 tick 不对齐 |
| `TILT_EPSILON` | `SnowboardControl` | 0.01 | 俯仰角与目标差小于它就视为到位，停止补间 |
| `MAX_ENTRIES` | `SkiGlide` / `SnowboardControl` / `SkiBoost` / `SnowSpray` / `GlideSounds` | 64 | 静态表（动量 / 锁定朝向 / 冲刺 / 雪粒子的「上一 tick 速度」与雪雾冷却 / 切雪声计时）条数上限，超了**整表清空**，防地图残留状态泄漏 |
| `COOLDOWN_TICKS` | `item/SkiPoleItem` | 10（0.5 s） | 两次撑杖之间的冷却；冷却中右键直接 `FAIL`（所以连撑最快 0.5 s 一次） |
| `BOARD_TOP` | `SkiBoardModel` / `SnowboardModel` | 23.0 | 板身与翘头在模型空间里的 y 值（板顶面高度） |

## 双板 vs 单板（现行为）

| | 双板 | 单板 |
|---|---|---|
| 朝向 | **锁身体、不锁视角**：身体朝向锁在穿上板那一刻（`SkisControl.BODY_YAW`），由 A / D 改、**按 W 时还会朝视角追 2.4°/tick**；**视角由鼠标转、并限在身体左右各 ±90°**（与单板同一套实现、各自一个值，用户 2026-10-17 定 — 之前拿视角当朝向是错的） | 锁定（穿上板那一刻），视角同样限在 ±135° |
| 转向 | **A / D 只转身体**（`SkisControl.turnBody`，`TURN_RATE_DEGREES` 2.0°/tick ≈ 40°/s，180° 需 90 tick ＝ 4.5 s；yaw 减小＝左、增大＝右）；**按 W 时身体再朝视角方向补 `VIEW_TURN_RATE_DEGREES` 2.4°/tick**（`turnTowardView`，限幅不越过，残余 90° 约 38 tick ≈ 1.9 s 追上；S 不触发）。两种转法都**不碰视角**（只有顶到 ±90° 限位时视角才被身体拖着走）；客户端与服务端每 tick 各跑一步（先 A/D 再 W），客户端写 `yBodyRot / yBodyRotO`。速度方向由服务端 `SkiControl` 按**身体前方**摆过去：**地上跟着转、空中不跟**（空中身体随便转，速度方向一点都不动）；**落地后速度方向按 `ALIGN_DEGREES_PER_TICK` 9°/tick 快速朝身体前方对齐**（`SkisControl.shouldAlignOnGround` + `alignVelocity`，**按 S 时不对齐**）—— 转完身落地方向立刻跟上，不靠 `SkiControl.steer` 慢慢摆；**真正把两条路钉在一起的是 `SkisControl.alignTurnHeading`**（`SkiGlide` 里双板分支、整 tick 最后一个写速度的点）：只要这一 tick `turnBody` / `turnTowardView` 真的转过身体，就把水平速度方向直接写成「入弯方向角（`TURN_HEADING`，`atan2(-x, z)` 口径，与 `alignVelocity` 同一套）+ 身体累计转角（`bodyYaw 现在 − TURN_YAW0`）」—— **身体转多少速度就转多少**，只剩入弯那一刻固有的「身体−速度」偏角；这一 tick 没转身体就清表（`forgetBody` / 脱板路径一起清）。空中 / 按 S 时 `alignVelocity` 不跑，但这条照样锁 | 压刃（W/S）＋侧压（A/D），`TURN_RATE_DEGREES` 1.2°/tick |
| 前进 | **W 前进**；**按住 S 且还在往前滑 = 刹车**（`BRAKE_DECAY` 0.92，只减速、不拨方向）；**静止 / 后退时才往后走**（速度与**身体前方**点积 ≤ `BRAKE_MIN_ALIGN` 0.3）。**W / S 一律沿身体前后**（`ClientInputMixin` 把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系），所以视角转到哪都不影响溜的方向 | W/S 只做前倾 / 后倾（**不加推力**） |
| 左右 | **A 左 / D 右 只转身体**（`SkisControl.turnBody`，`TURN_RATE_DEGREES` 2.0°/tick ≈ 40°/s），**视角不跟着动**（顶到 ±90° 限位才被拖）；横向输入被清掉（不横移），滑行方向在地上跟着身体转（落地后另有 `ALIGN_DEGREES_PER_TICK` 9°/tick 快速对齐）、空中不跟 | A / D 沿身体左右**平移** |
| 站姿 | 腿往后倾 `SKI_LEG_TILT` -0.15 | 腿往后倾 `BOARD_LEG_TILT` -0.15（**与双板同值**，用户 2026-10-13 定） |
| 视角限位 | **±`VIEW_LIMIT_DEGREES` 90°**（`SkisControl`）：鼠标能转，但相对锁定的身体朝向最多左右各 90°（合计 180°，刚好到正侧方）；没顶到限位时视角**留在世界坐标里不动**（身体在下面转），顶到之后头被身体拖着走。**按 W 时身体朝视角方向追 2.4°/tick**（`VIEW_TURN_RATE_DEGREES`）—— 追的是身体，视角在世界坐标里仍然不动 | **±`VIEW_LIMIT_DEGREES` 135°**（`SnowboardControl`）：同一套实现（`client/SkiViewSmoothing` 的唯一 `headOffset`，鼠标只记账、相机每帧写一次）、**各自一个常量** |
| 音效 / 粒子 | 同一套：滑行雪粒子 + 随速度变密的切雪声（暂停即停，无风声） | 同左 |
| 摩擦（动量每 tick 保留） | `SkisControl.MOMENTUM_DECAY` **0.998** | `SnowboardControl.MOMENTUM_DECAY` **0.998**（直行）/ **0.999**（carve 转向期间）—— 两板同值，用户 2026-10-13 定；单板 carve 另有速度下限 `guardCarveSpeed`（转向期间实际不衰减） |
| 重力 | **贴地**（`onGround`）`GRAVITY_MULTIPLIER` **1.5**（`Attributes.GRAVITY` 0.08 → 0.12 格/tick²）；**腾空**（跳跃 / 坠落）`AIR_GRAVITY_MULTIPLIER` **0.8**（→ 0.064 格/tick²）；脱板还原 | 贴地 **1.3**（0.104 格/tick²）、腾空 **0.8**（0.064）；各自一份常量，改一个不动另一个。两板满速跳高都卡在 `JUMP_MAX_HEIGHT` 2.00 格（**按腾空 0.064 反解的表**，与贴地的 1.5 / 1.3 无关） |
| 跳跃 / 空翻 | 起跳力 `JUMP_POWER_BY_SPEED_LUT` 11 点表（0.186 → 0.494，满速跳高 2.00 格）；起跳速度 ≥ `0.25` 才进入可翻转状态，**连续按住满 `FLIP_HOLD_DELAY_TICKS` 5 tick 才转**（转速 **12.0°/tick ＝ 30 tick 一圈 1.50 s**），松手定格且计数归零、落地与直立差 ≤ ±25° 才是净落，否则速度 ×0.35 + 20 tick 失控 | `JUMP_POWER_BY_SPEED_LUT`（0.179 → 0.494，满速跳高 2.00 格）；起跳速度 ≥ `0.20` 才进入可翻转状态，**连续按住满 5 tick 才转**（转速 **18.0°/tick ＝ 20 tick 一圈 1.00 s**，比双板快 0.5 s，两板不同值）；±25° 净落容差、×0.35 + 20 tick 失控与双板同值，但各自一份常量 |
