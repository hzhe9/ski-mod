# 手感与数值（可调旋钮 / 物品属性 / 两块板的行为差异）

> 工程数值的唯一事实源。改手感先看这里；`README.md` 只面向使用者。

### 物品属性（都在 `SkisMod` 注册时写死）

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
| `STEP_BLOCKS` | 1.0 | 每累计下降这么多格结算一次加速（**双板**；单板另有自己的 0.5 格步长，见下一行） |
| `DESCENT_STEP_BLOCKS`（单板） | 0.5 | **单板的结算步长**（`SnowboardControl`）：每降 0.5 格结算一次，单次 = 0.11 × 0.5 = 0.055 —— 每格总量仍 0.11/格，下坡降没半格就有第一份（不用攒满一整格） |
| `FALL_GAIN_PER_BLOCK`（双板） | 0.1 | **双板**下落基数（`SkisControl`） |
| `FALL_GAIN_PER_BLOCK`（单板） | 0.11 | **单板**同项（`SnowboardControl`；只取左右分量、体感弱，所以基数比双板大，用户 2026-10-12 定） |
| `FALL_GAIN_PER_TICK_MAX` | 两板均 0.3 | **单 tick 下落加速上限**：一次结算最多加这么多（防止从高处一步把速度给爆） |
| `FALL_GAIN_EXPONENT` | 1.0 | 两板共用的曲线指数。**当前 1.0 = 线性**（用户要求先去掉衰减试手感）；0.8 = 很缓的边际递减、0.5 = 开方 |
| `STICK_DOWN` / `STICK_DOWN_MAX` / `STICK_MAX_DROP` | 0.08 / 0.8 / 1.0 | **双板下坡贴地**：没在地面、正在下落、**没按跳跃键**、且**脚底离地 ≤ 1 格**时，每 tick 额外补向下速度（贴坡面滑，不弹跳）。⚠️ 离地更远＝真的在坠落，完全不插手，保留原版重力加速 |
| `STEP_UP_PENALTY` | 0.04 格/tick | 滑上 1 格台阶扣掉的水平速度（动量一起扣，否则下一步被托回来） |
| `STEP_UP_MIN_HEIGHT` | 0.5 格 | 判定“上了一格台阶”的最小抬升高度 |
| `VEER_SMOOTHING` / `VEER_MAX_DEGREES` | 0.35 / 4° | 单板只用 W/S 时的平滑趋近系数与单 tick 上限（替代原“固定 2°/tick + 到目标死停”） |
| `MAX_SPEED`（单板） | 0.55 格/tick | 单板速度上限 ≈ 11 m/s ≈ 40 km/h（参照：原版步行 0.216、疾跑 0.281、创造飞行 0.545 格/tick） |
| `MAX_SPEED`（双板） | 1.4 格/tick | 双板速度上限 ≈ 100 km/h |
| `MOMENTUM_DECAY` | 0.99 | 滑行动量每 tick 保留比例 ＝ 有效摩擦力（双板；0.985 太涩 → 0.993 → 用户定 0.99） |
| `ADD` / `MIN_PEAK` | 0.06 / 0.32 | 滑雪杖助推（原 0.1 / 0.45，用户要“慢一点”） |
| `MOMENTUM_DECAY` | 0.99 | 单板（用户 2026-10-11 定 0.99，与双板同值；0.99 = 每 tick 保留 99%，1 秒后剩 81.8%，半衰期 3.45 s） |
| （无刹车常量） | — | **单板：按 A/D 分“同向 / 反向”两种**。① **反向按**（向左滑时按 D）＝刹车 —— 原版横移把人往右挪、速度自然抵消，代码**不覆盖玩家速度、也不托动量**（只把动量对齐当前速度），反着按才真的减得下来；② **同向按**（顺着滑行方向按）＝动量**托住**速度，下落加速攒出来的速度不会被地面摩擦（每 tick ×≈0.55）吃掉 —— 否则一直按住 A/D 下坡会“只有基础速度、叠加不上去”（用户报的正是这个）。**S 与刹车无关** |
| `BRAKE_DECAY` | 0.90 | **双板按 S＝刹车**：动量每 tick 再乘这个系数（≈半秒停住），松开后不会被动量拉回原速 |
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
| A / D | 沿**身体朝向**的左右平移 —— `ClientInputMixin` 把左右输入按 `(身体朝向 − 视角)` 旋转后交回原版，所以视角转了也仍往同一侧平移；身体朝向不会因此改变。**速度不会被“拨”到侧向**：方向变化一律走 carve 的每 tick 旋转（与 W/S 同一套机制），只靠原版移动把位置走出来 |
| W | **前倾**（**渐进**到位，不瞬跳；W 与 S 同按判中立，避免快速切换时跳成前倾）：**整个人连同脚上的板**绕脚底向前倾 `TILT_DEGREES`（15°），**相机位置跟着倾斜后的头**（前移 眼高·sin15°），但**视线朝向不变**（仍正对原来的方向）；**不给任何推进** |
| S | **后倾**：同样整个人 + 板一起向后仰 `TILT_DEGREES`，相机位置跟着后移，视线不变 |
| **carve（切雪转弯）：W/S 只转向、不加力** | **前提是压刃**：必须按着 W 或 S，**光按 A/D 只是纯横移**。触发后身体朝向每 tick 转 `TURN_RATE_DEGREES`（2°/tick ＝ 180°/s），**速度的方向跟着转同样的角度**（`rotateVelocity`，大小不变）—— 朝向转多少速度方向转多少，带惯性的路线慢慢弯过去，全程**不补任何推力**（W/S 只改方向）。<br>转向方向：按 A/D 时由侧压决定，没按 A/D 时只要滑速 ≥ `CARVE_MIN_SPEED` 就前倾/后倾各走一边；**后倾（S）＝后刃**，转向与前倾相反。腾空也能转 |
| 视角范围 | 头只能相对身体朝向左右各 `VIEW_LIMIT_DEGREES`（135°），合计 270 —— 只剩背后 90° 是禁区（`SnowboardHeadLimitMixin` 在 `Entity#turn` 末尾夹住 yaw；carve 转动身体后客户端 tick 也会把视角拉回限制内） |
| 空格 | 不能跳（`makeJump` 取消） |
| 鼠标（头 / 视角） | 可以随便转 |
| 身体朝向 | **不跟着视角转** —— 锁定在穿上板那一刻；客户端每 tick 写回 `yBodyRot` + `yBodyRotO`（渲染读的是 render state 的 `bodyRot`，`yBodyRotO` 一起写是为了不让插值把身体拉回视角方向） |
| 滑行物理 | **只保留下落加速**：每降 1 格 +`FALL_GAIN_PER_BLOCK` 0.11（基数比双板 0.1 略大；每 0.5 格结算一次）。方向按 **单板只有左右**：把当前移动方向**投影到身体横轴**，只取左右分量（绝不给“往前”的加速），没左右分量又不按 A/D 就不加。空中也照样给。不做雪面推进、动量也不捕获玩家自己走出来的速度 —— 否则平地走路 / 站坡上都会“莫名往前” |
| 点杖冲刺 | 单板时不给冲刺（`SkiBoost.burst` 直接 return） |
| 换朝向 | 把板脱下来再穿上（重新锁定） |

