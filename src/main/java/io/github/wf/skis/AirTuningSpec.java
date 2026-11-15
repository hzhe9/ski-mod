package io.github.wf.skis;

/**
 * 起跳 / 空翻的<b>共享规格容器</b>：两块板（双板 {@link AlpineSkiControl}、单板 {@link SnowboardControl}）的
 * 这一组数值逐字同构 —— 13 个字段 + 一张速度→起法力查找表 + 表长校验 + 交给 {@link SkiAir.Tuning} 的构造。
 *
 * <p><b>这里只放结构与通用语义，不放数值</b>：每块板仍然在各自的类里写自己的常量与查找表（数值是手感的唯一
 * 事实源，也必须一处可调），构造时把 13 个值交给本类，本类负责
 * <ol>
 *   <li>校验查找表长度（＝ {@link #JUMP_CURVE_SAMPLES}）：写错在这里立刻炸，别带到游戏里；</li>
 *   <li>把同样的 13 个值按固定顺序装进 {@link SkiAir.Tuning}（{@link #toTuning()}）—— 顺序写错会静默串值，
 *       所以只留这一处。</li>
 * </ol>
 *
 * <p><b>构造时机不变</b>：各板在自己的静态初始化里 {@code new AirTuningSpec(...).toTuning()}，
 * 于是「表长不对 → 类初始化失败」仍然发生在穿板之前，与拆分前一致。
 */
public final class AirTuningSpec {
	/**
	 * 速度→起法力查找表的点数（{@code s = 0.0 / 0.1 / … / 1.0} 共 <b>11</b> 点，步长 0.1）。
	 * 两块板共用同一个点数 —— 插值步长 {@code 1 / (n − 1) = 0.1} 由它决定。
	 */
	public static final int JUMP_CURVE_SAMPLES = 11;

	/**
	 * 速度 → 起跳力度（格/tick）的查找表：下标 ＝ {@code s × 10}，{@code s ＝ min(水平速度 / maxSpeed, 1)}，
	 * 两点之间由 {@link SkiAir.Tuning#jumpPower} <b>线性插值</b>。
	 *
	 * <p>表是对数形曲线（前段涨得快、后段趋平）在离散 tick 物理下反解出来的，<b>表才是力度的唯一事实源</b> ——
	 * 代码不从跳高上限反推。
	 */
	private final double[] jumpPowerBySpeedLut;
	/** 这张表对应的<b>跳高上限</b>（格）：只作依据 / 文档对账，不参与反推。 */
	private final double jumpMaxHeight;
	/**
	 * <b>离地后的「逗留窗」（土狼时间，coyote time）</b>：离开地面（{@code onGround} 由真变假）之后还有这么多
	 * tick 仍算「刚离地」，照样能起跳 —— 滑行时 {@code onGround} 在下坡 / 起伏上本来就会抖，不留这个窗口就会
	 * 出现「明明还贴着雪面，按空格却不跳」。
	 */
	private final int coyoteTicks;
	/**
	 * <b>输入缓冲</b>（tick）：按下空格那一刻起这么多 tick 内，只要起跳条件成立就自动发出，不用重新按 ——
	 * 解决「提前一点按下去没反应」。
	 */
	private final int jumpBufferTicks;
	/**
	 * <b>起跳冷却</b>（tick）：一次起跳后这么多 tick 内不再因「按住空格」重复起跳（落地后由贴地条件自然放行）。
	 * 落地沿也会按它重新起算，但只在这一趟腾空里起过跳 / 开过翻时才算。
	 */
	private final int jumpCooldownTicks;
	/** 归一化水平速度用的速度上限（格/tick）＝ 该板自己的 {@code MAX_SPEED}，用来把速度映射到表下标。 */
	private final double maxSpeed;
	/** 空中<b>每 tick 实时判</b>的水平速度门槛（格/tick）：低于它这一 tick 不转角（只是普通小跳）。 */
	private final double flipMinSpeed;
	/**
	 * 后空翻<b>按住空格</b>时每 tick 转多少度。
	 *
	 * <p>口径是「<b>按住多久就转多少</b>」：{@code holdTicks} 从<b>按下那一刻</b>起算、<b>只由松手归零</b>
	 * （落地 / 起跳成功 / 失控都不清），空中每 tick 实时判 {@code holdTicks > flipHoldDelayTicks} ＋ 速度够
	 * ＋ 没失控才加这个值；松手立刻定格（模型停在那偏角）、计数归零。角度不封顶，可以按住转多圈；
	 * 落地看与直立差多少（{@link #flipLandingToleranceDegrees}）。
	 */
	private final double flipDegreesPerTick;
	/**
	 * 连续按住空格多少个 tick <b>之后</b>才开始累加角度（判据 {@code holdTicks > 它}，<b>严格大于</b>）：
	 * 按住的前这么多个 tick 角度恒为 <b>0</b> —— 这就是「静止段」（模型定住不动，之后才起翻）。
	 * 短按（≤ 它）＝纯跳跃、一点不转。只有从地面起跳那一下受它约束；空中已经开翻后再按住立刻续翻。
	 */
	private final int flipHoldDelayTicks;
	/** 落地时与<b>直立</b>（0° / 360° / 720°…）的角距在这个范围内＝干净落地；超出＝摔倒（多翻几圈只要停在直立附近也算净落）。 */
	private final double flipLandingToleranceDegrees;
	/** 摔倒时水平速度保留的比例。 */
	private final double crashSpeedMultiplier;
	/** 摔倒后失控多少 tick：转向 / 压刃 / carve / 追视角全部跳过，且禁止起跳。 */
	private final int crashStunTicks;
	/**
	 * 摔倒扣血的上限（{@code 0} ＝ 关闭）。{@code > 0} 时服务端按「与直立偏差 / 180 × 它」扣血，
	 * 只在 {@code ServerPlayer} 分支、走 {@code hurtServer}。
	 *
	 * <p><b>为什么要用 {@code damageSources().generic()} 而不是 {@code fall()}</b>：本模组的
	 * {@code SnowLanding} 挂在 Fabric {@code ServerLivingEntityEvents.ALLOW_DAMAGE}（＝{@code LivingEntity#hurtServer}）
	 * 上，对 {@code DamageTypeTags.IS_FALL} 的伤害返回 {@code false} 直接取消（雪 / 冰面上、或 60 tick 内踩过雪）——
	 * 摔落类型的「摔倒扣血」在雪地上会被整份吃掉。{@code minecraft:generic} 不在 {@code is_fall} tag 里，
	 * 所以拦不到；同时它在 {@code bypasses_armor} tag 里，护甲不会把点数折掉，扣的就是标称值。
	 * 只走 {@code hurtServer} + 现有偏移公式，不动 {@code deltaMovement} 以外的任何东西（不 setPos）。
	 */
	private final double crashDamageMax;

