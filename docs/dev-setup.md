# 开发者笔记（构建 / 装机 / 架构 / 坑）

> 不含使用说明；使用者请看 `README.md`。

## 分支

| 分支 | 手臂 | 冲刺 |
|---|---|---|
| `main` | **平时不动**，只有右键撑杖才播放一次摆臂（从最高处往下摆到最后处，再收回最高处） | 右键一次：速度抬到「当前 + `ADD`（0.06）」，再在 60 tick（3 秒）内线性回到「右键当时的原速」 |
| `swing` | 滑行时**双臂持续同相划动**；滞空或落地不满 5 tick 时停在离地前最后一帧姿势 | 无（只有撑杖推进） |

两版共用物品注册、模型、贴图、配方、滑行物理。**这里不维护差异文件清单**（枚举一次就会过期）——
要清单直接跑：

```bash
git diff --name-only main swing          # 全部差异文件
git diff --stat main swing               # 带行数
```

只有两条稳定的归属关系值得记住：`SkiBoost.java`（冲刺）与 `SwingHooks.java`（main→client 的动画钩子）**只在 `main`**；
`swing` 用 `SkiArmorRenderer` 而 `main` 是 `BoardArmorRenderer`（前者不带单板）。

## 构建与装机

```bash
./gradlew build        # Windows 上用 gradlew.bat；产物 build/libs/skis-mod-1.0.0.jar
```

- 需要 **JDK 25**（`gradle.properties` 没绑路径，用系统 `JAVA_HOME` 即可）
- 装机：把 `build/libs/skis-mod-1.0.0.jar` 复制到实例的 `mods/` 目录（开了版本隔离的启动器就是该版本的 `mods/`）
- 同一个 mod id 的两个 jar **不能同时启用**：不用的那个改名成 `xxx.jar.disabled` 即可随时切换
- 只改数值常量时**编译通过即可**；改到 Mixin / 模型层注册 / 装备注册这类"只有启动才暴露"的东西才需要真启动一次自检
- **只改注释 / javadoc 时主 jar 不会重编**（Gradle 按 class 哈希判 up-to-date，只有 sources jar 更新）—— 这是正常的，别以为改动没生效
- **Mixin 类里只能有 `private` 字段**（`@Shadow` 的除外，它本来就要和目标类同修饰符）：非 private 字段会被当成「要合并进目标类的成员」，其中非 private 的 **static** 字段会被校验器直接拒掉 —— `InvalidMixinException: ... contains non-private static field`，抛出点在资源重载的 worker 线程里，表现就是**启动黑屏**（日志里只提 `Mixin apply ... failed`）。要跨 mixin / 跨类共享的常量（如子部件名字），放到普通类（`SkiStance` 一类）里再引用；别指望「常量会被内联所以引用没事」—— `javap -c` 的常量池仍会留下那个类名，且重构一次就炸

## 准备 Python 环境（生成器 / 文档守卫）

`tools/` 下的生成器（贴图、物品图标）与文档守卫需要 Python + Pillow，依赖固定在仓库根的 `requirements.txt`。
建议在仓库根自建一个项目内的 venv（`.venv/` 已在 `.gitignore` 里），**生成器一律用它的解释器跑**：

```bash
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt    # Windows
.venv/bin/python     -m pip install -r requirements.txt    # macOS / Linux
```

之后：

```bash
.venv\Scripts\python tools/gen_assets.py        # Windows；换成 .venv/bin/python 即 macOS / Linux
```

产物贴图 / 模型 / 图标全部由这两个生成器写出，**不要手改**；`requirements.txt` 锁的是实测能把现有产物
**逐字节重生成**的那版 Pillow（`tools/check_docs.py` 每次都会重跑生成器做逐字节比对，换版本时由它把关）。

## 架构速览

