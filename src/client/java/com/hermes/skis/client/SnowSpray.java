package com.hermes.skis.client;

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
 * <b>滑行雪粒子</b>：穿着滑雪板在雪面上滑、且有速度时，每 tick 从<b>板的两侧</b>各溅起几颗雪屑
 * —— 雪往后上方飞，越滑越快、越多。
 *
 * <p>纯客户端、纯观感（{@link ClientLevel#addParticle}），服务端不知道、也不参与任何手感计算：
 * 粒子多少不影响速度，速度只决定粒子多少。
 *
 * <p>「板的两侧」＝与当前滑行方向<b>垂直</b>的左右两边（沿速度方向求左向量），落点在脚底高度，
 * 所以双板与单板共用同一套（单板板身横着，看着就是从切雪的那条边溅起来）。
 */
public final class SnowSpray {
	/** 粒子种类：{@code ITEM_SNOWBALL} = 雪块崩开的小白屑（最像飞雪）；换 {@code SNOWFLAKE} 就是慢慢飘的雪花。 */
	private static final ParticleOptions PARTICLE = ParticleTypes.ITEM_SNOWBALL;
	/** 速度低于这个（格/tick）就不溅 —— 慢慢挪一步不该扬雪。 */
	private static final double MIN_SPEED = 0.10D;
	/** 粒子数随速度放大：每侧数量 = 速度 × 这个系数（再夹到上限）。 */
	private static final double COUNT_PER_SPEED = 6.0D;
	/** 每侧每 tick 的粒子数上限。 */
	private static final int MAX_PER_SIDE = 2;
	/** 板两边的横向偏移（格）：撒在脚的两侧，而不是脚底下。 */
	private static final double SIDE_OFFSET = 0.34D;
	/** 向外（垂直于滑行方向）的溅出速度。 */
	private static final double OUTWARD = 0.14D;
	/** 向上分量。 */
	private static final double UPWARD = 0.10D;
	/** 向后（与滑行相反）的分量 —— 雪是往后飞的。 */
	private static final double BACKWARD = 0.12D;
	/** 出生点随机散布（格）。 */
	private static final double SPREAD = 0.16D;
	/** 出生点相对脚底的高度偏移（格）。 */
	private static final double Y_OFFSET = 0.06D;

	private SnowSpray() {
	}

	/** 每客户端 tick 跑一次：遍历场上的玩家，只给穿着板、在雪面上、正滑得动的人溅雪。 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		for (Player player : level.players()) {
			if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
				continue;
			}
			if (!player.onGround()) {
				continue;   // 腾空不溅
			}
			Vec3 motion = player.getDeltaMovement();
			double speed = motion.horizontalDistance();
			if (speed < MIN_SPEED) {
				continue;
			}
			if (!SkiGlide.isOnSnowOrIce(level, player.blockPosition())) {
				continue;   // 只在雪 / 冰面上
			}
			spray(level, player, motion, speed);
		}
	}

	private static void spray(ClientLevel level, Player player, Vec3 motion, double speed) {
		// 滑行方向与它的左向量（都在水平面上）
		double dirX = motion.x / speed;
		double dirZ = motion.z / speed;
		double leftX = -dirZ;
		double leftZ = dirX;
		RandomSource random = player.getRandom();
		int count = Mth.clamp((int) (speed * COUNT_PER_SPEED), 1, MAX_PER_SIDE);
		double y = player.getY() + Y_OFFSET;
		for (int side = -1; side <= 1; side += 2) {
			double baseX = player.getX() + leftX * SIDE_OFFSET * side;
			double baseZ = player.getZ() + leftZ * SIDE_OFFSET * side;
			double outX = leftX * OUTWARD * side - dirX * BACKWARD;   // 向外 + 向后
			double outZ = leftZ * OUTWARD * side - dirZ * BACKWARD;
			for (int i = 0; i < count; i++) {
				level.addParticle(PARTICLE,
						baseX + (random.nextDouble() - 0.5D) * SPREAD,
						y + random.nextDouble() * Y_OFFSET,
						baseZ + (random.nextDouble() - 0.5D) * SPREAD,
						outX, UPWARD * (0.5D + random.nextDouble()), outZ);
			}
		}
	}
}
