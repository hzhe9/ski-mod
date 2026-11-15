package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>起跳 / 按住空格的后空翻 / 落地判定</b>的共用状态机（不依赖任何客户端类）。
 *
 * <p><b>起跳由本模组接管</b>（唯一入口 {@link #tryJump}）：原版 {@code jumpFromGround()} 在穿板时被
 * {@code mixin/LivingEntityJumpMixin} 取消，起跳只从这里发，触发条件<b>只有两条</b>。曾经还有第三条
 * 「离地 ≤ 1 格就打向下射线补跳」，实机日志证实它在腾空下落时被服务端每 tick 命中、把人永远弹起
 * （落地沿 / 落地判定全不触发），已删除 —— 见 {@code git log d5bee03}；同一次改动把空翻从
 * 「离地第一 tick 拍快照」改成空中每 tick 实时判（{@code flipping} 字段已删）。
 *
 * <p><b>当前契约</b>：
 * <table border="1">
 *   <caption>起跳 / 空翻状态机</caption>
 *   <tr><th>项</th><th>内容</th></tr>
 *   <tr><td>触发条件（2 条）</td>
 *       <td>{@code onGround} 立即跳；离地未满 <b>6 tick</b> 的逗留窗内跳</td></tr>
 *   <tr><td>计时器（4 个）</td>
 *       <td>逗留窗 6 / 输入缓冲 5 / 起跳冷却 10 / 摔倒失控 20（tick；两板同值，常量各自一份）</td></tr>
 *   <tr><td>状态字段（6 个）</td>
 *       <td>{@code angle} / {@code prevAngle}（渲染插值对）、{@code holdTicks}（连续按住）、
 *       {@code flipStarted}（本段已开翻）、{@code jumpedThisAir}（本段起过跳）、
 *       {@code airTicks}（连续腾空，饱和 3）</td></tr>
 *   <tr><td>簿记（3 个）</td>
 *       <td>{@code airborne}（落地边沿）、{@code prevHoldingJump}（按下沿）、
 *       {@code wasOnGround}（默认 {@code true}，免得刚建表那一 tick 被当成落地）</td></tr>
 * </table>
 *
 * <p><b>后空翻 ＝ 按住多久就转多少</b>：{@code holdTicks} 从按下那一刻起算、<b>只在松手时归零</b>
 * （落地 / 起跳成功 / 失控都不清），所以地面按住的 tick 会带进空中。空中每 tick 实时判：按住超过
 * 该板的 {@code FLIP_HOLD_DELAY_TICKS}（4）<b>或</b>本段已开翻（{@code flipStarted}），且没失控、
 * 水平速度 ≥ 该板的 {@code FLIP_MIN_SPEED}，才加该板的 {@code FLIP_DEGREES_PER_TICK}（16°）。
 * 从地面起跳那一下用的是 {@code >}，所以按住的前 4 个 tick 角度恒 0（模型静止段，短按＝纯跳跃）；
 * 松手立刻定格（角度与模型都停在那偏角），但空中<b>已开翻过就不必重数</b> —— 再按住下一 tick 立刻续上
 * （{@code flipStarted} 一旦置位就保持到落地）。角度不在 360° 封顶（可以多圈），内部按 360 取模保存
 * （[0,360)，避免数值膨胀）。
 *
 * <p><b>落地判定</b>：按「与直立的最小角距」—— a ＝ 角度对 360 取模后映射到 (−180,180]，
 * {@code |a| ≤ 该板的 FLIP_LANDING_TOLERANCE_DEGREES}（±35°）＝干净落地（角度归零、不掉速）；
 * 否则＝摔倒：水平速度 ×该板的 {@code CRASH_SPEED_MULTIPLIER}、向上速度归零、进入该板的
 * {@code CRASH_STUN_TICKS}（20）失控（<b>失控期间禁止起跳</b>）。多翻了几圈只要停在直立附近也算净落。
 *
 * <p><b>冷却与落地禁跳</b>：该板的 {@code JUMP_COOLDOWN_TICKS}（10）在起跳成功时置满；落地沿也重新起算，
 * 但<b>只在这一趟腾空里起过跳（{@code jumpedThisAir}）或开过翻（{@code flipStarted}）时</b> ——
 * 只是从坡上滑落 / 走下台阶 / 掉到地上的，落地不罚。落地沿只认<b>真落地</b>：连续腾空
 * ≥ {@link #LANDING_LOCK_MIN_AIR_TICKS}（3）才算，坡面 1–2 tick 的贴面抖动不刷冷却。
 * 写入方式只有一种：{@code deltaMovement.y} ＝ 起力、{@code hurtMarked} ＋ {@code needsSync}，<b>不 setPos</b>；
 * <b>空翻中不补跳</b>（{@code !onGround && holdTicks > FLIP_HOLD_DELAY_TICKS} 直接返回）。
 *
 * <p><b>两端分工</b>（角度只有客户端知道 —— 服务端拿不到「玩家这一 tick 有没有按住空格」）：
 * <ul>
 *   <li><b>客户端</b> {@link #tickClient}：读本地跳跃键、推进角度、跑落地判定，落地时把<b>最终角度</b>
 *       通过 {@link SkiFlip} 的 C2S 包交给服务端；本地同时按判定结果进失控（观感 / 输入门与服务器一致）。</li>
 *   <li><b>服务端</b> {@link #tickServer}：只递减失控计时（角度不归它算）；收到包后走
 *       {@link #applyLanding} —— 降速 + {@code hurtMarked}，<b>不 setPos</b>。</li>
 *   <li><b>判定只有一份</b>：{@link #isCleanLanding}。客户端落地与 {@link #applyLanding} 都调它，
 *       两边不各写一套公式。</li>
 *   <li>两端各调一次 {@link #tryJump}，都放在<b>实体 tick 之前</b>（客户端 {@code LocalPlayer#aiStep} 的 HEAD、
 *       服务端 {@code START_SERVER_TICK}），这样服务端的 {@code ClientboundSetEntityMotionPacket}
 *       带的就是起跳速度，不会把客户端写下的冲量整份覆盖掉。</li>
 * </ul>
 *
 * <p><b>数值不在这里</b>：两块板各一套 {@link Tuning}（单板 {@code SnowboardControl.airTuning()}、
 * 双板 {@code AlpineSkiControl.airTuning()}），本类只按 {@link SnowboardControl#isSnowboard} 选一份。
 */
public final class SkiAir {

	/** 一整圈。角度按它取模保存（多圈只体现在落地判定上）。 */
	public static final double FULL_TURN_DEGREES = 360.0D;

	/** 客户端交上来的角度上限（度，＝10 圈）：超过直接当非法包丢掉。 */
	public static final double MAX_NET_ANGLE_DEGREES = 3600.0D;

	/**
	 * 落地沿要算「真落地」至少得连续腾空多少 tick（1–2 tick 的贴面抖动不算落地，
	 * 否则坡上会反复刷起跳冷却，表现成「怎么按都跳不起来」）。
	 *
	 * <p><b>为什么取 3</b>：一次真实的跳跃 / 空翻腾空远长于 3 tick —— 起跳力 0.2–0.5 格/tick、
	 * 重力：腾空档 0.072、起跳首 tick 读贴地档 0.104 / 0.12 格/tick²，满速跳也就 1.5 格 ≈ 十几 tick；而贴面滑行时 {@code onGround}
	 * 的逐 tick 抖动通常是 1–2 tick。取 3 正好把「抖动」和「真腾空」分开，
	 * 闸门只为防刷冷却，不会削弱正常落地后的处罚。
	 *
	 * <p>不放进 {@link Tuning}：它不是手感旋钮，两块板共用同一个判据。
	 */
	private static final int LANDING_LOCK_MIN_AIR_TICKS = 3;

	/**
	 * 空翻的旋转轴高度（格，<b>从脚底往上</b>）：<b>绕腰翻，不是绕脚底</b>。
	 *
	 * <p>这个值同时决定两个人身上的位置同源：第三人称模型（{@code EntityTiltMixin} 转整个 poseStack）
	 * 与第一人称相机（{@code CameraFlipMixin} 把相机位置绕它转）<b>必须用同一个值</b> ——
	 * 否则相机会脱出头的圆弧：模型绕腰翻时头（眼，眼高 1.62）画在半径 {@code 1.62 − 1.0 = 0.62}
	 * 的圆上，相机若留在竖直眼高那点原地转，翻到 180° 时眼睛不在头的位置，第一人称就看见自己的后背。
	 * 所以常量只在这里记一处，两处引用它（改一处两边跟着走）。
	 */
	public static final float FLIP_PIVOT_HEIGHT = 1.0F;

	/** 落地回调：把「最终角度 + 是否净落」交给调用方（客户端用它发包）。 */
	@FunctionalInterface
	public interface LandingListener {
		void landed(double angleDegrees, boolean clean);
	}

	/**
	 * 一块板的起跳 / 空翻数值。
	 *
	 * <p>做成对象而不是一串裸参数：状态机只有一份，数值由各自的板类在 {@code airTuning()} 里给。
	 *
	 * <p><b>注释里的常量名都不在本类</b>：{@code COYOTE_TICKS} / {@code JUMP_BUFFER_TICKS} /
	 * {@code JUMP_COOLDOWN_TICKS} / {@code FLIP_HOLD_DELAY_TICKS} / {@code FLIP_MIN_SPEED} /
	 * {@code CRASH_STUN_TICKS} 等在两块板各自一份 —— 双板在 {@code AlpineSkiControl}、单板在
	 * {@code SnowboardControl}（都 private），本类只按构造参数保存它们的值，在这里 grep 找不到定义。
	 */
	public static final class Tuning {
		/** 速度 → 起跳力度（格/tick）的查找表：下标 s = 0, 1/(n−1), …, 1（长度 11 ＝ 步长 0.1）。 */
		private final double[] powerBySpeedLut;
		/** 这张表对应的跳高上限（格）：只作依据/自检，不参与反推（表才是唯一事实源）。 */
		private final double jumpMaxHeight;
		/** 离开地面后多少 tick 内按空格仍算「刚离地」可起跳（土狼时间 / coyote time）。 */
		private final int coyoteTicks;
		/** 按下空格后多少 tick 内只要条件满足就自动发出（输入缓冲）。 */
		private final int jumpBufferTicks;
		/**
		 * 起跳后多少 tick 内不再因为「按住」重复起跳（防连发）；<b>落地沿也按它重新起算，
		 * 但只在这一趟腾空里起过跳或开过翻时才算</b> —— 所以「跳过 / 翻过再落地」之后这么久内跳不起来
		 * （不论干净落地还是摔倒）；纯滑落 / 走下台阶 / 掉下来的落地<b>不罚</b>。
		 * 「真落地」＝连续腾空 ≥ {@code LANDING_LOCK_MIN_AIR_TICKS}（3）tick，坡面 1–2 tick 的贴面抖动不算。
		 */
		private final int jumpCooldownTicks;
		/** 归一化水平速度用的速度上限（格/tick，＝该板的 {@code MAX_SPEED}，在 {@code AlpineSkiControl} / {@code SnowboardControl}）。 */
		private final double maxSpeed;
		/** 空中每 tick 判一次：水平速度低于它就不转角（只是普通小跳）。 */
		private final double flipMinSpeed;
		/** 满足条件时每 tick 转多少度。 */
		private final double flipDegreesPerTick;
		/**
		 * 比它<b>严格更大</b>的连续按住 tick 数才开始累加角度（＝前这么多个 tick 角度恒 0，静止段）。
		 * <b>只有从地面起跳那一下</b>受它约束；空中已经开翻后再按住（{@code flipStarted}）立刻续翻、不再等。
		 */
		private final int flipHoldDelayTicks;
		/** 落地时与直立（0°/360°/720°…）的角距在这个范围内就算净落。 */
		private final double flipLandingToleranceDegrees;
		/** 摔倒时水平速度保留的比例。 */
		private final double crashSpeedMultiplier;
		/** 摔倒后失控多少 tick（转向 / 压刃 / carve / 追视角全部跳过）。 */
		private final int crashStunTicks;
		/** 摔倒扣血上限（0 = 关闭）。 */
		private final double crashDamageMax;

		public Tuning(double[] powerBySpeedLut, double jumpMaxHeight,
				int coyoteTicks, int jumpBufferTicks, int jumpCooldownTicks, double maxSpeed,
				double flipMinSpeed,
				double flipDegreesPerTick, int flipHoldDelayTicks, double flipLandingToleranceDegrees,
				double crashSpeedMultiplier, int crashStunTicks, double crashDamageMax) {
			if (powerBySpeedLut == null || powerBySpeedLut.length < 2) {
				throw new IllegalArgumentException("powerBySpeedLut 至少要 2 个点（线性插值两端）");
			}
			this.powerBySpeedLut = powerBySpeedLut.clone();   // 拷一份：外部那张表以后被改也不影响已建的 Tuning
			this.jumpMaxHeight = jumpMaxHeight;
			this.coyoteTicks = coyoteTicks;
			this.jumpBufferTicks = jumpBufferTicks;
			this.jumpCooldownTicks = jumpCooldownTicks;
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
		 * 所以这里只做「按表取值」，不再有任何线性公式。满速返回表的最后一点（＝跳高上限 1.5 格）。
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

		/** 离地后多少 tick 内按空格仍算「刚离地」（土狼时间）。 */
		public int coyoteTicks() {
			return coyoteTicks;
		}

		/** 按下空格后多少 tick 内条件满足就自动发出（输入缓冲）。 */
		public int jumpBufferTicks() {
			return jumpBufferTicks;
		}

		/**
	 * 起跳后（以及<b>这一趟腾空里起过跳 / 开过翻的真落地</b>后）多少 tick 内不再因「按住」重复起跳
	 * （贴面抖动不算落地，纯滑落 / 走下台阶的落地也不罚）。
	 */
		public int jumpCooldownTicks() {
			return jumpCooldownTicks;
		}

		/** 空中每 tick 判一次：起跳水平速度低于它就不转角（只是普通小跳）。 */
		public double flipMinSpeed() {
			return flipMinSpeed;
		}

		/** 满足条件时每 tick 转多少度。 */
		public double flipDegreesPerTick() {
			return flipDegreesPerTick;
		}

		/**
		 * 连续按住 tick 数<b>大于</b>它才转角（前这么多个 tick 角度恒 0）。
		 * <b>只对从地面起跳那一下生效</b>；空中已开翻（{@code flipStarted}）之后松手再按立刻续上。
		 */
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
		/**
		 * 从按下那一刻起的<b>连续按住</b>跳跃 tick 数：<b>只在松手时归零</b>（落地 / 起跳成功 / 失控都不清），
		 * 所以地面已按住的 tick 会带进空中 —— 按住多久就转多少。大于 {@code FLIP_HOLD_DELAY_TICKS} 才开始转角。
		 */
		private int holdTicks;
		/**
		 * <b>本段空翻是否已经真的转过角度</b>（= 自上次落地以来 {@code angle} 至少加过一次）。
		 *
		 * <p>它的唯一用途是让<b>空中续翻不等静止段</b>：{@code holdTicks} 松手就归零，若只看它，
		 * 空中松手再按要重新数满 {@code FLIP_HOLD_DELAY_TICKS}；本字段一旦置位就保持到下次落地，
		 * 于是「已经开翻过＝立刻续上」。
		 *
		 * <p><b>不能用「{@code angle != 0}」代替</b>：{@code angle} 是 [0,360) 取模的，
		 * 刚好翻满一圈的那一 tick 会回到 ≈0 —— 用角度判会在那一 tick 退化回「要等静止段」，
		 * 表现为转满一圈后按住不放也卡住。
		 */
		private boolean flipStarted;
		/**
		 * <b>本次腾空里 {@link #tryJump} 真的发过一次起跳</b>（起跳成功那一刻置位，落地沿清掉）。
		 *
		 * <p>唯一用途是<b>落地禁跳的准入</b>：落地沿只在「这一趟腾空里起过跳」或「开过翻」时才把
		 * {@code JUMP_COOLDOWN_TICKS} 重新起算。只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳的，
		 * 落地不该被罚（否则「从坡上滑下来落一下地」就白白锁 0.5 s，按不出跳）。
		 *
		 * <p>只由 {@link #tryJump} 自己置与清（{@code flipStarted} 仍归 {@code landClient} 清），
		 * 所以两个字段不会在同一个地方被写两次。落地沿读的是 {@code tryJump} 那一 tick 的值 ——
		 * 它比 {@code landClient} 早（客户端 {@code aiStep} HEAD / 服务端 {@code START_SERVER_TICK}），
		 * 所以落地那一 tick 读到的 {@code flipStarted} 还是 true，正是要的。
		 */
		private boolean jumpedThisAir;
		/** 还剩多少 tick 的失控。0 = 正常。 */
		private int stunTicks;
		/** 离地后还剩多少 tick 的「逗留窗」（土狼时间）：落地那 tick 重置为该板的 {@code COYOTE_TICKS}（在 {@code AlpineSkiControl} / {@code SnowboardControl}）。 */
		private int coyoteTicks;
		/** 输入缓冲：按下空格后还剩多少 tick 内条件满足就自动发出。 */
		private int jumpBufferTicks;
		/** 起跳冷却：上次起跳后还剩多少 tick 不再因「按住」重复起跳。 */
		private int jumpCooldownTicks;
		/**
		 * 本次腾空<b>已经连续多少 tick {@code onGround == false}</b>（贴地那 tick 归零，腾空时累加，
		 * 饱和到 {@code LANDING_LOCK_MIN_AIR_TICKS}（3））。
		 *
		 * <p>唯一用途是给落地沿做<b>防抖</b>：滑行时 {@code onGround} 在上坡 / 起伏 / 贴面走但没严格落地时
		 * 经常为假、逐 tick 抖动，纯 {@code onGround && !wasOnGround} 的边沿会在坡上每抖一次就刷满
		 * {@code JUMP_COOLDOWN_TICKS}，表现成「怎么按都跳不起来」。只有连续腾空 ≥ 3 tick 的落地才算真落地。
		 */
		private int airTicks;
		/** 上一 tick 是否按住跳跃键（按下沿检测：缓冲只在「按下」那一刻充值）。 */
		private boolean prevHoldingJump;
		/**
		 * 上一 tick 是否在地上（{@link #tryJump} 的落地沿检测）。
		 *
		 * <p><b>默认 true</b>：状态表刚建出来时玩家通常本来就站在地上（出生 / 刚穿板），
		 * 默认 false 会把那一刻当成一次落地、白锁 {@code JUMP_COOLDOWN_TICKS}。
		 */
		private boolean wasOnGround = true;
	}

	/**
	 * 客户端那张表（{@code level().isClientSide()} 为真）：角度、按住计数、渲染插值都在这一份。
	 */
	private static final IdMap<AirState> CLIENT_STATES = new IdMap<>();

	/**
	 * 服务端那张表：失控计时、起跳冷却 / 缓冲 / 逗留窗在服务端实体上的一份。
	 *
	 * <p><b>为什么按端分开</b>：单机（集成服务端与客户端同一个 JVM）里 {@code LocalPlayer} 与
	 * {@code ServerPlayer} <b>共用同一个 entity id</b>，而 {@code tryJump} 每 tick 被调两次
	 * （客户端 {@code aiStep} HEAD 一次、服务端 {@code START_SERVER_TICK} 一次）。共用一张表会让
	 * 冷却 / 缓冲 / 逗留窗每 tick 递减两次（10 tick 实际只有 5 tick），而且先调用的那端起跳把冷布置成
	 * 10 之后，同一 tick 后调用的那端会被 {@code jumpCooldownTicks > 0} 直接拦下 —— 服务端不起跳，
	 * 它发出去的 {@code ClientboundSetEntityMotionPacket} 仍带着下落速度，把客户端的冲量抹掉。
	 */
	private static final IdMap<AirState> SERVER_STATES = new IdMap<>();

	private SkiAir() {
	}

	/**
	 * 按脚上的板取一份数值：单板 {@code SnowboardControl.airTuning()}、双板 {@code AlpineSkiControl.airTuning()}；
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
			return AlpineSkiControl.airTuning();
		}
		return null;
	}

	/**
	 * <b>起跳 —— 本模组接管的唯一入口</b>。两端各调一次，都放在<b>实体 tick 之前</b>：
	 * 客户端 {@code mixin.client/LocalPlayerJumpMixin}（{@code LocalPlayer#aiStep} 的 HEAD，早于 {@code travel()}）、
	 * 服务端 {@code SkiGlide.register} 的 {@code START_SERVER_TICK}（读 {@code getLastClientInput().jump()}）。
	 *
	 * <p><b>为什么两端都要</b>：原版是「这一 tick 写速度 → 这一 tick 立刻位移」；服务端 {@code hurtMarked} 会经
	 * {@code ServerEntity#sendChanges} 把自己的整份速度发给玩家本人，客户端 {@code Entity#lerpMotion} 是<b>整份覆盖</b>
	 * {@code deltaMovement}。只要服务端这一 tick 没跳（滑行代码几乎每 tick 都 hurtMarked），包里带的就是下落速度，
	 * 客户端写的起跳冲量下一 tick 开始前就被抹掉 —— 「穿板按空格没反应」。
	 * 客户端放在 {@code aiStep} 的 HEAD 而不是 {@code END_CLIENT_TICK}：后者玩家已经 tick / 位移完，冲量要晚一 tick。
	 *
	 * <p><b>为什么接管</b>：原版起跳写在 {@code LivingEntity#aiStep} 里，条件是 {@code this.jumping}
	 * <b>且（{@code onGround()} 或在水里）</b> —— 穿板滑行时 {@code onGround} 经常为假（上坡 / 起伏 /
	 * 贴面走但不严格落地），于是「按空格没反应」。现在原版那条路在 {@code LivingEntity#jumpFromGround()}
	 * 的 HEAD 处被取消（{@code mixin/LivingEntityJumpMixin}，穿板时 {@code ci.cancel()}），
	 * 起跳只从这里发一次，不会和原版各写一遍 {@code deltaMovement.y}。
	 *
	 * <p><b>触发条件</b>（先要「想跳」＝按住空格，或按下那一次还在缓冲窗内）：
	 * <ol>
	 *   <li>{@code onGround} → 立即起跳；</li>
	 *   <li>不在 {@code onGround}，但离地还没超过 {@code COYOTE_TICKS}（6 tick「逗留窗」）→ 仍算刚离地。</li>
	 * </ol>
	 *
	 * <p><b>为什么没有「空中向下打射线」那一条</b>（曾经存在、已删，见 {@code d5bee03}）：原第三条是
	 * 「不在 {@code onGround}、{@code vy ≤ 0}、脚底到下方地面 ≤ 1.0 格就打射线补跳」。实机日志证实腾空下落时
	 * 脚底离地本来就在 1 格内，服务端每 tick 都命中并把人再次弹起 —— 永远碰不到地，落地沿 / 落地判定 / 禁跳
	 * 全不触发；而且服务端 {@code holdTicks} 恒为 0（只在客户端累加），「空翻中不补跳」那道门在服务端也形同虚设。
	 * 它原本要兜的「坡面 {@code onGround} 抖动 → 按空格没反应」已由该板的 {@code COYOTE_TICKS}（6 tick，
	 * {@code AlpineSkiControl} / {@code SnowboardControl} 各一份）覆盖：实测贴面抖动最多 3 tick。
	 *
	 * <p><b>输入缓冲</b>：按下沿把 {@code JUMP_BUFFER_TICKS}（5 tick）充上，提前一点按下、等条件成立那一 tick
	 * 自动发出。<b>防连发</b>：{@code JUMP_COOLDOWN_TICKS}（10 tick）在起跳成功时置满；<b>落地沿也重新起算，
	 * 但只在这一趟腾空里起过跳</b>（{@code AirState#jumpedThisAir}，本方法起跳成功那一刻置位）<b>或开过翻</b>
	 * （{@code AirState#flipStarted}）<b>时才 {@code max()} 到满值</b> ——
	 * 只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳也没开翻的，落地<b>不罚</b>。
	 * 所以跳过 / 翻过之后<b>按住空格也不能落地即跳</b>，要等冷却走完（摔倒另叠 {@code CRASH_STUN_TICKS} 失控）。
	 * 落在 {@code jumpedThisAir} / {@code flipStarted} 上的两个标志各有归属：前者只由本方法置与清，
	 * 后者仍由 {@code landClient} 在落地时清（本方法<b>不碰</b>它，避免两处写同一个字段）。
	 * 而且 {@code jumpedThisAir} <b>也只在真落地时才清</b>（不是一 {@code onGround} 就清）：
	 * 一次真跳跃中途 {@code onGround} 闪一 tick 就会走贴地分支，若无条件清标记，真落地时标记已为 false、
	 * 没翻的纯跳落地就<b>不罚</b>了 —— 贴面抖动不能把「这一趟起过跳」抹掉。
	 * {@code airTicks} 仍然一贴地就归零（它是「连续腾空」的计数，抖动打断就是该重新数）。
	 *
	 * <p><b>落地沿只认真腾空（防抖）</b>：滑行时 {@code onGround} 在上坡 / 起伏 / 贴面走但没严格落地时
	 * 经常为假、还会逐 tick 抖动 —— 纯「{@code onGround && !wasOnGround}」的边沿会在坡上每抖一次就把
	 * {@code JUMP_COOLDOWN_TICKS} 刷满，表现成「怎么按都跳不起来」。所以状态里另记
	 * {@code AirState#airTicks}（本次连续腾空了多少 tick，贴地归零、腾空累加并饱和），
	 * <b>只有连续腾空 ≥ {@code LANDING_LOCK_MIN_AIR_TICKS}（3）才算一次落地</b>；1–2 tick 的贴面抖动
	 * 不算落地、<b>不刷冷却</b>。取 3 的理由：真跳跃 / 空翻的腾空远长于 3 tick（十几 tick 起），
	 * 而贴面抖动通常只有 1–2 tick —— 闸门只为防刷，正常落地后的处罚一点没变。
	 * 这一层是<b>额外的防抖闸门</b>：过了它之后还要「这一趟起过跳或开过翻」才真的重新起算冷却。
	 * 落地沿的判定只读各自这一端的 {@code onGround}，两端每 tick 各调一次 {@code tryJump}，
	 * 因此双方自然得到同一个落地沿、不需要额外发包。
	 *
	 * <p>力度<b>只</b>从该板 {@link Tuning#jumpPower(double)}（11 点表 + 线性插值）取，不另写公式；
	 * 只写 {@code deltaMovement}（y ＝ 起力、水平不变）＋ {@code hurtMarked} ＋ {@code needsSync}，<b>不 setPos</b>。
	 *
	 * <p><b>空翻中不补跳</b>：{@code !onGround && holdTicks > FLIP_HOLD_DELAY_TICKS} 时直接返回 {@code false} ——
	 * 已经开翻就别再补一次跳去打断它（也不会清计数）。贴地起跳照旧（仍受起跳冷却约束）。
	 * 起跳成功<b>不再动</b> {@code holdTicks}：这个计数从按下沿起算，只由松手归零。
	 *
	 * @return 这一 tick 是否真的起跳了
	 */
	public static boolean tryJump(Player player, Tuning tuning, boolean holdingJump, boolean onGround) {
		if (player == null || tuning == null) {
			return false;
		}
		if (player.getAbilities().flying) {
			// 创造飞行：不起跳（含计时也不推进）—— 飞行完全按原版走。客户端 LocalPlayerJumpMixin
			// 每 tick 无条件调这里，所以这道闸门必须在本方法里，不能只靠调用方的穿板判据。
			return false;
		}
		AirState state = state(player);
		// 计时先走：冷却 / 缓冲 / 逗留窗都是「每 tick 掉一格」的计数
		if (state.jumpCooldownTicks > 0) {
			state.jumpCooldownTicks--;
		}
		if (state.jumpBufferTicks > 0) {
			state.jumpBufferTicks--;
		}
		if (holdingJump && !state.prevHoldingJump) {
			state.jumpBufferTicks = tuning.jumpBufferTicks();   // 按下沿：充值输入缓冲
		}
		state.prevHoldingJump = holdingJump;
		if (onGround) {
			state.coyoteTicks = tuning.coyoteTicks();           // 贴地：逗留窗充满
		} else if (state.coyoteTicks > 0) {
			state.coyoteTicks--;
		}
		if (onGround) {
			// 真落地 = 上一次不贴地、且这一趟连续腾空够长（≥ LANDING_LOCK_MIN_AIR_TICKS），与下面「这一趟起过跳/开过翻」分开判
			boolean realLanding = !state.wasOnGround && state.airTicks >= LANDING_LOCK_MIN_AIR_TICKS;
			if (realLanding && (state.jumpedThisAir || state.flipStarted)) {
				// 落地沿：这一趟腾空里起过跳（jumpedThisAir）或开过翻（flipStarted）才重新起算起跳冷却。
				// 只是从坡上滑落 / 走下台阶 / 掉到地上、全程没起跳也没翻的，落地不罚（否则按不出跳）。
				// tryJump 两端（客户端 aiStep HEAD / 服务端 START_SERVER_TICK）每 tick 各调一次，
				// 两边读的都是自己的 onGround，所以双方得到同一个落地沿、不需要额外发包。
				state.jumpCooldownTicks = Math.max(state.jumpCooldownTicks, tuning.jumpCooldownTicks());
			}
			if (realLanding) {
				// 只有真落地才清：贴面抖动（1~2 tick 的假 onGround）不能把「这一趟起过跳」抹掉，
				// 否则一次真跳跃中途闪一 tick，真落地时 jumpedThisAir 已是 false，这一趟就不罚了。
				state.jumpedThisAir = false;                // （flipStarted 归 landClient 清，这里不碰）
			}
			state.airTicks = 0;                             // 贴地：腾空计数清零（下一次离地从 0 数）
		} else {
			// 腾空：累加（饱和到 LANDING_LOCK_MIN_AIR_TICKS 就够，不必无限涨）
			state.airTicks = Math.min(state.airTicks + 1, LANDING_LOCK_MIN_AIR_TICKS);
		}
		state.wasOnGround = onGround;
		if (state.stunTicks > 0) {
			return false;                                       // 摔倒失控中：禁止起跳（两端都生效
			//                                                     —— 客户端 landClient 与服务端 applyLanding 都会置这个计时）
		}
		if (!onGround && state.holdTicks > tuning.flipHoldDelayTicks()) {
			return false;                                       // 空翻中：不再补跳（别打断已开翻的那一下）
		}
		if (!holdingJump && state.jumpBufferTicks <= 0) {
			return false;                                       // 没按住、也没有待发的缓冲
		}
		if (state.jumpCooldownTicks > 0) {
			return false;                                       // 刚起跳过：防连发
		}
		if (!canJump(onGround, state)) {
			return false;
		}
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x, tuning.jumpPower(motion.horizontalDistance()), motion.z);
		player.hurtMarked = true;                               // 把这份速度同步出去（不 setPos）
		player.needsSync = true;                                // 原版 jumpFromGround 也这么写：本 tick 末重算碰撞/同步
		state.jumpBufferTicks = 0;
		state.jumpCooldownTicks = tuning.jumpCooldownTicks();
		state.coyoteTicks = 0;
		state.jumpedThisAir = true;                             // 这一趟腾空里真的起过跳（落地沿据此才禁跳）
		return true;
	}

	/**
	 * 这一 tick「脚在哪儿」是否满足起跳：<b>只两条</b> —— 贴地，或离地仍在土狼窗内。
	 *
	 * <p>这里<b>不能</b>再按「脚底到下方地面 ≤ 若干格」放行（曾经有过、已删除）：腾空下落时脚底到地面的距离
	 * 本来就在 1 格左右，服务端每 tick 都会命中，把人反复弹起、永远落不了地 —— 落地沿与落地判定因此全都不触发。
	 * （详细日志证据见 {@link #tryJump} 的 javadoc。）
	 */
	private static boolean canJump(boolean onGround, AirState state) {
		return onGround || state.coyoteTicks > 0;
	}

	/**
	 * <b>客户端</b>每 tick 一次（只对本机玩家跑）。
	 *
	 * <p>顺序（就是新口径的全部）：① 记录上一 tick 的角度（渲染插值用）；② 递减失控计时；
	 * ③ <b>按住计数</b> —— {@code holdingJump} 为真就 {@code holdTicks++}，为假（松手）<b>才归零</b>；
	 * ④ 贴地：有落地边沿就做判定并回调（{@link LandingListener}），然后直接返回 ——
	 * <b>这里不清 {@code holdTicks}</b>，所以地面按住的 tick 会带进空中；
	 * ⑤ 空中：<b>每 tick 实时判</b> {@code holdTicks > flipHoldDelayTicks}（4）<b>或本段已开翻</b>（{@code flipStarted}）
	 * ＋ 没失控 ＋ 水平速度 ≥ 门槛，满足才加 {@code flipDegreesPerTick}（16°）并置 {@code flipStarted}。
	 * <b>只有从地面起跳那一下</b>的前 4 个按住 tick 角度恒 0 ＝模型静止段；短按＝纯跳跃；
	 * 空中松手定格后<b>再按立刻续上</b>（{@code holdTicks} 已归零，但 {@code flipStarted} 还在）。
	 *
	 * @param holdingJump 本 tick 是否按住跳跃键（{@code LocalPlayer#input.keyPresses.jump()}）
	 * @param listener    落地回调（把最终角度交给服务端）；可为 {@code null}
	 */
	public static void tickClient(Player player, Tuning tuning, boolean holdingJump, LandingListener listener) {
		if (player == null || tuning == null) {
			return;
		}
		AirState state = state(player);
		state.prevAngle = state.angle;                              // ① 渲染插值下限
		if (state.stunTicks > 0) {                                  // ② 失控计时
			state.stunTicks--;
		}
		if (holdingJump) {                                          // ③ 按住计数：只由松手归零
			state.holdTicks++;
		} else {
			state.holdTicks = 0;
		}

		if (player.onGround()) {                                    // ④ 落地：只做边沿判定，不动 holdTicks
			if (state.airborne) {
				landClient(state, tuning, listener);
			}
			state.airborne = false;
			return;
		}

		state.airborne = true;                                      // ⑤ 空中：门槛每 tick 实时判
		if (holdingJump
				&& (state.flipStarted || state.holdTicks > tuning.flipHoldDelayTicks())
				&& state.stunTicks <= 0
				&& player.getDeltaMovement().horizontalDistance() >= tuning.flipMinSpeed()) {
			state.angle = normalizeAngle(state.angle + tuning.flipDegreesPerTick());
			state.flipStarted = true;                               // 本段已开翻：之后续翻不再等静止段
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
	 *
	 * <p><b>契约（无效输入）</b>：{@code tuning == null} 或角度非有限值时本方法返回 {@code true}（净落），
	 * 意思是「<b>不惩罚</b>一份读不懂的输入」—— 它不是「非法即安全」的判据。调用方必须<b>先</b>自己校验
	 * （客户端状态机给的角永远是有限的；服务端要先过 {@link SkiFlip} 的包校验），并在校验失败时
	 * <b>早退、整份不结算</b>；走到这里时参数按契约已经合法。
	 */
	public static boolean isCleanLanding(double angleDegrees, Tuning tuning) {
		if (tuning == null || !Double.isFinite(angleDegrees)) {
			return true;   // 契约见 javadoc：无效输入不惩罚，由调用方早退，不当判据
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

	/**
	 * 客户端落地：判定 → 回调（发包）→ 摔倒则本地也进失控（降速只在服务端做）。
	 *
	 * <p>不管判定结果如何，这里都把 {@code angle} / {@code prevAngle} <b>归 0</b>（下一 tick 起
	 * {@link SkiAir#angleDegrees} 读到的就是 0），<b>落地即归 0 是两件事共用的</b>：
	 * ① 身体模型与相机翻转角回到直立（不留半圈的偏角）；② 第一人称相机退回原姿态。
	 * 与「干净落地 / 摔倒」无关 —— 摔了也只是多进 {@code CRASH_STUN_TICKS} 的失控，角度同样归 0。
	 * 落地禁跳不在这里做：{@link #tryJump} 的落地沿会在「这一趟腾空里起过跳或开过翻」时把
	 * {@code JUMP_COOLDOWN_TICKS} 重新起算（两端各算一次），纯滑落落地不罚。
	 */
	private static void landClient(AirState state, Tuning tuning, LandingListener listener) {
		double angle = state.angle;
		boolean clean = isCleanLanding(angle, tuning);
		// 落地那一帧回到 0°（渲染读 0，模型直立；插值区间两头一起归零，不会倒着转回去）
		state.angle = 0.0D;
		state.prevAngle = 0.0D;
		// flipStarted 必须一起清：它表示「本段（这一次腾空）已经开翻过」，
		// 落地后就是新的一段 —— 下一跳重新按地面规则数满静止段
		state.flipStarted = false;
		// holdTicks 不动：它只由松手归零（落地后还按着 ＝ 下一跳一离地就接着转）
		if (listener != null) {
			listener.landed(angle, clean);
		}
		if (!clean) {
			state.stunTicks = tuning.crashStunTicks;
		}
	}

	/**
	 * <b>服务端落地结算</b>（{@link SkiFlip} 的包处理器调这里）：用同一份 {@link #isCleanLanding} 判定；
	 * 净落什么都不做；摔倒则水平速度 ×{@code CRASH_SPEED_MULTIPLIER}、<b>向上的速度归零</b>（{@code motion.y = min(motion.y, 0)}）
	 * ＋ {@code hurtMarked} 并进失控，且 {@code CRASH_DAMAGE_MAX > 0} 时按「与直立偏差 / 180」比例扣血。
	 * <b>只走 deltaMovement + hurtMarked + hurtServer，不 setPos</b>。
	 *
	 * <p><b>为什么还要抹掉向上的速度</b>：客户端与服务端的 tick 时序（客户端 {@code aiStep} HEAD 先跳、
	 * 服务端 {@code START_SERVER_TICK} 后跳，落地包还要走一趟网络）可能让「落地那一 tick 的起跳」抢在
	 * {@code stunTicks} 被置上之前发出 —— 那一下会把玩家再弹起来。这一行把那次弹起吃掉，保证摔倒就是往下走；
	 * 之后的起跳由 {@link #tryJump} 的 {@code stunTicks > 0} 闸门拦住。
	 *
	 * <p>扣血用的伤害类型是 {@code damageSources().generic()}，不是 {@code fall()} ——
	 * 本模组的 {@code SnowLanding} 在 {@code hurtServer} 的 ALLOW_DAMAGE 上会取消所有
	 * {@code DamageTypeTags.IS_FALL} 的伤害（雪 / 冰面落地免伤），用 fall 等于雪地摔倒不掉血。
	 *
	 * @return 是否净落
	 */
	public static boolean applyLanding(ServerPlayer player, double angleDegrees, Tuning tuning) {
		if (player == null || tuning == null) {
			return true;
		}
		if (!Double.isFinite(angleDegrees)) {
			// 非法输入一律丢弃、整份不结算（与 SkiFlip 的包校验同一条口径）：
			// 不要依赖 isCleanLanding 对无效输入返回 true 来「兜底」，那是契约外的用法。
			return true;
		}
		if (player.getAbilities().flying) {
			return true;   // 创造飞行：不结算落地（不降速、不扣血）—— 飞行时没有「落地」这回事
		}
		boolean clean = isCleanLanding(angleDegrees, tuning);
		if (clean) {
			return true;
		}
		AirState state = state(player);
		state.stunTicks = tuning.crashStunTicks();
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x * tuning.crashSpeedMultiplier, Math.min(motion.y, 0.0D),
				motion.z * tuning.crashSpeedMultiplier);
		player.hurtMarked = true;   // 让客户端跟着服务端速度走（不 setPos）
		if (tuning.crashDamageMax() > 0.0D) {
			double missing = Math.abs(uprightOffsetDegrees(angleDegrees)) / 180.0D;
			// 伤害类型必须是 generic（不能是 fall）：SnowLanding 在 hurtServer 的 ALLOW_DAMAGE 上
			// 对 DamageTypeTags.IS_FALL 一律取消（雪 / 冰面、或 60 tick 内踩过雪）—— 用 fall 的话
			// 雪地上的摔倒一点血都不掉。generic 不在 is_fall 里（拦不到），又在 bypasses_armor 里
			// （护甲不折这 4 点），所以扣的就是标称值。
			player.hurtServer(player.level(), player.damageSources().generic(),
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
		AirState state = CLIENT_STATES.get(entityId);   // 纯渲染：只读客户端那张表
		if (state == null) {
			return 0.0F;
		}
		double prev = state.prevAngle;
		if (state.angle < prev) {
			prev -= FULL_TURN_DEGREES;
		}
		return Mth.lerp(partialTick, (float) prev, (float) state.angle);
	}

	/**
	 * 这个实体此刻是不是正在空翻（客户端那张表的当前角度 ≠ 0）。渲染期用（FirstPersonModelCompat）。
	 *
	 * <p>每次都是一次 HashMap 查询、无反射、null 安全：渲染期每帧都会问它
	 * （{@code client/FirstPersonModelCompat} 注册给 First Person Model 的 {@code ActivationHandler}）。
	 * 角度是 [0,360) 取模的，翻满一圈那一 tick 会读到 ≈0 —— 对「这一帧要不要画第一人称身体」无所谓
	 * （那正是直立的一帧），真正需要「是否已开翻」的是 {@code flipStarted}，它只在状态机内部用。
	 */
	public static boolean isFlipping(int entityId) {
		AirState state = CLIENT_STATES.get(entityId);
		return state != null && state.angle != 0.0D;
	}

	/**
	 * 这个实体现在是不是在摔倒的失控里（转向 / 压刃 / carve / 追视角的入口按它跳闸）。
	 *
	 * <p>两张表<b>任一</b> {@code stunTicks > 0} 即为 true：调用方（{@code SnowboardControl#isCarving}、
	 * {@code AlpineSkiControl} 的转向入口）两端都会调，而单机下两端共用同一 entity id ——
	 * 「任一端失控」就是原来的语义。
	 */
	public static boolean isStunned(int entityId) {
		AirState client = CLIENT_STATES.get(entityId);
		if (client != null && client.stunTicks > 0) {
			return true;
		}
		AirState server = SERVER_STATES.get(entityId);
		return server != null && server.stunTicks > 0;
	}

	/**
	 * <b>客户端</b>那张表丢掉这个实体（脱板 / 换板 / 没穿板）：<b>只清客户端</b>。
	 *
	 * <p>单机里 {@code LocalPlayer} 与 {@code ServerPlayer} 共用同一个 entity id，而客户端的
	 * {@code SkisClient} 每 tick 都会对「没穿板 / 创造飞行」的玩家调一次清表 —— 如果这里顺手清服务端那张，
	 * 服务端的 {@code stunTicks} / {@code jumpCooldownTicks} / {@code jumpBufferTicks} 会被客户端一起抹掉
	 * （失控期间能起跳、冷却凭空消失）。所以两端各有一个入口，谁调谁清自己那张。
	 */
	public static void forgetClient(int entityId) {
		CLIENT_STATES.remove(entityId);
	}

	/** <b>服务端</b>那张表丢掉这个实体（脱板 / 换板）：只清服务端，理由见 {@link #forgetClient}。 */
	public static void forgetServer(int entityId) {
		SERVER_STATES.remove(entityId);
	}

	/**
	 * 客户端<b>整表</b>丢掉：换世界 / 重连 / 换维度时调（{@code SkisClient}）。
	 *
	 * <p>换维度时玩家的 entity id 会变，旧表里的 id 再也回不来 —— 留着只会占容量，还会在 id 被复用
	 * （单机反复进出世界、旁观传送到别的维度）时把上一段空翻的角度 / 失控计时串给「新」实体。
	 * 服务端那张表不归客户端管，服务端自己靠脱板清理。
	 */
	public static void forgetAllClient() {
		CLIENT_STATES.clear();
	}

	/**
	 * 取这个玩家<b>本端</b>的那份空中状态（{@code level().isClientSide()} 选表）。
	 *
	 * <p>单机下 {@code LocalPlayer} 与 {@code ServerPlayer} 共用同一个 entity id，所以<b>不能</b>
	 * 只按 id 查——那样两端会写同一份状态（计时器每 tick 递减两次、后调用的那端被冷却拦下）。
	 */
	private static AirState state(Player player) {
		IdMap<AirState> states = player.level().isClientSide() ? CLIENT_STATES : SERVER_STATES;
		int entityId = player.getId();
		AirState state = states.get(entityId);
		if (state == null) {
			state = new AirState();
			states.put(entityId, state);
		}
		return state;
	}
}