| 文件 | 职责 |
|---|---|
| `SkisMod.java` | **入口**：`onInitialize` 只挂载滑行物理 / 冲刺 / 落地免伤 / 两个 C2S 包（`SkiFlip` 空翻落地、`SnowboardAxis` 板轴）/ 创造页；对外保留 `MOD_ID` / `LOGGER` / `MODERN_SKI` / `SNOWBOARD` / `SKI_POLE` / `isSkiFeet`（转发到 `registry/ModItems`，mixin 与渲染侧十几处按这些名字取物品） |
| `Constants.java` | `MOD_ID`（注册 id 命名空间 / 资源路径前缀 / 日志名） |
| `registry/ModItems.java` | 物品定义与注册：双板 / 单板（脚部装备、护甲 +2、移速 +0.010、耐久 390）、滑雪杖、装备资源 `SKI_EQUIPMENT_ASSET`；款式表 `VARIANTS` 与 `variantStack`；`isSkiFeet` 判定。注册时机仍在类加载时（`SkisMod` 的转发字段一被读就触发） |
| `registry/ModCreativeTabs.java` | 独立创造标签页 `itemGroup.skis`：6 款 × 2 + 1 根杖 |
| `util/IdMap.java` | `实体 id → 值`的缓存表：容量 64、写入超限**按插入序淘汰最旧的一条**（不是整表清空 —— 整表清空是悬崖式降级：所有动量归零、`BODY_YAW` 被重锁、carve 失效）；两块板的朝向 / 姿态、滑行动量、雪粒子与切雪声的上一 tick 状态都用它 |
| `item/SkiPoleItem.java` | 滑雪杖右键行为（客户端摆臂 + 服务端冲刺） |
| `SkiBoost.java` | 冲刺：冲量 + 按 tick 衰减的速度曲线 |
| `SkiGlide.java` | 每 tick 滑行物理：下落加速、**穿板时的重力（`Attributes.GRAVITY` ×倍数：贴地 `GRAVITY_MULTIPLIER`、腾空 `AIR_GRAVITY_MULTIPLIER`，按 `onGround()` 二选一；写 GRAVITY 的地方全类只有两处 —— `applyGravity`（只在换档 / 换板时写一次）与脱板还原 `exitSkiAttributes`）**、低摩擦动量、雪面推进、脚上装备的台阶高度（`STEP_HEIGHT` 1.0，只在**进入穿板状态那一 tick** 写一次，脱板写回**进入前的原值**而不是写死 0.6 —— 每 tick `setBaseValue` 会永久抹掉别的模组 / 药水对属性的修改）；`isOnSnowOrIce` 是「雪面」的唯一定义（滑行 / 雪粒子 / 落地免伤共用） |
| `SnowLanding.java` | 穿板摔在雪 / 冰面上免摔伤：Fabric `ServerLivingEntityEvents.ALLOW_DAMAGE` 事件（挂在 `hurtServer` 上，不需要 mixin），只拦 `DamageTypeTags.IS_FALL` |
| `SkiAir.java` | **起跳力度 / 按住空格的后空翻 / 落地判定的共用状态机**（不依赖客户端类）：每玩家状态（角度、上一 tick 角度、在空中、**本段是否已开翻 `flipStarted`**（自上次落地起一直保持，让空中续翻不等静止段；不能用「角度 ≠ 0」代替，角度是取模的）、连续按住计数、失控计时）等存 `util/IdMap`；`tickClient` 只对本机玩家跑（读跳跃键、数「连续按住」的 tick、满 `FLIP_HOLD_DELAY_TICKS` 才累加角度、跑落地判定并把最终角度回调出去），`tickServer` 只递减失控计时；`isCleanLanding` 是**两端共用的唯一判定**（`applyLanding` 供 C2S 包 `SkiFlip` 调用）；只按 `SnowboardControl.isSnowboard` 选数值、**不含任何一块板的数**；`tryJump` 是**起跳唯一入口**，两端各调一次、都放在**实体 tick 之前**（客户端 `mixin/client/LocalPlayerJumpMixin` 注入 `LocalPlayer#aiStep` 的 HEAD；服务端 `SkiGlide.register` 的 `START_SERVER_TICK`），写完置 `hurtMarked` ＋ `needsSync`；**空中状态按端分开存（客户端 `CLIENT_STATES` / 服务端 `SERVER_STATES` 各一张 `IdMap`，按 `level().isClientSide()` 选表）** —— 单机下两端共用同一 entity id，共用一张表会让计时器每 tick 递减两次、且后调用的那端被冷却拦下；`angleDegrees(id, partialTick)` 给渲染插值读、`isStunned(id)` 给两块板的转向入口跳闸、`isFlipping(id)` 给 `client/FirstPersonModelCompat` 用（渲染期每帧问一次「这个实体这一帧是不是在空翻」＝客户端表里角度 ≠ 0，一次 map 查询、null 安全）、`airPoseBlend(id)` 给 `HumanoidModelMixin` 用（渲染期每帧问一次「这一帧混多少空中姿势」，只读 `CLIENT_STATES`、没有状态就 `0.0F`，**远端玩家永远是地面姿势**）—— 它**只有 0 / 1 两个值、判据只有一条**：`AirState#jumpedThisAir`（**这一趟腾空里起过跳**，`tryJump` 置位、真落地才清）→ 1，其余一切腾空 → 0（地面姿势）。**没有距离、没有渐变、`flipStarted` 也不单独触发**（用户 2026-11-18「只保留跳跃一条路」）：`AIR_POSE_NEAR_GROUND_RANGE` / `AIR_POSE_BLEND_TICKS` / `AirState#farGroundTicks` 与那次射线整一套已删除；落地那一 tick `tryJump` 的落地沿已清掉标志，所以**落地当 tick 就回地面姿势**；渲染期不做任何射线，也不与 `SnowboardControl.NEAR_GROUND_RANGE` 1.0（贴地判定）联动 |
| `AlpineSkiControl.java` | **双板专属数值**（下落基数 0.1 / 单 tick 上限 / 重力倍数 贴地 1.5、腾空 0.9 / 速度上限 0.85 / 动量衰减 0.998 / 刹车 0.92）＋**身体朝向锁表 `BODY_YAW_CLIENT` / `BODY_YAW_SERVER`**（按端两份，见 `docs/tuning.md` 的「状态表按端拆分」）与按键转向 `turnBody`（A / D 每 tick `TURN_RATE_DEGREES` 4.0°，只转身体；地面转向、空中不转）＋ **W 朝视角转向 `turnTowardView`**（`VIEW_TURN_RATE_DEGREES` 4.8°/tick，限幅不越过、残余 < 0.5° 停；同样只转身体）＋**视角：第三人称不限位、第一人称夹 ±90°**（夹在 `client/SkiViewSmoothing#frame` 的唯一状态 `headOffset` 上，常量复用 `SnowboardControl.HEAD_MODEL_LIMIT_DEGREES` 90°、只此一处；双板原先那条独立的 `VIEW_LIMIT_DEGREES` 已删）＋ **A / D 有输入的那一 tick 不追视角**（`turnTowardView` 的调用点各自判 `sideSign == 0`：W 与 A / D 同按两个都要生效）＋**贴地转向期间速度方向由身体推导 `alignTurnHeading`**（`TURN_HEADING` / `TURN_YAW0` / `TURN_LAST_YAW` 三张表（同样按端两份，实际只在服务端用）；`SkiGlide` 双板分支里整 tick 最后一个写速度的点，**`!onGround` 直接跳闸**、只转身体；与单板的 `alignCarveHeading` 各一套、互不借用）＋**转向姿态的两个纯观感参数**：`TURN_ROLL_DEGREES` **6.0**（上身绕腰侧倾，12.0 → 6.0）与 `TURN_LEG_TILT_DEGREES` **15.0**（外侧腿整条绕髋侧倒，雪鞋与板在 `AlpineSkiModel` 里同角一起转；内侧腿不动 —— 老的 `TURN_OUTWARD_LEG_DEGREES` / `TURN_OUTWARD_LEG_BEND_SCALE` 已删，`legTiltRadians` / `legBendScale` 供渲染侧读）＋ 死区 `TURN_LEAN_MIN_STEP_DEGREES` **0.25°/tick** ＋ 平滑系数 `TURN_LEAN_LERP` **0.35** ＋ 两张**客户端单份**的表 `TURN_LEAN`（侧倾系数）/ `TURN_LEAN_LAST_YAW`（上一 tick 的 body yaw）：只在 `SkisClient#tickAlpineLean` 写、渲染侧读；**目标 = 本 tick `bodyYaw` 与上一 tick 的差过死区后的符号**（差 > 0 ＝ yaw 增大 ＝ 向右 ⇒ 右侧倾、差 < 0 ⇒ 左侧倾），**不看按键、不要求贴地**，按 `TURN_LEAN_LERP` 0.35/tick 平滑 |
| `GlideSteering.java` | 方向控制：读玩家真实按键、把双板速度方向朝身体前方摆（只服务双板、只在地面上；单板不参与方向）＋按板分派的速度上限 |
| `AirTuningSpec.java` | **两块板共用的起跳 / 空翻规格容器**：13 个字段（跳高上限、土狼时间、输入缓冲、冷却、速度上限、空翻与摔倒各项）＋ 速度→起法力查找表 ＋ 表长校验（`JUMP_CURVE_SAMPLES` 11，写错在类初始化就炸）＋ `toTuning()` 装配成 `SkiAir.Tuning`；**数值仍写在两块板各自的常量里**，本类只放结构与通用语义（参数顺序写错会静默串值，所以只留这一处装配） |
| `SnowboardControl.java` | 单板状态：锁住并**可被 carve 转动**的身体朝向、俯仰姿态、**头模型 ±90° 夹（第三人称视角不限位、第一人称视角夹同一个 ±90°，见 `headModelOffset` / `SkiViewSmoothing#frame`）**、carve 常量；只用 W / S 时的摆角**按按键组合锁存**（`veerStep` + `VEER_STEP_* / VEER_KEYS_*`：空中速度冻住时实时误差会随身体转动归零，锁存后才能连续转过 90°），`boardSide` 另有 0.20 的**换边滞后**（跨 90° 侧向分量过零不翻符号）；`applyCarve` 由服务端与客户端**各跑一遍同一规则**以保持一致；`guardCarveSpeed` 在 carve 持续期间给水平速度一个「入场速度 × `CARVE_MOMENTUM_DECAY`^tick」的下限（**撞方块那一 tick 也补**：沿 `move()` 裁剪后剩下的方向等比缩放，朝墙分量已为 0 所以不会顶墙推进；**撞墙 tick 另有封顶** —— 不超过撞前速度（`CARVE_PRE_HIT_SPEED`）、且最多放大 `CARVE_HIT_BOOST_MAX` 4 倍，近正面撞不再被 20 倍弹回满速；回正那一步仍跳过撞墙 tick），**转向期间不掉速** —— 由 `SkiGlide` 在动量结算之后调一次（只对单板）；紧接其后还有 `alignCarveHeading`（同样只对单板、是整 tick 最后一个写速度的点）：**速度方向自动回正到板轴** —— 目标量是**板轴**（身体左右轴，`sideLocked`；单板横着滑，板轴就是行进方向），哪一侧由 `boardSide` 定（**速度回正与越肩头偏角共用这一个判定**：`alignCarveHeading` 的目标板轴；头方向 `headSide` ＝ `-boardSide`，脸正对滑行方向那一侧）；**生效条件**：穿单板 + 不在失控（`SkiAir.isStunned`）+ 水平速度 > 1e-4 + 可改速度方向（**`SnowboardControl.isSpeedAlignable`：`onGround()` 或脚底 1.0 格内有方块且 `vy ≤ 0`** —— 近地那档兜坡面 `onGround` 抖动；真腾空速度方向一点不动，落地后才朝板轴追，与双板同一条口径）—— **不要求在 carve 里**，也**没有入弯基准表**（旧 `CARVE_HEADING` / `CARVE_YAW0` 已删）；每 tick 用 `wrapDegrees` 取「板轴方向 − 当前方向」的最短角差，≤ 0.05° 走死区、否则按**步长 `max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK` 9.0`, 本 tick 身体转角绝对值)`（夹到 `ALIGN_MAX_STEP_DEGREES` 45°）** **旋转**过去（大小与 y 不动、`hurtMarked`）；**顶墙那一 tick 不掰**（`horizontalCollision` 为真就直接返回：本 tick 掰出去的角度会被同一 tick 的 `move()` 逐轴裁剪吃掉，位移不变、只把「板轴 vs 位移」的夹角白白拉开一次，还会打断连续撞墙计数，让 3 tick 门槛的撞墙减速收不到）；`applyCarve` 那边每 tick 转角由 `TURN_RADIUS_BLOCKS` 3 格算出（`toDegrees(vTravel / 3)`，夹 3.0–16.0°/tick：满速算出 19.23、封顶 16.0；这三个值经「拆表 ×2 + 板轴同步 `af42725` 再 ×2」后又按用户反馈回调到 2 倍档的 2/3），贴地且**没撞墙**时速度跟着身体转（撞墙 tick 只转 `BODY_YAW`、不转速度 —— 转出去的分量会被同 tick 的 `move()` 裁掉；空中也不转速度）；`alignCarveHeading` 与 `applyCarve` 一样**服务端与客户端各跑一遍同一份实现**（签名 `alignCarveHeading(Entity, Input)`：服务端 `SkiGlide` 传 `player.getLastClientInput()`，客户端 `client/SkisClient` 的 `END_CLIENT_TICK` 里 `applyCarve` 之后**只对本机玩家**传 `input.keyPresses`）—— 因为玩家位置是**客户端权威**（`Player#isClientAuthoritative()`），只靠服务端发包（`hurtMarked` → `ClientboundSetEntityMotionPacket`，仅误差 > 死区才发）在无误差时不会触发 |
| `SnowboardAxis.java` | **单板板轴同步包**（C2S，通道 `skis:board_axis`，载荷一个 float）＋服务端接收器：板轴**只有客户端一份权威**，`client/SkisClient` 每 tick 把本机锁定的 `BODY_YAW_CLIENT` 发上来，服务端 `SnowboardControl.acceptClientAxis` **覆盖式**写 `BODY_YAW_SERVER`（收到包的那一 tick 服务端不再自己累加，避免领先一 tick 造成锯齿）。不加这个包时两端各积分各的轴（每 tick 转角取决于本端速度 / `onGround()` / 脚下方块摩擦，同一 tick 能差 2–4°），那条差值是隐藏状态、永不自我纠正 —— 位移取服务端方向、板子按客户端轴画，于是画面上是一个固定夹角。接收器只写接收者自己那张表、覆盖式、不改速度大小，所以不需要频率门 |
| `mixin/client/ClientInputMixin.java` | 穿单板时把 A/D 换算到身体左右轴、丢掉前后分量（**空格不再被拦截**：2026-10-15 起单板也能跳）；**双板把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系**（W/S 沿身体前后走）并清掉横向输入（A/D 只转身体；**前后分量一个字不清** —— W 与 A / D 同按两个都生效，清掉横向也不影响 `sideSign`，它读的是原始 `keyPresses`、不是这里的返回值）；**没穿板原样交回原版**。**越肩视角开着时单板那条整段跳过**（原样交回）：Shoulder Surfing 的 `InputHandler#updateMovementInput` 会把本方法返回的向量再旋一次、`setMoveVector` 写回 `ClientInput#moveVector`（原版消费的是它那份、基准是**相机 yaw**；A/D 还被换成 ≈前后而撞上 `move.x == 0 → Vec2.ZERO` 的清零），所以单板横移改在 `LocalPlayerBoardInputMixin` 做 |
| `mixin/client/LocalPlayerJumpMixin.java` | **客户端 mixin**（`skis.client.mixins.json`）：`LocalPlayer#aiStep` 的 HEAD 调一次 `SkiAir.tryJump`（读 `input.keyPresses.jump()`）—— **必须在 `travel()` 之前**：原版是「这一 tick 写速度 → 这一 tick 位移」，挂在 `END_CLIENT_TICK` 时玩家已经 tick 完，冲量要晚一 tick；配合服务端 `START_SERVER_TICK` 的同 tick 起跳，服务端的 `ClientboundSetEntityMotionPacket` 带的才是起跳速度而不是把它覆盖掉 |
| `mixin/client/LocalPlayerBoardInputMixin.java` | **越肩视角下单板 A / D 的横移基准（只影响越肩开着时）**：注入 `LocalPlayer#modifyInput(Vec2)` 的 **RETURN**（原版 `applyInput` 里最后一次碰移动向量的地方，返回值直接落进 `xxa` / `zza`，之后 `travel()` 才按 `getYRot()` 旋进世界），按 `δ = 身体朝向 − player.getYRot()` 重算 `(xxa, zza)` —— 结果沿板轴左右，与没开越肩时 `ClientInputMixin` 的口径一致。**为什么必须在这儿**：对方模组在 `LocalPlayer#aiStep`（`ClientInput#tick` 之后）读 `getMoveVector()` → 按**相机 yaw** 旋到世界 → `setYRot` 面朝按键方向 → `setMoveVector` 写回，`ClientInputMixin` 的返回值会被它再旋一次、且 A / D 在玩家坐标系里变成 ≈`(0, ±1)` 被清零（「越肩下单板 A/D 完全没反应」的根因，`javap` 对方 jar 的 `InputHandler#updateMovementInput` 可核）。`ShoulderSurfingCompat.isActive()` 为假、非单板、创造飞行 / 旁观时整段不执行；幅度取原版算出的模长，只有被抹成 0 时才退回 0.98 |
| `client/SkiViewSmoothing.java` | **视角的每帧记账 + 帧间插值（两块板同一套）**：在 `Camera#alignWithEntity` 的 HEAD 每帧跑一次，把**锁定的身体朝向按 `partialTick` 插值**，再用「插值后的朝向 + `headOffset`」写一次视角。**第三人称不限位、第一人称夹 ±90°**：`headOffset` 每帧 `Mth.wrapDegrees` 折回 (−180°, 180°]，第一人称再按 `Minecraft#options.getCameraType().isFirstPerson()` 夹到 ±`SnowboardControl.HEAD_MODEL_LIMIT_DEGREES`（**与头模型同一个常量、值只此一处**；接手那一帧也走同一份夹，切视角首帧不跳变），第三人称完全自由；鼠标入口（`SkiHeadLimitMixin`）只记账、**不撤任何角度**（夹只在 `frame` 里）；`frame` 是**唯一**的每帧写入点；`turnView` 给 `yRot / yRotO / yHeadRot / yHeadRotO` 同量偏移。**为什么必须每帧**：`LocalPlayer#getViewYRot(float)` 不是乘客时直接返回裸 `getYRot()`，本地镜头没有任何帧间插值 |
| `client/SkiState.java` | 客户端动画计时（摆臂倒计时，`IdMap`）；**只有本机玩家的 id 会写进来**（右键触发、无 S2C 包），所以远端玩家不摆臂（模型侧只读本机 avatar） |
| `client/SkiStance.java` | 站姿几何常量（腿/板的横向与前后位置、外撇角）＋ **大腿子部件名 `THIGH_PART`（`"skis_thigh"`）**：`PlayerModelThighMixin` 建、`HumanoidModelMixin` 取，名字必须同一个值；它**不能**放在 mixin 类里（mixin 只允许 private 字段） |
| `client/SnowSpray.java` | 滑行雪粒子：每 tick 从板**两侧**（垂直于滑行方向）`addParticle` —— 贴地雪屑 + 扬起雪屑（都随速度变多，后者随速度抛得更高）+ **一 tick 掉速超过 `BURST_DROP` 时炸一团雪屑**（急刹/撞墙/压刃刹车）；只用 `ITEM_SNOWBALL` 一种粒子（不用雾/烟类），纯客户端观感 |
| `client/HumanoidModelMixin.java` | 模型姿态：腿的站姿 + 手臂撑杖姿势。**腿分两段**（`PlayerModelThighMixin` 加的大腿子部件挂在 `leg` 下、`leg.skipDraw = true` 藏原版整条 12 单位的腿，只画 6 单位的大腿），**枢轴在靴口（模型 y=18）**：大腿绕靴口翻 `SQUAT_LEG_TILT`（地面值 -0.26），躯干前压 `SQUAT_BODY_LEAN`（0.16）、头略低 `SQUAT_HEAD_PITCH`（0.10），三个都在 `client/SkiStance`、两板共用；雪鞋（y=18…24）就是竖直的下半段，脚底 / 板面 / 鞋不动；**跳跃那一趟**腿与躯干改取 `SQUAT_LEG_TILT_AIR`（-0.15 ≈ 地面值的 58%）/ `SQUAT_BODY_LEAN_AIR`（0.08 ＝ 地面值的一半），按 `SkiAir.airPoseBlend(id)`（**只有 0 / 1**，只读客户端那张表；判据只有 `AirState#jumpedThisAir` ＝ **这一趟腾空里起过跳**，**不看高度、没有渐变、`flipStarted` 不单独触发**；非跳跃的腾空（滑落 / 掉落）保持地面姿势、**落地那一 tick 立刻回地面值** —— `AIR_POSE_NEAR_GROUND_RANGE` / `AIR_POSE_BLEND_TICKS` / `farGroundTicks` 已删）`Mth.lerp` 过去；实体 id 取自 `AvatarRenderState.id`，非 Avatar 与远端玩家没有状态 → blend 0（按地面姿势，已知限制）；蹲行时躯干 / 头仍保持原版；外层 `leftPants` / `rightPants` 同样只置 `skipDraw`（它们本来就跟着腿的位姿走）；护甲模型（没有大腿子部件）站姿生效时整条腿 `visible = false`、用一个实例字段记住「是我们藏的」才恢复。**双板转向姿势（`AlpineSkiControl.turnLean(id)`，+1 = 全量向左倾 / -1 = 全量向右倾 / 0 = 站姿，中间值按本 tick 转角比例 —— `clamp(step / TURN_LEAN_RATE_REF_DEGREES(4.0), -1, +1)`，转得越急倾得越狠、4.0°/tick 顶满 6° / 15°）**：**上身 + 外侧腿两条** —— ① 上身绕**腰**（模型 y=12）刚性侧倾 `TURN_ROLL_DEGREES` **6°**（`skis$applyUpperBodyRoll`）：`body.zRot` / `head.zRot` 的枢轴其实都在**颈线**、头与手臂是那两个部件的**兄弟**，所以按 `T = W − R·W` 把 `body.x/y`、`head.x/y`、两支手臂的 `x/y` 一起补齐 —— 只写 `body.zRot` 会让头不倒、髋往反方向甩；`poseStack` 一个都不碰（整人 + 板一起转是单板那条路，双板那么干板与雪鞋会往地里缴）。② **外侧腿整条绕髋侧倒** `TURN_LEG_TILT_DEGREES` **15°**（`legTiltRadians` 的符号定哪条腿在外面）：腿写 `zRot`、该腿的大腿前屈乘 `legBendScale`（1 − 系数，全倒时收到 0 ⇒ 膝口没有拐点）、`poseThigh` 的 `zRot` 仍恒 0（大腿绕靴口那一转是另一个枢轴）；雪鞋与板由 `AlpineSkiModel#setLegTilt` 用同一个角转，三截共线、髋（y=12）不移位；内侧腿与系数 0 时逐字不变。蹲行时整段不写；模型实例是共用的，位姿字段靠 `skis$upperBodyRolled` 记住「是我们搬的」才在回 0 时精确还原，腿的 `zRot` 在非站姿分支也写 0 |
| `client/AlpineSkiModel.java` / `SnowboardModel.java` / `BoardModel.java` | 脚上板的外观（层定义 + 贴图）＋两块板**逐字相同**的骨架 `client/BoardModel.java`（abstract，`BOARD_TOP` 23.0、雪鞋盒子 `addBox(-2,18,-2,4,6,4)` + `texOffs(0,0)` + z 0.05、`LayerDefinition.create(mesh,128,32)`、`entityCutout`）：两个模型只留各自的板身 / 板头；单板整组绕 Y 转 90° 横在脚下。**双板的 `ski_<side>` 组枢轴在髋 `HIP_Y` = 12**（cube 的 y 统一减 12：雪鞋 18→6、板面 23→11、翘头 22.3→10.3，静止时世界位置逐字不变），于是 `setLegTilt` 写的 `zRot` 与玩家模型那条腿的 `zRot` 是**同一个绕髋变换**（转向时外侧腿 + 雪鞋 + 板共线）；单板那条一个数都没动 |
| `client/BoardArmorRenderer.java` | Fabric `ArmorRenderer`：把板模型渲染到脚部。`collector.submitModel` **只是排队**（26.2 的 `ModelFeatureRenderer` 之后才按 state 调 `setupAnim` + `renderToBuffer`），所以双板的外侧腿侧倒写在 `AlpineSkiModel#setupAnim` 里、不在这里改部件（在这里改会被刷队列时别的实体的姿势覆盖） |
| `mixin/client/PlayerModelThighMixin.java` | **腿的两段化（建模阶段）**：`PlayerModel#createMesh`（static，PLAYER 与 PLAYER_SLIM 都走它）的 **RETURN** 处给 `left_leg` / `right_leg` 各挂一个子部件 `skis_thigh`（名字取自 `SkiStance.THIGH_PART`，**不在 mixin 里定义常量**：mixin 只允许 private 字段）（`PartPose.offset(0, 6, 0)` ＝ 靴口，往下 6 单位补回髋到靴口那一段）。UV 取原版腿 / 裤腿的同一 `texOffs`：4×6×4 的侧面正好是 4×12 原腿侧面图的上 6 行，朝靴口那端面读到的鞋底像素埋在雪鞋里看不见 —— 所以**就是原版皮肤的上半段**，不是另画的贴图。`HumanoidModelMixin` 靠 `skipDraw` 藏原版整条腿、只画它 |
| `mixin/client/EntityTiltMixin.java` | 单板 W / S 时在 `LivingEntityRenderer#setupRotations` 的 TAIL 转整个 poseStack —— 人与板一起倾（只改模型部件的话板不跟）；后空翻也在这里绕「脚底往上 `SkiAir.FLIP_PIVOT_HEIGHT`（1.0 格）」转，**该常量已从本 mixin 挪到 `SkiAir`**，第一人称相机（`CameraFlipMixin`）引用同一个值 |
| `mixin/client/CameraTiltMixin.java` | 单板 W / S 时把相机**位置**挂到倾斜后的头上：`alignWithEntity` 里 `setPosition`(**两个重载都要拦**) 加绕脚底旋转后的眼高偏移；视线朝向不动。**两个输入都按 `partialTick` 帧间插值**（B-1 / B-1b）：倾斜 `tiltValue(id, partialTick)` ＝ `Mth.lerp(pt, TILT_PREV_*, TILT_VALUE_*)`，偏移方向 `Mth.rotLerp(pt, yBodyRotO, yBodyRot)` —— 不插值时相机注视点每 tick 跳 ≤0.117 格（满速 carve 16°/tick）。只读，不改模型 / 手感 |
| `mixin/client/CameraFlipMixin.java` | **第一人称相机随空翻翻滚（位置也跟）**：`Camera#alignWithEntity(F)V` 的 **TAIL** 再 `setRotation(yRot, xRot − flip)`，**并把位置绕 `SkiAir.FLIP_PIVOT_HEIGHT`（1.0 格，脚底往上）转过去（跟模型同一条圆弧）** —— 模型绕腰翻时头画在半径 `1.62 − 1.0 = 0.62` 的圆上，相机留在竖直眼高那点原地转、翻到 180° 就会看见自己后背。`flip = SkiAir.angleDegrees(id, partialTick)`（与第三人称 `EntityTiltMixin` 同一套符号：`Axis.XP.rotationDegrees(flip)` 正角＝向后翻，相机 `xRot` 正＝俯视，所以减）；刚体旋转的局部轴用**身体朝向** `Mth.rotLerp(pt, yBodyRotO, yBodyRot)`（模型的翻转发生在 `180 − bodyRot` 之后的局部系，局部 +z＝背后；用视角偏航会在歪头时把圆弧转错平面），枢轴取插值后的脚底点 + `FLIP_PIVOT_HEIGHT`。只在第一人称 + 穿本模组的板 + 角度非 0 时动手，读的是 `CameraTiltMixin` 已经写好的 `position()`，所以是「先倾斜修正、再把整份偏移一起旋过去」；第三人称那条链一点不动 |
| `mixin/client/CameraViewSmoothingMixin.java` | 每帧在 `Camera#alignWithEntity(F)V` 的 HEAD 调 `SkiViewSmoothing.frame`（相机读视角朝向前的最后一刻，`partialTick` 与相机随后用的是同一个）—— 只对「相机跟的就是本地玩家」生效 |
| `mixin/client/SkiHeadLimitMixin.java` | **鼠标转视角的记账点（两块板同一套）**：HEAD 记下转之前的 yaw，TAIL 把这一帧真正转过的角度加进 `SkiViewSmoothing.headOffset`（这里**不夹、不撤任何角度**，只折回整圈 —— 第一人称那个 ±90° 的夹在 `SkiViewSmoothing#frame` 里做）。若第三人称下 ±90° 的**头模型**夹见 `SnowboardControl.headModelOffset`（两块板共用） |
| `mixin/client/EntityStepSoundMixin.java` + `PlayerStepSoundMixin.java` | 穿板时**静音脚步**：掐 `Entity#vibrationAndSoundEffectsFromBlock` 里对 `walkingStepSound` 的调用（这样连 PresenceFootsteps 挂在同一处的钩子一起哑），再从 `Player#playStepSound` 兜一层 |
| `client/GlideAudioGate.java` | **三条音效共用的判据**：`isBoarded`（脚上是我们的板）、`isBoardedOnSnow`（再加雪 / 冰面，落地闷响与免摔伤同口径）、`isGliding(minSpeed, groundRange)`（再加有速度 + 脚底离地门槛，滑行切雪声用）；脚步 / 落地 mixin 与 `GlideSounds` 只留自己的注入点与播放逻辑 |
| `mixin/client/LivingEntityFallSoundMixin.java` | 穿板摔在雪 / 冰面上：`LivingEntity#causeFallDamage` **HEAD 直接取消**（那声落地闷响 + 伤害都在这里；只在 `hurtServer` 免伤拦不住声音）+ `LivingEntity#playBlockFallSound` 兜一层 |
| `mixin/client/GameRendererBobMixin.java` | 穿板时**取消走路镜头晃动**（`GameRenderer#bobView`） |
| `client/SnowSpray.java` | 滑行雪粒子（贴地 + 扬起 + 急减速炸一团），只用 `ITEM_SNOWBALL`；触发看「脚底离地 1.5 格内」而不是 `onGround`；离相机 32 格外的玩家**连雪面扫描都不跑**（`CULL_DISTANCE_SQR`，与 `spawn` 的 `addParticle` 同值），换世界整表清（`forgetAll`） |
| `client/GlideSounds.java` | 滑行音效：按速度调的间隔播切雪声（`SNOW_STEP`），音量/音高随速度；**无风声**（原先那条 `ELYTRA_FLYING` 循环已删）；**暂停时只清自己的计时表 `silence()`** —— 不调 `SoundManager#stop(SNOW_STEP, PLAYERS)`，那对（音效, 声道）是**全局**键，会连场上别人的雪地脚步一起掐掉；离相机 32 格外不推进计时（听不见，省掉每 tick 的一次射线） |
| `client/SkisClient.java` | 客户端入口：注册模型层、渲染器、tick 回调；脱板 / 退出世界时清 `SkiViewSmoothing` 的限位状态；**换世界 / 重连 / 换维度的唯一清理点** `resetWorldState()`（每 tick 比对 `ClientLevel` 实例 ＋ `ClientPlayConnectionEvents.JOIN`；清雪粒子四张表、摆臂倒计时、两块板的朝向 / 姿态 / 压刃、`SkiAir` 客户端表、视角限位 —— 这些表只在「实体被 tick 到且在滑」时维护，不清就会带着上一维度的速度 / 贴地状态跨维度）；**越肩视角下跳过客户端的 `turnTowardView`**（W 不再让身体追相机；另：**A / D 有输入时也跳过** —— W 与 A / D 同按两个都生效）并把 `yHeadRot` 写成「双板 = 身体朝向 / 单板 = 身体朝向 + `SnowboardControl.headOffset(id)`」（方向随滑行方向左右：`headSide` ＝ `-boardSide`，往左滑脸正对左、往右滑正对右；幅度固定 `SNOWBOARD_HEAD_DEGREES` 90°，不再随 W / S 变；由同类的 `tickHead` 平滑推进 —— 换边那 180° 经 0° 扫过去；只写本地玩家，`yHeadRotO` 不动、交给原版插值）；**非越肩（原版第三人称）时给两块板的头模型上夹**：`yHeadRot = 身体朝向 + SnowboardControl.headModelOffset(视 yaw − 身体朝向)` —— **无状态**：每 tick 现算、先 `Mth.wrapDegrees` 折叠再夹 ±90（`HEAD_MODEL_LIMIT_DEGREES`），不读 `SkiViewSmoothing` 的跨 tick 偏角（旧版读那个连续累加量，转多圈后永久冻在 135，上一轮已修）；骑乘 / 睡眠时整段不写、交回原版；**双板转向侧倾**由同类的 `tickAlpineLean` 每 tick 推一步（只推本机，且**排在 `tickMotion` 之后**读转过完的 yaw：`AlpineSkiControl.tickTurnLean(id, AlpineSkiControl.bodyYaw(id, yRot, true))` —— 增量由 `AlpineSkiControl` 自己与 `TURN_LEAN_LAST_YAW` 比，过死区 0.25° 就取方向；**不看按键、不要求贴地**，空中按 A / D 与按 W 追视角、视角带动身体转都会倾；没穿板 / 飞行 / 旁观 / 换成单板时直接 `clearTurnLean`，它连 yaw 基准一起清） |
| `client/ShoulderSurfingCompat.java` | **越肩视角探测（无编译依赖）**：反射 `Class.forName("com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing")` → `getInstance()` → `isShoulderSurfing()`；类 / 方法句柄只解析一次（任一步抛异常就永久 `false`，没装该模组时行为与不兼容时完全一致），但 `isShoulderSurfing()` 每次现问（玩家随时开关的活状态）。为真时：`SkiViewSmoothing.frame` 与 `SkiHeadLimitMixin` 不写视角、不限位，单板 A / D 的横移基准改由 `LocalPlayerBoardInputMixin` 在 `modifyInput` 里按相机自由转动后的 `getYRot()` 重算（`ClientInputMixin` 那条老路在越肩下会被对方改写 `moveVector` 冲掉）|
| `client/FirstPersonModelCompat.java` | **First Person Model 兼容（无编译依赖）**：对方在渲染期把玩家实体按**世界坐标**搬一个 offset（`WorldRendererMixin` → `LogicHandler.updatePositionOffset`，直加玩家的 `rawPosition` / `xo,yo,zo`，画完再撤），而我们的空翻是**绕腰转**（`EntityTiltMixin` / `CameraFlipMixin`）—— 那个 offset 是按**未翻转**朝向算的，翻到 ≈90° 时相对身体错开整个翻转角，身体被搬进第一人称镜头（0° / 180° 恰好对得上，所以只在翻的中段出现）。**减不掉**（世界坐标直加、内部算式不可见），所以改走对方的公开 API：启动时把 `dev.tr7zw.firstperson.api.ActivationHandler` 的一个 `Proxy`（`Proxy.newProxyInstance`，用**接口自己的 ClassLoader**）`add` 进 `FirstPersonAPI#getActivationHandlers()` 返回的 `List`，`preventFirstperson()` 返回「本地玩家正在空翻」（`SkiAir.isFlipping(player.getId())`，没有本地玩家＝false）—— **翻的那几帧对方不画第一人称身体**。`SkisClient.onInitializeClient()` 末尾调一次 `install()`（先 `FabricLoader.isModLoaded("firstperson")`，不在就完全不碰）；类 / 方法名按 `javap` 全名逐字写死，任何一步抛异常（类不在 / 接口变了 / List 不可改）只记**一条** `LOGGER.warn` 然后永久停用这条路，**绝不每帧抛异常**，对方不在时与不兼容时行为完全一致 |
| `mixin/client/compat/VividMoveAnimationsMixin.java` + `VividMoveAnimationControllerMixin.java` + `SkisVividMoveMixinPlugin.java` | **Vivid Move（灵动）兼容（无编译依赖、整套可选）**：独立 mixin 配置 `skis.vivid_move.mixins.json`，`SkisVividMoveMixinPlugin` 用 `FabricLoader.isModLoaded("vivid_move")` 决定参不参与（没装就彻底不碰目标类）。目标都是字符串 `targets` + `remap = false`：① `VividMoveAnimations.playerAnimationsEnabled(AbstractClientPlayer)` HEAD 返回 `false`（下蹲混合 / 走路腿部 IK / 落地压缩 / 手臂物品变换 / 盔甲缩放那一层）；② `VividMoveAnimations$PhaseAwarePlayerAnimationController.isActive()` HEAD 返回 `false`（PAL 骨骼动画层本身 —— 门闸①不覆盖它，`AnimationStack.setupAnim/get3DTransform` 只处理 active 层，`tick` 仍走帧所以脱板不跳帧）。判定都是 `SkisMod.isSkiFeet(脚部装备)`；玩家为 null / 字段或方法改名时静默失效、不抛异常 |
| `SwingHooks.java` | main→client 的动画钩子（源集方向限制，见下） |

