package io.github.wf.skis.client;

import io.github.wf.skis.util.IdMap;

/**
 * 变体版（右键冲刺）的客户端状态：
 *
 * <p>手臂**不做持续摆动**——只有右键撑杖时播放一次「摆臂」，动画由这里的倒计时驱动，
 * 三维模型那边（HumanoidModelMixin）按剩余 tick 算出这一下划动的幅度。
 *
 * <p><b>只有本机玩家的 id 会被写进来</b>：触发点是本机右键（{@code SwingHooks}），没有任何 S2C 包，
 * 所以远端玩家的摆臂是<b>看不到的</b>（模型侧也只读本机 avatar，见 {@code HumanoidModelMixin}）。
 *
 * <p>表用 {@link IdMap}（与其它 7 张 id 表同一套容量兜底），不是裸 {@code ConcurrentHashMap}：
 * {@link #triggerSwing} 是右键触发的，连点 / 作弊客户端可以高频写入，得有个上限。
 */
public final class SkiState {
	/** 一次摆臂动画的时长（tick）。12 tick = 0.6 秒。 */
	private static final int SWING_TICKS = 12;

	/** 玩家 id → 摆臂剩余 tick。 */
	private static final IdMap<Integer> SWING_LEFT = new IdMap<>();

	private SkiState() {
	}

	/** 右键撑杖时调用（客户端）：开始一次摆臂。 */
	public static void triggerSwing(int entityId) {
		SWING_LEFT.put(entityId, SWING_TICKS);
	}

	/** 客户端每 tick 推进动画计时；走完的条目直接移除。 */
	public static void tick() {
		SWING_LEFT.forEach((entityId, left) -> {
			if (left > 1) {
				SWING_LEFT.put(entityId, left - 1);
			} else {
				SWING_LEFT.remove(entityId);   // 走到 0：本条摆臂结束
			}
		});
	}

	/** 换世界 / 重连 / 换维度：整表丢掉（旧 id 全部作废，别把摆臂带进新世界）。 */
	public static void clear() {
		SWING_LEFT.clear();
	}

	/** 剩余 tick；0 表示当前没在摆臂（手臂静止）。 */
	public static int swingLeft(int entityId) {
		return SWING_LEFT.getOrDefault(entityId, 0);
	}

	public static int swingTicks() {
		return SWING_TICKS;
	}
}
