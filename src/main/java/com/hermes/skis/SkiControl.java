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
 *
 * <p>冲刺（SkiBoost）和滑行动量（SkiGlide）都用这里的转向，规则一致。
 *
 * <p>速度上限 {@link #MAX_GLIDE_SPEED} 的现实参照（1 格 = 1 米，格/tick × 72 = km/h）：
 * <ul>
 *   <li>MC 走路 0.216 格/tick ≈ 15.6 km/h；疾跑 0.28 ≈ 20.2 km/h（游戏本身已比现实快约 3 倍）；</li>
 *   <li>现实世界杯速降赛普遍 &gt;130 km/h，最快纪录 161.9 km/h（Johan Clarey，2013 Wengen）；</li>
 *   <li>现实速度滑雪（KL）世界纪录 255.5 km/h（Simon Billy，2023 Vars）＝ 3.55 格/tick，
 *       在 MC 里会一 tick 穿过 3.5 格，直接漏过碰撞检测，所以只能取一个"游戏能承受"的值。</li>
 * </ul>
 */
public final class SkiControl {
	/** 离地不超过这个高度（格）才允许换方向。 */
	private static final double STEER_MAX_HEIGHT = 1.0D;
	/** 每 tick 把方向朝按键方向转多少（0.2 ≈ 每 10 tick 转 90°）。 */
	private static final double STEER_RATE = 0.2D;

	/**
	 * 滑行 / 冲刺能达到的最高水平速度（格/tick）。换算：1 格 = 1 米，所以 {@code b/tick × 72 = km/h}。
	 *
	 * <p>现取值 1.4 格/tick = 28 格/秒 ≈ <b>100 km/h</b>，约 MC 疾跑速度的 5 倍，
	 * 相当于现实里"高水平速降滑行"的量级（现实对照见类注释）。
	 */
	public static final double MAX_GLIDE_SPEED = 1.4D;

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

	/** 玩家按键在世界坐标下的水平方向；没按方向键、离地太高、拿不到输入都返回 null。 */
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
		// 双板（2026-10 用户定案：回到最开始的状态）—— 以**视角**为基准，A / D 正常参与左右，
		// 视角转多少人就朝哪走。不锁朝向、不夹视角。
		double forward = (input.forward() ? 1.0D : 0.0D) - (input.backward() ? 1.0D : 0.0D);
		double strafe = (input.left() ? 1.0D : 0.0D) - (input.right() ? 1.0D : 0.0D);
		if (forward == 0.0D && strafe == 0.0D) {
			return null;
		}
		// yaw=0 朝向 +z，左手方向为 +x
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double forwardX = -Mth.sin(yaw);
		double forwardZ = Mth.cos(yaw);
		double leftX = Mth.cos(yaw);
		double leftZ = Mth.sin(yaw);
		Vec3 direction = new Vec3(forwardX * forward + leftX * strafe, 0.0D, forwardZ * forward + leftZ * strafe);
		return direction.lengthSqr() > 1.0E-4D ? direction.normalize() : null;
	}

	/** 现在允许转向吗：踩在地上，或脚底离地不超过 {@link #STEER_MAX_HEIGHT} 格。单板的前倾推进也用它。 */
	public static boolean canSteer(ServerPlayer player) {
		return player.onGround() || groundDistance(player) <= STEER_MAX_HEIGHT;
	}

	/** 脚底到下方地面的距离（格）；下面探不到东西就返回一个很大的值。 */
	private static double groundDistance(ServerPlayer player) {
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
