package com.hermes.skis;

/**
 * <b>双板（skis）专属</b>的滑行数值。
 *
 * <p>这个类和单板的 {@link SnowboardControl} <b>完全独立</b>：只有双板自己的参数、自己的方法。
 * 允许共用的只有“平台级”的东西（{@link SkisMod} 的物品判定、{@link SkiGlide} 的 tick 循环骨架）。
 * 以后调双板的手感只改这里，不会碰到单板。
 *
 * <p><b>朝向</b>：双板<b>不锁朝向</b> —— 视角转多少人就朝哪走，滑行方向也不额外锁定
 * （2026-10 用户定案：回到最开始的状态）。所以这里没有朝向表、没有转向方法。
 */
public final class SkisControl {

	/**
	 * 下落加速的基数：每下落 1 格加多少（用户 2026-10 定案 0.08）。
	 *
	 * <p>注意实际增量是 {@code 基数 × √(累计下落格数)}（见 {@link SkiGlide}）——
	 * <b>越掉越快不起来</b>：掉得越多，每多掉 1 格带来的增量越小（用户要求“增长量反而越来越小”）。
	 */
	private static final double FALL_GAIN_PER_BLOCK = 0.1D;

	/** 单 tick 下落加速上限（防止悬崖一瞬间给爆）。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;

	/** 双板速度上限（格/tick）：1.4 ≈ 100 km/h，仍是最快的那个。 */
	private static final double MAX_SPEED = 1.4D;

	/** 动量每 tick 保留比例 ＝ 有效摩擦力（0.985 ≈ 2 秒掉一半）。 */
	private static final double MOMENTUM_DECAY = 0.99D;   // 原 0.985 太涩；用户定 0.99

	/** 按 S（后倾）刹车的额外衰减：0.90 ≈ 半秒停住，松开后不会被动量拉回原速。 */
	private static final double BRAKE_DECAY = 0.90D;

	private SkisControl() {
	}

	/** 脱下板时清一下（当前双板不持有任何朝向状态，留着是为了脱板路径的统一调用）。 */
	public static void forget(int entityId) {
		// 双板是“视角即朝向”，没有需要忘掉的锁定状态。
	}

	// ---------------------------------------------------------------- 参数

	/** 双板：每下落 1 格加多少水平速度。 */
	public static double fallGainPerBlock() {
		return FALL_GAIN_PER_BLOCK;
	}

	/** 双板：速度上限（格/tick）。 */
	public static double maxSpeed() {
		return MAX_SPEED;
	}

	/** 双板：单 tick 下落加速上限。 */
	public static double fallGainPerTickMax() {
		return FALL_GAIN_PER_TICK_MAX;
	}

	/** 双板：动量衰减（越接近 1 越滑）。 */
	public static double momentumDecay() {
		return MOMENTUM_DECAY;
	}

	/** 双板：按 S 刹车的额外衰减系数。 */
	public static double brakeDecay() {
		return BRAKE_DECAY;
	}
}
