package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>双板（skis）专属</b>的转向规则与滑行参数。
 *
 * <p>这个类和单板的 {@link SnowboardControl} <b>完全独立</b>：有自己的朝向表、自己的参数、自己的方法。
 * 允许共用的只有“平台级”的东西（{@link SkisMod} 的物品判定、{@link SkiGlide} 的 tick 循环骨架）。
 * 以后调双板的手感只改这里，不会碰到单板。
 *
 * <p><b>它做什么</b>：锁住板 / 身体的朝向（穿上板那一刻），只有 A / D 能改这个朝向 ——
 * 视角随便转都不参与（原来用玩家 yaw 当基准，转视角板就跟着转）。
 */
public final class SkisControl {

	/** 双板锁定朝向：实体 id → yaw（穿板那一刻的值，之后只被 A / D 改）。 */
	private static final Map<Integer, Float> HEADING = new ConcurrentHashMap<>();

	/** 按 A / D 时锁定朝向每 tick 转多少度。 */
	private static final float TURN_RATE_DEGREES = 3.0F;

	/** 每下落 1 格加多少水平速度（双板值；用户 2026-10 定案：0.18 / 0.10 都太猛，0.08）。 */
	private static final double FALL_GAIN_PER_BLOCK = 0.08D;

	/** 单 tick 下落加速上限（防止悬崖一瞬间给爆）。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;

	/** 动量每 tick 保留比例 ＝ 有效摩擦力（双板 0.985 ≈ 2 秒掉一半）。 */
	private static final double MOMENTUM_DECAY = 0.985D;

	/** 按 S（后倾）刹车的额外衰减：0.90 ≈ 半秒停住，松开后不会被动量拉回原速。 */
	private static final double BRAKE_DECAY = 0.90D;

	private SkisControl() {
	}

	// ---------------------------------------------------------------- 朝向

	/** 第一次穿上板时把朝向定下来（已有就不动）。 */
	public static void ensureLocked(int entityId, float fallbackYaw) {
		HEADING.putIfAbsent(entityId, fallbackYaw);
	}

	/** 锁定的朝向；没锁过就用兜底值（视角 yaw）。 */
	public static float heading(int entityId, float fallbackYaw) {
		return HEADING.getOrDefault(entityId, fallbackYaw);
	}

	/** 脱下板时忘掉。 */
	public static void forget(int entityId) {
		HEADING.remove(entityId);
	}

	public static void forgetAll() {
		HEADING.clear();
	}

	/** 转锁定朝向（A / D 用）。 */
	public static void turn(int entityId, float degrees) {
		HEADING.computeIfPresent(entityId, (id, yaw) -> yaw + degrees);
	}

	// ---------------------------------------------------------------- 每 tick

	/**
	 * 每 tick：把 A / D 变成转向 —— 转的是<b>锁定朝向本身</b>，所以模型和板会跟着一起转
	 * （不像“只改滑行方向”那样模型纹丝不动）。
	 *
	 * <p>⚠️ MC 里 yaw 增大＝往右转（yaw=0 朝 +z，yaw=90 朝 -x），所以“按 A（side=+1）往左”要减 yaw。
	 */
	public static void steer(Player player, Input input) {
		double side = sideSign(input);
		if (side == 0.0D) {
			return;
		}
		turn(player.getId(), (float) (-side * TURN_RATE_DEGREES));
	}

	/** 当前按的左右：+1 = 左（A），-1 = 右（D），0 = 没按。 */
	public static double sideSign(Input input) {
		if (input == null) {
			return 0.0D;
		}
		return (input.left() ? 1.0D : 0.0D) - (input.right() ? 1.0D : 0.0D);
	}

	// ---------------------------------------------------------------- 方向

	/** 沿锁定朝向的前方（w=视角 yaw 时指向 +z）。 */
	public static Vec3 front(int entityId, float fallbackYaw) {
		float rad = heading(entityId, fallbackYaw) * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(rad), 0.0D, Mth.cos(rad));
	}

	/** 沿锁定朝向的左 / 右（sign：+1 左、-1 右）。 */
	public static Vec3 left(int entityId, float fallbackYaw, double sign) {
		float rad = heading(entityId, fallbackYaw) * Mth.DEG_TO_RAD;
		return new Vec3(Mth.cos(rad) * sign, 0.0D, Mth.sin(rad) * sign);
	}

	// ---------------------------------------------------------------- 参数

	public static double fallGainPerBlock() {
		return FALL_GAIN_PER_BLOCK;
	}

	public static double fallGainPerTickMax() {
		return FALL_GAIN_PER_TICK_MAX;
	}

	public static double momentumDecay() {
		return MOMENTUM_DECAY;
	}

	public static double brakeDecay() {
		return BRAKE_DECAY;
	}
}
