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

## 架构速览

| 文件 | 职责 |
|---|---|
| `SkisMod.java` | 物品注册、独立创造标签页（`itemGroup.skis`）、装备资源；`isModernSki` / `isSkiFeet` 判定 |
| `item/SkiPoleItem.java` | 滑雪杖右键行为（客户端摆臂 + 服务端冲刺） |
| `SkiBoost.java` | 冲刺：冲量 + 按 tick 衰减的速度曲线 |
| `SkiGlide.java` | 每 tick 滑行物理：下落加速、低摩擦动量、雪面推进、脚上装备的台阶高度；`isOnSnowOrIce` 是「雪面」的唯一定义（滑行 / 雪粒子 / 落地免伤共用） |
| `SnowLanding.java` | 穿板摔在雪 / 冰面上免摔伤：Fabric `ServerLivingEntityEvents.ALLOW_DAMAGE` 事件（挂在 `hurtServer` 上，不需要 mixin），只拦 `DamageTypeTags.IS_FALL` |
| `SkisControl.java` | **双板专属数值**（下落基数 / 单 tick 上限 / 速度上限 1.4 / 动量衰减 0.998）＋双板按键转向；不持有朝向状态（视角即朝向） |
| `SkiControl.java` | 方向控制：读玩家真实按键、离地高度判定、速度上限（单板不参与方向） |
| `SnowboardControl.java` | 单板状态：锁住并**可被 carve 转动**的身体朝向、俯仰姿态、视角限制、carve 常量；`applyCarve` 由服务端与客户端**各跑一遍同一规则**以保持一致 |
| `mixin/client/ClientInputMixin.java` | 穿单板时把 A/D 换算到身体左右轴、丢掉前后分量、取消 `makeJump` |
| `client/SkiState.java` | 客户端动画计时（摆臂倒计时） |
| `client/SkiStance.java` | 站姿几何常量（腿/板的横向与前后位置、外撇角） |
| `client/SnowSpray.java` | 滑行雪粒子：每 tick 从板**两侧**（垂直于滑行方向）`addParticle` —— 贴地雪屑 + 扬起雪屑（都随速度变多，后者随速度抛得更高）+ **一 tick 掉速超过 `BURST_DROP` 时炸一团雪屑**（急刹/撞墙/压刃刹车）；只用 `ITEM_SNOWBALL` 一种粒子（不用雾/烟类），纯客户端观感 |
| `client/HumanoidModelMixin.java` | 模型姿态：腿的站姿 + 手臂撑杖姿势 |
| `client/SkiBoardModel.java` / `SnowboardModel.java` | 脚上板的外观（层定义 + 贴图）；单板整组绕 Y 转 90° 横在脚下 |
| `client/BoardArmorRenderer.java` | Fabric `ArmorRenderer`：把板模型渲染到脚部 |
| `mixin/client/EntityTiltMixin.java` | 单板 W / S 时在 `LivingEntityRenderer#setupRotations` 的 TAIL 转整个 poseStack —— 人与板一起倾（只改模型部件的话板不跟） |
| `mixin/client/CameraTiltMixin.java` | 单板 W / S 时把相机**位置**挂到倾斜后的头上：`alignWithEntity` 里 `setPosition`(**两个重载都要拦**) 加绕脚底旋转后的眼高偏移；视线朝向不动 |
| `mixin/client/SnowboardHeadLimitMixin.java` | 单板时在 `Entity#turn` 末尾把视角 yaw 夹到「锁定身体朝向 ± `VIEW_LIMIT_DEGREES`」 |
| `mixin/client/EntityStepSoundMixin.java` + `PlayerStepSoundMixin.java` | 穿板时**静音脚步**：掐 `Entity#vibrationAndSoundEffectsFromBlock` 里对 `walkingStepSound` 的调用（这样连 PresenceFootsteps 挂在同一处的钩子一起哑），再从 `Player#playStepSound` 兜一层 |
| `mixin/client/LivingEntityFallSoundMixin.java` | 穿板摔在雪 / 冰面上：`LivingEntity#causeFallDamage` **HEAD 直接取消**（那声落地闷响 + 伤害都在这里；只在 `hurtServer` 免伤拦不住声音）+ `LivingEntity#playBlockFallSound` 兜一层 |
| `mixin/client/GameRendererBobMixin.java` | 穿板时**取消走路镜头晃动**（`GameRenderer#bobView`） |
| `client/SnowSpray.java` | 滑行雪粒子（贴地 + 扬起 + 急减速炸一团），只用 `ITEM_SNOWBALL`；触发看「脚底离地一格内」而不是 `onGround` |
| `client/GlideSounds.java` | 滑行音效：按速度调的间隔播切雪声（`SNOW_STEP`），音量/音高随速度；**无风声**（原先那条 `ELYTRA_FLYING` 循环已删）；**暂停时主动 `silence()`**（声音引擎暂停时不再推进游戏内声音，不主动停会一直响） |
| `client/SkisClient.java` | 客户端入口：注册模型层、渲染器、tick 回调 |
| `SwingHooks.java` | main→client 的动画钩子（源集方向限制，见下） |

