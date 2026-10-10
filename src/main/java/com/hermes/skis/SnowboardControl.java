package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * 单板（snowboard）的专属操控：站着滑的时候「人 + 板」固定成一个朝向，
 * <ul>
 *   <li><b>A / D</b>：朝<b>身体朝向</b>的左 / 右平移（鼠标转视角不影响方向，所以永远往“板头指的那一边”滑）；</li>
 *   <li><b>W</b>：前倾——每 tick 沿身体前方补一点推进（“稍微往前”）；</li>
 *   <li><b>S</b>：后倾——不给推进（松着滑，靠动量自己衰减）；</li>
 *   <li>鼠标只负责看，不参与移动方向。</li>
 * </ul>
 *
 * <p>身体朝向在<b>开始滑行的那一刻</b>锁定（{@link #ensureLocked}），之后直到脱下板 / 起飞 / 骑乘都不会变。
 * 想换基准方向：把板脱下来再穿上重新锁一次。
 *
 * <p>可调旋钮见 {@link #SIDE_MIN_SPEED} 与 {@link #TILT_PUSH}。
 */
public final class SnowboardControl {
	/**
	 * 横向平移（A/D）的最小速度：站着不动按一下 A/D 也能立刻滑出去，
	 * 而不是要先把速度攒起来（格/tick，0.30 ≈ 21.6 km/h）。
	 */
	private static final double SIDE_MIN_SPEED = 0.30D;
	/** 前倾（W）每 tick 沿身体前方补多少推进；要“更用力往前”就加大。 */
	private static final double TILT_PUSH = 0.03D;

	private static final int MAX_ENTRIES = 64;

	/** 玩家实体 id → 开始滑行时锁定的身体朝向（度）。 */
	private static final Map<Integer, Float> BODY_YAW = new ConcurrentHashMap<>();

	private SnowboardControl() {
	}

	public static boolean isSnowboard(ServerPlayer player) {
		return player.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.SNOWBOARD;
	}

	/** 开始滑行：把身体朝向锁定在当前视角朝向（已经锁过就不动）。 */
	public static void ensureLocked(ServerPlayer player) {
		BODY_YAW.putIfAbsent(player.getId(), player.getYRot());
		if (BODY_YAW.size() > MAX_ENTRIES) {
			BODY_YAW.clear();
		}
	}

	public static void forget(int id) {
		BODY_YAW.remove(id);
	}

	public static void forgetAll() {
		BODY_YAW.clear();
	}

	/** 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。 */
	public static float bodyYaw(ServerPlayer player) {
		return BODY_YAW.getOrDefault(player.getId(), player.getYRot());
	}

	/** 身体前方（水平单位向量）；yaw = 0 时朝 +z，与 {@link SkiControl} 的约定一致。 */
	public static Vec3 front(ServerPlayer player) {
		float yaw = bodyYaw(player) * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
	}

	/** 身体左手方向（水平单位向量）；yaw = 0 时为 +x。 */
	public static Vec3 left(ServerPlayer player) {
		float yaw = bodyYaw(player) * Mth.DEG_TO_RAD;
		return new Vec3(Mth.cos(yaw), 0.0D, Mth.sin(yaw));
	}

	/** 左 / 右方向的符号：+1 = 往身体左侧，-1 = 往身体右侧，0 = 没按。 */
	public static double sideSign(Input input) {
		if (input == null) {
			return 0.0D;
		}
		return (input.left() ? 1.0D : 0.0D) - (input.right() ? 1.0D : 0.0D);
	}

	/** 正在按 A / D（横向平移）。 */
	public static boolean strafing(Input input) {
		return sideSign(input) != 0.0D;
	}

	/** 正在按 W（前倾）。 */
	public static boolean tilting(Input input) {
		return input != null && input.forward();
	}

	public static double sideMinSpeed() {
		return SIDE_MIN_SPEED;
	}

	public static double tiltPush() {
		return TILT_PUSH;
	}
}
