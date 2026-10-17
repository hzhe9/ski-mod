package com.hermes.skis;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 滑行时的方向控制：读玩家的真实按键（服务端 {@code ServerPlayer.getLastClientInput()}），
 * 并判断“现在能不能换方向”。
 *
 * <p>能不能换方向 = 踩在地上，或者脚底离地不超过 {@link #STEER_MAX_HEIGHT} 格
 * （贴地滑行 / 刚起跳 / 快落地）；再高就锁死方向，沿当前方向继续滑。
 * <b>2026-10-17 起这套「离地也能转」的判定只服务单板</b>：双板在空中速度方向完全不转
 * （视角 / 身体随便转），落地后才按 {@link #STEER_RATE} 渐渐摆过去。
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
	/**
	 * 离地不超过这个高度（格）才允许换方向。
	 *
	 * <p><b>现在只服务单板</b>（双板只在地面上转向，见 {@link #desiredDirection}）；
	 * {@link #canSteer} 也还被单板的前倾推进用着，所以数值保留 1.0。
	 */
	private static final double STEER_MAX_HEIGHT = 1.0D;
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

	/** 玩家按键在世界坐标下的水平方向；没按方向键、双板在空中、离地太高、拿不到输入都返回 null。 */
	public static Vec3 desiredDirection(ServerPlayer player) {
		if (!canSteer(player)) {
			return null;
		}
		Input input = player.getLastClientInput();
		if (input == null) {
			return null;
		}
		if (SnowboardControl.isSnowboard(player)) {
			return null;   // 单板：人板一体站定，按键不参与方向
		}
		// 双板（2026-10-17 用户定）：空中只让视角 / 身体转，速度方向不动；落地后才开始跟着转。
		if (!player.onGround()) {
			return null;
		}
		// 双板（2026-10-17 用户定）：W 前进 / S 刹车或后退（沿**身体前方**），A / D 只转身体
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
		float yaw = SkisControl.bodyYaw(player.getId(), player.getYRot()) * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw) * sign, 0.0D, Mth.cos(yaw) * sign);
	}

	/** 现在允许转向吗：踩在地上，或脚底离地不超过 {@link #STEER_MAX_HEIGHT} 格（<b>只服务单板</b>）。 */
	public static boolean canSteer(ServerPlayer player) {
		return player.onGround() || groundDistance(player) <= STEER_MAX_HEIGHT;
	}

	/** 脚底到下方地面的距离（格）；下面探不到东西就返回一个很大的值。 */
	public static double groundDistance(ServerPlayer player) {
		Vec3 from = player.position();
		Vec3 to = from.add(0.0D, -(STEER_MAX_HEIGHT + 0.1D), 0.0D);
		BlockHitResult hit = player.level().clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return Double.MAX_VALUE;
		}
		return from.y - hit.getLocation().y;
	}
}