- 穿板时 `Attributes.STEP_HEIGHT` 设为 **1.0**（原版玩家默认 0.6），脱板恢复 0.6 —— 26.2 里**没有** `setMaxUpStep`，台阶高度是属性。
- ⚠️ **MC 里 yaw 增大是「往右转」**（yaw=0 朝 +z，yaw=90 朝 -x）：单板 carve 的侧压符号按这条写（`turn = -side` 前倾 / `side` 后倾）。
- 双板需求拆成两件独立的事：① **视角不影响模型左右朝向** ② **A/D 要转模型朝向**。做法就是 `ensureLocked` + `steerSkis`(turnBody) + `SkisClient` 写 `yBodyRot`；`desiredDirection` 对双板只认 W/S（沿锁定朝向），**不要**把 A/D 当横向分量（那样就成了“只摆滑行方向、模型不动”）。
- 双板：**转视角只转视角**，板的方向＝锁定朝向，只有 A/D 改它（`SnowboardControl.turnBody`）；`SkiControl.desiredDirection` 对双板用 `frontLocked` 而不是玩家 yaw，并且忽略 A/D 的横移分量。
- **光改逻辑方向不够**：模型的朝向来自 `yBodyRot`，双板不写它就会被原版拉着跟视角转 —— `SkisClient` 的每 tick 锁朝向必须对**单板 + 双板**都执行（判断用 `SkisMod.isSkiFeet(player.getItemBySlot(FEET))`，`isSkiFeet` 收的是 ItemStack 不是 Player）。
- 单板：只用 W/S（没按 A/D）时，转向目标是「身体前方 / 后方」（按当前滑行方向就近摆），**不是**固定左转 / 右转 —— 否则往左滑按 W 朝前、往右滑按 W 朝后。
- 双板：**按 S ＝ 向后转向，不是刹车**（用户 2026-10-13 定）：S 只把滑行方向往后摆（`SkiControl.desiredDirection` 的按键转向，`STEER_RATE` 逐渐摆过去），动量照常托住速度。原来的 `BRAKE_DECAY` 0.90 已删 —— 高速按 S 会变成急停，与「向后转向」冲突。

## 代码结构：单板与双板彻底分开

| 归属 | 类 | 内容 |
|---|---|---|
| **单板** | `SnowboardControl` | 锁定朝向表 `BODY_YAW`、carve（`applyCarve`/`rotateVelocity`）、俯仰 `tilt*`、视角限制（±135°）、**单板专属数值**（`FALL_GAIN_PER_BLOCK` 0.15 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998 / `CARVE_MOMENTUM_DECAY` 0.999 / `DESCENT_STEP_BLOCKS` 0.5） |
| **双板** | `SkisControl` | **双板专属数值**：`FALL_GAIN_PER_BLOCK` 0.12 / `FALL_GAIN_PER_TICK_MAX` 0.3 / `MOMENTUM_DECAY` 0.998。不持有朝向状态（视角即朝向）；按 S 是转向不是刹车（没有刹车常量） |
| 平台（共用骨架，不含手感数值） | `SkiGlide`（tick 循环）、`SkiControl`（原版移动辅助）、`SkisMod`（物品）、`SkiBoost`（杖） | 两边各走各的分支，各取各的参数 |

- **唯一的交叉点**是分类谓词 `SnowboardControl.isSnowboard(entity)`（读脚上穿的是哪块板）—— 纯判断、无状态，不会把一边的手感带进另一边。改双板只动 `SkisControl`，改单板只动 `SnowboardControl`。

## 约定与坑

以下每条都是这个工程实际踩过的（含报错原文与定位法）：

- 26.x **没有 yarn 映射**，核 API 签名要对官方 Mojang 映射 `javap`
- 物品注册必须 `Item.Properties.setId`；资源要 `items/`（物品定义）+ `models/`（模型）双层
- `src/main` 看不到 `src/client`（依赖方向 client → main），跨侧行为要走 `SwingHooks` 这类中转钩子
- 持续施加速度后要置 `player.hurtMarked = true`，否则服务端不同步、客户端会和你抢速度
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
