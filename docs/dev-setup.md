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
| `SkisMod.java` | **入口**：`onInitialize` 只挂载滑行物理 / 冲刺 / 落地免伤 / 创造页；对外保留 `MOD_ID` / `LOGGER` / `MODERN_SKI` / `SNOWBOARD` / `SKI_POLE` / `isSkiFeet`（转发到 `registry/ModItems`，mixin 与渲染侧十几处按这些名字取物品） |
| `Constants.java` | `MOD_ID`（注册 id 命名空间 / 资源路径前缀 / 日志名） |
| `registry/ModItems.java` | 物品定义与注册：双板 / 单板（脚部装备、护甲 +2、移速 +0.010、耐久 390）、滑雪杖、装备资源 `SKI_EQUIPMENT_ASSET`；款式表 `VARIANTS` 与 `variantStack`；`isSkiFeet` 判定。注册时机仍在类加载时（`SkisMod` 的转发字段一被读就触发） |
| `registry/ModCreativeTabs.java` | 独立创造标签页 `itemGroup.skis`：6 款 × 2 + 1 根杖 |
| `util/IdMap.java` | `实体 id → 值`的缓存表：容量 64、写入超限**按插入序淘汰最旧的一条**（不是整表清空 —— 整表清空是悬崖式降级：所有动量归零、`BODY_YAW` 被重锁、carve 失效）；两块板的朝向 / 姿态、滑行动量、雪粒子与切雪声的上一 tick 状态都用它 |
| `item/SkiPoleItem.java` | 滑雪杖右键行为（客户端摆臂 + 服务端冲刺） |
| `SkiBoost.java` | 冲刺：冲量 + 按 tick 衰减的速度曲线 |
| `SkiGlide.java` | 每 tick 滑行物理：下落加速、**穿板时的重力（`Attributes.GRAVITY` ×倍数：贴地 `GRAVITY_MULTIPLIER`、腾空 `AIR_GRAVITY_MULTIPLIER`，按 `onGround()` 二选一；写 GRAVITY 的地方全类只有两处 —— `applyGravity`（只在换档 / 换板时写一次）与脱板还原 `exitSkiAttributes`）**、低摩擦动量、雪面推进、脚上装备的台阶高度（`STEP_HEIGHT` 1.0，只在**进入穿板状态那一 tick** 写一次，脱板写回**进入前的原值**而不是写死 0.6 —— 每 tick `setBaseValue` 会永久抹掉别的模组 / 药水对属性的修改）；`isOnSnowOrIce` 是「雪面」的唯一定义（滑行 / 雪粒子 / 落地免伤共用） |
| `SnowLanding.java` | 穿板摔在雪 / 冰面上免摔伤：Fabric `ServerLivingEntityEvents.ALLOW_DAMAGE` 事件（挂在 `hurtServer` 上，不需要 mixin），只拦 `DamageTypeTags.IS_FALL` |
| `SkiAir.java` | **起跳力度 / 按住空格的后空翻 / 落地判定的共用状态机**（不依赖客户端类）：每玩家状态（角度、上一 tick 角度、在空中、**本段是否已开翻 `flipStarted`**（自上次落地起一直保持，让空中续翻不等静止段；不能用「角度 ≠ 0」代替，角度是取模的）、连续按住计数、失控计时）等存 `util/IdMap`；`tickClient` 只对本机玩家跑（读跳跃键、数「连续按住」的 tick、满 `FLIP_HOLD_DELAY_TICKS` 才累加角度、跑落地判定并把最终角度回调出去），`tickServer` 只递减失控计时；`isCleanLanding` 是**两端共用的唯一判定**（`applyLanding` 供 C2S 包 `SkiFlip` 调用）；只按 `SnowboardControl.isSnowboard` 选数值、**不含任何一块板的数**；`tryJump` 是**起跳唯一入口**，两端各调一次、都放在**实体 tick 之前**（客户端 `mixin/client/LocalPlayerJumpMixin` 注入 `LocalPlayer#aiStep` 的 HEAD；服务端 `SkiGlide.register` 的 `START_SERVER_TICK`），写完置 `hurtMarked` ＋ `needsSync`；**空中状态按端分开存（客户端 `CLIENT_STATES` / 服务端 `SERVER_STATES` 各一张 `IdMap`，按 `level().isClientSide()` 选表）** —— 单机下两端共用同一 entity id，共用一张表会让计时器每 tick 递减两次、且后调用的那端被冷却拦下；`angleDegrees(id, partialTick)` 给渲染插值读、`isStunned(id)` 给两块板的转向入口跳闸、`isFlipping(id)` 给 `client/FirstPersonModelCompat` 用（渲染期每帧问一次「这个实体这一帧是不是在空翻」＝客户端表里角度 ≠ 0，一次 map 查询、null 安全） |
| `SkisControl.java` | **双板专属数值**（下落基数 0.1 / 单 tick 上限 / 重力倍数 贴地 1.5、腾空 0.9 / 速度上限 0.85 / 动量衰减 0.998 / 刹车 0.92）＋**身体朝向锁表 `BODY_YAW`** 与按键转向 `turnBody`（A / D 每 tick `TURN_RATE_DEGREES` 2.0°，只转身体；地面转向、空中不转）＋ **W 朝视角转向 `turnTowardView`**（`VIEW_TURN_RATE_DEGREES` 2.4°/tick，限幅不越过、残余 < 0.5° 停；同样只转身体）＋**视角限位 `VIEW_LIMIT_DEGREES` 90°**（左右各 90、合计 180；与单板各自一份常量）＋**贴地转向期间速度方向由身体推导 `alignTurnHeading`**（`TURN_HEADING` / `TURN_YAW0` / `TURN_LAST_YAW` 三张表；`SkiGlide` 双板分支里整 tick 最后一个写速度的点，**`!onGround` 直接跳闸**、只转身体；与单板的 `alignCarveHeading` 各一套、互不借用） |
| `SkiControl.java` | 方向控制：读玩家真实按键、把双板速度方向朝身体前方摆（只服务双板、只在地面上；单板不参与方向）＋按板分派的速度上限 |
| `SnowboardControl.java` | 单板状态：锁住并**可被 carve 转动**的身体朝向、俯仰姿态、视角限制、carve 常量；`applyCarve` 由服务端与客户端**各跑一遍同一规则**以保持一致；`guardCarveSpeed` 在 carve 持续期间给水平速度一个「入场速度 × `CARVE_MOMENTUM_DECAY`^tick」的下限（撞方块那一 tick 不补），**转向期间不掉速** —— 由 `SkiGlide` 在动量结算之后调一次（只对单板）；紧接其后还有 `alignCarveHeading`（同样只对单板、是整 tick 最后一个写速度的点）：**速度方向自动回正到板轴** —— 目标量是**板轴**（身体左右轴，`sideLocked`；单板横着滑，板轴就是行进方向），哪一侧由 `boardSide` 定（与头朝向 `headSide` **同一个判定**）；**生效条件**：穿单板 + 不在失控（`SkiAir.isStunned`）+ 水平速度 > 1e-4 + 近地（`onGround()` 或脚底往下 **1.5 格**内有碰撞方块，`SkiGlide.groundDistance`）—— **不要求在 carve 里**，也**没有入弯基准表**（旧 `CARVE_HEADING` / `CARVE_YAW0` 已删）；每 tick 用 `wrapDegrees` 取「板轴方向 − 当前方向」的最短角差，≤ 0.05° 走死区、否则按 `ALIGN_MAX_DEGREES_PER_TICK` 6°/tick **旋转**过去（大小与 y 不动、`hurtMarked`）；**撞墙那一 tick 也照拉**（不再提前返回）；`applyCarve` 那边每 tick 转角由 `TURN_RADIUS_BLOCKS` 8 格算出（`toDegrees(vTravel / 8)`，夹 1.0–6.0°/tick），撞墙 tick 也照转速度；`alignCarveHeading` 与 `applyCarve` 一样**服务端与客户端各跑一遍同一份实现**（签名 `alignCarveHeading(Entity, Input)`：服务端 `SkiGlide` 传 `player.getLastClientInput()`，客户端 `client/SkisClient` 的 `END_CLIENT_TICK` 里 `applyCarve` 之后**只对本机玩家**传 `input.keyPresses`）—— 因为玩家位置是**客户端权威**（`Player#isClientAuthoritative()`），只靠服务端发包（`hurtMarked` → `ClientboundSetEntityMotionPacket`，仅误差 > 死区才发）在无误差时不会触发 |
| `mixin/client/ClientInputMixin.java` | 穿单板时把 A/D 换算到身体左右轴、丢掉前后分量（**空格不再被拦截**：2026-10-15 起单板也能跳）；**双板把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系**（W/S 沿身体前后走）并清掉横向输入（A/D 只转身体）；**没穿板原样交回原版** |
| `mixin/client/LocalPlayerJumpMixin.java` | **客户端 mixin**（`skis.client.mixins.json`）：`LocalPlayer#aiStep` 的 HEAD 调一次 `SkiAir.tryJump`（读 `input.keyPresses.jump()`）—— **必须在 `travel()` 之前**：原版是「这一 tick 写速度 → 这一 tick 位移」，挂在 `END_CLIENT_TICK` 时玩家已经 tick 完，冲量要晚一 tick；配合服务端 `START_SERVER_TICK` 的同 tick 起跳，服务端的 `ClientboundSetEntityMotionPacket` 带的才是起跳速度而不是把它覆盖掉 |
| `client/SkiViewSmoothing.java` | **单板「身体拖视角」的每帧插值 + `turnView` 工具**：在 `Camera#alignWithEntity` 的 HEAD 每帧跑一次，把**锁定的身体朝向按 `partialTick` 插值**、用插值后的朝向把视角夹回各自限位（**双板 90° / 单板 135°**，`limit(snowboard)` 从两块板各自的常量取）；`renderBodyYaw` 供 `SnowboardHeadLimitMixin` 共用同一基准；`turnView` 给 `yRot / yRotO / yHeadRot / yHeadRotO` 同量偏移。**单板双板共用**：限位状态只有一个 `headOffset`，鼠标入口（`SkiHeadLimitMixin`）只按 `headOffset` 记账并撤掉越界那点，`frame` 是**唯一**的每帧写入点（目标 = 插值后的身体朝向 + `headOffset`），所以不会出现「两处各夹一次、基准差一帧互相拉回」。**为什么必须每帧**：`LocalPlayer#getViewYRot(float)` 不是乘客时直接返回裸 `getYRot()`，本地镜头没有任何帧间插值 |
| `client/SkiState.java` | 客户端动画计时（摆臂倒计时，`IdMap`）；**只有本机玩家的 id 会写进来**（右键触发、无 S2C 包），所以远端玩家不摆臂（模型侧只读本机 avatar） |
| `client/SkiStance.java` | 站姿几何常量（腿/板的横向与前后位置、外撇角）＋ **大腿子部件名 `THIGH_PART`（`"skis_thigh"`）**：`PlayerModelThighMixin` 建、`HumanoidModelMixin` 取，名字必须同一个值；它**不能**放在 mixin 类里（mixin 只允许 private 字段） |
| `client/SnowSpray.java` | 滑行雪粒子：每 tick 从板**两侧**（垂直于滑行方向）`addParticle` —— 贴地雪屑 + 扬起雪屑（都随速度变多，后者随速度抛得更高）+ **一 tick 掉速超过 `BURST_DROP` 时炸一团雪屑**（急刹/撞墙/压刃刹车）；只用 `ITEM_SNOWBALL` 一种粒子（不用雾/烟类），纯客户端观感 |
| `client/HumanoidModelMixin.java` | 模型姿态：腿的站姿 + 手臂撑杖姿势。**腿分两段**（`PlayerModelThighMixin` 加的大腿子部件挂在 `leg` 下、`leg.skipDraw = true` 藏原版整条 12 单位的腿，只画 6 单位的大腿），**枢轴在靴口（模型 y=18）**：大腿绕靴口翻 `SQUAT_LEG_TILT`，雪鞋（y=18…24）就是竖直的下半段，脚底 / 板面 / 鞋不动；外层 `leftPants` / `rightPants` 同样只置 `skipDraw`（它们本来就跟着腿的位姿走）；护甲模型（没有大腿子部件）站姿生效时整条腿 `visible = false`、用一个实例字段记住「是我们藏的」才恢复 |
| `client/SkiBoardModel.java` / `SnowboardModel.java` | 脚上板的外观（层定义 + 贴图）；单板整组绕 Y 转 90° 横在脚下 |
| `client/BoardArmorRenderer.java` | Fabric `ArmorRenderer`：把板模型渲染到脚部 |
| `mixin/client/PlayerModelThighMixin.java` | **腿的两段化（建模阶段）**：`PlayerModel#createMesh`（static，PLAYER 与 PLAYER_SLIM 都走它）的 **RETURN** 处给 `left_leg` / `right_leg` 各挂一个子部件 `skis_thigh`（名字取自 `SkiStance.THIGH_PART`，**不在 mixin 里定义常量**：mixin 只允许 private 字段）（`PartPose.offset(0, 6, 0)` ＝ 靴口，往下 6 单位补回髋到靴口那一段）。UV 取原版腿 / 裤腿的同一 `texOffs`：4×6×4 的侧面正好是 4×12 原腿侧面图的上 6 行，朝靴口那端面读到的鞋底像素埋在雪鞋里看不见 —— 所以**就是原版皮肤的上半段**，不是另画的贴图。`HumanoidModelMixin` 靠 `skipDraw` 藏原版整条腿、只画它 |
| `mixin/client/EntityTiltMixin.java` | 单板 W / S 时在 `LivingEntityRenderer#setupRotations` 的 TAIL 转整个 poseStack —— 人与板一起倾（只改模型部件的话板不跟）；后空翻也在这里绕「脚底往上 `SkiAir.FLIP_PIVOT_HEIGHT`（1.0 格）」转，**该常量已从本 mixin 挪到 `SkiAir`**，第一人称相机（`CameraFlipMixin`）引用同一个值 |
| `mixin/client/CameraTiltMixin.java` | 单板 W / S 时把相机**位置**挂到倾斜后的头上：`alignWithEntity` 里 `setPosition`(**两个重载都要拦**) 加绕脚底旋转后的眼高偏移；视线朝向不动 |
| `mixin/client/CameraFlipMixin.java` | **第一人称相机随空翻翻滚（位置也跟）**：`Camera#alignWithEntity(F)V` 的 **TAIL** 再 `setRotation(yRot, xRot − flip)`，**并把位置绕 `SkiAir.FLIP_PIVOT_HEIGHT`（1.0 格，脚底往上）转过去（跟模型同一条圆弧）** —— 模型绕腰翻时头画在半径 `1.62 − 1.0 = 0.62` 的圆上，相机留在竖直眼高那点原地转、翻到 180° 就会看见自己后背。`flip = SkiAir.angleDegrees(id, partialTick)`（与第三人称 `EntityTiltMixin` 同一套符号：`Axis.XP.rotationDegrees(flip)` 正角＝向后翻，相机 `xRot` 正＝俯视，所以减）；刚体旋转的局部轴用**身体朝向** `Mth.rotLerp(pt, yBodyRotO, yBodyRot)`（模型的翻转发生在 `180 − bodyRot` 之后的局部系，局部 +z＝背后；用视角偏航会在歪头时把圆弧转错平面），枢轴取插值后的脚底点 + `FLIP_PIVOT_HEIGHT`。只在第一人称 + 穿本模组的板 + 角度非 0 时动手，读的是 `CameraTiltMixin` 已经写好的 `position()`，所以是「先倾斜修正、再把整份偏移一起旋过去」；第三人称那条链一点不动 |
| `mixin/client/CameraViewSmoothingMixin.java` | 每帧在 `Camera#alignWithEntity(F)V` 的 HEAD 调 `SkiViewSmoothing.frame`（相机读视角朝向前的最后一刻，`partialTick` 与相机随后用的是同一个）—— 只对「相机跟的就是本地玩家」生效 |
| `mixin/client/SkiHeadLimitMixin.java` | 单板 / 双板都在 `Entity#turn` 上记账：HEAD 记下转之前的 yaw，TAIL 把这一帧真正转过的角度加进 `SkiViewSmoothing.headOffset` 并夹到 ±`VIEW_LIMIT_DEGREES`，只撤掉越界那点（**不再**用身体朝向当基准把 yRot 另夹一遍 —— 那会和每帧那次差一帧互相拉回） |
| `mixin/client/EntityStepSoundMixin.java` + `PlayerStepSoundMixin.java` | 穿板时**静音脚步**：掐 `Entity#vibrationAndSoundEffectsFromBlock` 里对 `walkingStepSound` 的调用（这样连 PresenceFootsteps 挂在同一处的钩子一起哑），再从 `Player#playStepSound` 兜一层 |
| `client/GlideAudioGate.java` | **三条音效共用的判据**：`isBoarded`（脚上是我们的板）、`isBoardedOnSnow`（再加雪 / 冰面，落地闷响与免摔伤同口径）、`isGliding(minSpeed, groundRange)`（再加有速度 + 脚底离地门槛，滑行切雪声用）；脚步 / 落地 mixin 与 `GlideSounds` 只留自己的注入点与播放逻辑 |
| `mixin/client/LivingEntityFallSoundMixin.java` | 穿板摔在雪 / 冰面上：`LivingEntity#causeFallDamage` **HEAD 直接取消**（那声落地闷响 + 伤害都在这里；只在 `hurtServer` 免伤拦不住声音）+ `LivingEntity#playBlockFallSound` 兜一层 |
| `mixin/client/GameRendererBobMixin.java` | 穿板时**取消走路镜头晃动**（`GameRenderer#bobView`） |
| `client/SnowSpray.java` | 滑行雪粒子（贴地 + 扬起 + 急减速炸一团），只用 `ITEM_SNOWBALL`；触发看「脚底离地 1.5 格内」而不是 `onGround`；离相机 32 格外的玩家**连雪面扫描都不跑**（`CULL_DISTANCE_SQR`，与 `spawn` 的 `addParticle` 同值），换世界整表清（`forgetAll`） |
| `client/GlideSounds.java` | 滑行音效：按速度调的间隔播切雪声（`SNOW_STEP`），音量/音高随速度；**无风声**（原先那条 `ELYTRA_FLYING` 循环已删）；**暂停时只清自己的计时表 `silence()`** —— 不调 `SoundManager#stop(SNOW_STEP, PLAYERS)`，那对（音效, 声道）是**全局**键，会连场上别人的雪地脚步一起掐掉；离相机 32 格外不推进计时（听不见，省掉每 tick 的一次射线） |
| `client/SkisClient.java` | 客户端入口：注册模型层、渲染器、tick 回调；脱板 / 退出世界时清 `SkiViewSmoothing` 的限位状态；**换世界 / 重连 / 换维度的唯一清理点** `resetWorldState()`（每 tick 比对 `ClientLevel` 实例 ＋ `ClientPlayConnectionEvents.JOIN`；清雪粒子四张表、摆臂倒计时、两块板的朝向 / 姿态 / 压刃、`SkiAir` 客户端表、视角限位 —— 这些表只在「实体被 tick 到且在滑」时维护，不清就会带着上一维度的速度 / 贴地状态跨维度）；**越肩视角下跳过客户端的 `turnTowardView`**（W 不再让身体追相机）并把 `yHeadRot` 写成「双板 = 身体朝向 / 单板 = 身体朝向 + `SnowboardControl.headOffset(id)`」（带符号、随横移方向，幅度 75 / 前倾 90 / 后倾 60，由同类的 `tickHead` 平滑推进；只写本地玩家，`yHeadRotO` 不动、交给原版插值） |
| `client/ShoulderSurfingCompat.java` | **越肩视角探测（无编译依赖）**：反射 `Class.forName("com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing")` → `getInstance()` → `isShoulderSurfing()`；类 / 方法句柄只解析一次（任一步抛异常就永久 `false`，没装该模组时行为与不兼容时完全一致），但 `isShoulderSurfing()` 每次现问（玩家随时开关的活状态）。为真时：`SkiViewSmoothing.frame` 与 `SkiHeadLimitMixin` 不写视角、不限位 |
| `client/FirstPersonModelCompat.java` | **First Person Model 兼容（无编译依赖）**：对方在渲染期把玩家实体按**世界坐标**搬一个 offset（`WorldRendererMixin` → `LogicHandler.updatePositionOffset`，直加玩家的 `rawPosition` / `xo,yo,zo`，画完再撤），而我们的空翻是**绕腰转**（`EntityTiltMixin` / `CameraFlipMixin`）—— 那个 offset 是按**未翻转**朝向算的，翻到 ≈90° 时相对身体错开整个翻转角，身体被搬进第一人称镜头（0° / 180° 恰好对得上，所以只在翻的中段出现）。**减不掉**（世界坐标直加、内部算式不可见），所以改走对方的公开 API：启动时把 `dev.tr7zw.firstperson.api.ActivationHandler` 的一个 `Proxy`（`Proxy.newProxyInstance`，用**接口自己的 ClassLoader**）`add` 进 `FirstPersonAPI#getActivationHandlers()` 返回的 `List`，`preventFirstperson()` 返回「本地玩家正在空翻」（`SkiAir.isFlipping(player.getId())`，没有本地玩家＝false）—— **翻的那几帧对方不画第一人称身体**。`SkisClient.onInitializeClient()` 末尾调一次 `install()`（先 `FabricLoader.isModLoaded("firstperson")`，不在就完全不碰）；类 / 方法名按 `javap` 全名逐字写死，任何一步抛异常（类不在 / 接口变了 / List 不可改）只记**一条** `LOGGER.warn` 然后永久停用这条路，**绝不每帧抛异常**，对方不在时与不兼容时行为完全一致 |
| `mixin/client/compat/VividMoveAnimationsMixin.java` + `VividMoveAnimationControllerMixin.java` + `SkisVividMoveMixinPlugin.java` | **Vivid Move（灵动）兼容（无编译依赖、整套可选）**：独立 mixin 配置 `skis.vivid_move.mixins.json`，`SkisVividMoveMixinPlugin` 用 `FabricLoader.isModLoaded("vivid_move")` 决定参不参与（没装就彻底不碰目标类）。目标都是字符串 `targets` + `remap = false`：① `VividMoveAnimations.playerAnimationsEnabled(AbstractClientPlayer)` HEAD 返回 `false`（下蹲混合 / 走路腿部 IK / 落地压缩 / 手臂物品变换 / 盔甲缩放那一层）；② `VividMoveAnimations$PhaseAwarePlayerAnimationController.isActive()` HEAD 返回 `false`（PAL 骨骼动画层本身 —— 门闸①不覆盖它，`AnimationStack.setupAnim/get3DTransform` 只处理 active 层，`tick` 仍走帧所以脱板不跳帧）。判定都是 `SkisMod.isSkiFeet(脚部装备)`；玩家为 null / 字段或方法改名时静默失效、不抛异常 |
| `SwingHooks.java` | main→client 的动画钩子（源集方向限制，见下） |

