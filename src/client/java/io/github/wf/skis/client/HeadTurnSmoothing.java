package io.github.wf.skis.client;

import io.github.wf.skis.SnowboardControl;
import io.github.wf.skis.util.IdMap;
import net.minecraft.util.Mth;

/**
 * <b>第三人称头模型的「扭头」过渡</b>：把 {@link SnowboardControl#headModelOffset} 那个
 * 无状态的 ±90° 夹包成一条按 tick 推进的角速度路径 —— 只在<b>视角扫过正背后</b>（相对角越过 ±180°、
 * 头的目标从 +90 翻到 −90 或反过来）时生效，其余时候与旧实现<b>逐字一致</b>。
 *
 * <p><b>为什么需要它</b>：{@code headModelOffset} 先把相对角 {@code Mth.wrapDegrees} 折到 (−180°, 180°]
 * 再夹 ±90，于是视角从背后这一点跨过去时（+179 → −179），夹出来的目标从 <b>+90 直接翻成 −90</b> ——
 * 一 tick 之内头模型横穿 180°（看上去像「头一下从左甩到右」，用户 2026-11-19 的口径：要一个慢一点的扭头过程）。
 * 这条规则本身没错（|rel| ≥ 90 就停在肩上），缺的是「从一侧绕到另一侧」的那段路。
 *
 * <p><b>两条路</b>（每 tick 一次，只对本机玩家）：
 * <ol>
 *   <li><b>普通跟随仍 1:1</b>：目标等价角与当前平滑值相差 ≤ {@link SnowboardControl#HEAD_MODEL_LIMIT_DEGREES}
 *       90° 时，直接返回 {@code headModelOffset(视 yaw − 身体朝向)} —— <b>逐 tick 逐字等于旧值</b>，
 *       没有滞后、没有插值；平滑值也随手写成同一个数，不积累任何偏移。</li>
 *   <li><b>只有需要翻侧时才扭头</b>：上式 > 90°（等价于「翻侧：+90 → −90」）时进入扭头状态，
 *       每 tick 朝目标走 {@link #HEAD_TURN_SWEEP_DEGREES_PER_TICK} 15°，残余 &lt;
 *       {@link #HEAD_TURN_SNAP_DEGREES} 1° 时吸附并退出（180° ⇒ 12 tick ≈ 0.6 s）。</li>
 * </ol>
 *
 * <p><b>绕后脑走、不穿前脑</b>：目标一律写成 {@code targetClamped + 360·k}，取 k 让候选值离当前平滑值最近
 * （当前 +90、目标夹 −90 ⇒ 取 +270，即 +90 → +180 → +270，渲染时 {@code Mth.wrapDegrees} 成 −90）——
 * 绝不会出现「先甩回正前方再偏到另一侧」。正好 ±180° 的平局（翻侧那一刻就差整 180°）按<b>视角这一 tick
 * 转动的方向</b>取：往正方向转就绕 +270、往负方向转就绕 −270，与鼠标的转向同向。
 *
 * <p><b>状态按实体 id 存</b>（{@link IdMap}，上限 64 条、超了淘汰最旧）：平滑值 + 是否在扭头 + 上一次的
 * 视角相对角（只用于上面那个平局的方向）+ 当时是哪块板（换板即作废）。脱板 / 不滑行
 * （{@code SkisClient#tickPlayerMotion} 的清理支路）与换世界（{@code SkisClient#resetWorldState}）各清一次。
 * <b>只有本机玩家</b>会写这张表（与旧的写入点一致：远端玩家的 {@code yHeadRot} 由服务端同步）。
 */
public final class HeadTurnSmoothing {
	/**
	 * 扭头时的角速度（度/tick）：15°/tick ≈ 300°/s，翻侧那 180° 走 <b>12 tick（0.6 s）</b>。
	 * 想更慢就调小（8°/tick ⇒ 22.5 tick），想更快就调大；只影响翻侧那一段，普通跟随不看它。
	 */
	public static final float HEAD_TURN_SWEEP_DEGREES_PER_TICK = 15.0F;

	/** 走到目标多远以内算「到了」（度）：吸附掉浮点残差并退出扭头状态，下一 tick 起恢复 1:1。 */
	private static final float HEAD_TURN_SNAP_DEGREES = 1.0F;