	/**
	 * 收下一块板的起跳 / 空翻数值并校验（参数顺序与 {@link SkiAir.Tuning} 的构造一致，见 {@link #toTuning()}）。
	 *
	 * @throws IllegalStateException 查找表长度 ≠ {@link #JUMP_CURVE_SAMPLES}（＝插值步长不是 0.1，表与下标脱节）
	 */
	public AirTuningSpec(double[] jumpPowerBySpeedLut, double jumpMaxHeight,
			int coyoteTicks, int jumpBufferTicks, int jumpCooldownTicks, double maxSpeed,
			double flipMinSpeed, double flipDegreesPerTick, int flipHoldDelayTicks,
			double flipLandingToleranceDegrees, double crashSpeedMultiplier, int crashStunTicks,
			double crashDamageMax) {
		// 表长必须＝采样点数（插值步长 = 1 / (SAMPLES − 1) = 0.1）：长度写错在这里立刻炸，别带到游戏里
		if (jumpPowerBySpeedLut.length != JUMP_CURVE_SAMPLES) {
			throw new IllegalStateException("JUMP_POWER_BY_SPEED_LUT 应有 " + JUMP_CURVE_SAMPLES + " 点");
		}
		this.jumpPowerBySpeedLut = jumpPowerBySpeedLut;
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
	 * 装成 {@link SkiAir.Tuning}（状态机在 {@link SkiAir}，数值留在这里）。
	 * <b>参数顺序必须与 {@link SkiAir.Tuning} 的构造逐位对齐</b> —— 这是全模组唯一一处 13 参数的装配点。
	 */
	public SkiAir.Tuning toTuning() {
		return new SkiAir.Tuning(
				jumpPowerBySpeedLut, jumpMaxHeight,
				coyoteTicks, jumpBufferTicks, jumpCooldownTicks, maxSpeed,
				flipMinSpeed, flipDegreesPerTick, flipHoldDelayTicks, flipLandingToleranceDegrees,
				crashSpeedMultiplier, crashStunTicks, crashDamageMax);
	}
}