- 穿板时 `Attributes.STEP_HEIGHT` 设为 **1.0**（原版玩家默认 0.6），脱板写回**进入穿板状态前那个 base 值**（不是写死 0.6）—— 26.2 里**没有** `setMaxUpStep`，台阶高度是属性；`setBaseValue` 是覆盖式写入，每 tick 都写会永久抹掉别的模组 / 药水对它的修改，所以只在**进出穿板状态各写一次**。
- ⚠️ **MC 里 yaw 增大是「往右转」**（yaw=0 朝 +z，yaw=90 朝 -x）：单板 carve 的侧压符号按这条写（`turn = -side` 前倾 / `side` 后倾）。
- 双板需求拆成两件独立的事：① **视角不影响模型左右朝向** ② **A/D 要转模型朝向**。做法就是 `ensureLocked` + `turnBody` + `SkisClient` 写 `yBodyRot`；`desiredDirection` 对双板只认 W/S（沿身体前方），**不要**把 A/D 当横向分量（那样就成了“只摆滑行方向、模型不动”）。
- 双板：**转视角只转视角**（限在锁定身体朝向 ±90° 内），板的方向＝锁定朝向（`SkisControl.BODY_YAW`），由 A/D（`SkisControl.turnBody`，2.0°/tick）与**按 W 时的朝视角追**（`SkisControl.turnTowardView`，2.4°/tick，限幅不越过）改；`SkiControl.desiredDirection` 用 `SkisControl.bodyYaw` 而不是玩家 yaw，并且忽略 A/D 的横移分量。
- **光改逻辑方向不够**：模型的朝向来自 `yBodyRot`，双板不写它就会被原版拉着跟视角转 —— `SkisClient` 的每 tick 锁朝向必须对**单板 + 双板**都执行（判断用 `SkisMod.isSkiFeet(player.getItemBySlot(FEET))`，`isSkiFeet` 收的是 ItemStack 不是 Player），但**只写本机玩家**的 `yBodyRot` / `yBodyRotO`：远端玩家的这两个字段由服务端同步，客户端每 tick 覆盖是白做，双板那句「连 `yBodyRotO` 一起对齐」还会把远端的转动插值抹平。
- 单板：只用 W/S（没按 A/D）时，转向目标是「身体前方 / 后方」（按当前滑行方向就近摆），**不是**固定左转 / 右转 —— 否则往左滑按 W 朝前、往右滑按 W 朝后。
- 双板：**W 前进 / 按住 S 且还在往前滑＝刹车（不拨方向）、静止或倒滑时才沿身体后方后退 ＋ A 左 / D 右只转身体**（用户 2026-10-17 定；刹车 `SkisControl.BRAKE_DECAY` 0.92、判定 `BRAKE_MIN_ALIGN` 0.3）：转向由 `SkisControl.turnBody` 每 tick 转 `TURN_RATE_DEGREES` 2.0°（A 让身体 yaw 减小、D 让 yaw 增大），**服务端 `SkiGlide` 与客户端 `SkisClient` 各跑一步、写进 `yBodyRot / yBodyRotO`，A/D 不直接动视角（`yRot`）**（只有顶到 ±90° 限位后视角才被身体拖着走）；**按 W 时身体还会朝视角方向追**（`SkisControl.turnTowardView`，`VIEW_TURN_RATE_DEGREES` 2.4°/tick，限幅不越过目标、残余 < 0.5° 停；顺序是先 A/D 再 W，S 不触发），两端同一条规则各跑一次 —— **转的始终是身体，视角在世界坐标里不动**（由 `SkiViewSmoothing` 的 `headOffset -= bodyStep` 自然收敛）；`ClientInputMixin` 清掉横向分量、把前后分量按 `δ = 身体朝向 − 视角` 旋到视角坐标系（W/S 沿身体前后）；服务端 `SkiControl.desiredDirection` 只把速度方向按**身体前方**摆过去（地上摆、空中不摆），按 S 刹车时返回 `null` 交给 `SkiGlide` 的刹车分支。**落地后另有一条快的**：`SkisControl.shouldAlignOnGround` + `alignVelocity`（`ALIGN_DEGREES_PER_TICK` 9°/tick）把速度方向快速朝身体前方掰（① 贴地 ② 没按 S —— input 为 null 放行 ③ 水平速度平方 > 1.0E-4；只改方向、大小不变、`hurtMarked = true`），对齐后 `SkiGlide` 重读 `motion / speed` 再往下走。
- **本地镜头没有帧间插值**：`Entity#getViewYRot(float)` 本来是 `Mth.rotLerp(partial, yRotO, yRot)`，但 `LocalPlayer#getViewYRot(float)` 在不是乘客时`return getYRot()`（丢 partialTick），而 `Camera#alignWithEntity` 用的就是它 —— 所以「视角类」的写入必须按帧做（`client/SkiViewSmoothing` + `mixin/client/CameraViewSmoothingMixin`），按 tick 写就是 20 Hz 一档。鼠标之所以顺，是因为 `MouseHandler.handleAccumulatedMovement`（每帧，在 `Minecraft#runTick` 里）→ `LocalPlayer#turn` 每帧都在改 yRot，且 `Entity#turn` 给 `yRot` 与 `yRotO` **同时**加同一增量 —— 我们自己的写入也要照这个来（`SkiViewSmoothing.turnView`，单板双板的限位补正都走它）。**限位只有一处权威状态**（`SkiViewSmoothing.headOffset`）：鼠标入口只改这个标量并撤掉越界那点，相机入口每帧写一次 —— 别再加第二处「按身体朝向夹 yRot」，两处基准差一帧就会在极限处一卡一抽。