- 穿板时 `Attributes.STEP_HEIGHT` 设为 **1.0**（原版玩家默认 0.6），脱板写回**进入穿板状态前那个 base 值**（不是写死 0.6）—— 26.2 里**没有** `setMaxUpStep`，台阶高度是属性；`setBaseValue` 是覆盖式写入，每 tick 都写会永久抹掉别的模组 / 药水对它的修改，所以只在**进出穿板状态各写一次**。
- ⚠️ **MC 里 yaw 增大是「往右转」**（yaw=0 朝 +z，yaw=90 朝 -x）：单板 carve 的侧压符号按这条写（`turn = -side` 前倾 / `side` 后倾）。
- 双板需求拆成两件独立的事：① **视角不影响模型左右朝向** ② **A/D 要转模型朝向**。做法就是 `ensureLocked` + `turnBody` + `SkisClient` 写 `yBodyRot`；`desiredDirection` 对双板只认 W/S（沿身体前方），**不要**把 A/D 当横向分量（那样就成了“只摆滑行方向、模型不动”）。
- 双板：**转视角只转视角**（第三人称不限位、第一人称夹 ±90°，只跟鼠标；第三人称下 ±90° 只是头模型的夹），板的方向＝锁定朝向（`AlpineSkiControl.BODY_YAW_CLIENT` / `_SERVER`），由 A/D（`AlpineSkiControl.turnBody`，4.0°/tick）与**按 W 时的朝视角追**（`AlpineSkiControl.turnTowardView`，4.8°/tick，限幅不越过）改；`GlideSteering.desiredDirection` 用 `AlpineSkiControl.bodyYaw` 而不是玩家 yaw，并且忽略 A/D 的横移分量。
- **光改逻辑方向不够**：模型的朝向来自 `yBodyRot`，双板不写它就会被原版拉着跟视角转 —— `SkisClient` 的每 tick 锁朝向必须对**单板 + 双板**都执行（判断用 `SkisMod.isSkiFeet(player.getItemBySlot(FEET))`，`isSkiFeet` 收的是 ItemStack 不是 Player），但**只写本机玩家**的 `yBodyRot` / `yBodyRotO`：远端玩家的这两个字段由服务端同步，客户端每 tick 覆盖是白做，双板那句「连 `yBodyRotO` 一起对齐」还会把远端的转动插值抹平。
- 单板：只用 W/S（没按 A/D）时，转向目标是「身体前方 / 后方」（按当前滑行方向就近摆），**不是**固定左转 / 右转 —— 否则往左滑按 W 朝前、往右滑按 W 朝后。
- 双板：**W 前进 / 按住 S 且还在往前滑＝刹车（不拨方向）、静止或倒滑时才沿身体后方后退 ＋ A 左 / D 右只转身体**（W 与 A / D **同按两个都生效**：W 照旧前进、A / D 照旧转身体）（用户 2026-10-17 定；刹车 `AlpineSkiControl.BRAKE_DECAY` 0.92、判定 `BRAKE_MIN_ALIGN` 0.3）：转向由 `AlpineSkiControl.turnBody` 每 tick 转 `TURN_RATE_DEGREES` 2.0°（A 让身体 yaw 减小、D 让 yaw 增大），**服务端 `SkiGlide` 与客户端 `SkisClient` 各跑一步、写进 `yBodyRot / yBodyRotO`，A/D 不直接动视角（`yRot`）**（视角不限位、也不被身体拖着走）；**按 W 时身体还会朝视角方向追**（`AlpineSkiControl.turnTowardView`，`VIEW_TURN_RATE_DEGREES` 4.8°/tick，限幅不越过目标、残余 < 0.5° 停；顺序是先 A/D 再 W，S 不触发），两端同一条规则各跑一次；**A / D 有输入的那一 tick 两端都不追视角**（追视角 4.8°/tick 大于 A / D 的 4.0°/tick，一起写同一张 `BODY_YAW` 会抵消甚至反向 —— 这就是「按着 W 时 D 不起作用」的根因；输入不抑制：W 照旧前进；闸门在 `SkisClient` / `SkiGlide` 两个调用点，口径见 `turnTowardView` 的 javadoc） —— **转的始终是身体，视角在世界坐标里不动**（由 `SkiViewSmoothing` 的 `headOffset -= bodyStep` 自然收敛）；`ClientInputMixin` 清掉横向分量、把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系（W/S 沿身体前后）；服务端 `GlideSteering.desiredDirection` 只把速度方向按**身体前方**摆过去（地上摆、空中不摆），按 S 刹车时返回 `null` 交给 `SkiGlide` 的刹车分支。**落地后另有一条快的**：`AlpineSkiControl.shouldAlignOnGround` + `alignVelocity`（`ALIGN_DEGREES_PER_TICK` 9°/tick，单板落地回正也拿它当基准步长）把速度方向快速朝身体前方掰（① 贴地 ② 没按 S —— input 为 null 放行 ③ 水平速度平方 > 1.0E-4；只改方向、大小不变、`hurtMarked = true`），对齐后 `SkiGlide` 重读 `motion / speed` 再往下走。
- **本地镜头没有帧间插值**：`Entity#getViewYRot(float)` 本来是 `Mth.rotLerp(partial, yRotO, yRot)`，但 `LocalPlayer#getViewYRot(float)` 在不是乘客时`return getYRot()`（丢 partialTick），而 `Camera#alignWithEntity` 用的就是它 —— 所以「视角类」的写入必须按帧做（`client/SkiViewSmoothing` + `mixin/client/CameraViewSmoothingMixin`），按 tick 写就是 20 Hz 一档。鼠标之所以顺，是因为 `MouseHandler.handleAccumulatedMovement`（每帧，在 `Minecraft#runTick` 里）→ `LocalPlayer#turn` 每帧都在改 yRot，且 `Entity#turn` 给 `yRot` 与 `yRotO` **同时**加同一增量 —— 我们自己的写入也要照这个来（`SkiViewSmoothing.turnView`，单板双板的限位补正都走它）。**限位只有一处状态**（`SkiViewSmoothing.headOffset`）：鼠标入口只改这个标量（不夹）、相机入口每帧写一次 —— **第三人称两块板都不限位、第一人称两块板都夹 ±90°**（与头模型同一个 `HEAD_MODEL_LIMIT_DEGREES`，夹在 `frame` 的 `headOffset` 上），别再加第二处「按身体朝向夹 yRot」。

