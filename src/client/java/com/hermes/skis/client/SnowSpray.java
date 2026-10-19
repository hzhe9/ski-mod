package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import com.hermes.skis.util.IdMap;

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
 *       转向（一 tick 转过 {@link #TURN_DEGREES_PER_TICK} 度以上）时这一层更多、再抬高 {@link #FLURRY_APEX_TURN_BONUS} 格；</li>
 *   <li><b>急减速雪屑</b>（{@link #burst}）：一 tick 内速度掉了 {@link #BURST_DROP} 以上时，
 *       从脚下一圈炸开一大团雪屑（急刹车 / 撞墙 / 压刃刹车都是这个形状），粒子数随掉落量增加。
 *       <b>只在真贴地时才算急刹</b>：上一 tick 与本 tick 都贴地（{@link #GROUND_TOUCH_TOLERANCE}）
 *       且垂直速度小（{@link #FALL_TOLERANCE}）—— 落地那一 tick 水平速度也会突降，但那是「跳下来」不是「刹车」，
 *       不加这三道闸门时从一两格高落地也会炸一大团。</li>
 * </ul>
 *
 * <p><b>横向不再固定成两条线</b>：每颗粒子自己抽横向落点（{@link #LATERAL_MIN}–{@link #LATERAL_MAX}）与
 * 横向速度（{@link #OUTWARD_MIN}–{@link #OUTWARD_MAX}，再叠 {@link #OUTWARD_JITTER} 的正负抖动），
 * 高速再按速度把横向速度扇开（{@code fan}）—— 一片雾，不是两条平行线。
 * <b>贴地层与扬起层各一套参数</b>：贴地层走 {@link #FLECK_LATERAL_MIN}–{@link #FLECK_LATERAL_MAX}（0.20–1.10 格）、
 * {@link #FLECK_OUTWARD_MIN}–{@link #FLECK_OUTWARD_MAX}、{@link #FLECK_OUTWARD_JITTER}、{@link #FLECK_SPREAD}，
 * 区间更外更宽 —— 低速时视野里几乎全是贴地层，原来那套窄区间看着是一撮挤在板边。
 *
 * <p><b>所有粒子的出生点都在脚底那片「雪面」上</b>（{@code surface + Y_OFFSET ± Y_JITTER}），
 * <b>不跟着人身子抬</b> —— 高度层次只靠顶点高度做出来，所以看着是雪从地面被卷起来、抛上去。
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求左向量），落点在这片雪面上，
 * 所以双板与单板共用同一套（单板板身横着，看着就是从切雪的那条边溅起来）。
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
	/** 扬起雪屑的每侧数量上限（转向的 {@link #TURN_EXTRA_PER_SIDE} 是加在夹上限之后的）。 */
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
	/** 转向时顶点再抬高这么多格（转急弯把雪甩到身上）。 */
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
	/** 转向时：扬起层每侧再多几颗（加在夹上限之后，所以转向时能超过 {@link #FLURRY_MAX_PER_SIDE}）。 */
	private static final int TURN_EXTRA_PER_SIDE = 4;
	/** 滑行方向这一 tick 转了多少度算「在转向」。 */
	private static final double TURN_DEGREES_PER_TICK = 2.0D;
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
	/** 横向（向外）速度区间（格/tick）：抽在这个区间里，再叠 {@link #OUTWARD_JITTER} 的正负抖动。 */
	private static final double OUTWARD_MIN = 0.05D;
	private static final double OUTWARD_MAX = 0.30D;
	/** 横向速度的正负抖动：让一部分粒子稍微往里收，避免所有雪都朝同一个方向排开。 */
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
	private static final double FLECK_OUTWARD_MIN = 0.12D;
	private static final double FLECK_OUTWARD_MAX = 0.45D;
	/** 贴地层向外速度的正负抖动：比扬起层 ±0.06 大，雪屑才不是一律朝外排。 */
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
					turning = Math.abs(delta) > Math.toRadians(TURN_DEGREES_PER_TICK);
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
			spray(level, player, speed, turning, surface);
		}
	}

	/** 滑行中持续扬雪：贴地雪屑 + 扬起雪屑，数量都随速度变多；扬起层顶点高度随速度长（见 {@link #randomApex}）。 */
	private static void spray(ClientLevel level, Player player, double speed, boolean turning, double surface) {
		Vec3 motion = player.getDeltaMovement();
		double dirX = motion.x / speed;
		double dirZ = motion.z / speed;
		double leftX = -dirZ;   // 左向量
		double leftZ = dirX;
		RandomSource random = player.getRandom();
		// 数量：保底 1 颗 + 随速度线性涨，全程单调（0.1 → 1 / 1、0.3 → 3 / 5、0.85 → 8 / 14）。
		// 那个 1e-9 是二进制浮点的取整噪声补偿：0.3 − 0.06 会被算成 0.23999…，×20 直接截断会少一颗。
		int flecks = Mth.clamp((int) (SPRAY_MIN_PER_SIDE + (speed - MIN_SPEED) * SPRAY_PER_SPEED + 1.0E-9D),
				SPRAY_MIN_PER_SIDE, SPRAY_MAX_PER_SIDE);
		int flurries = Mth.clamp((int) (FLURRY_MIN_PER_SIDE + (speed - MIN_SPEED) * FLURRY_PER_SPEED + 1.0E-9D),
				FLURRY_MIN_PER_SIDE, FLURRY_MAX_PER_SIDE) + (turning ? TURN_EXTRA_PER_SIDE : 0);
		// 出生高度：那片雪面的上表面（已经按距离判过），再抬一点点 + 抖动；**不跟着人身子抬**。
		double spawnY = Math.min(surface, player.getY() - 0.05D) + Y_OFFSET;
		// 高速散得更开：1 + 1.6 × 归一化速度（低速 ≈1 倍、顶速 ≈2.6 倍），只放大扬起层的横向速度。
		double fan = 1.0D + 1.6D * Mth.clamp((speed - MIN_SPEED) / (APEX_SPEED_HI - MIN_SPEED), 0.0D, 1.0D);
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < flecks; i++) {
				// 贴地层用自己那套（FLECK_*）：落点更外、更宽，向外速度更大、抖动更猛 —— 低速也看得出横向铺开。
				// 扬起层继续用 LATERAL_* / OUTWARD_*（下面那个循环），两层互不影响。
				double lateral = side * fleckLateral(random);
				double out = side * fleckOutward(random) + randomSign(random) * FLECK_OUTWARD_JITTER;
				double along = (random.nextDouble() - 0.5D) * 2.0D * ALONG_JITTER - BACKWARD;
				double up = UPWARD * (FLECK_UPWARD_MIN
						+ random.nextDouble() * (FLECK_UPWARD_MAX - FLECK_UPWARD_MIN));
				spawn(player.getX() + leftX * lateral + fleckJitter(random),
						spawnY + yJitter(random),
						player.getZ() + leftZ * lateral + fleckJitter(random),
						leftX * out + dirX * along,
						up,
						leftZ * out + dirZ * along, 0);
			}
			for (int i = 0; i < flurries; i++) {
				// 每颗自己抽顶点高度（随速度长、两档分层）+ 自己抽横向落点与横向速度。
				boolean high = random.nextDouble() < FLURRY_HIGH_FRACTION;
				double vy = riseFor(randomApex(random, high, turning, speed));
				double lateral = side * lateralOffset(random);
				double out = (side * outwardSpeed(random) + randomSign(random) * OUTWARD_JITTER) * fan;
				double along = (random.nextDouble() - 0.5D) * 2.0D * ALONG_JITTER - BACKWARD;
				// 高飞档要活到顶点（≈26 tick），默认寿命均值才 10 tick —— 只抬这一档。
				spawn(player.getX() + leftX * lateral + jitter(random),
						spawnY + yJitter(random),
						player.getZ() + leftZ * lateral + jitter(random),
						leftX * out + dirX * along, vy, leftZ * out + dirZ * along,
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

	/** ±1：横向速度的正负抖动，让一部分雪稍微往里收。 */
	private static double randomSign(RandomSource random) {
		return random.nextBoolean() ? 1.0D : -1.0D;
	}

	/**
	 * 抽一颗扬起雪屑的顶点高度（格）：区间随速度从「贴地」长到「胸 / 头」，
	 * 高飞档取区间上半、低飞档取下半，转向再 +{@link #FLURRY_APEX_TURN_BONUS}，最后一律夹到 {@link #FLURRY_APEX_CAP}。
	 */
	private static double randomApex(RandomSource random, boolean high, boolean turning, double speed) {
		double t = Mth.clamp((speed - APEX_SPEED_LO) / (APEX_SPEED_HI - APEX_SPEED_LO), 0.0D, 1.0D);
		double lo = Mth.lerp(t, APEX_LOW_FROM, APEX_LOW_TO);
		double hi = Mth.lerp(t, APEX_HIGH_FROM, APEX_HIGH_TO);
		double mid = (lo + hi) * 0.5D;
		double apex = high
				? mid + random.nextDouble() * (hi - mid)
				: lo + random.nextDouble() * (mid - lo);
		if (turning) {
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
