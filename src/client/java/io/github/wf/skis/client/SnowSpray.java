package io.github.wf.skis.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SnowboardControl;
import io.github.wf.skis.util.IdMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * <b>滑行雪粒子</b>：穿着滑雪板在雪面上滑时扬起的雪，纯客户端观感（见 {@link #spawn}），
 * 服务端不参与、也不影响任何速度 —— 速度只决定粒子，粒子不决定速度。
 *
 * <p><b>初速只能经 {@code ParticleEngine#createParticle} + {@code Particle#setParticleSpeed} 给</b>：
 * {@code ITEM_SNOWBALL} 的 provider（{@code BreakingItemParticle$SnowballProvider}）在
 * {@code createParticle(...)} 里**根本不读**后三个 `xSpeed/ySpeed/zSpeed` 参数（字节码只读 x/y/z），
 * 直接 `new BreakingItemParticle(level, x, y, z, sprite)`，而那个构造器把速度写成随机 ±0.1。
 * 所以走 {@code ClientLevel#addParticle} 传初速**永远无效**；必须拿回 {@link Particle} 再硬写一次速度。
 *
 * <p><b>只用 {@link #SNOW} 一种粒子</b>：{@code ITEM_SNOWBALL}＝雪球崩碎的小白屑（雪地就该是这种实心碎屑）。
 * 模组里**不用**任何雾 / 烟 / 云类粒子（`SNOWFLAKE`、`POOF`、`CLOUD` 这些是一团白雾，和「板刃切起来的碎屑」不是一回事）。
 *
 * <p><b>离地门槛是距离，不是格数</b>（{@link #AIR_RANGE}）：脚底到雪面<b>上表面</b>的距离 ≤ 1.5 格就扬雪。
 * 距离由 {@link #snowSurface} 用「方块坐标 + 该方块形状的最高点」算（1 层雪 = 0.125、满方块 = 1.0），
 * 所以 1 层雪不会被当成整整一格。这条门槛**不再依赖** {@code player.blockPosition()} 正好落在某一格，
 * 也不再依赖「雪块就在脚下一两格」这个假设 —— 前几版正是靠这两条，所以人一离地就整段失效。
 *
 * <p>三层效果，数量都随速度涨（低速也保底每侧 {@link #SPRAY_MIN_PER_SIDE} / {@link #FLURRY_MIN_PER_SIDE} 颗）：
 * <ul>
 *   <li><b>贴地雪屑</b>：从板的两侧向外、略向上溅，顶点多半在 0.3 格以内；</li>
 *   <li><b>扬起雪屑</b>：每颗抽一个<b>随速度增长</b>的顶点高度（{@link #randomApex}：速度 0.10 时
 *       0.20–0.40 格贴着地面，0.85 时 1.00–2.30 格胸到头），初速由 {@link #riseFor} 按离散粒子物理反解；
 *       按 {@link #FLURRY_HIGH_FRACTION} 拆两档在空中形成层次；高飞档寿命抬到 {@link #FLURRY_HIGH_LIFETIME}；
 *       转向（一 tick 转过 {@link #TURN_DEGREES_PER_TICK} 度以上）时这一层更多、再抬高 {@link #FLURRY_APEX_TURN_BONUS} 格
 *       （<b>只在没有按键偏向时才两侧都给</b>，见下面的受力侧一段）；</li>
 *   <li><b>急减速雪屑</b>（{@link #burst}）：一 tick 内速度掉了 {@link #BURST_DROP} 以上时，
 *       从脚下一圈炸开一大团雪屑（急刹车 / 撞墙 / 压刃刹车都是这个形状），粒子数随掉落量增加。
 *       <b>只在真贴地时才算急刹</b>：上一 tick 与本 tick 都贴地（{@link #GROUND_TOUCH_TOLERANCE}）
 *       且垂直速度小（{@link #FALL_TOLERANCE}）—— 落地那一 tick 水平速度也会突降，但那是「跳下来」不是「刹车」，
 *       不加这三道闸门时从一两格高落地也会炸一大团。</li>
 * </ul>
 *
 * <p><b>横向不再固定成两条线、也不再往回抖</b>：每颗粒子自己抽横向落点（{@link #LATERAL_MIN}–{@link #LATERAL_MAX}）与
 * 横向速度（{@link #OUTWARD_MIN}–{@link #OUTWARD_MAX}，再叠 {@link #OUTWARD_JITTER} 的<b>只朝外</b>抖动），
 * 高速再按速度把横向速度扇开（{@code fan}）—— 一片雾，不是两条平行线；而且左侧的雪一定朝左、右侧一定朝右
 * （抖动只放大外抛量，落点与速度恒同号），全速率下都不存在「一半朝内」抵消。
 * <b>贴地层与扬起层各一套参数</b>：贴地层走 {@link #FLECK_LATERAL_MIN}–{@link #FLECK_LATERAL_MAX}（0.20–1.10 格）、
 * {@link #FLECK_OUTWARD_MIN}–{@link #FLECK_OUTWARD_MAX}、{@link #FLECK_OUTWARD_JITTER}、{@link #FLECK_SPREAD}，
 * 区间更外更宽 —— 低速时视野里几乎全是贴地层，原来那套窄区间看着是一撮挤在板边。
 *
 * <p><b>所有粒子的出生点都在脚底那片「雪面」上</b>（{@code surface + Y_OFFSET ± Y_JITTER}），
 * <b>不跟着人身子抬</b> —— 高度层次只靠顶点高度做出来，所以看着是雪从地面被卷起来、抛上去。
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求<b>右</b>向量：末地坐标下朝 +Z 前进时
 * 它是 −X，即玩家<b>右手边</b>，{@code side = +1} 就走这一侧、{@code -1} 是另一侧），落点在这片雪面上，
 * 所以双板与单板共用同一套（单板板身横着，看着就是从切雪的那条边溅起来）。
 *
 * <p><b>受力侧不对称（只有本地玩家的按键是真读的）</b>：
 * <ul>
 *   <li><b>双板 = 弯外侧</b>：按 A（左转，{@code sideSign = +1}）时身体<b>右侧</b>、按 D（{@code -1}）时
 *       <b>左侧</b>那一侧的数量 ×{@link #BIAS_COUNT_MUL}、扬起层顶点 +{@link #BIAS_APEX_BONUS} 格
 *       （所以 {@code biasSide = sideSign(keys)} —— 与 {@code side} 是同一套符号）；</li>
 *   <li><b>单板 = 板端 + 弯外侧（同一套旋钮）</b>：按 W（前倾）在人<b>背后</b>、按 S（后倾）在人<b>正前方</b>，
 *       偏移沿<b>身体朝向</b>（{@code SnowboardControl.frontLocked}）而不是行进方向 —— 单板横着滑，
 *       行进方向几乎与身体正面垂直，拿它当轴那簇雪就拍到侧面去了 —— 偏移 {@link #END_OFFSET} 格，
 *       <b>额外</b>加一簇 {@link #END_EXTRA_PER_TICK}（转向时再加 {@link #TURN_EXTRA_PER_SIDE}）颗扬起级粒子；
 *       <b>同时</b>「本 tick 速度方向转过的那一侧（弯外侧）」也吃 {@link #BIAS_COUNT_MUL} 与
 *       {@link #BIAS_APEX_BONUS}（与双板同一套旋钮）：转角 {@code turnDelta > 0} ＝ 右转 ⇒ 弯心在右 ⇒
 *       外侧是<b>左</b>（{@code side = -1}），{@code turnDelta < 0} 反之；<b>没在转向时两侧仍完全对称</b>；
 *       <b>真在转向时弯内侧一颗都不喷</b>（{@link #SINGLE_SIDE_SPRAY}）：那侧的贴地层与扬起层数量都置 0，
 *       所以单板切弯只看到<b>弯外侧一条雪线</b>（这是「转向时往两边扩散成两条线」的修法）；</li>
 *   <li>其它玩家拿不到输入 → 两个偏向都是 0，左右 / 前后完全对称（与不改时的行为一致）。</li>
 * </ul>
 * <b>转向的对称加成只在「没有任何按键偏向」时才铺到两侧</b>：{@link #TURN_EXTRA_PER_SIDE} 那 4 颗与
 * {@link #FLURRY_APEX_TURN_BONUS} 那 0.35 格，在 {@code biasSide != 0}（双板按着 A / D、单板按着 W / S
 * 且在转向）时<b>只加在受力侧</b>；在 {@code biasEnd != 0}（单板按着 W / S）时<b>两侧不加这一份对称加成</b>，
 * 改由板端那一簇自己吃（否则两侧同时 +4 颗 / +0.35 格，看着就是<b>两边一起变多</b>、偏向被冲淡
 * —— 双板按住 A/D 时落地对齐每 tick 要转好几度，超过 {@link #TURN_DEGREES_PER_TICK}，{@code turning} 全程为真）。
 * 两个偏向都是 0（没按键 / 拿不到按键 / 其它玩家 / 单板没在转向）时与改前完全一致：两侧都给。
 */
public final class SnowSpray {
	/** 唯一的雪粒子：{@code ITEM_SNOWBALL} = 雪球崩碎的白色碎屑（重力 1.0，会落回雪面）。 */
	private static final ParticleOptions SNOW = ParticleTypes.ITEM_SNOWBALL;
	/**
	 * 调试开关（<b>默认关闭</b>）：打开后每 20 个客户端 tick 用 {@code SkisMod.LOGGER} 打一行
	 * {@code [skis] snowspray feetY=.. surface=.. gap=.. speed=.. turning=.. range=..}。
	 *
	 * <p>用来在游戏里一眼分清「离地还有没有扬雪」断在哪一环：{@code surface=NaN} = 门槛（附近 1.5 格内没雪）、
	 * {@code speed} 太小 = 速度门槛、{@code gap} 正常却看不见 = 生成点 / 可见性。静态读代码只能推到这几种可能。
	 */
	private static final boolean DEBUG_LOG = false;
	/** 速度低于这个（格/tick）就不持续扬雪 —— 站着不动不该扬雪（0.06 让「慢慢挪一步」也扬一点）。 */
	private static final double MIN_SPEED = 0.06D;
	/**
	 * <b>离地阈值（格）</b>：脚底到雪面<b>上表面</b>的距离 ≤ 这个值就扬雪。
	 *
	 * <p>按<b>距离</b>算，不按「往下数几格」算：{@code minecraft:snow} 的 1–8 层雪形状高度 = 层数 × 0.125，
	 * 1 层只有 0.125 格 —— 按格数判会把「站在 1 层雪上」当成离地 1 格。这里一律用
	 * {@code 方块 y + 该方块形状最高点} 得到真正的上表面，再和脚底相减。
	 */
	private static final double AIR_RANGE = 1.5D;
	/** 允许脚底陷进雪面这么多格（站在雪层里 / 雪压到腿）仍然算「在雪面上」。 */
	private static final double EMBED_TOLERANCE = 0.35D;
	/**
	 * 雪面扫描深度（<b>方块</b>，从脚底那一格往下数）：{@link #AIR_RANGE} 1.5 格 + 取整余量。
	 *
	 * <p>取 3：脚底在格子最下面（y = k + 0.01）时 1.5 格以下已经落到 k−2 格，3 格深度才盖得住；
	 * 脚底在格子最上面（k + 0.99）时 1.5 格只到 k−1 格，多出来的深度就是留给斜坡与跳起的余量。
	 */
	private static final int SCAN_DEPTH = 3;
	/**
	 * 雪面扫描的<b>水平半径</b>（格）：中心列（脚底那一列）找不到时，再看 3×3 列。
	 *
	 * <p>为什么需要：坡上 / 崖边一跳，雪面往往不在人的正下方而是斜下方一格 ——
	 * 只扫一列会在「离地还不高」时直接判成没有雪可扬。中心列优先（贴地时出生点就压在脚下），
	 * 只有中心列空手时才用邻列。
	 */
	private static final int SCAN_RADIUS = 1;
	/**
	 * 贴地雪屑：每侧数量 = {@link #SPRAY_MIN_PER_SIDE} + (速度 − {@link #MIN_SPEED}) × 这个系数，再夹上限。
	 *
	 * <p>保底 1 颗 + 线性增长：观感是「越滑越密」，低速（刚过门槛）也有雪、只是贴地。
	 */
	private static final double SPRAY_PER_SPEED = 12.0D;
	/** 贴地雪屑的每侧数量上限。 */
	private static final int SPRAY_MAX_PER_SIDE = 8;
	/** 贴地雪屑每侧保底数量（低速也扬，只是贴地）。 */
	private static final int SPRAY_MIN_PER_SIDE = 1;
	/** 扬起雪屑：每侧数量 = {@link #FLURRY_MIN_PER_SIDE} + (速度 − {@link #MIN_SPEED}) × 这个系数（比贴地那层涨得快）。 */
	private static final double FLURRY_PER_SPEED = 20.0D;
	/** 扬起雪屑的每侧数量上限（转向的 {@link #TURN_EXTRA_PER_SIDE} 是加在夹上限之后的；有按键偏向时只加在受力侧）。 */
	private static final int FLURRY_MAX_PER_SIDE = 14;
	/** 扬起雪屑每侧保底数量（低速也扬，顶点只有 0.2–0.4 格，贴着地面）。 */
	private static final int FLURRY_MIN_PER_SIDE = 1;
	/**
	 * <b>顶点高度随速度线性长的两个锚点</b>：速度 {@link #APEX_SPEED_LO}（0.10）= 贴地、{@link #APEX_SPEED_HI}（0.85）= 顶速。
	 *
	 * <p>区间两端各是一条从低速到高速的直线：下沿 {@link #APEX_LOW_FROM}→{@link #APEX_LOW_TO}、
	 * 上沿 {@link #APEX_HIGH_FROM}→{@link #APEX_HIGH_TO}。于是低速时整团雪压在 0.2–0.4 格，
	 * 顶速时铺到 1.0–2.3 格 —— 不再是「一出发就 1.5–3 格」的固定区间。
	 */
	private static final double APEX_SPEED_LO = 0.10D;
	private static final double APEX_SPEED_HI = 0.85D;
	/** 顶点区间下沿：低速 0.20 格 → 顶速 1.00 格。 */
	private static final double APEX_LOW_FROM = 0.20D;
	private static final double APEX_LOW_TO = 1.00D;
	/** 顶点区间上沿：低速 0.40 格（贴地）→ 顶速 2.30 格（头上一截）。 */
	private static final double APEX_HIGH_FROM = 0.40D;
	private static final double APEX_HIGH_TO = 2.30D;
	/**
	 * 转向时顶点再抬高这么多格（转急弯把雪甩到身上）；<b>只有没有按键偏向时才铺到两侧</b>：
	 * 双板按 A / D、以及单板按 W / S 且真在转向时只落在受力侧（弯外侧），单板压着刃但没转向时
	 * 两侧不给、由板端那一簇自己吃（见 {@link #TURN_EXTRA_PER_SIDE}）。
	 */
	private static final double FLURRY_APEX_TURN_BONUS = 0.35D;
	/** 顶点封顶（格）：任何情况下不许超过它（避免又变成雪柱）。 */
	private static final double FLURRY_APEX_CAP = 2.80D;
	/** 扬起层里走高飞档（顶点取区间上半）的比例，其余抽下半 —— 两档混着才有层次。 */
	private static final double FLURRY_HIGH_FRACTION = 0.65D;
	/**
	 * 高飞档雪屑的最低寿命（tick）。原版 {@code Particle} 构造器里
	 * {@code lifetime = (int)(4.0 / (rand*0.9 + 0.1))}＝<b>随机 4–40 tick、均值约 10</b>，
	 * 而顶点 2.3 格要 ≈26 tick 才走完上升 + 回落 —— 默认寿命会让粒子在半空凭空消失。只抬不降。
	 */
	private static final int FLURRY_HIGH_LIFETIME = 34;
	/**
	 * 雪粒子的重力（格/tick²）。原版 {@code Particle.tick} 里是 {@code yd -= 0.04 * gravity}，
	 * 而雪球（{@code BreakingItemParticle} 构造器）的 {@code gravity} 是 1.0 —— 即每 tick −0.04。
	 */
	private static final double PARTICLE_GRAVITY = 0.04D;
	/** 雪粒子的每 tick 阻尼：原版 {@code Particle} 构造器里 {@code friction} 默认 0.98（每 tick 速度 ×0.98）。 */
	private static final double PARTICLE_DRAG = 0.98D;
	/**
	 * 转向时：扬起层每侧再多几颗（加在夹上限之后，所以能超过 {@link #FLURRY_MAX_PER_SIDE}）。
	 *
	 * <p><b>只有完全没有按键偏向时才会铺到两侧</b>（{@code symmetricTurn = turning && biasSide == 0 && biasEnd == 0}）：
	 * <ul>
	 *   <li>双板按着 A / D（{@code biasSide != 0}）→ 这 4 颗只给受力侧，弯里侧保持基础值；</li>
	 *   <li>单板按着 W / S 且在转向（{@code biasSide != 0} 取弯外侧）→ 同样只给受力侧；</li>
	 *   <li>单板按着 W / S 但没在转向（{@code biasEnd != 0} 而 {@code biasSide == 0}）→ 两侧都不加这一份，
	 *       只有板端那一簇照旧多吃这 4 颗（{@link #END_EXTRA_PER_TICK} → 8 + 4 = 12）；</li>
	 *   <li>没按键 / 拿不到按键 / 其它玩家 → 两侧都给（与改前一致）。</li>
	 * </ul>
	 * 否则按着键时两侧同时 +4 颗，看着就是「两边一起变多」、偏向被冲淡。
	 */
	private static final int TURN_EXTRA_PER_SIDE = 4;
	/** 滑行方向这一 tick 转了多少度算「在转向」。 */
	private static final double TURN_DEGREES_PER_TICK = 2.0D;

	// ---------------------------------------------------- 按键不对称（弯外侧 / 板端）
	// 只有**本地玩家**的按键是真读的（见 tick 里 `player == client.player` 那一步）；
	// 其它玩家拿不到输入 → 下面两个「偏向」都是 0，左右 / 前后保持对称（与旧行为一致）。
	/**
	 * <b>单板转向时只喷弯外侧</b>（<b>开关，默认开</b>）：置 {@code true} 时，若本 tick 的
	 * {@code biasSide != 0}（＝单板按着 W / S 且确实在转向）且 {@code snowboard}，则<b>弯内侧那一侧</b>
	 * 的贴地层 {@code fleckCount} 与扬起层 {@code flurryCount} 都置 <b>0</b> —— 视野里只剩弯外侧<b>一条雪线</b>，
	 * 不再左右各一条。弯外侧照旧吃 {@link #BIAS_COUNT_MUL} / {@link #TURN_EXTRA_PER_SIDE} /
	 * {@link #BIAS_APEX_BONUS} / {@link #BIAS_OUTWARD_MUL}。
	 *
	 * <p>只有单板、且只在真转向时生效：<b>双板按 A / D 一行不动</b>（弯外侧加强、内侧保留基础值）；
	 * {@code biasSide == 0}（没在转向 / 拿不到按键 / 其它玩家）时两侧保持完全对称；
	 * 板端那一簇（{@link #END_EXTRA_PER_TICK}，跟着 {@code biasEnd} 走）也不受这个开关影响。
	 *
	 * <p><b>想改回「两侧都喷」的旧行为</b>：这一行置 {@code false} 即可，其它都不用动。
	 */
	private static final boolean SINGLE_SIDE_SPRAY = true;
	/**
	 * <b>「弯外侧」的数量倍率</b>：双板按 A（左转，{@code sideSign = +1}）时<b>身体右侧</b>
	 * （{@code side = +1}）、按 D（{@code -1}）时<b>身体左侧</b>（{@code side = -1}）那一侧的贴地层与扬起层
	 * 数量 × 这个值，弯里侧仍按原公式 —— 切起来的雪本来就该堆在弯外那一侧
	 * （{@code biasSide = sideSign(keys)}；{@code side} 的正负见侧循环：+1 = 右向量那一侧）。
	 *
	 * <p><b>单板同一套旋钮</b>：按着 W / S 且本 tick 速度方向转过
	 * {@link #TURN_DEGREES_PER_TICK} 以上时，受力侧 = 这一 tick 转弯的<b>外侧</b>
	 * （{@code turnDelta > 0} ＝ 右转 ⇒ 弯心在右 ⇒ 外侧是左 {@code = -1}；{@code turnDelta < 0} 反之），
	 * 没在转向时两侧对称。单板转向时<b>弯里侧不是「按原公式」，而是直接归零</b>（{@link #SINGLE_SIDE_SPRAY}：
	 * 两层数量都置 0）—— 只剩弯外侧一条雪线；双板的弯里侧仍是原公式。
	 *
	 * <p><b>调手感</b>：调「弯外侧比内侧厚多少」—— 2.0 ＝ 两倍；1.0 退回完全对称（即旧手感），
	 * 3.0 会一眼看出偏向。只改<b>数量</b>，高度看 {@link #BIAS_APEX_BONUS}。
	 */
	private static final double BIAS_COUNT_MUL = 2.0D;
	/**
	 * <b>受力侧的横向（向外）速度倍率</b>：{@code biasSide} 那一侧（双板 = 弯外侧；单板 = 本 tick 转弯的外侧）
	 * 的<b>贴地层与扬起层</b>横向速度再 × 这个值 —— 与 {@link #BIAS_COUNT_MUL}（数量）、
	 * {@link #BIAS_APEX_BONUS}（顶点高度）成套：雪不但在那一侧更多、更高，还<b>抛得更远</b>。
	 *
	 * <p>只乘横向分量，不动沿行进方向的分量（{@code along} / {@link #BACKWARD}）；
	 * 两侧的横向速度都只朝外（抖动只往正方向加，见 {@link #OUTWARD_JITTER}），所以不会与「朝内抖动」抵消。
	 *
	 * <p><b>调手感</b>：调「弯外侧的雪比内侧冲多远」—— 1.5 ＝ 横向速度多五成；1.0 退回各侧同速。
	 */
	private static final double BIAS_OUTWARD_MUL = 1.5D;
	/**
	 * <b>受力侧（双板弯外侧 / 单板弯外侧与板端那一簇）的顶点加高</b>（格）：该侧扬起层顶点再 + 这个值，
	 * 最后仍夹 {@link #FLURRY_APEX_CAP} —— 加在 {@link #randomApex} 抽完之后。
	 * 转向的 {@link #FLURRY_APEX_TURN_BONUS} 在<b>有按键偏向时只给受力侧</b>，所以两条不会同时叠在一颗上：
	 * 受力侧拿的是这一条、弯里侧拿基础值（没按键时才是两侧都拿转向那条）。
	 *
	 * <p><b>调手感</b>：调「受力侧的雪抛多高」—— 0.80 已经接近胸口；嫌抢眼改 0.5，想要甩得更夸张改 1.2。
	 */
	private static final double BIAS_APEX_BONUS = 0.80D;
	/**
	 * <b>单板「板端」那一簇的出生偏移</b>（格，沿<b>身体朝向</b>）：W（前倾）→ 人物<b>背后</b>
	 * （{@code biasEnd = -1}）、S（后倾）→ 人物<b>正前方</b>（{@code +1}）；
	 * 出生点 = 身体位置 + 身体前方（{@code SnowboardControl.frontLocked}）× {@code biasEnd} × 这个值 ——
	 * 人压着板的那一端，雪就从那一端底下被挤出来。
	 *
	 * <p><b>为什么用身体朝向、不用行进方向</b>：单板是横着滑的（A / D 沿身体左右平移），行进方向几乎与
	 * 身体正面<b>垂直</b>——拿行进方向当「板的前后轴」，那一簇雪会拍到人的侧面去，而不是板头 / 板尾。
	 *
	 * <p><b>调手感</b>：调「那一簇离人多远」—— 太小（&lt;0.5）会藏在脚下看不出，太大（&gt;1.2）会飘到板外；
	 * 0.85 大致在板头 / 板尾的位置。
	 */
	private static final double END_OFFSET = 0.85D;
	/**
	 * <b>板端那一簇沿身体前后轴的位置抖动</b>（格，±）：几颗挤在同一个点上看就是一根雪柱，
	 * 沿板身前后摊开才是一小片。
	 *
	 * <p><b>调手感</b>：调「那簇雪沿板拉多长」—— 0.40 ≈ 板端前后各 0.4 格；改小更聚拢、改大更散。
	 */
	private static final double END_SPREAD = 0.40D;
	/**
	 * <b>板端那一簇的横向落点</b>（格，±，相对身体左右轴）：与扬起层的 {@link #LATERAL_MIN}–{@link #LATERAL_MAX}
	 * 不同，这一簇是<b>围绕身体中线</b>均匀抽 ± 这个值（受力的是板的一端，不是某一侧）。
	 *
	 * <p><b>调手感</b>：调「那簇雪摊多宽」—— 0.55 ≈ 左右各半格；改小更聚、改大更散。
	 */
	private static final double END_LATERAL = 0.55D;
	/**
	 * <b>单板按住 W / S 时每 tick 在板的那一端额外加的粒子数</b>（扬起级）：
	 * 这是<b>额外的一簇</b>，不占 {@link #SPRAY_MAX_PER_SIDE} / {@link #FLURRY_MAX_PER_SIDE} 的配额，
	 * 也不参与 {@code biasSide} 那侧的 {@link #BIAS_COUNT_MUL} 倍率。
	 *
	 * <p>转向时这一簇自己再多吃 {@link #TURN_EXTRA_PER_SIDE} 颗（8 → 12）；两侧的对称转向加成则被收走
	 * （{@code symmetricTurn} 要求 {@code biasEnd == 0}）—— 否则按 W / S 时两侧一起 +4 颗，看着是「两边一起变多」。
	 *
	 * <p><b>调手感</b>：调「板端扬雪有多猛」—— 8 颗/tick 已经是明显的一股；12 更冲、4 只是意思一下。
	 */
	private static final int END_EXTRA_PER_TICK = 8;
	/**
	 * <b>横向落点区间（格）</b>：每颗粒子自己抽一个 {@link #LATERAL_MIN}–{@link #LATERAL_MAX} 的横向偏移，
	 * 再乘左右符号 —— <b>不再是固定的那一个侧向偏移量</b>。
	 *
	 * <p>固定偏移会让每 tick 的粒子精确排在同一对平行线上 —— 连着几 tick 叠起来就是板两侧各一条明显的轨迹。
	 * 改成连续抽 0.10–0.80 之后，单侧横向落点是这个 0.70 格宽区间上的<b>均匀分布</b>
	 * （均值 0.45 格、标准差 0.20 格），两侧合起来是一整片而不是两条线。
	 */
	private static final double LATERAL_MIN = 0.10D;
	private static final double LATERAL_MAX = 0.80D;
	/** 横向（向外）速度区间（格/tick）：抽在这个区间里，再叠 {@link #OUTWARD_JITTER} 的<b>只朝外</b>的抖动。 */
	private static final double OUTWARD_MIN = 0.10D;
	private static final double OUTWARD_MAX = 0.42D;
	/**
	 * 横向速度的抖动（格/tick，<b>只往正方向加</b>：{@code out = side × (base + jitter)}）——
	 * 每颗抽 {@code [0, OUTWARD_JITTER]} 叠在基值上，避免所有雪精确认同一个方向上排开。
	 *
	 * <p><b>为什么不再是 ±</b>：旧的 {@code randomSign × OUTWARD_JITTER} 会让一部分粒子<b>朝内</b>飞，
	 * 与「板左侧的雪往左抛、右侧往右抛」正好抵消一截 —— 看着两侧的雪都往板中间塌。
	 * 现在抖动只放大外抛量，落点（{@code lateral}）与速度恒同号。
	 */
	private static final double OUTWARD_JITTER = 0.06D;
	/** 沿滑行方向的前后抖动（格/tick）：雪屑不排在同一条法线上。 */
	private static final double ALONG_JITTER = 0.10D;
	/** 贴地雪屑的向上分量基准（再乘 0.35–0.85 的随机数：顶点约 0.2–0.9 格，仍然贴地）。 */
	private static final double UPWARD = 0.30D;
	/** 向后（与滑行相反）的分量 —— 雪是往后飞的。 */
	private static final double BACKWARD = 0.16D;
	/** 出生点水平随机散布（格）。 */
	private static final double SPREAD = 0.16D;
	/*
	 * 贴地层（flecks）自己一套横向散开参数 —— **不跟扬起层共用** {@link #LATERAL_MIN}–{@link #LATERAL_MAX}、
	 * {@link #OUTWARD_MIN}–{@link #OUTWARD_MAX}、{@link #OUTWARD_JITTER}、{@link #SPREAD}。
	 *
	 * <p>原因：低速时扬起层只有 1–2 颗、还压在 0.2–0.4 格，视野里几乎全是贴地层；原来那套
	 * 0.10–0.80 的落点 / 0.05–0.30 的向外速度抽出来就是贴着板边的一细条。贴地层要「低速也看得出铺开」，
	 * 所以区间整体外移并加宽（落点 0.20–1.10、向外 0.12–0.45），抖动也加大。
	 * 两边各一套常量，调贴地层不会连带改掉扬起层的手感（扬起层继续用上面那几个）。
	 */
	private static final double FLECK_LATERAL_MIN = 0.20D;
	private static final double FLECK_LATERAL_MAX = 1.10D;
	private static final double FLECK_OUTWARD_MIN = 0.22D;
	private static final double FLECK_OUTWARD_MAX = 0.58D;
	/** 贴地层向外速度的抖动：与扬起层同样<b>只往正方向加</b>（{@code [0, 0.10]}），比扬起层的 0.06 大 —— 雪屑才不是一律同一个外抛速度。 */
	private static final double FLECK_OUTWARD_JITTER = 0.10D;
	/** 贴地层出生点的水平散布（格）：比 {@link #SPREAD} 0.16 宽，同样几颗也不排在一条线上。 */
	private static final double FLECK_SPREAD = 0.22D;
	/** 贴地层纵向初速的倍率区间（乘 {@link #UPWARD}）：低速也拉开高低差，别挤在一个平面上。 */
	private static final double FLECK_UPWARD_MIN = 0.30D;
	private static final double FLECK_UPWARD_MAX = 1.00D;
	/**
	 * 出生点离地高度（格）：所有粒子都贴着它们脚下那片雪面起。
	 *
	 * <p>再加 ±{@link #Y_JITTER} 的随机抖动，避免一整层粒子精确排在同一平面上。
	 */
	private static final double Y_OFFSET = 0.05D;
	/** 出生高度的随机抖动（格，正负各一半）：{@code surface + Y_OFFSET ± Y_JITTER}。 */
	private static final double Y_JITTER = 0.05D;

	/** 一 tick 内速度掉了这么多（格/tick）就算「急减速」—— 0.08 能兜住双板按 S 的持续刹车（每 tick 约掉 10%）。 */
	private static final double BURST_DROP = 0.08D;
	/** 掉落之前至少得有这么快（站着不动凭空掉到 0 不算）。 */
	private static final double BURST_MIN_SPEED = 0.12D;
	/** 一团雪屑的粒子数 = 掉落量 × 这个系数（再夹到上限）。 */
	private static final double BURST_PER_DROP = 450.0D;
	/** 一团雪屑的粒子数上限（急刹那一 tick 的爆发量）。 */
	private static final int BURST_MAX = 160;
	/** 两团雪屑之间的最短间隔（tick）—— 持续刹车时就是「每隔几 tick 炸一团」。 */
	private static final int BURST_COOLDOWN_TICKS = 3;
	/** 雪屑炸开时的上升速度（抛向空中，顶点约 2 格）。 */
	private static final double BURST_UPWARD = 0.5D;
	/** 雪屑炸开时的随机散布半径（格）。 */
	private static final double BURST_RADIUS = 0.75D;
	/**
	 * <b>「贴地」容差（格）</b>：脚底到雪面上表面的距离 ≤ 这个值才算站在雪面上。
	 *
	 * <p>急刹爆发要求<b>上一 tick 与本 tick 都贴地</b>。落地那一 tick 水平速度会突降（动量被重置 / 摩擦吃满），
	 * 看着像急刹，其实是「从一两格高跳下来」，那一下不该炸雪。取 0.15 格：站/滑时 gap ≈ 0，
	 * 而落地前一两 tick 的 gap 是 0.3–1.5 格（{@link #AIR_RANGE} 内），所以「上一 tick 是否贴地」这一条就能挡住整段落地。
	 */
	private static final double GROUND_TOUCH_TOLERANCE = 0.15D;
	/**
	 * <b>垂直速度容差（格/tick）</b>：|vy| 小于它才算「在地面上滑」，大就说明在坠落。
	 *
	 * <p>自由落体的落地瞬时 vy：1 格 ≈ −0.38、2 格 ≈ −0.52、3 格 ≈ −0.65（原版重力 0.08 格/tick² + 0.98 阻尼的离散值），
	 * 是这里的 4–7 倍；而贴地滑行/急刹时 y 被碰撞压在 0 附近（|vy| ≈ 0）。<= 0.10 把这两种情况分得很干净。
	 */
	private static final double FALL_TOLERANCE = 0.10D;

	/** 实体 id → 上一 tick 的水平速度（用来发现「突然减速」）。 */
	private static final IdMap<Double> LAST_SPEED = new IdMap<>();
	/** 实体 id → 上一 tick 的滑行方向角（弧度）。 */
	private static final IdMap<Double> LAST_DIR = new IdMap<>();
	/** 实体 id → 雪屑冷却剩余 tick。 */
	private static final IdMap<Integer> BURST_COOLDOWN = new IdMap<>();
	/** 实体 id → 上一 tick 是否贴地（急刹爆发的第一道闸门：落地那一 tick 不算刹车）。 */
	private static final IdMap<Boolean> LAST_GROUNDED = new IdMap<>();
	/** {@link #DEBUG_LOG} 用：客户端 tick 计数（每 20 tick 打一行）。 */
	private static int DEBUG_TICKS = 0;

	private SnowSpray() {
	}

	/**
	 * 每客户端 tick 跑一次：遍历场上的玩家，只给穿着板、脚底离雪面 {@link #AIR_RANGE} 格以内的人扬雪。
	 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		for (Player player : level.players()) {
			int id = player.getId();
			if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
				forget(id);
				continue;
			}
			double speed = player.getDeltaMovement().horizontalDistance();
			Double last = LAST_SPEED.put(id, speed);
			// 转向检测（两板通用）：滑行方向这一 tick 摆了多少。每 tick 都写回（即使这 tick 不喷粒子），
			// 否则重新开始喷的那一刻会拿一个几秒前的方向去比，误判成「在转向」。
			boolean turning = false;
			// 本 tick 速度方向摆过的角度（弧度，已归一化到 (−π, π]，+ = 朝右向量那侧转），
			// 只给单板定「弯外侧」用；没有上一 tick 的方向 / 没在动时是 0。
			double turnDelta = 0.0D;
			if (speed >= 1.0E-4D) {
				Vec3 motion = player.getDeltaMovement();
				double dir = Math.atan2(motion.z, motion.x);
				Double lastDir = LAST_DIR.put(id, dir);
				if (lastDir != null) {
					double delta = dir - lastDir;
					while (delta > Math.PI) {
						delta -= Math.PI * 2.0D;
					}
					while (delta < -Math.PI) {
						delta += Math.PI * 2.0D;
					}
					// 判据带 1e-9 弧度容差：carve 的 rotateVelocity 每 tick 正好转 TURN_RATE_DEGREES（= TURN_DEGREES_PER_TICK）
					// 2°，严格的 `>` 会让浮点末位噪声决定这一 tick 算不算「在转向」（纯 carve 实测约 1/4–1/3 的 tick
					// 落在阈值下面），单板的弯外侧偏向与板端簇的转向加成就会跟着闪。按「≥ 阈值 − 1e-9」算。
					turning = Math.abs(delta) > Math.toRadians(TURN_DEGREES_PER_TICK) - 1.0E-9D;
					turnDelta = delta;
				}
			}
			int cooldown = BURST_COOLDOWN.getOrDefault(id, 0);
			if (cooldown > 0) {
				BURST_COOLDOWN.put(id, cooldown - 1);
			}
			// 雪面 + 距离：一次扫描同时给出「雪面上表面的高度」和「脚底到它的距离」，
			// 门槛只看距离（≤ AIR_RANGE）—— 不看 blockPosition() 落在哪一格、不看往下数几格、不看碰撞射线。
			double surface = snowSurface(level, player);
			if (DEBUG_LOG && ++DEBUG_TICKS % 20 == 0) {
				SkisMod.LOGGER.info("[skis] snowspray feetY=" + player.getY() + " surface=" + surface
						+ " gap=" + (player.getY() - surface) + " speed=" + speed
						+ " turning=" + turning + " range=" + AIR_RANGE);
			}
			if (Double.isNaN(surface)) {
				// 空中 / 附近没雪：记成「不贴地」，免得落地那一刻拿几分钟前的旧状态当「一直在雪面上」。
				LAST_GROUNDED.put(id, false);
				continue;
			}
			// 贴地判定（浮点比较，不用 autoboxing 的 ==）：脚底到雪面上表面 ≤ 容差才算站在雪面上。
			boolean grounded = player.getY() - surface <= GROUND_TOUCH_TOLERANCE;
			Boolean wasGrounded = LAST_GROUNDED.put(id, grounded);
			// 急减速：炸一团雪屑抛向空中（急刹、撞墙、压刃刹车都是这个形状）。
			// 三道闸门挡掉「落地」这种假急刹：① 上一 tick 贴地 ② 本 tick 贴地 ③ 垂直速度小（真在往下掉就不是刹车）。
			// 落地那一 tick 水平速度会突降（动量重置 + 落地摩擦），只看差值的话从一两格高跳下来也会炸一大团。
			if (last != null && cooldown == 0
					&& Boolean.TRUE.equals(wasGrounded) && grounded
					&& Math.abs(player.getDeltaMovement().y) < FALL_TOLERANCE
					&& last > BURST_MIN_SPEED && last - speed > BURST_DROP) {
				burst(level, player, last - speed, surface);
				BURST_COOLDOWN.put(id, BURST_COOLDOWN_TICKS);
			}
			if (speed < MIN_SPEED) {
				continue;   // 太慢（站着不动）：不持续扬雪
			}
			// 按键不对称（弯外侧 / 板端）只对**本地玩家**读真实输入 —— 与 SkisClient 同一条写法；
			// 别人（以及本地输入拿不到时）一律 null → 两侧 / 两端保持对称，与旧行为一致。
			Input keys = player == client.player && client.player != null && client.player.input != null
					? client.player.input.keyPresses : null;
			spray(level, player, speed, turning, turnDelta, surface, keys);
		}
	}

	/**
	 * 滑行中持续扬雪：贴地雪屑 + 扬起雪屑，数量都随速度变多；扬起层顶点高度随速度长（见 {@link #randomApex}）。
	 *
	 * @param turnDelta 本 tick 速度方向摆过的角度（弧度，+ = 朝右向量那侧转）；单板据此把受力侧定在<b>弯外侧</b>
	 */
	private static void spray(ClientLevel level, Player player, double speed, boolean turning, double turnDelta,
			double surface, Input keys) {
		Vec3 motion = player.getDeltaMovement();
		double dirX = motion.x / speed;
		double dirZ = motion.z / speed;
		// 右向量（＝玩家右手边）：朝 +Z 前进时它是 (−1, 0) ＝ −X ＝ 西。
		// 核对：MC 水平朝向 = (−sin yaw, cos yaw)，yaw = 0 朝 +Z（南）；面朝南时右手边正是西（−X），
		// 与 SnowboardControl.sideLocked 的符号一致（sign = −1 取右手边 = (cos, sin) × −1 = (−1, 0)）。
		double rightX = -dirZ;
		double rightZ = dirX;
		RandomSource random = player.getRandom();
		// 两个「偏向」：拿不到按键（其它玩家 / 本地输入为空）时都是 0 ＝ 对称（旧行为）：
		//   双板 → 弯外侧：sideSign A=+1（左转）→ biasSide=+1（身体右侧更猛）、D=-1 → -1（左侧更猛）；
		//   单板 → 板端：W 前倾 → 背后（biasEnd=-1）、S 后倾 → 正前方（+1），W/S 同按或都没按 → 0；
		//          板端照旧，另外**按着 W / S 且本 tick 在转向**时，受力侧 = 这一 tick 转弯的**外侧**
		//          （与双板同一套 BIAS_COUNT_MUL / BIAS_APEX_BONUS），没在转向就仍对称。
		boolean snowboard = SnowboardControl.isSnowboard(player);
		int biasEnd = (snowboard && keys != null && keys.forward() != keys.backward())
				? (keys.forward() ? -1 : 1) : 0;
		// 单板的弯外侧符号：dir = atan2(dz, dx)、右向量 = (−sinθ, cosθ)，θ 增大 ⇒ 速度方向朝右向量偏
		// （右转）⇒ 弯心在右 ⇒ 外侧是左 = side −1。数值验算（Python，三点定曲率圆心）：θ = 0°/90°/−45° 上
		// delta = ±2.5° 时圆心都落在 right 向量那侧（dot(圆心−位置, right) 与 delta 同号），外侧符号恒为 −sign(delta)。
		int biasSide = snowboard
				? (keys != null && biasEnd != 0 && turning && turnDelta != 0.0D ? (turnDelta > 0.0D ? -1 : 1) : 0)
				: (int) SnowboardControl.sideSign(keys);
		// 两组互斥：单板不算「按 A / D 的弯外侧」，双板不算板端那一簇。
		// 转向的**对称**加成（两侧都 +TURN_EXTRA_PER_SIDE 颗、顶点都 +FLURRY_APEX_TURN_BONUS 格）只在
		// **完全没有按键偏向**时生效（biasSide = 0 且 biasEnd = 0：没按键 / 拿不到按键 / 其它玩家 —— 与旧行为一致）。
		// 有偏向时：那 4 颗只加在受力侧；单板压着刃但没转向时两侧都不加，改由板端那一簇自己吃 ——
		// 否则按 W / S 时两侧同时 +4 颗 / +0.35 格，看着就是「两边一起变多」、弯外侧 / 板端的偏向被冲淡。
		boolean symmetricTurn = turning && biasSide == 0 && biasEnd == 0;
		// 数量：保底 1 颗 + 随速度线性涨，全程单调（0.1 → 1 / 1、0.3 → 3 / 5、0.85 → 8 / 14）。
		// 那个 1e-9 是二进制浮点的取整噪声补偿：0.3 − 0.06 会被算成 0.23999…，×20 直接截断会少一颗。
		int flecks = Mth.clamp((int) (SPRAY_MIN_PER_SIDE + (speed - MIN_SPEED) * SPRAY_PER_SPEED + 1.0E-9D),
				SPRAY_MIN_PER_SIDE, SPRAY_MAX_PER_SIDE);
		// 扬起层基础值**不含**转向那份额外：那 4 颗在侧循环里单独发（受力侧独占 / 无偏向时两侧都给）。
		int flurries = Mth.clamp((int) (FLURRY_MIN_PER_SIDE + (speed - MIN_SPEED) * FLURRY_PER_SPEED + 1.0E-9D),
				FLURRY_MIN_PER_SIDE, FLURRY_MAX_PER_SIDE);
		// 出生高度：那片雪面的上表面（已经按距离判过），再抬一点点 + 抖动；**不跟着人身子抬**。
		double spawnY = Math.min(surface, player.getY() - 0.05D) + Y_OFFSET;
		// 高速散得更开：1 + 1.6 × 归一化速度（低速 ≈1 倍、顶速 ≈2.6 倍），只放大扬起层的横向速度。
		double fan = 1.0D + 1.6D * Mth.clamp((speed - MIN_SPEED) / (APEX_SPEED_HI - MIN_SPEED), 0.0D, 1.0D);
		for (int side = -1; side <= 1; side += 2) {
			// side 的含义由 rightX/rightZ 决定：+1 = 沿右向量（身体右侧）、-1 = 身体左侧。
			// 弯外侧（双板按 A / D；单板按 W / S 且在转向 = 本 tick 转弯的外侧）数量 ×BIAS_COUNT_MUL，内侧保持原公式；
			// biasSide = 0（没按键 / 别人 / 单板没在转向）时 side 永远不等于它 ＝ 完全对称。
			int biased = side == biasSide ? 1 : 0;
			int fleckCount = biased != 0 ? (int) (flecks * BIAS_COUNT_MUL) : flecks;
			// 转向那份额外 4 颗：有按键偏向时**只给受力侧**（弯里侧不加，否则两侧一起变厚、看不出偏向）；
			// 没有任何按键偏向时（symmetricTurn）两侧照旧都加 —— 与改前的对称行为一致。
			int turnExtra = biased != 0 ? (turning ? TURN_EXTRA_PER_SIDE : 0)
					: (symmetricTurn ? TURN_EXTRA_PER_SIDE : 0);
			int flurryCount = (biased != 0 ? (int) (flurries * BIAS_COUNT_MUL) : flurries) + turnExtra;
			// 单板转向时**弯内侧一颗都不喷**（SINGLE_SIDE_SPRAY）：贴地层与扬起层都归零，只剩弯外侧一条雪线。
			// 只在 snowboard && biasSide != 0（单板按着 W / S 且真在转向）时生效 —— 双板按 A / D 走不到这里
			// （它的 biasSide 来自 sideSign(keys)，但 snowboard 为假），没在转向时 biasSide == 0 也走不到，
			// 所以双板与「两侧对称」的旧行为一行不变；置 false 就整段失效、回到两侧都喷。
			if (SINGLE_SIDE_SPRAY && snowboard && biasSide != 0 && side != biasSide) {
				fleckCount = 0;
				flurryCount = 0;
			}
			for (int i = 0; i < fleckCount; i++) {
				// 贴地层用自己那套（FLECK_*）：落点更外、更宽，向外速度更大、抖动更猛 —— 低速也看得出横向铺开。
				// 扬起层继续用 LATERAL_* / OUTWARD_*（下面那个循环），两层互不影响。
				double lateral = side * fleckLateral(random);
				// 横向速度：基值 + 只朝外的抖动，再乘左右符号 —— 抖动不再有「朝内」那一半，落点与速度恒同号。
				// 受力侧（弯外侧）再 ×BIAS_OUTWARD_MUL，与 BIAS_COUNT_MUL / BIAS_APEX_BONUS 成套。
				double out = side * (fleckOutward(random) + random.nextDouble() * FLECK_OUTWARD_JITTER);
				if (biased != 0) {
					out *= BIAS_OUTWARD_MUL;
				}
				double along = (random.nextDouble() - 0.5D) * 2.0D * ALONG_JITTER - BACKWARD;
				double up = UPWARD * (FLECK_UPWARD_MIN
						+ random.nextDouble() * (FLECK_UPWARD_MAX - FLECK_UPWARD_MIN));
				spawn(player.getX() + rightX * lateral + fleckJitter(random),
						spawnY + yJitter(random),
						player.getZ() + rightZ * lateral + fleckJitter(random),
						rightX * out + dirX * along,
						up,
						rightZ * out + dirZ * along, 0);
			}
			for (int i = 0; i < flurryCount; i++) {
				// 每颗自己抽顶点高度（随速度长、两档分层）+ 自己抽横向落点与横向速度。
				boolean high = random.nextDouble() < FLURRY_HIGH_FRACTION;
				// 顶点：对称转向加成只在无按键偏向时给（symmetricTurn）；有偏向时它归受力侧，
				// 受力侧另外再 +BIAS_APEX_BONUS（仍夹 FLURRY_APEX_CAP）。
				double apex = randomApex(random, high, symmetricTurn, speed);
				if (biased != 0) {
					apex = Math.min(apex + BIAS_APEX_BONUS, FLURRY_APEX_CAP);   // 受力侧再抬高，仍封顶
				}
				double vy = riseFor(apex);
				double lateral = side * lateralOffset(random);
				// 同样：抖动只朝外（[0, OUTWARD_JITTER]），受力侧 ×BIAS_OUTWARD_MUL，最后再按速度扇开（fan）。
				double out = side * (outwardSpeed(random) + random.nextDouble() * OUTWARD_JITTER) * fan;
				if (biased != 0) {
					out *= BIAS_OUTWARD_MUL;
				}
				double along = (random.nextDouble() - 0.5D) * 2.0D * ALONG_JITTER - BACKWARD;
				// 高飞档要活到顶点（≈26 tick），默认寿命均值才 10 tick —— 只抬这一档。
				spawn(player.getX() + rightX * lateral + jitter(random),
						spawnY + yJitter(random),
						player.getZ() + rightZ * lateral + jitter(random),
						rightX * out + dirX * along, vy, rightZ * out + dirZ * along,
						high ? FLURRY_HIGH_LIFETIME : 0);
			}
		}
		// 单板「板端」额外那一簇（W 前倾 → 人物背后 / S 后倾 → 人物正前方；biasEnd = 0 就整段跳过）：
		// 出生点在「身体位置 + 身体前方 × biasEnd × END_OFFSET」处 —— 前后轴用的是**身体朝向**（frontLocked），
		// 不是行进方向：单板横着滑，行进方向几乎与身体正面垂直，拿它当轴那簇雪会拍到侧面去。
		// 横向轴取身体朝向的右手边（endRight = (−frontZ, frontX)），横向 ±END_LATERAL、沿向 ±END_SPREAD 摊开；
		// 顶点走与扬起层同一套 randomApex（这里仍传 turning：板端簇自己吃转向的加成）再 +BIAS_APEX_BONUS，仍夹 FLURRY_APEX_CAP。
		if (biasEnd != 0) {
			Vec3 front = SnowboardControl.frontLocked(player.getId(), player.getYRot());   // 沿锁定的身体朝向，不是视角
			double frontX = front.x;
			double frontZ = front.z;
			double endRightX = -frontZ;   // 身体右手边（与上面 rightX/rightZ 同一定义）
			double endRightZ = frontX;
			double endX = player.getX() + frontX * (biasEnd * END_OFFSET);
			double endZ = player.getZ() + frontZ * (biasEnd * END_OFFSET);
			// 转向时这一簇自己多吃 TURN_EXTRA_PER_SIDE 颗（8 → 12）：两侧的对称转向加成已被 symmetricTurn 收走。
			int endCount = END_EXTRA_PER_TICK + (turning ? TURN_EXTRA_PER_SIDE : 0);
			for (int i = 0; i < endCount; i++) {
				double lateral = (random.nextDouble() * 2.0D - 1.0D) * END_LATERAL;    // 横向 ±END_LATERAL
				double alongOffset = (random.nextDouble() * 2.0D - 1.0D) * END_SPREAD;  // 沿身体前后轴 ±END_SPREAD
				boolean high = random.nextDouble() < FLURRY_HIGH_FRACTION;
				double apex = Math.min(randomApex(random, high, turning, speed) + BIAS_APEX_BONUS, FLURRY_APEX_CAP);
				// 横向速度跟着落点朝外（lateral 的符号就是这一颗在身体哪一侧），其余与扬起层同参数；
				// 速度方向仍按行进方向（dir）往后带 —— 只改出生点的轴向，粒子怎么飞保持原样。
				double out = ((lateral >= 0.0D ? 1.0D : -1.0D) * outwardSpeed(random)
						+ randomSign(random) * OUTWARD_JITTER) * fan;
				double along = (random.nextDouble() - 0.5D) * 2.0D * ALONG_JITTER - BACKWARD;
				spawn(endX + endRightX * lateral + frontX * alongOffset + jitter(random),
						spawnY + yJitter(random),
						endZ + endRightZ * lateral + frontZ * alongOffset + jitter(random),
						rightX * out + dirX * along, riseFor(apex), rightZ * out + dirZ * along,
						high ? FLURRY_HIGH_LIFETIME : 0);
			}
		}
	}

	/** 横向落点（格，正数）：在 {@link #LATERAL_MIN}–{@link #LATERAL_MAX} 上均匀抽，乘左右符号后即「板两侧」的偏移。 */
	private static double lateralOffset(RandomSource random) {
		return LATERAL_MIN + random.nextDouble() * (LATERAL_MAX - LATERAL_MIN);
	}

	/** 向外的横向速度（格/tick，正数）：在 {@link #OUTWARD_MIN}–{@link #OUTWARD_MAX} 上均匀抽。 */
	private static double outwardSpeed(RandomSource random) {
		return OUTWARD_MIN + random.nextDouble() * (OUTWARD_MAX - OUTWARD_MIN);
	}

	/** 贴地层的横向落点（格，正数）：{@link #FLECK_LATERAL_MIN}–{@link #FLECK_LATERAL_MAX} 上均匀抽，比扬起层更外更宽。 */
	private static double fleckLateral(RandomSource random) {
		return FLECK_LATERAL_MIN + random.nextDouble() * (FLECK_LATERAL_MAX - FLECK_LATERAL_MIN);
	}

	/** 贴地层的向外速度（格/tick，正数）：{@link #FLECK_OUTWARD_MIN}–{@link #FLECK_OUTWARD_MAX}。 */
	private static double fleckOutward(RandomSource random) {
		return FLECK_OUTWARD_MIN + random.nextDouble() * (FLECK_OUTWARD_MAX - FLECK_OUTWARD_MIN);
	}

	/** 贴地层出生点的水平抖动（格）：{@code ±FLECK_SPREAD / 2}，比扬起层的 {@link #jitter} 宽。 */
	private static double fleckJitter(RandomSource random) {
		return (random.nextDouble() - 0.5D) * FLECK_SPREAD;
	}

	/** ±1（只给板端那一簇用：它的横向落点围绕身体中线均匀抽，抖动仍允许往里）。 */
	private static double randomSign(RandomSource random) {
		return random.nextBoolean() ? 1.0D : -1.0D;
	}

	/**
	 * 抽一颗扬起雪屑的顶点高度（格）：区间随速度从「贴地」长到「胸 / 头」，
	 * 高飞档取区间上半、低飞档取下半，{@code turnBonus} 为真时再 +{@link #FLURRY_APEX_TURN_BONUS}，最后一律夹到 {@link #FLURRY_APEX_CAP}。
	 *
	 * <p>{@code turnBonus} 是「<b>要不要拿转向的对称加成</b>」，不是裸的 {@code turning}：侧循环传 {@code symmetricTurn}
	 * （有任何按键偏向时都为假：转向加成改由受力侧的 {@link #BIAS_APEX_BONUS} 承担 —— 双板按 A / D、
	 * 单板按 W / S 且真在转向时都是这条路；单板没转向时仍由板端那一簇自己吃 {@code turning}）。
	 * 单板板端那一簇仍传 {@code turning}（它自己吃这一份额外加成）。
	 */
	private static double randomApex(RandomSource random, boolean high, boolean turnBonus, double speed) {
		double t = Mth.clamp((speed - APEX_SPEED_LO) / (APEX_SPEED_HI - APEX_SPEED_LO), 0.0D, 1.0D);
		double lo = Mth.lerp(t, APEX_LOW_FROM, APEX_LOW_TO);
		double hi = Mth.lerp(t, APEX_HIGH_FROM, APEX_HIGH_TO);
		double mid = (lo + hi) * 0.5D;
		double apex = high
				? mid + random.nextDouble() * (hi - mid)
				: lo + random.nextDouble() * (mid - lo);
		if (turnBonus) {
			apex += FLURRY_APEX_TURN_BONUS;
		}
		return Math.min(apex, FLURRY_APEX_CAP);
	}

	/**
	 * 反解：要让雪屑飘到 {@code apexBlocks} 格高，出生时该给多大的向上初速（格/tick）。
	 *
	 * <p><b>用离散积分、不用解析式 {@code sqrt(2gh)}</b>：原版每 tick 先 {@code yd -= 0.04 * gravity}、
	 * 再用新的 yd 位移、最后 {@code yd *= 0.98}（阻尼）—— 阻尼会把顶点压低一截，
	 * 解析式会系统性高估。这里把整个上升过程逐 tick 复刻一遍（{@link #apexOf}），再二分搜初速。
	 */
	private static double riseFor(double apexBlocks) {
		double target = Mth.clamp(apexBlocks, 0.0D, FLURRY_APEX_CAP);
		double lo = 0.0D;
		double hi = 0.1D;
		while (apexOf(hi) < target && hi < 4.0D) {
			hi *= 2.0D;
		}
		for (int i = 0; i < 24; i++) {
			double mid = (lo + hi) * 0.5D;
			if (apexOf(mid) < target) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return (lo + hi) * 0.5D;
	}

	/** 给一个向上初速，逐 tick 复刻原版粒子物理，返回它最终能升到多高（格，从出生点算）。 */
	private static double apexOf(double vy) {
		double y = 0.0D;
		double v = vy;
		double apex = 0.0D;
		for (int t = 0; t < 240; t++) {
			v -= PARTICLE_GRAVITY;          // 原版：yd -= 0.04 * gravity
			y += v;                         // 原版：move(0, yd, 0)
			if (y > apex) {
				apex = y;
			}
			if (v <= 0.0D && y <= 0.0D) {
				break;                      // 已经落回出生高度，不用再算
			}
			v *= PARTICLE_DRAG;             // 原版：yd *= friction(0.98)
		}
		return apex;
	}

	/**
	 * 脚底那片雪面的<b>上表面高度</b>（格）；脚底离它超过 {@link #AIR_RANGE} 格时返回 {@link Double#NaN}。
	 *
	 * <p>三步，每一步都在拆前几版「一离地就没粒子」的一条隐含前提：
	 * <ol>
	 *   <li><b>先扫中心列</b>（脚底那一格）往下 {@link #SCAN_DEPTH} 格，看每一格本身是不是雪 / 冰
	 *       （{@link BlockTags#SNOW} / {@link BlockTags#ICE}）—— 不猜 {@code blockPosition()} 下面是哪一格，
	 *       也不看碰撞形状（1 层雪没有碰撞盒，{@code level.clip} 会穿过去命到下面的石头）；</li>
	 *   <li><b>中心列空手就扫 3×3 邻列</b>（{@link #SCAN_RADIUS}）—— 坡上 / 崖边一跳，雪面常在斜下方一格；</li>
	 *   <li><b>用形状最高点算距离</b>：{@code 方块 y + getShape().max(Y)}（1 层雪 0.125、满方块 1.0），
	 *       取脚下最高的候选，距离 = 脚底 y − 上表面，只有 ≤ {@link #AIR_RANGE} 才认。</li>
	 * </ol>
	 *
	 * @return 雪面上表面 y（格）；{@link Double#NaN} = 这个距离内没有雪 / 冰面
	 */
	private static double snowSurface(ClientLevel level, Player player) {
		BlockPos base = player.blockPosition();
		double feet = player.getY();
		double hit = scanRing(level, base, feet, 0);
		if (Double.isNaN(hit)) {
			hit = scanRing(level, base, feet, SCAN_RADIUS);
		}
		return hit;
	}

	/**
	 * 扫一圈列，返回半径内所有「离脚底 ≤ {@link #AIR_RANGE}」的雪面里<b>最高的</b>那个。
	 *
	 * @param radius 0 = 只扫中心列；1 = 3×3（跳过中心，中心已经由 radius 0 那一轮扫过）
	 */
	private static double scanRing(ClientLevel level, BlockPos base, double feet, int radius) {
		double best = Double.NaN;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (radius > 0 && dx == 0 && dz == 0) {
					continue;
				}
				double surface = scanColumn(level, base.offset(dx, 0, dz), feet);
				if (!Double.isNaN(surface) && (Double.isNaN(best) || surface > best)) {
					best = surface;
				}
			}
		}
		return best;
	}

	/** 一列往下扫 {@link #SCAN_DEPTH} 格，返回这一列里离脚底最近（最高）且 ≤ {@link #AIR_RANGE} 的雪面上表面。 */
	private static double scanColumn(ClientLevel level, BlockPos top, double feet) {
		double best = Double.NaN;
		for (int dy = 0; dy <= SCAN_DEPTH; dy++) {
			BlockPos pos = top.below(dy);
			BlockState state = level.getBlockState(pos);
			if (!state.is(BlockTags.SNOW) && !state.is(BlockTags.ICE)) {
				continue;
			}
			double surface = pos.getY() + state.getShape(level, pos).max(Direction.Axis.Y);
			double gap = feet - surface;
			if (gap > AIR_RANGE || gap < -EMBED_TOLERANCE) {
				continue;   // 太远，或者高过脚底太多（头顶的雪层不算地面）
			}
			if (Double.isNaN(best) || surface > best) {
				best = surface;
			}
		}
		return best;
	}

	/**
	 * 生成一颗雪粒子，并<b>把初速真的写进去</b>。
	 *
	 * <p>两条路都不能少，这是这层的全部要害：
	 * <ol>
	 *   <li>{@code ParticleEngine#createParticle} —— 它内部 {@code makeParticle} 之后<b>已经</b>把这个粒子
	 *       {@code add} 进引擎并返回引用，所以<b>不要</b>再调 {@code add}/{@code addParticle}（会出两颗）；</li>
	 *   <li>{@code Particle#setParticleSpeed} —— {@code ITEM_SNOWBALL} 的 provider 把传进去的
	 *       {@code xSpeed/ySpeed/zSpeed} 直接丢了（构造器给的是随机 ±0.1），只有这里硬写回
	 *       {@code xd/yd/zd} 才有效。</li>
	 * </ol>
	 *
	 * <p>走引擎而不走 {@code ClientLevel#addParticle}，就绕过了那边的「离相机 32 格 / ParticleStatus.MINIMAL」
	 * 两道筛子，所以这里自己补一条同样的距离判据，免得给远处的玩家扬雪。
	 *
	 * @param lifetime 0 = 用原版随机寿命（{@code (int)(4 / (rand*0.9 + 0.1))}；&gt;0 = 至少活这么多 tick）
	 */
	private static void spawn(double x, double y, double z, double vx, double vy, double vz, int lifetime) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		if (camera.distanceToSqr(x, y, z) > 1024.0D) {
			return;   // 与 ClientLevel#addParticle 同一道 32 格筛子
		}
		Particle particle = Minecraft.getInstance().particleEngine.createParticle(SNOW, x, y, z, vx, vy, vz);
		if (particle != null) {
			particle.setParticleSpeed(vx, vy, vz);   // provider 丢了初速，这里硬写回去
			if (lifetime > 0 && particle.getLifetime() < lifetime) {
				particle.setLifetime(lifetime);      // 只抬不降：别把别人原本更长的寿命砍短
			}
		}
	}

	/** 急减速：从脚下一圈炸开一大团抛向空中的雪屑（同样贴地起）。 */
	private static void burst(ClientLevel level, Player player, double drop, double surface) {
		RandomSource random = player.getRandom();
		int count = Mth.clamp((int) (drop * BURST_PER_DROP), 1, BURST_MAX);
		double y = surface + Y_OFFSET;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = random.nextDouble() * BURST_RADIUS;
			double dx = Math.cos(angle) * radius;
			double dz = Math.sin(angle) * radius;
			spawn(player.getX() + dx, y + yJitter(random), player.getZ() + dz,
					dx * 0.8D, BURST_UPWARD * (0.6D + random.nextDouble()), dz * 0.8D, 0);
		}
	}

	private static double jitter(RandomSource random) {
		return (random.nextDouble() - 0.5D) * SPREAD;
	}

	/** 出生高度的抖动：{@code ±Y_JITTER}（格）。 */
	private static double yJitter(RandomSource random) {
		return (random.nextDouble() - 0.5D) * 2.0D * Y_JITTER;
	}

	/** 脱板 / 换人：把该实体的记录清掉，免得 id 复用串状态。 */
	private static void forget(int id) {
		LAST_SPEED.remove(id);
		LAST_DIR.remove(id);
		BURST_COOLDOWN.remove(id);
		LAST_GROUNDED.remove(id);
	}
}