## 代码结构：单板与双板彻底分开

| 归属 | 类 | 内容 |
|---|---|---|
| **单板** | `SnowboardControl` | 锁定朝向表 `BODY_YAW`、carve（`applyCarve`/`rotateVelocity`）、俯仰 `tilt*`、**头模型夹 ±90°**（`HEAD_MODEL_LIMIT_DEGREES`；视角第三人称不限位、第一人称夹同一个 ±90°，与双板共用 `client/SkiViewSmoothing` 的记账 / 每帧写视角这套实现）、**单板专属数值**（`FALL_GAIN_PER_BLOCK` 0.15 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998 / `CARVE_MOMENTUM_DECAY` 1.0 / `DESCENT_STEP_BLOCKS` 0.5） |
| **双板** | `AlpineSkiControl` | **双板专属数值**：`FALL_GAIN_PER_BLOCK` 0.1 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998 / `BRAKE_DECAY` 0.92 / `BRAKE_MIN_ALIGN` 0.3 ＋ **身体朝向锁表 `BODY_YAW` 与转向 `TURN_RATE_DEGREES` 2.0°/tick**（`ensureLocked` / `turnBody` / `forgetBody`）＋ **落地对齐 `ALIGN_DEGREES_PER_TICK` 9°/tick**（`shouldAlignOnGround` / `alignVelocity`，贴地且没按 S 时把速度方向快速朝身体前方掰）＋ **视角：第三人称不限位、第一人称夹 ±90°**（常量复用 `SnowboardControl.HEAD_MODEL_LIMIT_DEGREES`）＋ **W 朝视角转向 `turnTowardView`（`VIEW_TURN_RATE_DEGREES` 4.8°/tick）**；按住 S 且还在往前滑＝刹车（`BRAKE_DECAY`，不拨方向） |
| 平台（共用骨架，不含手感数值） | `SkiGlide`（tick 循环）、`GlideSteering`（原版移动辅助）、`registry/ModItems`（物品）、`SkiBoost`（杖） | 两边各走各的分支，各取各的参数 |

