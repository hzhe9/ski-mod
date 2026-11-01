package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>起跳力度 / 后空翻 / 落地硬着陆</b>的共用状态机（不依赖任何客户端类）。
 *
 * <p>每玩家每 tick 跑一次，由两端各自的每 tick 入口调用（服务端 {@link SkiGlide#register()}、
 * 客户端 {@code client/SkisClient} 的玩家循环）——<b>客户端与服务端各跑同一条规则、各只跑一次</b>，
 * 不走网络包：两边都从本地物理（{@code onGround} / 速度）自己算，和 carve、转向推导是同一套做法。
 *
 * <p><b>三件事</b>：
 * <ol>
 *   <li><b>起跳力度</b>（{@link #jumpPower(Player)}）：{@code JUMP_BASE_POWER + JUMP_SPEED_POWER_GAIN ×
 *       min(水平速度 / MAX_SPEED, 1)} —— 滑得越快跳得越高。只在「穿着本模组的板」时返回值，
 *       没穿板（或不是玩家）返回 −1，原版跳跃原样不动。注入点是原版 {@code LivingEntity#getJumpPower()}
 *       （{@code jumpFromGround} 里唯一一次取值），所以空格仍然是<b>原版跳跃</b>、按住连跳照旧。</li>
 *   <li><b>后空翻</b>：每次真正离地起跳（离地且 {@code vy > 0}）时，起跳水平速度 ≥ {@code FLIP_MIN_SPEED}
 *       就开始翻；角度按空中 tick 每 tick 加 {@code FLIP_DEGREES_PER_TICK}，<b>超过 360° 就停在 360°</b>
 *       （不多翻圈）。速度不够＝普通小跳，不翻。</li>
 *   <li><b>落地判定</b>：翻过（{@code flipStarted}）才判定 —— 角度 ≥ 360 − {@code FLIP_COMPLETE_TOLERANCE_DEGREES}
 *       算干净落地（角度归零、速度不掉、无惩罚）；否则＝硬着陆：水平速度 ×{@code CRASH_SPEED_MULTIPLIER}、
 *       进入 {@code CRASH_STUN_TICKS} 的失控（{@code isStunned}，两块板的转向 / 压刃 / carve / 追视角
 *       在各自入口按它跳闸），{@code CRASH_DAMAGE_MAX > 0} 时服务端再按「缺失角度 / 360 × 它」扣血。</li>
 * </ol>
 *
 * <p><b>数值不在这里</b>：两块板各一套 {@link Tuning}（单板 {@code SnowboardControl.airTuning()}、
 * 双板 {@code SkisControl.airTuning()}），本类只按 {@link SnowboardControl#isSnowboard} 选一份。
 */
public final class SkiAir {
	/** 一整圈。翻满到这个角度就不再涨（不会翻第二圈）。 */
	public static final double FULL_TURN_DEGREES = 360.0D;

	/**
	 * 一块板的起跳 / 空翻数值。
	 *
	 * <p>做成对象而不是一串裸参数：状态机只有一份，数值由各自的板类在 {@code airTuning()} 里给。
	 */
	public static final class Tuning {
		/** 静止起跳的力度（格/tick，原版 0.42）。 */
		private final double basePower;
		/** 满速时在 {@link #basePower} 之上再抬多少。 */
		private final double speedPowerGain;
		/** 归一化水平速度用的速度上限（格/tick，＝该板的 {@code MAX_SPEED}）。 */
		private final double maxSpeed;
		/** 起跳水平速度低于它就不翻（只是普通小跳）。 */
		private final double flipMinSpeed;
		/** 空中每 tick 转多少度。 */
		private final double flipDegreesPerTick;
		/** 落地时允许缺多少度还算翻满。 */
		private final double flipToleranceDegrees;
		/** 硬着陆时水平速度保留的比例。 */
		private final double crashSpeedMultiplier;
		/** 硬着陆后失控多少 tick（转向 / 压刃 / carve / 追视角全部跳过）。 */
		private final int crashStunTicks;
		/** 硬着陆扣血上限（0 = 关闭）。 */
		private final double crashDamageMax;

		public Tuning(double basePower, double speedPowerGain, double maxSpeed, double flipMinSpeed,
				double flipDegreesPerTick, double flipToleranceDegrees, double crashSpeedMultiplier,
				int crashStunTicks, double crashDamageMax) {
			this.basePower = basePower;
			this.speedPowerGain = speedPowerGain;
			this.maxSpeed = maxSpeed;
			this.flipMinSpeed = flipMinSpeed;
			this.flipDegreesPerTick = flipDegreesPerTick;
			this.flipToleranceDegrees = flipToleranceDegrees;
			this.crashSpeedMultiplier = crashSpeedMultiplier;
			this.crashStunTicks = crashStunTicks;
			this.crashDamageMax = crashDamageMax;
		}

		/**
		 * 起跳力度：{@code basePower + speedPowerGain × min(水平速度 / maxSpeed, 1)} —— <b>线性</b>，
		 * 满速时正好是 {@code basePower + speedPowerGain}。
		 */
		public double jumpPower(double horizontalSpeed) {
			if (maxSpeed <= 0.0D) {
				return basePower;
			}
			double normalized = Mth.clamp(horizontalSpeed / maxSpeed, 0.0D, 1.0D);
			return basePower + speedPowerGain * normalized;
		}
	}

	/** 每个玩家的空中状态。{@code angle} / {@code prevAngle} 就是渲染插值用的那一对（对着 partialTick 取）。 */
	private static final class AirState {
		/** 本 tick 结束时的翻滚角（度，0…360，向后翻）。 */
		private double angle;
		/** 上一 tick 结束时的翻滚角（渲染插值下限）。 */
		private double prevAngle;
		/** 上一 tick 结束时是不是在空中（落地判定的边沿检测）。 */
		private boolean airborne;
		/** 这一次离地在翻（起跳速度够快且没在失控中）。 */
		private boolean flipping;
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
	 * 每 tick 一次的状态机（两端各跑一遍、各只跑一次）。
	 *
	 * <p>顺序：① 先把上一 tick 的角度记进 {@code prevAngle}（渲染插值用）；② 递减失控计时；
	 * ③ 落地那一 tick 做判定（{@link #land}）；④ 在空中：刚离地那一 tick 决定翻不翻，翻就累加角度。
	 */
	public static void tick(Player player, Tuning tuning) {
		if (player == null || tuning == null) {
			return;
		}
		int entityId = player.getId();
		AirState state = STATES.get(entityId);
		if (state == null) {
			state = new AirState();
			STATES.put(entityId, state);
		}
		state.prevAngle = state.angle;
		if (state.stunTicks > 0) {
			state.stunTicks--;
		}

		if (player.onGround()) {
			if (state.airborne) {
				land(player, state, tuning);
			}
			state.airborne = false;
			state.flipping = false;
			return;
		}

		if (!state.airborne) {
			// 刚离地：只有「真的起跳」（竖直速度向上）才可能翻 —— 走下坡 / 掉下悬崖（vy ≤ 0）不算起跳
			state.airborne = true;
			state.angle = 0.0D;
			state.prevAngle = 0.0D;
			state.flipping = state.stunTicks <= 0
					&& player.getDeltaMovement().y > 0.0D
					&& player.getDeltaMovement().horizontalDistance() >= tuning.flipMinSpeed;
		}
		if (state.flipping) {
			state.angle = Math.min(FULL_TURN_DEGREES, state.angle + tuning.flipDegreesPerTick);
		}
	}

	/** 落地：翻满了＝干净落地（角度归零、速度不掉）；没翻满＝硬着陆（速度惩罚＋失控，惩罚只在服务端落）。 */
	private static void land(Player player, AirState state, Tuning tuning) {
		double angle = state.angle;
		boolean failed = state.flipping && angle < FULL_TURN_DEGREES - tuning.flipToleranceDegrees;
		// 不管翻成没翻成，落地那一帧都回到 0°（渲染读 0，模型直立；插值区间两头一起归零，不会倒着转回去）
		state.angle = 0.0D;
		state.prevAngle = 0.0D;
		state.flipping = false;
		if (!failed) {
			return;
		}
		state.stunTicks = tuning.crashStunTicks;
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;   // 速度与伤害都是服务端的事（客户端只是跟着进入失控状态）
		}
		Vec3 motion = serverPlayer.getDeltaMovement();
		serverPlayer.setDeltaMovement(motion.x * tuning.crashSpeedMultiplier, motion.y,
				motion.z * tuning.crashSpeedMultiplier);
		serverPlayer.hurtMarked = true;
		if (tuning.crashDamageMax > 0.0D) {
			double missing = (FULL_TURN_DEGREES - angle) / FULL_TURN_DEGREES;
			serverPlayer.hurtServer(serverPlayer.level(), serverPlayer.damageSources().fall(),
					(float) (missing * tuning.crashDamageMax));
		}
	}

	/**
	 * 渲染用：当前翻滚角（度）按 {@code partialTick} 在「上一 tick / 本 tick」之间插值。
	 * 没有状态（没穿板 / 还没 tick 过）返回 0 ＝不转。
	 */
	public static float angleDegrees(int entityId, float partialTick) {
		AirState state = STATES.get(entityId);
		if (state == null) {
			return 0.0F;
		}
		return Mth.lerp(partialTick, (float) state.prevAngle, (float) state.angle);
	}

	/** 这个实体现在是不是在硬着陆的失控里（转向 / 压刃 / carve / 追视角的入口按它跳闸）。 */
	public static boolean isStunned(int entityId) {
		AirState state = STATES.get(entityId);
		return state != null && state.stunTicks > 0;
	}

	/** 脱板 / 离开世界：整份状态丢掉（失控计时也一起清）。 */
	public static void forget(int entityId) {
		STATES.remove(entityId);
	}
}