	/** 一条实体 id 的扭头状态。 */
	private static final class State {
		/** 当前平滑值（度，<b>不折圈</b>：扭头途中可能是 270 这种，渲染时才 {@code wrapDegrees}）。 */
		float angle;
		/** 上一次的视角相对角（{@code wrapDegrees(视 yaw − 身体朝向)}）：只在 ±180° 平局时用来判方向。 */
		float lastViewOffset;
		/** 是否正在扭头（true ＝ 按 {@link #HEAD_TURN_SWEEP_DEGREES_PER_TICK} 朝目标走）。 */
		boolean sweeping;
		/** 这张状态属于哪块板（单板 / 双板）：换板即作废，从头按当前视角起算。 */
		boolean snowboard;
	}

	/** 实体 id → 扭头状态（只写本机玩家）。 */
	private static final IdMap<State> STATES = new IdMap<>();

	private HeadTurnSmoothing() {
	}

	/**
	 * 推进一 tick，返回<b>头模型相对身体朝向的 yaw</b>（度，可能超出 ±180 —— 扭头途中是 90 → 270 这种）。
	 *
	 * <p>调用方照旧写 {@code yHeadRot = 身体朝向 + Mth.wrapDegrees(本方法(...))}：
	 * {@code wrapDegrees(270) = −90}，所以渲染出来就是「绕过后脑到另一侧」。
	 *
	 * @param entityId 实体 id（只应传本机玩家）
	 * @param bodyYaw  这一 tick 锁定的身体朝向（度）
	 * @param viewYaw  这一 tick 的视角 yaw（度）
	 * @param snowboard 是不是单板（换板时状态作废；两块板共用这一套）
	 * @return 头模型相对身体朝向的 yaw（度；非有限值原样返回，调用方自己跳过）
	 */
	public static float tick(int entityId, float bodyYaw, float viewYaw, boolean snowboard) {
		float offset = viewYaw - bodyYaw;
		if (!Float.isFinite(offset)) {
			return offset;   // NaN / ∞：别把状态写脏
		}
		// 旧实现逐字保留的「目标」：先折到 (−180°, 180°]、再夹 ±90°（|rel| ≥ 90 就停在肩上）
		float target = SnowboardControl.headModelOffset(offset);
		float viewOffset = Mth.wrapDegrees(offset);

		State state = STATES.get(entityId);
		if (state == null || state.snowboard != snowboard) {
			// 第一次见 / 换板：按当前视角起算，不扭头
			state = new State();
			state.angle = target;
			state.lastViewOffset = viewOffset;
			state.snowboard = snowboard;
			STATES.put(entityId, state);
			return target;
		}

		// 目标写成 target + 360·k，取离当前平滑值最近的那个（平局按视角这一 tick 转动的方向）
		float delta = Mth.wrapDegrees(target - state.angle);
		if (delta == -180.0F) {
			// 正好半圈：翻侧那一刻就差整 180°，往哪边绕都行 —— 顺着视角这一 tick 的转向
			delta = Mth.wrapDegrees(viewOffset - state.lastViewOffset) >= 0.0F ? 180.0F : -180.0F;
		}
		state.lastViewOffset = viewOffset;

		if (!state.sweeping && Math.abs(delta) <= SnowboardControl.HEAD_MODEL_LIMIT_DEGREES) {
			// ——— 普通跟随：1:1 等于旧值（逐字一致，不加任何滞后）———
			state.angle = target;
			return target;
		}

		// ——— 扭头：每 tick 恒定 HEAD_TURN_SWEEP_DEGREES_PER_TICK，朝候选值走，残余 1° 内吸附并退出 ———
		state.sweeping = true;
		float candidate = state.angle + delta;   // 目标等价角（target + 360·k）
		if (Math.abs(delta) <= HEAD_TURN_SNAP_DEGREES) {
			state.angle = target;
			state.sweeping = false;
			return target;
		}
		state.angle += Mth.clamp(delta, -HEAD_TURN_SWEEP_DEGREES_PER_TICK, HEAD_TURN_SWEEP_DEGREES_PER_TICK);
		if (Math.abs(candidate - state.angle) <= HEAD_TURN_SNAP_DEGREES) {
			// 到了：把平滑值折回 ±90 的代表值（270 → −90），下一 tick 起就是普通 1:1
			state.angle = target;
			state.sweeping = false;
			return target;
		}
		return state.angle;
	}

	/** 忘掉一个实体（脱板 / 不滑行）：下次穿板按当时的视角重新起算。 */
	public static void forget(int entityId) {
		STATES.remove(entityId);
	}

	/** 整表清空（换世界 / 换维度 / 重连）：旧 id 的状态再也没有意义。 */
	public static void forgetAll() {
		STATES.clear();
	}
}
