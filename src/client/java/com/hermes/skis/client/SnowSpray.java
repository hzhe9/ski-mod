package com.hermes.skis.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hermes.skis.SkiGlide;
import com.hermes.skis.SkisMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>滑行雪粒子</b>：穿着滑雪板在雪面上滑时扬起的雪，纯客户端观感（{@link ClientLevel#addParticle}），
 * 服务端不参与、也不影响任何速度 —— 速度只决定粒子，粒子不决定速度。
 *
 * <p><b>只用 {@link #SNOW} 一种粒子</b>：{@code ITEM_SNOWBALL}＝雪球崩碎的小白屑（雪地就该是这种实心碎屑）。
 * 模组里**不用**任何雾 / 烟 / 云类粒子（`SNOWFLAKE`、`POOF`、`CLOUD` 这些看着像一团白雾，用户不要）。
 *
 * <p>三层效果，数量都随速度涨：
 * <ul>
 *   <li><b>贴地雪屑</b>：从板的两侧（与滑行方向垂直的左右两边）向外、略向上溅；</li>
 *   <li><b>扬起雪屑</b>：同样从板两侧起，但按<b>顶点高度</b>调 —— 每颗抽一个
 *       {@link #FLURRY_APEX_MIN}–{@link #FLURRY_APEX_MAX} 格（腰到头）的顶点高度，初速由
 *       {@link #riseFor} 按离散粒子物理反解；按 {@link #FLURRY_HIGH_FRACTION} 拆成
 *       「下半 / 上半」两档，于是在空中形成<b>均匀的层次</b>。转向（滑行方向
 *       一 tick 转过 {@link #TURN_DEGREES_PER_TICK} 度以上）时这一层更多、再抬高
 *       {@link #FLURRY_APEX_TURN_BONUS} 格（封顶 {@link #FLURRY_APEX_CAP}）；</li>
 *   <li><b>急减速雪屑</b>（{@link #burst}）：一 tick 内速度掉了 {@link #BURST_DROP} 以上时，
 *       从脚下一圈炸开一大团雪屑（急刹车 / 撞墙 / 压刃刹车都是这个形状），粒子数随掉落量增加。</li>
 * </ul>
 *
 * <p><b>所有粒子的出生点都在脚底那片「地面」上</b>（{@code groundY + 0.05 ± 0.05}，{@code groundY} 来自
 * {@link SkiGlide#groundDistance}），<b>不跟着人身子抬</b> —— 高度层次只靠顶点高度做出来，
 * 所以看着是雪从地面被卷起来、抛上去，而不是从人身上冒出来。
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求左向量），落点在地面高度，
 * 所以双板与单板共用同一套（单板板身横着，看着就是从切雪的那条边溅起来）。
 */
public final class SnowSpray {
	/** 唯一的雪粒子：{@code ITEM_SNOWBALL} = 雪球崩碎的白色碎屑（重力 1.0，会落回雪面）。 */
	private static final ParticleOptions SNOW = ParticleTypes.ITEM_SNOWBALL;
	/** 速度低于这个（格/tick）就不持续扬雪 —— 慢慢挪一步不该扬雪。 */
	private static final double MIN_SPEED = 0.10D;
	/** 脚底离地面在这个高度以内就扬雪（格）：滑下坡时常常没踩着地，用「离地 1.5 格内」代替 `onGround`。 */
	private static final double GROUND_RANGE = 1.5D;
	/** 贴地雪屑：每侧数量 = 速度 × 这个系数（再夹到上限）。 */
	private static final double SPRAY_PER_SPEED = 10.0D;
	/** 贴地雪屑的每侧数量上限。 */
	private static final int SPRAY_MAX_PER_SIDE = 3;
	/** 扬起雪屑：每侧数量 = 速度 × 这个系数（比贴地那层涨得快）。 */
	private static final double FLURRY_PER_SPEED = 14.0D;
	/** 扬起雪屑的每侧数量上限。 */
	private static final int FLURRY_MAX_PER_SIDE = 3;
	/**
	 * 扬起雪屑的顶点高度区间（格，从地面算）：0.7 = 腰 / 膝，1.8 = 头顶。
	 *
	 * <p>这里写的是<b>「雪飘到多高」</b>，不是初速度 —— 初速由 {@link #riseFor} 按离散粒子物理反解，
	 * 所以调这两个数就能把雪屑抬到身体那么高，不用再猜 vy。
	 */
	private static final double FLURRY_APEX_MIN = 0.7D;
	private static final double FLURRY_APEX_MAX = 1.8D;
	/** 转向时顶点再抬高这么多格（转急弯把雪甩到身上）。 */
	private static final double FLURRY_APEX_TURN_BONUS = 0.3D;
	/** 顶点封顶（格）：任何情况下不许超过它（避免又变成雪柱）。 */
	private static final double FLURRY_APEX_CAP = 2.0D;
	/**
	 * 雪粒子的重力（格/tick²）。原版 {@code Particle.tick} 里是 {@code yd -= 0.04 * gravity}，
	 * 而雪球（{@code BreakingItemParticle} 构造器）的 {@code gravity} 是 1.0 —— 即每 tick −0.04。
	 */
	private static final double PARTICLE_GRAVITY = 0.04D;
	/** 雪粒子的每 tick 阻尼：原版 {@code Particle} 构造器里 {@code friction} 默认 0.98（每 tick 速度 ×0.98）。 */
	private static final double PARTICLE_DRAG = 0.98D;
	/** 扬起层里走高飞档（顶点取区间上半）的比例，其余抽下半。 */
	private static final double FLURRY_HIGH_FRACTION = 0.60D;
	/** 转向时：扬起层每侧再多几颗。 */
	private static final int TURN_EXTRA_PER_SIDE = 2;
	/** 滑行方向这一 tick 转了多少度算「在转向」。 */
	private static final double TURN_DEGREES_PER_TICK = 2.0D;
	/** 板两边的横向偏移（格）：撒在脚的两侧，而不是脚底下。 */
	private static final double SIDE_OFFSET = 0.34D;
	/** 贴地雪屑向外（垂直于滑行方向）的溅出速度。 */
	private static final double OUTWARD = 0.18D;
	/** 贴地雪屑的向上分量（从地面起跳的初速：0.20 · 0.5–1.5 倍 ≈ 0.1–0.3 格/tick，真的能飞起来）。 */
	private static final double UPWARD = 0.20D;
	/** 向后（与滑行相反）的分量 —— 雪是往后飞的。 */
	private static final double BACKWARD = 0.16D;
	/** 出生点随机散布（格）。 */
	private static final double SPREAD = 0.16D;
	/**
	 * 出生点离地高度（格）：所有粒子都贴着它们脚下那片地面起。
	 *
	 * <p>再加 ±{@link #Y_JITTER} 的随机抖动，避免一整层粒子精确排在同一平面上。
	 */
	private static final double Y_OFFSET = 0.05D;
	/** 出生高度的随机抖动（格，正负各一半）：{@code groundY + Y_OFFSET ± Y_JITTER}。 */
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
	/** 雪屑炸开时的上升速度（抛向空中）。 */
	private static final double BURST_UPWARD = 0.35D;
	/** 雪屑炸开时的随机散布半径（格）。 */
	private static final double BURST_RADIUS = 0.75D;

	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 上一 tick 的水平速度（用来发现「突然减速」）。 */
	private static final Map<Integer, Double> LAST_SPEED = new ConcurrentHashMap<>();
	/** 实体 id → 上一 tick 的水平滑行方向角（弧度）。 */
	private static final Map<Integer, Double> LAST_DIR = new ConcurrentHashMap<>();
	/** 实体 id → 雪屑冷却剩余 tick。 */
	private static final Map<Integer, Integer> BURST_COOLDOWN = new ConcurrentHashMap<>();

	private SnowSpray() {
	}

	/** 每客户端 tick 跑一次：遍历场上的玩家，只给穿着板、在雪面上、脚底离地 {@link #GROUND_RANGE} 格以内的人扬雪。 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		if (LAST_SPEED.size() > MAX_ENTRIES) {
			LAST_SPEED.clear();
			LAST_DIR.clear();
			BURST_COOLDOWN.clear();
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
			if (!SkiGlide.isOnSnowOrIce(level, player.blockPosition())) {
				continue;   // 没在雪面上就没有雪可扬
			}
			// 急减速：炸一团雪屑抛向空中（急刹、撞墙、压刃刹车都是这个形状）
			if (last != null && cooldown == 0
					&& last > BURST_MIN_SPEED && last - speed > BURST_DROP) {
				burst(level, player, last - speed);
				BURST_COOLDOWN.put(id, BURST_COOLDOWN_TICKS);
			}
			if (speed < MIN_SPEED || SkiGlide.groundDistance(player, GROUND_RANGE) > GROUND_RANGE) {
				continue;   // 太慢、或离地超过 1.5 格：不持续扬雪（贴地飞下坡照样扬）
			}
			spray(level, player, speed, turning);
		}
	}

	/** 滑行中持续扬雪：贴地雪屑 + 扬起雪屑，两层都随速度变多；扬起层的顶点高度在固定区间里抽（见 {@link #FLURRY_APEX_MIN}）。 */
	private static void spray(ClientLevel level, Player player, double speed, boolean turning) {
		Vec3 motion = player.getDeltaMovement();
		double dirX = motion.x / speed;
		double dirZ = motion.z / speed;
		double leftX = -dirZ;   // 左向量
		double leftZ = dirX;
		RandomSource random = player.getRandom();
		int flecks = Mth.clamp((int) (speed * SPRAY_PER_SPEED), 1, SPRAY_MAX_PER_SIDE);
		int flurries = Mth.clamp((int) (speed * FLURRY_PER_SPEED), 1, FLURRY_MAX_PER_SIDE)
				+ (turning ? TURN_EXTRA_PER_SIDE : 0);
		// 顶点高度（格）→ 上升初速：每颗自己抽一个顶点，由 riseFor 按离散粒子物理反解出 vy。
		// 出生高度**不随速度和档位变**：全部贴地起，层次只靠顶点高度做出来。
		// 两层（贴地 / 扬起）共用同一个出生高度：脚下那片地面 + Y_OFFSET，再 ±Y_JITTER 抖动。
		double spawnY = groundY(player) + Y_OFFSET;
		for (int side = -1; side <= 1; side += 2) {
			double baseX = player.getX() + leftX * SIDE_OFFSET * side;
			double baseZ = player.getZ() + leftZ * SIDE_OFFSET * side;
			double outX = leftX * OUTWARD * side - dirX * BACKWARD;
			double outZ = leftZ * OUTWARD * side - dirZ * BACKWARD;
			for (int i = 0; i < flecks; i++) {
				level.addParticle(SNOW,
						baseX + jitter(random), spawnY + yJitter(random), baseZ + jitter(random),
						outX, UPWARD * (0.5D + random.nextDouble()), outZ);
			}
			for (int i = 0; i < flurries; i++) {
				// 每颗雪屑自己抽一个「顶点高度」：低飞档抽区间下半（腰 / 膝），高飞档抽上半（胸到头），
				// 混起来才有层次 —— 不再是「要么贴地皮、要么 9 格雪柱」。
				boolean high = random.nextDouble() < FLURRY_HIGH_FRACTION;
				double vy = riseFor(randomApex(random, high, turning));
				double scatter = high ? 0.9D : 0.6D;   // 横向溅射沿用原来两档的系数
				level.addParticle(SNOW,
						baseX + jitter(random), spawnY + yJitter(random), baseZ + jitter(random),
						outX * scatter, vy, outZ * scatter);
			}
		}
	}

	/**
	 * 抽一颗扬起雪屑的顶点高度（格）：高飞档取区间上半、低飞档取下半，转向再 +{@link #FLURRY_APEX_TURN_BONUS}，
	 * 最后一律夹到 {@link #FLURRY_APEX_CAP}。
	 */
	private static double randomApex(RandomSource random, boolean high, boolean turning) {
		double mid = (FLURRY_APEX_MIN + FLURRY_APEX_MAX) * 0.5D;
		double lo = high ? mid : FLURRY_APEX_MIN;
		double hi = high ? FLURRY_APEX_MAX : mid;
		double apex = lo + random.nextDouble() * (hi - lo);
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
	 * 脚底那片「地面」的高度（脚底 y 减去到地面的距离）。
	 *
	 * <p>距离来自 {@link SkiGlide#groundDistance}{@code (player, GROUND_RANGE)}；探不到地面、
	 * 或离地超过 {@link #GROUND_RANGE} 格（人已经飞起来了）时退回 {@code player.getY()} ——
	 * 此时就用脚底当下那个高度，绝不再往上加。
	 */
	private static double groundY(Player player) {
		double gap = SkiGlide.groundDistance(player, GROUND_RANGE);
		if (gap > GROUND_RANGE) {
			return player.getY();
		}
		return player.getY() - gap;
	}

	/** 急减速：从脚下一圈炸开一大团抛向空中的雪屑（同样贴地起）。 */
	private static void burst(ClientLevel level, Player player, double drop) {
		RandomSource random = player.getRandom();
		int count = Mth.clamp((int) (drop * BURST_PER_DROP), 1, BURST_MAX);
		double y = groundY(player) + Y_OFFSET;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = random.nextDouble() * BURST_RADIUS;
			double dx = Math.cos(angle) * radius;
			double dz = Math.sin(angle) * radius;
			level.addParticle(SNOW,
					player.getX() + dx, y + yJitter(random), player.getZ() + dz,
					dx * 0.8D, BURST_UPWARD * (0.6D + random.nextDouble()), dz * 0.8D);
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
	}
}
