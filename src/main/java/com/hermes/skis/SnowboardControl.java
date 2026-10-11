package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 单板（snowboard）的「人板一体」状态与手感常量：
 *
 * <ul>
 *   <li><b>身体朝向锁定</b>：穿上那一刻记下，客户端每 tick 写回 {@code yBodyRot} / {@code yBodyRotO}
 *       —— 头（视角）能随便转、身体不跟着转；</li>
 *   <li><b>只能左右平移</b>：A / D 由客户端 {@code ClientInputMixin} 换算到身体左右轴，前后分量一律丢掉；</li>
 *   <li><b>W 前倾 / S 后倾</b>：整个人连同脚上的板绕脚底一起倾（{@link #tiltDegrees()}，渲染层见
 *       {@code EntityTiltMixin}），相机也一起（{@code CameraTiltMixin}）—— 只改姿态，<b>不给推进</b>。</li>
 * </ul>
 *
 * <p>滑行物理（下落加速、低摩擦动量、雪面推进）与双板同款，在 {@link SkiGlide} 里统一施加。
 */
public final class SnowboardControl {
	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final Map<Integer, Float> BODY_YAW = new ConcurrentHashMap<>();
	/** 实体 id → 俯仰姿态（+1 前倾 / -1 后倾），客户端每 tick 从按键刷新，供模型姿态读取。 */
	private static final Map<Integer, Integer> TILT = new ConcurrentHashMap<>();

	/** 前倾 / 后倾时<b>整个人连同板</b>绕脚底倾斜的角度（度）。 */
	private static final float TILT_DEGREES = 15.0F;

	private SnowboardControl() {
	}

	/** 这个实体是不是穿着单板（脚部槽）。 */
	public static boolean isSnowboard(Entity entity) {
		return entity instanceof LivingEntity living
				&& living.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.SNOWBOARD;
	}

	/** 锁住身体朝向：第一次调用时记下当时的 yaw，之后不再改变。 */
	public static void ensureLocked(int entityId, float yaw) {
		BODY_YAW.putIfAbsent(entityId, yaw);
		if (BODY_YAW.size() > MAX_ENTRIES) {
			BODY_YAW.clear();
		}
	}

	public static void forget(int entityId) {
		BODY_YAW.remove(entityId);
		TILT.remove(entityId);
	}

	public static void forgetAll() {
		BODY_YAW.clear();
		TILT.clear();
	}

	/** 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。 */
	public static float bodyYaw(int entityId, float fallback) {
		return BODY_YAW.getOrDefault(entityId, fallback);
	}

	/** 客户端每 tick 记录俯仰姿态：+1 前倾 / 0 无 / -1 后倾。 */
	public static void setTilt(int entityId, int tilt) {
		if (tilt == 0) {
			TILT.remove(entityId);
		} else {
			TILT.put(entityId, tilt);
		}
	}

	/** 当前俯仰姿态（+1 / 0 / -1），模型姿态用。 */
	public static int tilt(int entityId) {
		return TILT.getOrDefault(entityId, 0);
	}

	/** 给定 yaw 的前方（水平单位向量）；yaw = 0 时朝 +z，与 {@link SkiControl} 的约定一致。 */
	public static Vec3 front(int entityId, float yaw) {
		float rad = yaw * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(rad), 0.0D, Mth.cos(rad));
	}

	/**
	 * 沿<b>锁定的身体朝向</b>的前方 —— 倾斜的偏移方向、静止时的滑行方向都用它。
	 *
	 * <p><b>别用视角朝向</b>（{@code player.getYRot()}）：那是鼠标看的那个方向，玩家一转视野它就变，
	 * 偏移会绕着玩家转圈（症状：转视野时相机甩到身体外面去）。身体朝上是锁死的，才是不动的那个基准。
	 */
	public static Vec3 frontLocked(int entityId, float fallbackYaw) {
		return front(entityId, bodyYaw(entityId, fallbackYaw));
	}

	public static float tiltDegrees() {
		return TILT_DEGREES;
	}
}
