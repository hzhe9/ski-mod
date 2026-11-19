package com.hzhe9.skis.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑行姿态要用的玩家状态，按实体 id 存放：
 * <ul>
 *   <li>{@code onGround}：由 {@code ClientTickEvents.END_CLIENT_TICK} 每 tick 从真实实体读一次；</li>
 *   <li>{@code lastStroke}：离地前最后一帧的划动幅度（跨帧记忆，滞空时用它停住姿势）。</li>
 * </ul>
 *
 * <p>为什么不挂在渲染状态上：实体渲染状态实例是每帧重建的，写在它上面的字段既留不住跨帧值，
 * 也不保证 {@code setupAnim} 看到的就是写入的那个实例（之前就是这里出问题——
 * 离地判定一直读到默认值“在地面”，于是手臂在空里继续按行走动画摆）。
 */
public final class SkiState {
	private static final int MAX_ENTRIES = 512;
	/** 连续接触地面这么多 tick（0.25 秒）才恢复摆臂——落地即起跳的“一瞬接触”不算。 */
	private static final int GROUND_TICKS_TO_RESUME = 5;

	private static final Map<Integer, Boolean> ON_GROUND = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> GROUNDED_TICKS = new ConcurrentHashMap<>();
	private static final Map<Integer, Float> LAST_STROKE = new ConcurrentHashMap<>();

	private SkiState() {
	}

	public static void setOnGround(int entityId, boolean onGround) {
		if (onGround) {
			GROUNDED_TICKS.merge(entityId, 1, Integer::sum);
		} else {
			GROUNDED_TICKS.put(entityId, 0);
		}
		ON_GROUND.put(entityId, onGround);
	}

	/** 没有记录时按“在地面”处理（不影响未穿滑雪板的情况）。 */
	public static boolean onGround(int entityId) {
		return ON_GROUND.getOrDefault(entityId, Boolean.TRUE);
	}

	/** 稳定落地：已连续接触地面达到阈值。落地后立刻起跳（只碰到一瞬）返回 false。 */
	public static boolean stablyGrounded(int entityId) {
		return GROUNDED_TICKS.getOrDefault(entityId, 0) >= GROUND_TICKS_TO_RESUME;
	}

	/** 落地时每帧记录幅度并返回它。 */
	public static float rememberStroke(int entityId, float stroke) {
		LAST_STROKE.put(entityId, stroke);
		return stroke;
	}

	/** 滞空时取回离地前最后一帧的幅度（没有记录就退回传进来的值）。 */
	public static float recallStroke(int entityId, float fallback) {
		return LAST_STROKE.getOrDefault(entityId, fallback);
	}

	/** 实体走得多了就整体清一次，避免长期积累。 */
	public static void prune() {
		if (ON_GROUND.size() > MAX_ENTRIES) {
			ON_GROUND.clear();
			GROUNDED_TICKS.clear();
			LAST_STROKE.clear();
		}
	}
}
