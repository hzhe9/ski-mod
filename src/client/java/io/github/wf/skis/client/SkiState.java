package io.github.wf.skis.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变体版（右键冲刺）的客户端状态：
 *
 * <p>手臂**不做持续摆动**——只有右键撑杖时播放一次「摆臂」，动画由这里的倒计时驱动，
 * 三维模型那边（HumanoidModelMixin）按剩余 tick 算出这一下划动的幅度。
 */
public final class SkiState {
	/** 一次摆臂动画的时长（tick）。12 tick = 0.6 秒。 */
	private static final int SWING_TICKS = 12;

	private static final Map<Integer, Integer> SWING_LEFT = new ConcurrentHashMap<>();

	private SkiState() {
	}

	/** 右键撑杖时调用（客户端）：开始一次摆臂。 */
	public static void triggerSwing(int entityId) {
		SWING_LEFT.put(entityId, SWING_TICKS);
	}

	/** 客户端每 tick 推进动画计时；走完的条目直接移除。 */
	public static void tick() {
		if (SWING_LEFT.isEmpty()) {
			return;
		}
		SWING_LEFT.replaceAll((entityId, left) -> left - 1);
		SWING_LEFT.entrySet().removeIf(entry -> entry.getValue() <= 0);
	}

	/** 剩余 tick；0 表示当前没在摆臂（手臂静止）。 */
	public static int swingLeft(int entityId) {
		return SWING_LEFT.getOrDefault(entityId, 0);
	}

	public static int swingTicks() {
		return SWING_TICKS;
	}
}
