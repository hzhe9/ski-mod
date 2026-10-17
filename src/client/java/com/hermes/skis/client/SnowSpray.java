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
 *   <li><b>扬起雪屑</b>：同样从板两侧起，向上的初速度与出生高度都随速度线性增加
 *       —— <b>越快抛得越高</b>，数量涨得比贴地那层快；按 {@link #FLURRY_HIGH_FRACTION}
 *       拆成「低飞 / 高飞」两档，高飞版在 0.30–0.55 的上升初速、0.5–1.5 格的出生高度里
 *       随机撒 —— 于是雪是<b>均匀撒向空中</b>的层次，不是贴着地面一层皮。转向（滑行方向
 *       一 tick 转过 {@link #TURN_DEGREES_PER_TICK} 度以上）时这一层更多、更高、起得更离地；</li>
 *   <li><b>急减速雪屑</b>（{@link #burst}）：一 tick 内速度掉了 {@link #BURST_DROP} 以上时，
 *       从脚下一圈炸开一大团雪屑（急刹车 / 撞墙 / 压刃刹车都是这个形状），粒子数随掉落量增加。</li>
 * </ul>
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求左向量），落点在脚底高度，
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
	/** 扬起雪屑的基础上升速度。 */
	private static final double FLURRY_UPWARD = 0.08D;
	/** 上升速度里随速度线性增加的那份 —— 这就是「越快抛得越高」。 */
	private static final double FLURRY_UPWARD_PER_SPEED = 0.45D;
	/**
	 * 上升速度上限（格/tick）。踩住是因为雪屑有重力（每 tick −0.04），
	 * 抛起高度 ≈ 速度² / 0.08：0.40 封顶 ≈ 2 格 —— 不加的话双板顶速 1.0 会算到 0.53、抛起 3.5 格，像一堵雪墙。
	 */
	private static final double FLURRY_UPWARD_MAX = 0.40D;
	/** 扬起雪屑出生点随速度抬高多少（格每「格/tick」）：太快的时候从高处起抛。 */
	private static final double FLURRY_HEIGHT_PER_SPEED = 0.35D;
	/** 扬起层里走高飞版的比例，其余沿用原来的低飞版。 */
	private static final double FLURRY_HIGH_FRACTION = 0.45D;
	/** 高飞版的上升初速度随机区间（格/tick）：撒在这个区间里才有「均匀撒向空中」的层次。 */
	private static final double FLURRY_UPWARD_HIGH_MIN = 0.30D;
	private static final double FLURRY_UPWARD_HIGH_MAX = 0.55D;
	/** 高飞版的出生高度（相对脚底，格），随机撒：0.5–1.5 格。 */
	private static final double FLURRY_SPAWN_HIGH_MIN = 0.5D;
	private static final double FLURRY_SPAWN_HIGH_MAX = 1.5D;
	/** 转向时：上升初速额外加这么多、上限提到 TURN_MAX、出生点再抬高、每侧再多几颗。 */
	private static final double TURN_UPWARD_BONUS = 0.20D;
	private static final double FLURRY_UPWARD_TURN_MAX = 0.75D;
	private static final double TURN_SPAWN_LIFT = 0.35D;
	private static final int TURN_EXTRA_PER_SIDE = 2;
	/** 滑行方向这一 tick 转了多少度算「在转向」。 */
	private static final double TURN_DEGREES_PER_TICK = 2.0D;
	/** 板两边的横向偏移（格）：撒在脚的两侧，而不是脚底下。 */
	private static final double SIDE_OFFSET = 0.34D;
	/** 贴地雪屑向外（垂直于滑行方向）的溅出速度。 */
	private static final double OUTWARD = 0.18D;
	/** 贴地雪屑的向上分量。 */
	private static final double UPWARD = 0.12D;
	/** 向后（与滑行相反）的分量 —— 雪是往后飞的。 */
	private static final double BACKWARD = 0.16D;
	/** 出生点随机散布（格）。 */
	private static final double SPREAD = 0.16D;
	/** 出生点相对脚底的高度偏移（格）。 */
	private static final double Y_OFFSET = 0.06D;

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

	/** 滑行中持续扬雪：贴地雪屑 + 扬起雪屑，两层都随速度变多、后者还随速度抛得更高；转向时扬起层更多更高。 */
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
		// 越快：向上初速度越大、出生点越高 —— 于是雪屑抛得越来越高（上限见 FLURRY_UPWARD_MAX；
		// 转向时上限提到 FLURRY_UPWARD_TURN_MAX，把「转弯甩起来的雪」再抬高一层）
		double riseCap = turning ? FLURRY_UPWARD_TURN_MAX : FLURRY_UPWARD_MAX;
		double rise = Math.min(FLURRY_UPWARD + speed * FLURRY_UPWARD_PER_SPEED, riseCap);
		if (turning) {
			rise = Math.min(rise + TURN_UPWARD_BONUS, FLURRY_UPWARD_TURN_MAX);
		}
		double riseY = player.getY() + Y_OFFSET + speed * FLURRY_HEIGHT_PER_SPEED;
		// 转向时扬起层整体再抬高一点（贴地那层不动）
		double turnLift = turning ? TURN_SPAWN_LIFT : 0.0D;
		for (int side = -1; side <= 1; side += 2) {
			double baseX = player.getX() + leftX * SIDE_OFFSET * side;
			double baseZ = player.getZ() + leftZ * SIDE_OFFSET * side;
			double outX = leftX * OUTWARD * side - dirX * BACKWARD;
			double outZ = leftZ * OUTWARD * side - dirZ * BACKWARD;
			for (int i = 0; i < flecks; i++) {
				level.addParticle(SNOW,
						baseX + jitter(random), riseY + random.nextDouble() * Y_OFFSET, baseZ + jitter(random),
						outX, UPWARD * (0.5D + random.nextDouble()), outZ);
			}
			for (int i = 0; i < flurries; i++) {
				if (random.nextDouble() < FLURRY_HIGH_FRACTION) {
					// 高飞版：上升初速与出生高度都在区间里随机抽 —— 雪才有「均匀撒向空中」的层次，
					// 而不是全挤在脚边到一个固定高度之间。
					double vy = FLURRY_UPWARD_HIGH_MIN
							+ random.nextDouble() * (FLURRY_UPWARD_HIGH_MAX - FLURRY_UPWARD_HIGH_MIN)
							+ (turning ? TURN_UPWARD_BONUS : 0.0D);
					double highY = riseY + turnLift + FLURRY_SPAWN_HIGH_MIN
							+ random.nextDouble() * (FLURRY_SPAWN_HIGH_MAX - FLURRY_SPAWN_HIGH_MIN);
					level.addParticle(SNOW,
							baseX + jitter(random), highY, baseZ + jitter(random),
							outX * 0.9D, vy, outZ * 0.9D);
				} else {
					level.addParticle(SNOW,
							baseX + jitter(random), riseY + turnLift, baseZ + jitter(random),
							outX * 0.6D, rise * (0.7D + random.nextDouble() * 0.6D), outZ * 0.6D);
				}
			}
		}
	}

	/** 急减速：从脚下一圈炸开一大团抛向空中的雪屑。 */
	private static void burst(ClientLevel level, Player player, double drop) {
		RandomSource random = player.getRandom();
		int count = Mth.clamp((int) (drop * BURST_PER_DROP), 1, BURST_MAX);
		double y = player.getY() + Y_OFFSET;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = random.nextDouble() * BURST_RADIUS;
			double dx = Math.cos(angle) * radius;
			double dz = Math.sin(angle) * radius;
			level.addParticle(SNOW,
					player.getX() + dx, y, player.getZ() + dz,
					dx * 0.8D, BURST_UPWARD * (0.6D + random.nextDouble()), dz * 0.8D);
		}
	}

	private static double jitter(RandomSource random) {
		return (random.nextDouble() - 0.5D) * SPREAD;
	}

	/** 脱板 / 换人：把该实体的记录清掉，免得 id 复用串状态。 */
	private static void forget(int id) {
		LAST_SPEED.remove(id);
		LAST_DIR.remove(id);
		BURST_COOLDOWN.remove(id);
	}
}
