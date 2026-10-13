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
 * <p>三层效果：
 * <ul>
 *   <li><b>贴地雪屑</b>（{@link #SPRAY}）：从板的两侧（与滑行方向垂直的左右两边）向外、略向上溅，
 *       数量随速度增加；</li>
 *   <li><b>上升雪雾</b>（{@link #FLURRY}）：同样从板两侧起，但出生点更高、向上的初速度随速度线性增加
 *       —— <b>越快飘得越高</b>，数量也涨得比雪屑快；</li>
 *   <li><b>急减速雪雾</b>（{@link #burst}）：一 tick 内速度掉了 {@link #BURST_DROP} 以上时，
 *       炸开一大团雪雾飘向空中（急停 / 撞墙 / 压刃刹车都是这个形状），粒子数随掉落量增加。</li>
 * </ul>
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求左向量），落点在脚底高度，
 * 所以双板与单板共用同一套（单板板身横着，看着就是从切雪的那条边溅起来）。
 */
public final class SnowSpray {
	/** 贴地雪屑的粒子种类：{@code ITEM_SNOWBALL} = 雪块崩开的小白屑（有重力，落地即没）。 */
	private static final ParticleOptions SPRAY = ParticleTypes.ITEM_SNOWBALL;
	/** 上升雪雾的粒子种类：{@code SNOWFLAKE} = 雪花（几乎不受重力，给向上的初速度就会飘上去）。 */
	private static final ParticleOptions FLURRY = ParticleTypes.SNOWFLAKE;
	/** 速度低于这个（格/tick）就不溅 —— 慢慢挪一步不该扬雪。 */
	private static final double MIN_SPEED = 0.10D;
	/** 贴地雪屑：每侧数量 = 速度 × 这个系数（再夹到上限）。 */
	private static final double SPRAY_PER_SPEED = 6.0D;
	/** 贴地雪屑的每侧数量上限。 */
	private static final int SPRAY_MAX_PER_SIDE = 2;
	/** 上升雪雾：每侧数量 = 速度 × 这个系数（比雪屑涨得快）。 */
	private static final double FLURRY_PER_SPEED = 8.0D;
	/** 上升雪雾的每侧数量上限。 */
	private static final int FLURRY_MAX_PER_SIDE = 2;
	/** 上升雪雾的基础上升速度（速度最慢时也要有一点点飘）。 */
	private static final double FLURRY_UPWARD = 0.05D;
	/** 上升速度里随速度线性增加的那份 —— 这就是「越快飘得越高」。 */
	private static final double FLURRY_UPWARD_PER_SPEED = 0.35D;
	/** 上升雪雾出生点随速度抬高多少（格每「格/tick」）：太快的时候从高处起飘。 */
	private static final double FLURRY_HEIGHT_PER_SPEED = 0.30D;
	/** 板两边的横向偏移（格）：撒在脚的两侧，而不是脚底下。 */
	private static final double SIDE_OFFSET = 0.34D;
	/** 贴地雪屑向外（垂直于滑行方向）的溅出速度。 */
	private static final double OUTWARD = 0.14D;
	/** 贴地雪屑的向上分量。 */
	private static final double UPWARD = 0.10D;
	/** 向后（与滑行相反）的分量 —— 雪是往后飞的。 */
	private static final double BACKWARD = 0.12D;
	/** 出生点随机散布（格）。 */
	private static final double SPREAD = 0.16D;
	/** 出生点相对脚底的高度偏移（格）。 */
	private static final double Y_OFFSET = 0.06D;

	/** 一 tick 内速度掉了这么多（格/tick）才算「突然大幅降低」。 */
	private static final double BURST_DROP = 0.15D;
	/** 掉落之前至少得有这么快（站着不动凭空掉到 0 不算）。 */
	private static final double BURST_MIN_SPEED = 0.15D;
	/** 一团雪雾的粒子数 = 掉落量 × 这个系数（再夹到上限）。 */
	private static final double BURST_PER_DROP = 90.0D;
	/** 一团雪雾的粒子数上限。 */
	private static final int BURST_MAX = 30;
	/** 两团雪雾之间的最短间隔（tick）—— 防抖，免得速度抖动时连着炸。 */
	private static final int BURST_COOLDOWN_TICKS = 4;
	/** 雪雾炸开时的上升速度（飘向空中）。 */
	private static final double BURST_UPWARD = 0.22D;
	/** 雪雾炸开时的随机散布半径（格）。 */
	private static final double BURST_RADIUS = 0.55D;

	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 上一 tick 的水平速度（用来发现「突然减速」）。 */
	private static final Map<Integer, Double> LAST_SPEED = new ConcurrentHashMap<>();
	/** 实体 id → 雪雾冷却剩余 tick。 */
	private static final Map<Integer, Integer> BURST_COOLDOWN = new ConcurrentHashMap<>();

	private SnowSpray() {
	}

	/** 每客户端 tick 跑一次：遍历场上的玩家，只给穿着板、在雪面上的人扬雪。 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		if (LAST_SPEED.size() > MAX_ENTRIES) {
			LAST_SPEED.clear();
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
			int cooldown = BURST_COOLDOWN.getOrDefault(id, 0);
			if (cooldown > 0) {
				BURST_COOLDOWN.put(id, cooldown - 1);
			}
			if (!SkiGlide.isOnSnowOrIce(level, player.blockPosition())) {
				continue;   // 没在雪面上就没有雪可扬
			}
			// 突然减速：炸一团雪雾飘向空中（急停、撞墙、压刃刹车都是这个形状）
			if (last != null && cooldown == 0
					&& last > BURST_MIN_SPEED && last - speed > BURST_DROP) {
				burst(level, player, last - speed);
				BURST_COOLDOWN.put(id, BURST_COOLDOWN_TICKS);
			}
			if (!player.onGround() || speed < MIN_SPEED) {
				continue;   // 腾空 / 太慢：不持续扬雪
			}
			spray(level, player, speed);
		}
	}

	/** 滑行中持续扬雪：贴地雪屑 + 上升雪雾，两层都随速度变多、后者还随速度飘得更高。 */
	private static void spray(ClientLevel level, Player player, double speed) {
		Vec3 motion = player.getDeltaMovement();
		double dirX = motion.x / speed;
		double dirZ = motion.z / speed;
		double leftX = -dirZ;   // 左向量
		double leftZ = dirX;
		RandomSource random = player.getRandom();
		int flecks = Mth.clamp((int) (speed * SPRAY_PER_SPEED), 1, SPRAY_MAX_PER_SIDE);
		int flurries = Mth.clamp((int) (speed * FLURRY_PER_SPEED), 1, FLURRY_MAX_PER_SIDE);
		// 越快：上升速度越大、出生点越高 —— 于是雪雾飘得越来越高
		double rise = FLURRY_UPWARD + speed * FLURRY_UPWARD_PER_SPEED;
		double riseY = player.getY() + Y_OFFSET + speed * FLURRY_HEIGHT_PER_SPEED;
		for (int side = -1; side <= 1; side += 2) {
			double baseX = player.getX() + leftX * SIDE_OFFSET * side;
			double baseZ = player.getZ() + leftZ * SIDE_OFFSET * side;
			double outX = leftX * OUTWARD * side - dirX * BACKWARD;
			double outZ = leftZ * OUTWARD * side - dirZ * BACKWARD;
			for (int i = 0; i < flecks; i++) {
				level.addParticle(SPRAY,
						baseX + jitter(random), riseY + random.nextDouble() * Y_OFFSET, baseZ + jitter(random),
						outX, UPWARD * (0.5D + random.nextDouble()), outZ);
			}
			for (int i = 0; i < flurries; i++) {
				level.addParticle(FLURRY,
						baseX + jitter(random), riseY, baseZ + jitter(random),
						outX * 0.6D, rise * (0.7D + random.nextDouble() * 0.6D), outZ * 0.6D);
			}
		}
	}

	/** 急减速：从脚下一圈炸开一大团向上飘的雪雾。 */
	private static void burst(ClientLevel level, Player player, double drop) {
		RandomSource random = player.getRandom();
		int count = Mth.clamp((int) (drop * BURST_PER_DROP), 1, BURST_MAX);
		double y = player.getY() + Y_OFFSET;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = random.nextDouble() * BURST_RADIUS;
			double dx = Math.cos(angle) * radius;
			double dz = Math.sin(angle) * radius;
			level.addParticle(FLURRY,
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
		BURST_COOLDOWN.remove(id);
	}
}
