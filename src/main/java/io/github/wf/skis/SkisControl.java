package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * <b>双板（skis）专属</b>的滑行数值与身体朝向。
 *
 * <p>这个类和单板的 {@link SnowboardControl} <b>完全独立</b>：只有双板自己的参数、自己的方法。
 * 允许共用的只有“平台级”的东西（{@link SkisMod} 的物品判定、{@link SkiGlide} 的 tick 循环骨架）。
 * 以后调双板的手感只改这里，不会碰到单板。
 *
 * <p><b>朝向</b>：A / D 只转<b>身体朝向</b>
 * （{@link #BODY_YAW} 锁表，每 tick {@link #TURN_RATE_DEGREES} 度），滑行方向跟身体走。
 * 和单板一样是「锁住 + 按键转」，区别是双板的 W / S 仍然沿身体前后滑（单板的 W / S 只改姿态）。
 *
 * <p><b>按 W 时身体朝视角转</b>：{@link #turnTowardView} 每 tick 把身体朝视角方向拨
 * {@link #VIEW_TURN_RATE_DEGREES} 度（限幅、不越过目标）——<b>转的是身体，视角在世界坐标里仍然不动</b>，
 * 与 A / D 转身体是同一种事，位移只跟身体前方走。
 *
 * <p><b>落地对齐</b>：贴地且没按 S 时，把<b>速度方向</b>朝身体前方每 tick 掰
 * {@link #ALIGN_DEGREES_PER_TICK} 度（{@link #shouldAlignOnGround} / {@link #alignVelocity}，
 * 由 {@link SkiGlide} 的双板分支调用）—— 转完身落地，方向立刻跟上，不靠 {@code SkiControl.steer} 慢慢摆。
 *
 * <p><b>视角</b>：鼠标转，但相对锁定的身体朝向最多左右各 {@link #VIEW_LIMIT_DEGREES} 度
 * （与单板同一个限位、同一套实现，见 {@code client/SkiViewSmoothing}）。没顶到限位时视角留在世界坐标里
 * 不动（身体在下面转）；顶到之后头被身体拖着走。
 */
public final class SkisControl {
	/** 实体 id → 双板的身体朝向（度）。A/D 只改它，<b>不碰视角</b>。 */
	private static final IdMap<Float> BODY_YAW = new IdMap<>();
	/** 双板：按住 A / D 时每 tick 转身体多少度（4.5°/tick ≈ 90°/s，180° 约 2 s）。 */
	private static final float TURN_RATE_DEGREES = 4.5F;
	/**
	 * <b>双板：头（视角）相对锁定身体朝向能转的最大角度</b>：左右各 {@code 90} 度，合计 180 ——
	 * 刚好看到正侧方，背后整片都是禁区。
	 *
	 * <p>与单板的 {@link SnowboardControl#VIEW_LIMIT_DEGREES} <b>各自一份常量</b>：两块板各改各的，
	 * 改这里不会动单板。<b>限的是视角，不是转向</b> —— A / D 的 {@link #TURN_RATE_DEGREES} 一点没变。
	 */
	public static final float VIEW_LIMIT_DEGREES = 90.0F;

	/** W 时身体每 tick 朝视角方向转多少度（4°/tick ≈ 80°/s；90° 残余约 1.15 s 对齐）。 */
	private static final float VIEW_TURN_RATE_DEGREES = 4.0F;

	/** 残余偏角小于它就当成对齐（防抖，不要每 tick 无限逼近）。 */
	private static final float VIEW_TURN_MIN_DEGREES = 0.5F;

	/**
	 * 下落加速的基数：每下落 1 格加多少。
	 *
	 * <p>0.1 → 0.12 → 0.08 → <b>0.1</b>：取 <b>0.1</b> 是因为它下坡给得出推背感，又不至于一两格就顶到速度上限。
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
	 * 1.0 = 原版；1.3 = 0.104 格/tick²；<b>1.5 = 0.12 格/tick²</b>。
	 * 值越大下落越干脆，但跳高与滞空跟着被压（1.5 时跳高 ≈0.93 格）。
	 */
	private static final double GRAVITY_MULTIPLIER = 1.5D;

	/** 双板速度上限（格/tick）：0.85 格/tick ＝ 61.2 格/秒 ≈ 61 km/h（撑杖冲刺的峰值也封顶在这里）。 */
	private static final double MAX_SPEED = 0.85D;

	/** 动量每 tick 保留比例 ＝ 有效摩擦力（0.985 ≈ 2 秒掉一半）。 */
	private static final double MOMENTUM_DECAY = 0.998D;   // 每 tick 掉 0.2%（1 秒 −4%）：两板同值，滑行手感的基线

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

	/**
	 * <b>双板落地对齐的速度方向步长</b>（度/tick）：贴地后把水平速度的<b>方向</b>朝身体前方
	 * 每 tick 最多掰这么多度 —— <b>9.0°/tick ≈ 180°/s，落地后一两 tick 就把速度方向掰到朝向上</b>
	 * （转完身落地不再「方向不跟、不跟手」）。
	 *
	 * <p>只转方向、不动大小；按 S（刹车 / 倒滑）那一 tick 不对齐（见 {@link #shouldAlignOnGround}）。
	 * 调手感：调「落地后方向跟上得多快」—— 180°/s 是「立刻跟上但不闪」的档；嫌跳改 5、嫌拖改 14。
	 */
	private static final double ALIGN_DEGREES_PER_TICK = 9.0D;

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

	/**
	 * <b>W：把身体朝视角方向转一步</b>（步长限幅、不越过目标），返回新朝向。
	 *
	 * <p>只转 {@link #BODY_YAW}，<b>不碰视角</b>：视角在世界坐标里不动，身体在下面转过去；
	 * 「视角相对身体」的偏角因此每 tick 收窄 {@link #VIEW_TURN_RATE_DEGREES} 度，
	 * 由 {@code client/SkiViewSmoothing} 的 {@code headOffset -= bodyStep} 自动收敛。
	 * 残余偏角小于 {@link #VIEW_TURN_MIN_DEGREES} 就不动（防抖）。
	 *
	 * <p>客户端与服务端各跑一次同一条规则（顺序：先 A / D 的 {@link #turnBody}，再这里）。
	 */
	public static float turnTowardView(int entityId, float fallback, float viewYaw) {
		float yaw = BODY_YAW.getOrDefault(entityId, fallback);
		float delta = Mth.wrapDegrees(viewYaw - yaw);
		if (Float.isNaN(delta) || Math.abs(delta) < VIEW_TURN_MIN_DEGREES) {
			return yaw;
		}
		float step = Mth.clamp(delta, -VIEW_TURN_RATE_DEGREES, VIEW_TURN_RATE_DEGREES);
		yaw += step;
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

	// ---------------------------------------------------------------- 落地对齐（双板专用）

	/**
	 * <b>这一 tick 要不要做「贴地对齐」</b>（双板专用，由 {@link SkiGlide} 的双板分支调用）：
	 *
	 * <ol>
	 *   <li>{@code player.onGround()} —— 只有贴地才掰方向（空中改速度方向会把人「拽」着走）；</li>
	 *   <li><b>没按 S</b>：{@code input} 为 <b>null 时放行</b>（拿不到输入不该关掉对齐），
	 *       {@code input.backward()} 为 true 直接返回 false —— 刹车 / 倒滑这一 tick 不拨方向；</li>
	 *   <li>水平速度<b>平方 &gt; 1.0E-4</b> —— 几乎停下就不值得掰方向。</li>
	 * </ol>
	 */
	public static boolean shouldAlignOnGround(ServerPlayer player, Input input) {
		if (!player.onGround()) {
			return false;
		}
		if (input != null && input.backward()) {
			return false;
		}
		Vec3 motion = player.getDeltaMovement();
		return motion.x * motion.x + motion.z * motion.z > 1.0E-4D;
	}

	/**
	 * <b>贴地对齐</b>：把水平速度的<b>方向</b>朝身体前方（{@link #BODY_YAW}，拿不到锁时用玩家 yaw 兜底）
	 * 转 {@code min(|残差|, ALIGN_DEGREES_PER_TICK)} 度 —— <b>只改方向、大小一点不动</b>
	 * （绕 Y 旋转水平分量，{@code y} 原样保留），写回 {@code setDeltaMovement} 并置
	 * {@code hurtMarked = true}（不置会被客户端的移动预测抹掉）。
	 *
	 * <p>残差用 {@link Mth#wrapDegrees(float)} 算（−180…180），所以「已经转过身」时也是走<b>短的那条</b>弧、
	 * 不会绕远路；每 tick 只转 {@link #ALIGN_DEGREES_PER_TICK} 9° ≈ 180°/s，落地后一两 tick 就朝上。
	 * 方向角与 {@link #BODY_YAW} 同一套参数化：{@code 方向角 = atan2(−x, z)}，yaw 增大 ＝ 向右转
	 * （与 {@code SnowboardControl#rotateVelocity} 的旋转公式一致）。
	 *
	 * <p>判定在 {@link #shouldAlignOnGround}；本方法只做旋转，不判条件、也不动动量。
	 */
	public static void alignVelocity(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		double speedSqr = motion.x * motion.x + motion.z * motion.z;
		if (speedSqr <= 1.0E-4D) {
			return;   // 没速度可掰
		}
		double current = Math.toDegrees(Math.atan2(-motion.x, motion.z));
		float yaw = bodyYaw(player.getId(), player.getYRot());
		float residual = Mth.wrapDegrees((float) (yaw - current));
		double step = Mth.clamp(residual, -ALIGN_DEGREES_PER_TICK, ALIGN_DEGREES_PER_TICK);
		double rad = Math.toRadians(step);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		player.setDeltaMovement(motion.x * cos - motion.z * sin, motion.y, motion.z * cos + motion.x * sin);
		player.hurtMarked = true;
	}
}
