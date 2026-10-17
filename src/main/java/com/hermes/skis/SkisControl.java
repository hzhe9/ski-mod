package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * <b>双板（skis）专属</b>的滑行数值与身体朝向。
 *
 * <p>这个类和单板的 {@link SnowboardControl} <b>完全独立</b>：只有双板自己的参数、自己的方法。
 * 允许共用的只有“平台级”的东西（{@link SkisMod} 的物品判定、{@link SkiGlide} 的 tick 循环骨架）。
 * 以后调双板的手感只改这里，不会碰到单板。
 *
 * <p><b>朝向（2026-10-17 用户定）</b>：视角<b>只听鼠标</b>，A / D 只转<b>身体朝向</b>
 * （{@link #BODY_YAW} 锁表，每 tick {@link #TURN_RATE_DEGREES} 度），滑行方向跟身体走。
 * 和单板一样是「锁住 + 按键转」，区别是双板的 W / S 仍然沿身体前后滑（单板的 W / S 只改姿态）。
 */
public final class SkisControl {
	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 双板的身体朝向（度）。A/D 只改它，<b>不碰视角</b>。 */
	private static final Map<Integer, Float> BODY_YAW = new ConcurrentHashMap<>();
	/** 双板：按住 A / D 时每 tick 转身体多少度（8°/tick ≈ 160°/s，180° 约 1.1 s）。 */
	private static final float TURN_RATE_DEGREES = 8.0F;

	/**
	 * 下落加速的基数：每下落 1 格加多少。
	 *
	 * <p>0.1 → 0.12（2026-10-13）→ 0.08（2026-10-17 上午）→ <b>0.1</b>（2026-10-17 用户回调）。
	 * 每格 ≈ 7.2 km/h；每 0.5 格结算一次 ＝ 单次 0.05。
	 * 实际增量 = {@code 基数 × 累计下落格数^指数}（见 {@link SkiGlide} 的 {@code FALL_GAIN_EXPONENT}，
	 * 现在指数是 1.0 ＝ 线性；指数 < 1 时才是「越掉越快不起来」）。
	 */
	private static final double FALL_GAIN_PER_BLOCK = 0.1D;

	/** 单 tick 下落加速上限（防止悬崖一瞬间给爆）。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;

	/**
	 * <b>穿双板时的重力倍数</b>：把原版 {@code Attributes.GRAVITY}（0.08 格/tick²）乘上这个值，
	 * <b>全程生效</b>（贴地滑行、跳跃、坠落都算）。
	 * 1.0 = 原版；1.3 = 0.104 格/tick²；<b>1.5 = 0.12 格/tick²（用户 2026-10-17 定）</b>。
	 * 值越大下落越干脆，但跳高与滞空跟着被压（1.5 时跳高 ≈0.93 格）。
	 */
	private static final double GRAVITY_MULTIPLIER = 1.5D;

	/** 双板速度上限（格/tick）：0.85 格/tick ＝ 61.2 格/秒 ≈ 61 km/h（用户 2026-10-17 定；撑杖冲刺的峰值也封顶在这里）。 */
	private static final double MAX_SPEED = 0.85D;

	/** 动量每 tick 保留比例 ＝ 有效摩擦力（0.985 ≈ 2 秒掉一半）。 */
	private static final double MOMENTUM_DECAY = 0.998D;   // 用户 2026-10-13 定 0.998（两板同值）

	/**
	 * <b>双板刹车</b>：按住 S（且还在往前滑）时，动量与速度每 tick 保留的比例。
	 *
	 * <p>0.92 → 1 秒（20 tick）剩 {@code 0.92^20 ≈ 19%}，约 1 秒内从满速刹到走。
	 * 想更利落改 0.90、想更轻改 0.96。<b>只服务双板</b>，单板的摩擦看 {@link SnowboardControl}。
	 */
	private static final double BRAKE_DECAY = 0.92D;

	/**
	 * <b>双板刹车</b>的判定阈值：速度方向与视角前方（水平）的点积超过它，就算「还在往前滑」。
	 *
	 * <p>也就是夹角小于约 72°：此时按 S 是<b>刹车</b>（只减速、不拨方向）；
	 * 低于它（已经横着滑 / 在倒滑）按 S 才是真的「往后走」。
	 */
	private static final double BRAKE_MIN_ALIGN = 0.3D;

	private SkisControl() {
	}

	/** 脱下板时清一下（脱板路径的统一调用入口，实际清的是身体朝向）。 */
	public static void forget(int entityId) {
		forgetBody(entityId);
	}

	// ---------------------------------------------------------------- 身体朝向

	/** 穿双板时锁住身体朝向：<b>第一次调用</b>记下当时的朝向，之后只由 {@link #turnBody} 改。 */
	public static void ensureLocked(int entityId, float yaw) {
		BODY_YAW.putIfAbsent(entityId, yaw);
		if (BODY_YAW.size() > MAX_ENTRIES) {
			BODY_YAW.clear();
		}
	}

	/** 拿不到锁时用传入的视角兜底。 */
	public static float bodyYaw(int entityId, float fallback) {
		return BODY_YAW.getOrDefault(entityId, fallback);
	}

	/**
	 * 按输入转身体一步（+1 左 / -1 右 / 0 不转），返回新朝向。
	 *
	 * <p>MC 的水平朝向向量是 {@code (-sin yaw, cos yaw)}：<b>yaw 增大 ＝ 向右转</b>，
	 * 所以按 A（左）要<b>减</b>、按 D（右）要加。这里<b>只动身体朝向</b>，视角一律不碰。
	 */
	public static float turnBody(int entityId, float fallback, double side) {
		float yaw = BODY_YAW.getOrDefault(entityId, fallback) - (float) side * TURN_RATE_DEGREES;
		BODY_YAW.put(entityId, yaw);
		return yaw;
	}

	public static void forgetBody(int entityId) {
		BODY_YAW.remove(entityId);
	}

	public static void forgetBodyAll() {
		BODY_YAW.clear();
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

	/** 双板：<b>穿板时的重力倍数</b>（原版 0.08 格/tick² × 这个值；见 {@link #GRAVITY_MULTIPLIER}）。 */
	public static double gravityMultiplier() {
		return GRAVITY_MULTIPLIER;
	}

	/** 双板：按 S 刹车时每 tick 保留的比例（见 {@link #BRAKE_DECAY}）。 */
	public static double brakeDecay() {
		return BRAKE_DECAY;
	}

	/** 速度方向与<b>身体前方</b>（水平）的点积 &gt; {@link #BRAKE_MIN_ALIGN} 就算「还在往前滑」。 */
	public static boolean isMovingForward(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			return false;
		}
		// 用身体朝向而不是视角：双板现在视角可以独立于滑行方向，拿视角判「在往前滑」会在转过身后失效
		float yaw = bodyYaw(player.getId(), player.getYRot()) * Mth.DEG_TO_RAD;
		Vec3 front = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		return horizontal.normalize().dot(front) > BRAKE_MIN_ALIGN;
	}
}