> 单板的动量只在「下落加速」和「按着 A / D 横移」这两种情形下跟随，松开就靠原版摩擦停住；双板仍是原来的低摩擦动量（`SkisControl.MOMENTUM_DECAY` 0.99）。

双板不受影响（仍是原来的滑行 + 按键转向）。锁定表 = `SnowboardControl.BODY_YAW`（实体 id → 角度），条数超过 `MAX_ENTRIES` 整表清空。

### 冲刺 `SkiBoost.java`（仅 `main`）

| 常量 | 当前值 | 作用 |
|---|---|---|
| `ADD` | 0.06 | 每次右键在"当前速度"之上抬高多少 |
| `MIN_PEAK` | 0.32 | 起步速度下限（站着撑着也能冲出去） |
| `DURATION_TICKS` | 60 | 冲刺持续 3 秒后回到原速 |
| `CURVE` | 1.0 | 衰减曲线（1.0 = 线性匀速缓降，越大越"先猛后缓"） |
| `LIFT` | 0.12 | 右键那一下的向上分量 |

### 站姿与姿态

| 常量 | 位置 | 当前值 | 作用 |
|---|---|---|---|
| `BASE_LEG_X` | `SkiStance` | 1.9 | 双板腿的**基础**横向位置；`legOffsetX()` = `BASE_LEG_X` + `LEG_SPREAD` = 2.8 |
| `LEG_SPREAD` | `SkiStance` | 0.9 | 双板：单侧腿向外平移（两板间隔随之变化） |
| `BOARD_YAW_DEGREES` | `SkiStance` | 90 | 单板**外观**：板组（板身 + 板头）绕 Y 转的角度 —— 90 = 板横在脚下（与视线垂直）；-90 = 板头朝另一侧 |
| `BOARD_LEG_SPREAD` | `SkiStance` | 2.8 | 单板：两腿（以及鞋）在**模型坐标**里的横向偏移 —— 平行并列、向外岔开；腿与鞋必须同值（4.0 被用户嫌“间隔太宽”） |
| `TILT_LERP` | `SnowboardControl` | 0.25 | 俯仰姿态每 tick 向目标靠拢的比例（≈10 tick 到位）—— 倾斜是渐进的 |
| `TILT_DEGREES` | `SnowboardControl` | 15.0 度 | 单板 W / S 时**整个人 + 板**的倾斜角（`EntityTiltMixin` 转模型 poseStack；`CameraTiltMixin` 用同一角度把相机位置沿旋转后的眼高向量前移，视线不跟着转） |
| `VIEW_LIMIT_DEGREES` | `SnowboardControl` | 135.0 度 | 视角相对锁定身体朝向的最大偏转，左右各一份（合计 270°；留 90° 背后视线禁区） |
| `TURN_RATE_DEGREES` | `SnowboardControl` | 2.0 度/tick | carve 时身体朝向每 tick 转多少（180°/s ＝ 一直按着画圈） |
| `CARVE_MIN_SPEED` | `SnowboardControl` | 0.05 格/tick | 没按 A/D 时，光靠前/后倾触发 carve 所需的最低滑速 |
| `ROW_CENTER` / `ROW_AMPLITUDE` | `HumanoidModelMixin` | -0.35 / 0.95 | 手臂划动中心角与幅度（最高处 = 中心 − 幅度） |
| `DOWN_FRACTION` | `HumanoidModelMixin` | 0.6 | 一次摆臂里"下摆"占的比例（其余是收回） |
| `GLIDE_KNEE_BEND` / `GLIDE_LEAN` | `HumanoidModelMixin` | 0.08 / 0.05 | 屈膝角度 / 身体前倾（单板腿位） |
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
| `TILT_EPSILON` | `SnowboardControl` | 0.01 | 俯仰角与目标差小于它就视为到位，停止补间 |
| `MAX_ENTRIES` | `SkiGlide` / `SnowboardControl` / `SkiBoost` | 64 | 静态表（动量 / 锁定朝向 / 冲刺）条数上限，超了**整表清空**，防地图残留状态泄漏 |
| `COOLDOWN_TICKS` | `item/SkiPoleItem` | 10（0.5 s） | 两次撑杖之间的冷却；冷却中右键直接 `FAIL`（所以连撑最快 0.5 s 一次） |
| `BOARD_TOP` | `SkiBoardModel` / `SnowboardModel` | 23.0 | 板身与翘头在模型空间里的 y 值（板顶面高度） |

## 双板 vs 单板（现行为）

| | 双板 | 单板 |
|---|---|---|
| 朝向 | **不锁** —— 视角转多少人就朝哪走（用户 2026-10 定案：回到最开始的状态） | 锁定（穿上板那一刻），视角可自由转 ±135° |
| 转向 | 视角（不用键盘转）| 压刃（W/S）＋侧压（A/D），`TURN_RATE_DEGREES` 2°/tick |
| 前进 | W / S（原版移动） | W/S 只做前倾 / 后倾（**不加推力**） |
| 左右 | A / D 原版横移（视角为基准） | A / D 沿身体左右**平移** |