- **唯一的交叉点**是分类谓词 `SnowboardControl.isSnowboard(entity)`（读脚上穿的是哪块板）—— 纯判断、无状态，不会把一边的手感带进另一边。改双板只动 `AlpineSkiControl`，改单板只动 `SnowboardControl`。

## 约定与坑

以下每条都是这个工程实际踩过的（含报错原文与定位法）：

- 26.x **没有 yarn 映射**，核 API 签名要对官方 Mojang 映射 `javap`
- 物品注册必须 `Item.Properties.setId`；资源要 `items/`（物品定义）+ `models/`（模型）双层
- `src/main` 看不到 `src/client`（依赖方向 client → main），跨侧行为要走 `SwingHooks` 这类中转钩子
- 持续施加速度后要置 `player.hurtMarked = true`（起跳这类「本 tick 就要生效」的冲量再补 `needsSync = true`），否则服务端不同步、客户端会和你抢速度；**起跳还必须在实体 tick 之前写**（客户端 `LocalPlayer#aiStep` 的 HEAD、服务端 `START_SERVER_TICK`），挂在 `END_CLIENT_TICK` 会被服务端 `ClientboundSetEntityMotionPacket`（`Entity#lerpMotion` 整份覆盖 `deltaMovement`）下一 tick 抹掉
- 肢体 `zRot` 符号：-x 侧肢体正 zRot = 末端向外；搞反会让垂在身侧的长物品内倾穿模
- 渲染状态每帧重建，**不要**把跨帧状态挂在它上面（用静态表 + 实体 id）
- **改完 Mixin 要自己跑一次启动自检**：注入目标写错＝启动即崩（本项目被 `Camera#setup` 这个不存在的方法名坑过一次）。用 PCL 导出的启动脚本 bat 拉真实客户端，看 `logs/latest.log` 出现 `[skis] … 已加载` + `Sound engine started`、且无 `MixinApplyError` 才算过。
- **carve（转向）必须服务端 + 客户端各跑一遍同一套转向规则**：客户端管相机/模型（它有自己的朝向表）、服务端管力（下落加速、carve 前冲的方向），两边只靠“读同一份输入、算同一个 `applyCarve`”保持同步；只在一侧转会让模型与受力方向分家。
- **单板不能有任何“自动推力”**：排查“莫名往前”要同时看三条 —— `glidePush` 雪面推进、下落加速的方向兜底（没输入时用身体前方＝静止的人自己往前滑，应返回 `null`＝不加力）、动量把玩家走路/落地残留的速度一直托住。
- **下落加速的方向要跟着玩家的横向输入**：单板静止/低速下落时先用 `sideSign(按键)` → `sideLocked()`（往左滑就加左边），没按才退到 `frontLocked()`（身体前方）。只写“身体前方”会被用户判为“下落增加的速度是向前，不对”。
- **相机的倾斜偏移方向必须用「锁定的身体朝向」`frontLocked()`，不能用 `player.getYRot()`（视角 yaw）**：用视角的话玩家一转视野偏移就跟着转，相机绕人转圈、甩到身体外面去（用户原话：“转动轴没有跟着移动…视野跑偏到整个身体之外”）。倾斜是身体的事，方向基准只能是不动的那个。
- **相机要“整块挂到倾斜后的头上”＝位置 + 朝向都改，而且 `setPosition` 有两个重载**：普通（第一人称/第三人称）走 `setPosition(double, double, double)`，**只有骑矿车分支**才走 `setPosition(Vec3)` —— 只拦 Vec3 版等于完全没改，症状是“低头看见自己的后背”（First-person Model 渲染的身体已经倾了）。三个 double 参数各自 `@ModifyArg` 取 index 0/1/2，再加一个 Vec3 版兜底骑乘；朝向用 `setRotation(FF)` 的 index 1。
- **26.2 里相机算位置的方法叫 `alignWithEntity`（private，被 `update` 调用），没有 `setup`**：jump 到旧版本记忆里的方法名会直接崩在启动阶段。
- **「相机跟着头走」= 改相机的位置，不是朝向**：人绕脚底倾斜后眼睛从竖直的 `(0, 眼高, 0)` 转成 `(前移 眼高·sinθ, 眼高·cosθ)`，而原版相机的眼高永远竖直 —— 在 `Camera#setup` 里 `@ModifyArg` 它调 `setPosition(Vec3)` 的那个参数，加上旋转后的偏移即可（`Camera.entity` 是私有字段且无 getter，得 `@Shadow`）。
- **「整个人 + 脚上的板一起动」的姿态必须在渲染 poseStack 层做**：板是 Fabric `ArmorRenderer` 独立提交的模型，只改 `HumanoidModel` 的部件它不会跟。`LivingEntityRenderer#setupRotations` 的 TAIL 是安全注入点（poseStack 原点＝实体脚底、朝向已转好，模型与所有 RenderLayer 共用它）
- **GUI 里只显示模型 y 0~16**：长物品（杖/枪）在物品栏会被裁掉下半截；用 `display_context` 分流一个 2D 图标模型是正解，**长模型自己也留一份 GUI 缩放兜底**（select 没命中时不会只剩半截）
- 同一个 mod id 的 jar 不能共存，用 `.jar.disabled` 切换
