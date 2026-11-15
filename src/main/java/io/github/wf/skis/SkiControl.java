package io.github.wf.skis;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * 滑行时的方向控制：读玩家的真实按键（服务端 {@code ServerPlayer.getLastClientInput()}），
 * 把速度方向朝<b>身体前方</b>摆一点。
 *
 * <p><b>只服务双板，而且只在地面上</b>：单板是「人板一体」站定的，按键一律不给方向
 * （它的转向全在 {@link SnowboardControl#applyCarve}）；双板在空中也只让视角 / 身体转，速度方向一点都不动
 * （在空中改速度方向会把人「拽」着走）。所以判据就是一句 {@code player.onGround()} ——
 * 曾经还有一条「脚底离地 ≤ 1 格也能转」的门，可两个分支都把它的结果丢掉了（单板分支直接返回 null、
 * 双板分支在 {@code !onGround} 时返回 null），那套判定与它用的射线已删。
 *
 * <p>冲刺（SkiBoost）和滑行动量（SkiGlide）都用这里的转向，规则一致。
 *
 * <p>速度上限（{@link #maxGlideSpeed(ServerPlayer)}，按板取各自的上限）的现实参照
 * （1 格 = 1 米，格/tick × 72 = km/h）：
 * <ul>
 *   <li>MC 走路 0.216 格/tick ≈ 15.6 km/h；疾跑 0.28 ≈ 20.2 km/h（游戏本身已比现实快约 3 倍）；</li>
 *   <li>现实世界杯速降赛普遍 &gt;130 km/h，最快纪录 161.9 km/h（Johan Clarey，2013 Wengen）；</li>
 *   <li>现实速度滑雪（KL）世界纪录 255.5 km/h（Simon Billy，2023 Vars）＝ 3.55 格/tick，
 *       在 MC 里会一 tick 穿过 3.5 格，直接漏过碰撞检测，所以只能取一个"游戏能承受"的值。</li>
 * </ul>
 */
public final class SkiControl {
	/** 每 tick 把方向朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）。 */
	private static final double STEER_RATE = 0.2D;

	/** 速度上限按板分开：单板 {@code SnowboardControl.maxSpeed()}、双板 {@code SkisControl.maxSpeed()}。 */
	public static double maxGlideSpeed(ServerPlayer player) {
		return SnowboardControl.isSnowboard(player) ? SnowboardControl.maxSpeed() : SkisControl.maxSpeed();
	}

	private SkiControl() {
	}

	/**
	 * 把方向朝玩家按键的方向转一点；没按键或不允许转向时原样返回。
	 *
	 * <p>单板例外：它完全不参与方向（人板一体站定），按键一律不给方向。
	 */
	public static Vec3 steer(Vec3 current, ServerPlayer player) {
		Vec3 desired = desiredDirection(player);
		if (desired == null) {
			return current;
		}
		return current.lerp(desired, STEER_RATE).normalize();
	}

	/** 玩家按键在世界坐标下的水平方向；没按方向键、单板、不在贴地、拿不到输入都返回 null。 */
	public static Vec3 desiredDirection(ServerPlayer player) {
		// 双板：空中只让视角 / 身体转，速度方向不动；落地后才开始跟着转（在空中改速度方向会把人「拽」着走）。
		if (!player.onGround()) {
			return null;
		}
		Input input = player.getLastClientInput();
		if (input == null) {
			return null;
		}
		if (SnowboardControl.isSnowboard(player)) {
			return null;   // 单板：人板一体站定，按键不参与方向
		}
		// 双板：W 前进 / S 刹车或后退（沿**身体前方**），A / D 只转身体
		// （身体朝向由 SkisControl.BODY_YAW 持有，客户端与服务端每 tick 各转一步；**视角完全不跟**）。
		// 这里只回答「想往哪个方向」：
		//   W        → 沿身体前方；
		//   S 且还在往前滑（速度与身体同向）→ 返回 null ＝ 交给 SkiGlide 的刹车分支减速，**不拨方向**；
		//   S 且已经停住 / 在倒滑     → 沿身体后方（真的往后走）；
		//   只按 A/D（在转）          → 朝身体前方把速度方向摆过去；
		//   都没按                    → null（方向不变）。
		// 注意：MC 的水平朝向向量是 (-sin yaw, cos yaw)，yaw 增大 ＝ 向右转（不是左转）。
		double forward = (input.forward() ? 1.0D : 0.0D) - (input.backward() ? 1.0D : 0.0D);
		if (forward < 0.0D) {
			// S：还在往前滑（速度与身体同向）就当作刹车 —— 不返回方向，交给 SkiGlide 的刹车分支减速
			if (SkisControl.isMovingForward(player)) {
				return null;
			}
		}
		if (forward == 0.0D && !input.left() && !input.right()) {
			return null;
		}
		double sign = forward == 0.0D ? 1.0D : forward;   // 只按 A/D ＝ 朝身体前方转过去
		float yaw = SkisControl.bodyYaw(player.getId(), player.getYRot(), false) * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw) * sign, 0.0D, Mth.cos(yaw) * sign);
	}
}
