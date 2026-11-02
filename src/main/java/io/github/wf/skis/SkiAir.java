package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>起跳力度 / 按住空格的后空翻 / 落地判定</b>的共用状态机（不依赖任何客户端类）。
 *
 * <p><b>三件事</b>：
 * <ol>
 *   <li><b>起跳力度</b>（{@link #jumpPower(Player)}）：按该板的 <b>11 点速度→起法力查找表</b>
 *       （{@code JUMP_POWER_BY_SPEED_LUT}，s ＝ 水平速度 / {@code MAX_SPEED} 为下标、两点之间线性插值）取值 ——
 *       曲线是<b>对数形</b>的（前面涨得快、后面趋平），满速正好顶到跳高上限 2.0 格（{@code JUMP_MAX_HEIGHT}）。
 *       只在「穿着本模组的板」时返回值，没穿板（或不是玩家）返回 −1，原版跳跃原样不动。
 *       注入点是原版 {@code LivingEntity#getJumpPower()}（{@code jumpFromGround} 里唯一一次取值）。</li>
 *   <li><b>后空翻（按住够久才转）</b>：离地起跳（离地且 {@code vy > 0}）时起跳水平速度 ≥ {@code FLIP_MIN_SPEED}
 *       且没在失控里 ＝ <b>进入可翻转状态</b>，但角度<b>不在这一刻自动累加</b>。
 *       之后要<b>连续按住跳跃满 {@code FLIP_HOLD_DELAY_TICKS}（5）tick</b> 才开始每 tick 加
 *       {@code FLIP_DEGREES_PER_TICK}（短按＝纯跳跃，一点都不转）；
 *       <b>松手立刻定格</b>（角度与模型都停在那偏角），并且<b>连续计数归零</b> —— 再按住要重新数满 5 tick。
 *       角度<b>不在 360° 封顶</b>（可以多圈），内部按 360 取模保存（[0,360)，避免数值膨胀）。</li>
 *   <li><b>落地判定</b>：按「与直立的最小角距」—— a ＝ 角度对 360 取模后映射到 (−180,180]，
 *       {@code |a| ≤ FLIP_LANDING_TOLERANCE_DEGREES} ＝ <b>干净落地</b>（角度归零、不掉速）；
 *       否则＝<b>摔倒</b>：水平速度 ×{@code CRASH_SPEED_MULTIPLIER}、进入 {@code CRASH_STUN_TICKS} 的失控。
 *       多翻了几圈只要停在直立附近也算净落。</li>
 * </ol>
 *
 * <p><b>两端分工</b>（角度只有客户端知道 —— 服务端拿不到「玩家这一 tick 有没有按住空格」）：
 * <ul>
 *   <li><b>客户端</b> {@link #tickClient}：读本地跳跃键、推进角度、跑落地判定，落地时把<b>最终角度</b>
 *       通过 {@link SkiFlip} 的 C2S 包交给服务端；本地同时按判定结果进失控（画观 / 输入门与服务器一致）。</li>
 *   <li><b>服务端</b> {@link #tickServer}：只递减失控计时（角度不归它算）；收到包后走
 *       {@link #applyLanding} —— 降速 + {@code hurtMarked}，<b>不 setPos</b>。</li>
 *   <li><b>判定只有一份</b>：{@link #isCleanLanding}。客户端落地与 {@link #applyLanding} 都调它，
 *       两边不各写一套公式。</li>
 * </ul>
 *
 * <p><b>数值不在这里</b>：两块板各一套 {@link Tuning}（单板 {@code SnowboardControl.airTuning()}、
 * 双板 {@code SkisControl.airTuning()}），本类只按 {@link SnowboardControl#isSnowboard} 选一份。
 */
public final class SkiAir {
	/** 一整圈。角度按它取模保存（多圈只体现在落地判定上）。 */
	public static final double FULL_TURN_DEGREES = 360.0D;

	/** 客户端交上来的角度上限（度，＝10 圈）：超过直接当非法包丢掉。 */
	public static final double MAX_NET_ANGLE_DEGREES = 3600.0D;

	/** 落地回调：把「最终角度 + 是否净落」交给调用方（客户端用它发包）。 */
	@FunctionalInterface
	public interface LandingListener {
		void landed(double angleDegrees, boolean clean);
	}

	/**
	 * 一块板的起跳 / 空翻数值。
	 *
	 * <p>做成对象而不是一串裸参数：状态机只有一份，数值由各自的板类在 {@code airTuning()} 里给。
	 */
	public static final class Tuning {
		/** 速度 → 起跳力度（格/tick）的查找表：下标 s = 0, 1/(n−1), …, 1（长度 11 ＝ 步长 0.1）。 */
		private final double[] powerBySpeedLut;
		/** 这张表对应的跳高上限（格）：只作依据/自检，不参与反推（表才是唯一事实源）。 */
		private final double jumpMaxHeight;
		/** 归一化水平速度用的速度上限（格/tick，＝该板的 {@code MAX_SPEED}）。 */
		private final double maxSpeed;
		/** 起跳水平速度低于它就不进入可翻转状态（只是普通小跳）。 */
		private final double flipMinSpeed;
		/** 按住跳跃时，空中每 tick 转多少度。 */
		private final double flipDegreesPerTick;
		/** 起跳后要「连续按住」满多少 tick 才开始累加角度（短按＝纯跳跃）。 */
		private final int flipHoldDelayTicks;
		/** 落地时与直立（0°/360°/720°…）的角距在这个范围内就算净落。 */
		private final double flipLandingToleranceDegrees;
		/** 摔倒时水平速度保留的比例。 */
		private final double crashSpeedMultiplier;
		/** 摔倒后失控多少 tick（转向 / 压刃 / carve / 追视角全部跳过）。 */
		private final int crashStunTicks;
		/** 摔倒扣血上限（0 = 关闭）。 */
		private final double crashDamageMax;

		public Tuning(double[] powerBySpeedLut, double jumpMaxHeight, double maxSpeed, double flipMinSpeed,
				double flipDegreesPerTick, int flipHoldDelayTicks, double flipLandingToleranceDegrees,
				double crashSpeedMultiplier, int crashStunTicks, double crashDamageMax) {
			if (powerBySpeedLut == null || powerBySpeedLut.length < 2) {
				throw new IllegalArgumentException("powerBySpeedLut 至少要 2 个点（线性插值两端）");
			}
			this.powerBySpeedLut = powerBySpeedLut.clone();   // 拷一份：外部那张表以后被改也不影响已建的 Tuning
			this.jumpMaxHeight = jumpMaxHeight;
			this.maxSpeed = maxSpeed;
			this.flipMinSpeed = flipMinSpeed;
			this.flipDegreesPerTick = flipDegreesPerTick;
			this.flipHoldDelayTicks = flipHoldDelayTicks;
			this.flipLandingToleranceDegrees = flipLandingToleranceDegrees;
			this.crashSpeedMultiplier = crashSpeedMultiplier;
			this.crashStunTicks = crashStunTicks;
			this.crashDamageMax = crashDamageMax;
		}

		/**
		 * 起跳力度：把 s ＝ min(水平速度 / {@link #maxSpeed}, 1) 映射到 {@link #powerBySpeedLut} 的下标
		 * （0 … n−1），<b>两点之间线性插值</b>。表本身是对数形曲线（前段涨得快、后段趋平），
		 * 所以这里只做「按表取值」，不再有任何线性公式。满速返回表的最后一点（＝跳高上限 2.0 格）。
		 */
		public double jumpPower(double horizontalSpeed) {
			double normalized = maxSpeed <= 0.0D
					? 0.0D
					: Mth.clamp(horizontalSpeed / maxSpeed, 0.0D, 1.0D);
			int last = powerBySpeedLut.length - 1;
			double scaled = normalized * last;   // 0 … last（11 点表 → 0 … 10，步长 1 ＝ 速度步长 0.1）
			int index = (int) Math.floor(scaled);
			if (index >= last) {
				return powerBySpeedLut[last];
			}
			double lower = powerBySpeedLut[index];
			return lower + (powerBySpeedLut[index + 1] - lower) * (scaled - index);
		}

		/** 这张表对应的跳高上限（格）：给调试 / 文档对账用，不参与力度计算。 */
		public double jumpMaxHeight() {
			return jumpMaxHeight;
		}

		/** 按住跳跃时每 tick 转多少度。 */
		public double flipDegreesPerTick() {
			return flipDegreesPerTick;
		}

		/** 起跳后要「连续按住」满多少 tick 才开始累加角度。 */
		public int flipHoldDelayTicks() {
			return flipHoldDelayTicks;
		}

		/** 落地「净落」允许与直立差多少度。 */
		public double flipLandingToleranceDegrees() {
			return flipLandingToleranceDegrees;
		}

		/** 摔倒时水平速度保留的比例。 */
		public double crashSpeedMultiplier() {
			return crashSpeedMultiplier;
		}

		/** 摔倒后的失控 tick 数。 */
		public int crashStunTicks() {
			return crashStunTicks;
		}

		/** 摔倒扣血上限（0 = 关闭）。 */
		public double crashDamageMax() {
			return crashDamageMax;
		}
	}

	/** 每个玩家的空中状态。{@code angle} / {@code prevAngle} 就是渲染插值用的那一对（对着 partialTick 取）。 */
	private static final class AirState {
		/** 本 tick 结束时的翻滚角（度，[0,360)，向后翻；多圈只在落地判定里体现）。 */
		private double angle;
		/** 上一 tick 结束时的翻滚角（渲染插值下限）。 */
		private double prevAngle;
		/** 上一 tick 结束时是不是在空中（落地判定的边沿检测）。 */
		private boolean airborne;
		/** 这一次离地是否进入了可翻转状态（起跳速度够快且没在失控中）。 */
		private boolean flipping;
		/** 已<b>连续</b>按住跳跃多少 tick（松开 / 落地 / 再起跳都归零）；满 {@code FLIP_HOLD_DELAY_TICKS} 才开始转角。 */
		private int holdTicks;
		/** 还剩多少 tick 的失控。0 = 正常。 */
		private int stunTicks;
	}

	/** 实体 id → 空中状态。 */
	private static final IdMap<AirState> STATES = new IdMap<>();

	private SkiAir() {
	}

	/**
	 * 按脚上的板取一份数值：单板 {@code SnowboardControl.airTuning()}、双板 {@code SkisControl.airTuning()}；
	 * <b>没穿板返回 null</b>（＝这套机制完全不生效，原版跳跃 / 原版一切）。
	 *
	 * <p>这里只做「按板选值」，不含任何一块板的数 —— 与 {@link SnowboardControl#isSnowboard} 同一个交叉点。
	 */
	public static Tuning tuning(Player player) {
		if (player == null) {
			return null;
		}
		if (SnowboardControl.isSnowboard(player)) {
			return SnowboardControl.airTuning();
		}
		if (SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return SkisControl.airTuning();
		}
		return null;
	}

	/**
	 * <b>这一 tick 起跳该给多少力度</b>（注入原版 {@code getJumpPower()}）：
	 * 没穿板返回 {@code -1}＝调用方原样交回原版值。
	 */
	public static float jumpPower(Player player) {
		Tuning tuning = tuning(player);
		if (tuning == null) {
			return -1.0F;
		}
		return (float) tuning.jumpPower(player.getDeltaMovement().horizontalDistance());
	}

	/**
	 * <b>客户端</b>每 tick 一次（只对本机玩家跑）。
	 *
	 * <p>顺序：① 记录上一 tick 的角度（渲染插值用）；② 递减失控计时；
	 * ③ 落地那一 tick 做判定并回调（{@link LandingListener}）；④ 空中：刚离地那一 tick 只决定「能不能翻」，
	 * 之后<b>连续按住跳跃满 {@code flipHoldDelayTicks} tick</b> 起才累加角度 —— 这就是
	 * 「短按＝纯跳跃、按住满 5 tick 才开翻、松手定格且计数归零」。
	 *
	 * @param holdingJump 本 tick 是否按住跳跃键（{@code LocalPlayer#input.keyPresses.jump()}）
	 * @param listener    落地回调（把最终角度交给服务端）；可为 {@code null}
	 */
	public static void tickClient(Player player, Tuning tuning, boolean holdingJump, LandingListener listener) {
		if (player == null || tuning == null) {
			return;
		}
		AirState state = state(player);
		state.prevAngle = state.angle;
		if (state.stunTicks > 0) {
			state.stunTicks--;
		}

		if (player.onGround()) {
			if (state.airborne) {
				landClient(state, tuning, listener);
			}
			state.airborne = false;
			state.flipping = false;
			state.holdTicks = 0;
			return;
		}

		if (!state.airborne) {
			// 刚离地：只有「真的起跳」（竖直速度向上）且速度够、不在失控里，才进入可翻转状态。
			// 这里<b>不转角</b> —— 起跳本身不再自动开始空翻。
			state.airborne = true;
			state.angle = 0.0D;
			state.prevAngle = 0.0D;
			state.holdTicks = 0;
			state.flipping = state.stunTicks <= 0
					&& player.getDeltaMovement().y > 0.0D
					&& player.getDeltaMovement().horizontalDistance() >= tuning.flipMinSpeed;
		}
		if (state.flipping) {
			if (holdingJump) {
				// 连续按住的 tick 计数：满 FLIP_HOLD_DELAY_TICKS 之前<b>一点都不转</b>（短按＝纯跳跃）
				state.holdTicks++;
				if (state.holdTicks >= tuning.flipHoldDelayTicks()) {
					state.angle = normalizeAngle(state.angle + tuning.flipDegreesPerTick());
				}
			} else {
				// 中途松开：连续计数归零（角度原地定格，不清零），再按住要重新数满
				state.holdTicks = 0;
			}
		}
	}

	/**
	 * <b>服务端</b>每 tick 一次：只递减失控计时。角度归客户端算，服务端只在收到
	 * {@link SkiFlip} 的落地包时用 {@link #applyLanding} 结算 ——
	 * 所以这里<b>没有任何角度累加</b>（上轮的「起跳即开翻、转到 360° 就停」那套已全部删除）。
	 */
	public static void tickServer(Player player, Tuning tuning) {
		if (player == null || tuning == null) {
			return;
		}
		AirState state = state(player);
		if (state.stunTicks > 0) {
			state.stunTicks--;
		}
	}

	/**
	 * <b>落地判定（唯一一份）</b>：与直立的最小角距 {@code |a|} ≤ {@code FLIP_LANDING_TOLERANCE_DEGREES} ＝净落。
	 *
	 * <p>客户端落地与 {@link #applyLanding}（服务端收到包）都调这一个方法 —— 两边不允许各写一套公式。
	 */
	public static boolean isCleanLanding(double angleDegrees, Tuning tuning) {
		if (tuning == null || !Double.isFinite(angleDegrees)) {
			return true;
		}
		return Math.abs(uprightOffsetDegrees(angleDegrees)) <= tuning.flipLandingToleranceDegrees;
	}

	/**
	 * 与直立的最小角距（度，(−180,180]）：0 / 360 / 720 … 都返回 0，350 返回 −10，370 返回 10。
	 */
	public static double uprightOffsetDegrees(double angleDegrees) {
		double a = angleDegrees % FULL_TURN_DEGREES;
		if (a > 180.0D) {
			a -= FULL_TURN_DEGREES;
		} else if (a <= -180.0D) {
			a += FULL_TURN_DEGREES;
		}
		return a;
	}

	/** 内部保存用的取模：[0,360)，多圈不会把数值越叠越大。 */
	static double normalizeAngle(double angleDegrees) {
		double a = angleDegrees % FULL_TURN_DEGREES;
		if (a < 0.0D) {
			a += FULL_TURN_DEGREES;
		}
		return a;
	}

	/** 客户端落地：判定 → 回调（发包）→ 摔倒则本地也进失控（降速只在服务端做）。 */
	private static void landClient(AirState state, Tuning tuning, LandingListener listener) {
		double angle = state.angle;
		boolean clean = isCleanLanding(angle, tuning);
		// 落地那一帧回到 0°（渲染读 0，模型直立；插值区间两头一起归零，不会倒着转回去）
		state.angle = 0.0D;
		state.prevAngle = 0.0D;
		state.flipping = false;
		state.holdTicks = 0;
		if (listener != null) {
			listener.landed(angle, clean);
		}
		if (!clean) {
			state.stunTicks = tuning.crashStunTicks;
		}
	}

	/**
	 * <b>服务端落地结算</b>（{@link SkiFlip} 的包处理器调这里）：用同一份 {@link #isCleanLanding} 判定；
	 * 净落什么都不做；摔倒则水平速度 ×{@code CRASH_SPEED_MULTIPLIER} + {@code hurtMarked} 并进失控。
	 * <b>只走 deltaMovement + hurtMarked，不 setPos</b>。
	 *
	 * @return 是否净落
	 */
	public static boolean applyLanding(ServerPlayer player, double angleDegrees, Tuning tuning) {
		if (player == null || tuning == null) {
			return true;
		}
		boolean clean = isCleanLanding(angleDegrees, tuning);
		if (clean) {
			return true;
		}
		AirState state = state(player);
		state.stunTicks = tuning.crashStunTicks;
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x * tuning.crashSpeedMultiplier, motion.y,
				motion.z * tuning.crashSpeedMultiplier);
		player.hurtMarked = true;   // 让客户端跟着服务端速度走（不 setPos）
		if (tuning.crashDamageMax() > 0.0D) {
			double missing = Math.abs(uprightOffsetDegrees(angleDegrees)) / 180.0D;
			player.hurtServer(player.level(), player.damageSources().fall(),
					(float) (missing * tuning.crashDamageMax()));
		}
		return false;
	}

	/**
	 * 渲染用：当前翻滚角（度）按 {@code partialTick} 在「上一 tick / 本 tick」之间插值。
	 * 没有状态（没穿板 / 还没 tick 过）返回 0 ＝不转。
	 *
	 * <p>内部角度是 [0,360) 取模的：本 tick 比上一 tick 小＝刚绕过 360°，插值下限减一圈，
	 * 否则模型会在那一帧倒着转回去。
	 */
	public static float angleDegrees(int entityId, float partialTick) {
		AirState state = STATES.get(entityId);
		if (state == null) {
			return 0.0F;
		}
		double prev = state.prevAngle;
		if (state.angle < prev) {
			prev -= FULL_TURN_DEGREES;
		}
		return Mth.lerp(partialTick, (float) prev, (float) state.angle);
	}

	/** 这个实体现在是不是在摔倒的失控里（转向 / 压刃 / carve / 追视角的入口按它跳闸）。 */
	public static boolean isStunned(int entityId) {
		AirState state = STATES.get(entityId);
		return state != null && state.stunTicks > 0;
	}

	/** 脱板 / 离开世界：整份状态丢掉（失控计时也一起清）。 */
	public static void forget(int entityId) {
		STATES.remove(entityId);
	}

	private static AirState state(Player player) {
		int entityId = player.getId();
		AirState state = STATES.get(entityId);
		if (state == null) {
			state = new AirState();
			STATES.put(entityId, state);
		}
		return state;
	}
}
