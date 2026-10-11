package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * 单板（snowboard）的「人板一体、完全站定」状态：
 *
 * <ul>
 *   <li><b>不能移动</b>：WASD 一律没反应 —— 客户端 {@code ClientInputMixin} 把移动向量清零，
 *       客户端每 tick 还会把水平速度清零（坡上 / 冰上也不会自己滑）；</li>
 *   <li><b>身体不跟着视角转</b>：穿上那一刻的朝向锁在这里，客户端每 tick 写回 {@code yBodyRot} /
 *       {@code yBodyRotO}（渲染用的是 render state 的 {@code bodyRot}），所以头（视角）能随便转、身体不动。</li>
 * </ul>
 *
 * <p>锁定发生在穿上的那一刻（{@link #ensureLocked}），脱下板 / 起飞 / 骑乘才清掉。
 */
public final class SnowboardControl {
	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final Map<Integer, Float> BODY_YAW = new ConcurrentHashMap<>();

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
	}

	public static void forgetAll() {
		BODY_YAW.clear();
	}

	/** 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。 */
	public static float bodyYaw(int entityId, float fallback) {
		return BODY_YAW.getOrDefault(entityId, fallback);
	}
}