## 代码结构：单板与双板彻底分开

| 归属 | 类 | 内容 |
|---|---|---|
| **单板** | `SnowboardControl` | 锁定朝向表 `BODY_YAW`、carve（`applyCarve`/`rotateVelocity`）、俯仰 `tilt*`、视角限制常量（±135°，实现与双板共用 `client/SkiViewSmoothing`）、**单板专属数值**（`FALL_GAIN_PER_BLOCK` 0.15 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998 / `CARVE_MOMENTUM_DECAY` 1.0 / `DESCENT_STEP_BLOCKS` 0.5） |
| **双板** | `SkisControl` | **双板专属数值**：`FALL_GAIN_PER_BLOCK` 0.1 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998 / `BRAKE_DECAY` 0.92 / `BRAKE_MIN_ALIGN` 0.3 ＋ **身体朝向锁表 `BODY_YAW` 与转向 `TURN_RATE_DEGREES` 2.0°/tick**（`ensureLocked` / `turnBody` / `forgetBody`）＋ **落地对齐 `ALIGN_DEGREES_PER_TICK` 9°/tick**（`shouldAlignOnGround` / `alignVelocity`，贴地且没按 S 时把速度方向快速朝身体前方掰）＋ **视角限位 `VIEW_LIMIT_DEGREES` 90°**（左右各 90、合计 180；与单板各自一份常量）＋ **W 朝视角转向 `turnTowardView`（`VIEW_TURN_RATE_DEGREES` 2.4°/tick）**；按住 S 且还在往前滑＝刹车（`BRAKE_DECAY`，不拨方向） |
| 平台（共用骨架，不含手感数值） | `SkiGlide`（tick 循环）、`SkiControl`（原版移动辅助）、`registry/ModItems`（物品）、`SkiBoost`（杖） | 两边各走各的分支，各取各的参数 |

- **唯一的交叉点**是分类谓词 `SnowboardControl.isSnowboard(entity)`（读脚上穿的是哪块板）—— 纯判断、无状态，不会把一边的手感带进另一边。改双板只动 `SkisControl`，改单板只动 `SnowboardControl`。

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
