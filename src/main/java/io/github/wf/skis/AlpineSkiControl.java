package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * <b>双板（skis）专属</b>的滑行数值与身体朝向。
 *
 * <p>这个类和单板的 {@link SnowboardControl} <b>完全独立</b>：只有双板自己的参数、自己的方法。
 * 允许共用的只有“平台级”的东西（{@link SkisMod} 的物品判定、{@link SkiGlide} 的 tick 循环骨架）。
 * 以后调双板的手感只改这里，不会碰到单板。
 *
 * <p><b>朝向</b>：A / D 只转<b>身体朝向</b>
 * （{@code BODY_YAW} 锁表，每 tick {@link #TURN_RATE_DEGREES} 度），滑行方向跟身体走。
 * 和单板一样是「锁住 + 按键转」，区别是双板的 W / S 仍然沿身体前后滑（单板的 W / S 只改姿态）。
 *
 * <p><b>按 W 时身体朝视角转</b>：{@link #turnTowardView} 每 tick 把身体朝视角方向拨
 * {@link #VIEW_TURN_RATE_DEGREES} 度（限幅、不越过目标）——<b>转的是身体，视角在世界坐标里仍然不动</b>，
 * 与 A / D 转身体是同一种事，位移只跟身体前方走。
 *
 * <p><b>落地对齐</b>：贴地且没按 S 时，把<b>速度方向</b>朝身体前方每 tick 掰
 * {@link #ALIGN_DEGREES_PER_TICK} 度（{@link #shouldAlignOnGround} / {@link #alignVelocity}，
 * 由 {@link SkiGlide} 的双板分支调用）—— 转完身落地，方向立刻跟上，不靠 {@code GlideSteering.steer} 慢慢摆。
 *
 * <p><b>转向期间速度方向由身体朝向推导</b>（{@link #alignTurnHeading}）：只要这一 tick {@code BODY_YAW}
 * 真的被 {@link #turnBody} / {@link #turnTowardView} 转过，整 tick 末尾就把水平速度方向<b>直接写成</b>
 * 「入弯方向角 + 身体累计转角」—— 模型转多少、速度方向就转多少。上面那条 {@code alignVelocity} 是 9°/tick 的
 * <b>限幅趋近</b>，空中 / 按 S 时干脆不跑，所以只靠它速度会落后于身体；末尾这一道锁定把偏角钉在「入弯时的
 * 身体−速度偏角」上，不再逐 tick 拉大。<b>只在地面上生效</b>：空中身体照旧能转，但速度方向由惯性保持、
 * 一点都不改（不然下落加速会沿转身后的新朝向叠加）。<b>与单板的 {@code SnowboardControl#alignCarveHeading}
 * 完全独立</b>（各一套表、各一个方法，互相不借用）。
 *
 * <p><b>视角</b>：鼠标转，但相对锁定的身体朝向最多左右各 {@link #VIEW_LIMIT_DEGREES} 度
 * （与单板同一个限位、同一套实现，见 {@code client/SkiViewSmoothing}）。没顶到限位时视角留在世界坐标里
 * 不动（身体在下面转）；顶到之后头被身体拖着走。
 */
public final class AlpineSkiControl {
	/** <b>客户端</b>那张表：实体 id → 双板的身体朝向（度）。A/D 只改它，<b>不碰视角</b>。 */
	private static final IdMap<Float> BODY_YAW_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义（为什么分两份见 {@link #table}）。 */
	private static final IdMap<Float> BODY_YAW_SERVER = new IdMap<>();
	/**
	 * 双板：按住 A / D 时每 tick 转身体多少度（<b>4.0</b>°/tick ＝ 80°/s，转满 180° 需 45 tick ＝ 2.25 s）。
	 * 8.0 → 4.5 → 3.5 → 2.0（用户 2026-10-31 要「再慢一档」）→ <b>4.0</b>。
	 * 速度方向由 {@link #alignTurnHeading} 从身体推导，所以模型慢多少、速度方向就慢多少。
	 *
	 * <p><b>2.0 → 4.0 是按「每端各跑一次」重新标定</b>（见 {@link #table}）：这个常量是<b>累加型</b>
	 * 的每 tick 固定步长，拆表前单机里客户端与服务端各减一次 2.0（同一张 {@code BODY_YAW} 每 tick 走 4.0），
	 * 拆表后每端只写自己那张表，取 4.0 才能让单机每 tick 的转角不变；专用服务器也随之从 2.0 提到 4.0
	 * （＝终于和单机一致）。饱和步长型的 {@link #VIEW_TURN_RATE_DEGREES} 同理。
	 */
	private static final float TURN_RATE_DEGREES = 4.0F;
	/**
	 * <b>双板：头（视角）相对锁定身体朝向能转的最大角度</b>：左右各 {@code 90} 度，合计 180 ——
	 * 刚好看到正侧方，背后整片都是禁区。
	 *
	 * <p>与单板的 {@link SnowboardControl#VIEW_LIMIT_DEGREES} <b>各自一份常量</b>：两块板各改各的，
	 * 改这里不会动单板。<b>限的是视角，不是转向</b> —— A / D 的 {@link #TURN_RATE_DEGREES} 一点没变。
	 */
	public static final float VIEW_LIMIT_DEGREES = 90.0F;

	/**
	 * W 时身体每 tick 朝视角方向转多少度（<b>4.8</b>°/tick ＝ 96°/s；90° 残余约 19 tick ≈ 0.9 s 对齐，
	 * 转满 180° 需 37.5 tick ≈ 1.9 s）。4.0 → 2.4 → <b>4.8</b>：与 A / D 同步。
	 *
	 * <p>2.4 → 4.8 同 {@link #TURN_RATE_DEGREES} 的理由（每端各跑一次）。这一项是<b>饱和步长</b>
	 * （{@code clamp(delta, ±rate)}）：两次复合正好等于一次 2×rate，所以 ×2 是<b>逐 tick 精确等价</b>的
	 * （残余 ≤ rate 时两端各走一步就到目标，一次 2×rate 也一样到目标）。
	 */
	private static final float VIEW_TURN_RATE_DEGREES = 4.8F;

	/** 残余偏角小于它就当成对齐（防抖，不要每 tick 无限逼近）。 */
	private static final float VIEW_TURN_MIN_DEGREES = 0.5F;

	/** 本 tick 的 bodyYaw 与上一次 {@link #alignTurnHeading} 相比变化小于它 → 这一 tick 没在转向。 */
	private static final float TURN_EPS_DEGREES = 1.0E-4F;

	/**
	 * <b>服务端</b>那张表：实体 id → <b>入弯（开始转向）时的水平速度方向角</b>（度，{@code atan2(-x, z)} 口径 ——
	 * 与 {@link #alignVelocity} 同一套参数化）。
	 *
	 * <p>转向期间速度方向<b>不再靠每 tick 增量维持</b>（{@link #alignVelocity} 是 9°/tick 的限幅趋近，
	 * 空中 / 按 S 时干脆不跑，累计下来速度越来越落后于身体），而是每 tick 用它 + 身体累计转角<b>直接算出来</b>。
	 * 退出转向就清掉。<b>双板专属</b>，与单板的 {@code CARVE_*} 各一份、互不相干。
	 *
	 * <p>这张表只在 {@link #alignTurnHeading}（服务端）里读写 —— 单机里也只有一次写入，所以<b>不翻倍</b>。
	 */
	private static final IdMap<Double> TURN_HEADING_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Double> TURN_HEADING_CLIENT = new IdMap<>();
	/**
	 * <b>服务端</b>那张表：实体 id → <b>入弯时的身体朝向</b>（度，{@link #BODY_YAW_SERVER} 的那个 yaw）。
	 * 期望速度方向 = {@link #TURN_HEADING_SERVER} + ({@code BODY_YAW 现在 − 它})，所以「身体转多少、速度就转多少」；
	 * 两者之差就是<b>入弯那一刻固有的「身体−速度」偏角</b>，整段弯保持不变。
	 */
	private static final IdMap<Float> TURN_YAW0_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Float> TURN_YAW0_CLIENT = new IdMap<>();
	/**
	 * <b>服务端</b>那张表：实体 id → <b>上一次 tick 结束时的 bodyYaw</b>（{@link #alignTurnHeading} 每次跑都刷新）。
	 *
	 * <p>「这一 tick 到底有没有在转向」就看 {@code BODY_YAW 现在} 与它的差：{@link #turnBody} /
	 * {@link #turnTowardView} 真的转过才说明在转（A / D 按下一定转；按 W 且朝视角还有残差才转）。
	 * 不靠那两个方法打标记 —— 客户端与服务端都会在 tick 里写自己的 {@code BODY_YAW}，比对<b>实际写入量</b>才准。
	 */
	private static final IdMap<Float> TURN_LAST_YAW_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Float> TURN_LAST_YAW_CLIENT = new IdMap<>();

	/**
	 * 下落加速的基数：每下落 1 格加多少。
	 *
	 * <p>0.1 → 0.12 → 0.08 → <b>0.1</b>：取 <b>0.1</b> 是因为它下坡给得出推背感，又不至于一两格就顶到速度上限。
	 * 每格 ≈ 7.2 km/h；每 0.5 格结算一次 ＝ 单次 0.05。
	 * 实际增量 = {@code 基数 × 累计下落格数^指数}（见 {@link SkiGlide} 的 {@code FALL_GAIN_EXPONENT}，
	 * 现在指数是 1.0 ＝ 线性；指数 < 1 时才是「越掉越快不起来」）。
	 */
	private static final double FALL_GAIN_PER_BLOCK = 0.1D;

	/** 单 tick 下落加速上限（防止悬崖一瞬间给爆）。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;

	/**
	 * <b>穿双板、<u>贴地</u>时的重力倍数</b>：把原版 {@code Attributes.GRAVITY}（0.08 格/tick²）乘上这个值，
	 * <b>只在 {@code onGround()} 时生效</b>（滑行贴地、落地压雪）。腾空时改用 {@link #AIR_GRAVITY_MULTIPLIER}。
	 * 1.0 = 原版；1.3 = 0.104 格/tick²；<b>1.5 = 0.12 格/tick²</b>。
	 * 值越大贴地下落越干脆；跳高主体<b>不吃这个值</b>（空中按 {@link #AIR_GRAVITY_MULTIPLIER} 0.072 反解，
	 * 只有起跳首 tick 仍读贴地档 0.120 —— 见 {@link #JUMP_MAX_HEIGHT}、{@link #JUMP_POWER_BY_SPEED_LUT}）。
	 */
	private static final double GRAVITY_MULTIPLIER = 1.5D;

	/**
	 * <b>穿双板、<u>腾空</u>时的重力倍数</b>（跳跃 / 下落全程）：0.08 × 0.9 = <b>0.072</b> 格/tick²，
	 * 比原版轻一点 —— 空中飘一点、滞空更长（空翻能按住的 tick 更多）。
	 * {@link #JUMP_POWER_BY_SPEED_LUT} 就是按这个重力反解的（起跳首 tick 例外，仍是贴地档 0.120）；
	 * 1.0 = 原版（0.08）。终端速度（{@code 49 × g}）3.92 → <b>3.53</b> 格/tick。
	 */
	private static final double AIR_GRAVITY_MULTIPLIER = 0.9D;

	/** 双板速度上限（格/tick）：0.85 格/tick ＝ 61.2 格/秒 ≈ 61 km/h（撑杖冲刺的峰值也封顶在这里）。 */
	private static final double MAX_SPEED = 0.85D;

	/**
	 * <b>双板：跳高上限</b>（格）。满速起跳在离散物理（每 tick 先位移、再 {@code vy = (vy − g) × 0.98}）下
	 * 正好落在 <b>1.50</b> 格 —— 下面的 {@link #JUMP_POWER_BY_SPEED_LUT} 就是按它反解出来的。
	 *
	 * <p>这里的 g 分两段：<b>首 tick 用贴地档 0.120</b>（0.08 × {@link #GRAVITY_MULTIPLIER} 1.5），
	 * <b>第二 tick 起</b>换腾空档 <b>0.072</b>（0.08 × {@link #AIR_GRAVITY_MULTIPLIER} 0.9）。
	 * <b>只是表的上限依据</b>：代码不拿它反推力度（表才是唯一事实源），改上限要整张表重算。
	 */
	private static final double JUMP_MAX_HEIGHT = 1.5D;
	/**
	 * <b>双板：速度 → 起跳力度</b>（格/tick）的 11 点查找表，下标 = s × 10（s = 0.0 / 0.1 / … / 1.0，
	 * s ＝ 水平速度 / {@link #MAX_SPEED}），两点之间由 {@link SkiAir.Tuning#jumpPower} <b>线性插值</b>。
	 *
	 * <p>表来自<b>对数曲线</b> {@code h(s) = 0.36 + (1.5 − 0.36) × ln(1 + 9s) / ln(10)}（h = 跳高，格）
	 * 在离散 tick 物理下反解力度（<b>首 tick 贴地档 0.120、第二 tick 起腾空 0.072</b>；
	 * 起跳点略高于单板，所以同一档力度略高）：
	 * <b>前半段涨得快、越往后越平、末段增量趋于 0</b> —— 20% 速度已经拿到 58% 的高度，
	 * 满速恰好卡在 {@link #JUMP_MAX_HEIGHT} 1.50 格。
	 *
	 * <p>对应跳高（格）：0.36 / 0.68 / 0.87 / 1.01 / 1.12 / 1.20 / 1.28 / 1.34 / 1.40 / 1.45 / 1.50。
	 * 与单板各一份：调这里不动单板。表长（{@link AirTuningSpec#JUMP_CURVE_SAMPLES}）、插值口径与摔落结算
	 * 等通用语义见 {@link AirTuningSpec}。
	 */
	private static final double[] JUMP_POWER_BY_SPEED_LUT = {
			0.226D, 0.318D, 0.363D, 0.392D, 0.414D, 0.431D, 0.444D, 0.456D, 0.466D, 0.475D, 0.483D
	};
	/** <b>双板：离地后的逗留窗</b>（土狼时间，tick）：离地后 <b>6 tick</b> 内按空格仍算「刚离地」可起跳（语义见 {@link AirTuningSpec}）。 */
	private static final int COYOTE_TICKS = 6;
	/** <b>双板：输入缓冲</b>（tick）：按下空格后 <b>5 tick</b> 内条件成立就自动发出起跳。 */
	private static final int JUMP_BUFFER_TICKS = 5;
	/** <b>双板：起跳冷却</b>（tick）：起跳后 <b>10 tick</b> 内不再因「按住」重复起跳。 */
	private static final int JUMP_COOLDOWN_TICKS = 10;
	/**
	 * <b>双板</b>：空中<b>每 tick 实时判</b>的水平速度门槛（格/tick）：低于它这一 tick 就不转角（普通小跳）。
	 * <b>0.15 ≈ 双板上限 0.85 的 18%</b>。
	 */
	private static final double FLIP_MIN_SPEED = 0.15D;
	/**
	 * <b>双板</b>：后空翻<b>按住空格</b>时每 tick 转多少度（<b>16.0</b>°/tick ＝ 320°/s，
	 * 一圈 360° 要 <b>22.5 tick</b> ＝ <b>1.125 s</b>）；计数与静止段的完整口径见 {@link AirTuningSpec}。
	 * <b>与单板同值（两板都是 16.0°/tick ＝ 22.5 tick ＝ 1.125 s）</b>，各一个常量、改一块不动另一块。
	 */
	private static final double FLIP_DEGREES_PER_TICK = 16.0D;
	/** <b>双板</b>：连续按住空格 <b>4</b> tick <b>之后</b>才开始累加角度（判据严格大于；短按＝纯跳跃、一点不转）。 */
	private static final int FLIP_HOLD_DELAY_TICKS = 4;
	/**
	 * <b>双板</b>：落地时与<b>直立</b>（0°/360°/720°…）的角距在 ±45° 内＝干净落地；超出＝摔倒（多翻几圈只要停在
	 * 直立附近也算净落）。<b>±45° 与单板同值</b>（两块板各一个常量，改一块不动另一块）。
	 */
	private static final double FLIP_LANDING_TOLERANCE_DEGREES = 45.0D;
	/** <b>双板</b>：摔倒时水平速度保留的比例（0.35 ＝ 掉 65%）。 */
	private static final double CRASH_SPEED_MULTIPLIER = 0.35D;
	/** <b>双板</b>：摔倒后失控多少 tick（20 tick ＝ 1 s）：转向 / 压刃 / carve / 追视角全部跳过。 */
	private static final int CRASH_STUN_TICKS = 20;
	/** <b>双板</b>：摔倒扣血的上限（4.0 ＝ 偏差 180° 扣 2 颗心、90° 扣 1 颗心）；为什么用 {@code generic()} 而不是 {@code fall()} 见 {@link AirTuningSpec}。 */
	private static final double CRASH_DAMAGE_MAX = 4.0D;

	/**
	 * 双板这一份起跳 / 空翻数值（状态机在 {@link SkiAir}，数值留在这里，容器、表长校验与
	 * {@link SkiAir.Tuning} 的装配在 {@link AirTuningSpec}）。
	 */
	private static final SkiAir.Tuning AIR_TUNING = new AirTuningSpec(
			JUMP_POWER_BY_SPEED_LUT, JUMP_MAX_HEIGHT,
			COYOTE_TICKS, JUMP_BUFFER_TICKS, JUMP_COOLDOWN_TICKS, MAX_SPEED,
			FLIP_MIN_SPEED, FLIP_DEGREES_PER_TICK, FLIP_HOLD_DELAY_TICKS, FLIP_LANDING_TOLERANCE_DEGREES,
			CRASH_SPEED_MULTIPLIER, CRASH_STUN_TICKS, CRASH_DAMAGE_MAX).toTuning();

	/**
	 * 动量每 tick 保留比例 ＝ 有效摩擦力：<b>0.998 ＝ 每 tick 保留 99.8%</b>（1 秒 −4%，半衰期 ≈ 17.3 s）；
	 * 两板同值，滑行手感的基线。
	 */
	private static final double MOMENTUM_DECAY = 0.998D;

	/**
	 * <b>双板刹车</b>：按住 S（且还在往前滑）时，动量与速度每 tick 保留的比例。
	 *
	 * <p>0.92 → 1 秒（20 tick）剩 {@code 0.92^20 ≈ 19%}，约 1 秒内从满速刹到走。
	 * 想更利落改 0.90、想更轻改 0.96。<b>只服务双板</b>，单板的摩擦看 {@link SnowboardControl}。
	 */
	private static final double BRAKE_DECAY = 0.92D;

	/**
	 * <b>双板刹车</b>的判定阈值：速度方向与视角前方（水平）的点积超过它，就算「还在往前滑」。
	 *
	 * <p>也就是夹角小于约 72°：此时按 S 是<b>刹车</b>（只减速、不拨方向）；
	 * 低于它（已经横着滑 / 在倒滑）按 S 才是真的「往后走」。
	 */
	private static final double BRAKE_MIN_ALIGN = 0.3D;

	/**
	 * <b>双板落地对齐的速度方向步长</b>（度/tick）：贴地后把水平速度的<b>方向</b>朝身体前方
	 * 每 tick 最多掰这么多度 —— <b>9.0°/tick ≈ 180°/s，落地后一两 tick 就把速度方向掰到朝向上</b>
	 * （转完身落地不再「方向不跟、不跟手」）。
	 *
	 * <p>只转方向、不动大小；按 S（刹车 / 倒滑）那一 tick 不对齐（见 {@link #shouldAlignOnGround}）。
	 * 调手感：调「落地后方向跟上得多快」—— 180°/s 是「立刻跟上但不闪」的档；嫌跳改 5、嫌拖改 14。
	 *
	 * <p><b>单板也读这个值</b>：{@code SnowboardControl#alignCarveHeading} 落地后的追赶步长基准就是它
	 * （两块板落地后回正同一个基准速度，见那边的常量 {@code ALIGN_MAX_STEP_DEGREES} 的注释）。
	 * 所以这里不加 {@code private}（同包可见），改这个数两块板一起动。
	 */
	static final double ALIGN_DEGREES_PER_TICK = 9.0D;

	private AlpineSkiControl() {
	}

	/**
	 * 按端选一份表（照 {@link SkiAir} 的 {@code CLIENT_STATES} / {@code SERVER_STATES}，与
	 * {@link SnowboardControl} 同一套做法）。
	 *
	 * <p><b>为什么每张表都要两份</b>：单机（集成服务端与客户端在同一个 JVM）里 {@code LocalPlayer} 与
	 * {@code ServerPlayer} <b>共用同一个 entity id</b>，而 {@link #turnBody} / {@link #turnTowardView}
	 * 这类「每 tick 加一个增量」的方法是<b>两端各调一次</b>。共用一张表时同一份增量被加两次 ——
	 * 单机的转向速率因此是专用服务器的 2 倍（`TURN_RATE_DEGREES` 2.0 在单机实际是 4.0）。
	 *
	 * <p><b>常量按「每端各跑一次」标定</b>：拆开后单机每 tick 只有一次写入，所以累加型 / 饱和步长型的
	 * {@link #TURN_RATE_DEGREES} 与 {@link #VIEW_TURN_RATE_DEGREES} 都 ×2，
	 * 单机逐 tick 与拆表前相同、专用服务器终于和单机一致。只在一端跑的
	 * {@code TURN_HEADING / TURN_YAW0 / TURN_LAST_YAW}（{@link #alignTurnHeading}）与
	 * {@link #ALIGN_DEGREES_PER_TICK}（{@link #alignVelocity}）<b>不翻倍</b>。
	 *
	 * @param clientSide {@code true} ＝ 客户端那一份、{@code false} ＝ 服务端那一份
	 */
	private static <T> IdMap<T> table(IdMap<T> clientTable, IdMap<T> serverTable, boolean clientSide) {
		return clientSide ? clientTable : serverTable;
	}

	// ---------------------------------------------------------------- 身体朝向

	/**
	 * 穿双板时锁住身体朝向：<b>第一次调用</b>记下当时的朝向，之后只由 {@link #turnBody} 改。
	 *
	 * @param clientSide {@code true} ＝ 客户端（{@code SkisClient}）、{@code false} ＝ 服务端（{@link SkiGlide}）
	 */
	public static void ensureLocked(int entityId, float yaw, boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).putIfAbsent(entityId, yaw);
	}

	/** 拿不到锁时用传入的视角兜底。 */
	public static float bodyYaw(int entityId, float fallback, boolean clientSide) {
		return table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).getOrDefault(entityId, fallback);
	}

	/**
	 * 按输入转身体一步（+1 左 / -1 右 / 0 不转），返回新朝向。
	 *
	 * <p>MC 的水平朝向向量是 {@code (-sin yaw, cos yaw)}：<b>yaw 增大 ＝ 向右转</b>，
	 * 所以按 A（左）要<b>减</b>、按 D（右）要加。这里<b>只动身体朝向</b>，视角一律不碰。
	 *
	 * @param clientSide 写哪一端那张表（{@code SkisClient} 传 {@code true}、{@link SkiGlide} 传 {@code false}）
	 */
	public static float turnBody(int entityId, float fallback, double side, boolean clientSide) {
		IdMap<Float> bodyYaw = table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide);
		if (SkiAir.isStunned(entityId)) {
			return bodyYaw.getOrDefault(entityId, fallback);   // 硬着陆失控：A / D 不再转身体
		}
		float yaw = bodyYaw.getOrDefault(entityId, fallback) - (float) side * TURN_RATE_DEGREES;
		bodyYaw.put(entityId, yaw);
		return yaw;
	}

	/**
	 * <b>W：把身体朝视角方向转一步</b>（步长限幅、不越过目标），返回新朝向。
	 *
	 * <p>只转 {@code BODY_YAW}（本端那张），<b>不碰视角</b>：视角在世界坐标里不动，身体在下面转过去；
	 * 「视角相对身体」的偏角因此每 tick 收窄 {@link #VIEW_TURN_RATE_DEGREES} 度，
	 * 由 {@code client/SkiViewSmoothing} 的 {@code headOffset -= bodyStep} 自动收敛。
	 * 残余偏角小于 {@link #VIEW_TURN_MIN_DEGREES} 就不动（防抖）。
	 *
	 * <p>客户端与服务端各跑一次同一条规则（顺序：先 A / D 的 {@link #turnBody}，再这里），各写自己那张表。
	 *
	 * @param clientSide 写哪一端那张表（{@code SkisClient} 传 {@code true}、{@link SkiGlide} 传 {@code false}）
	 */
	public static float turnTowardView(int entityId, float fallback, float viewYaw, boolean clientSide) {
		IdMap<Float> bodyYaw = table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide);
		float yaw = bodyYaw.getOrDefault(entityId, fallback);
		if (SkiAir.isStunned(entityId)) {
			return yaw;   // 硬着陆失控：W 不再把身体朝视角追过去
		}
		float delta = Mth.wrapDegrees(viewYaw - yaw);
		if (Float.isNaN(delta) || Math.abs(delta) < VIEW_TURN_MIN_DEGREES) {
			return yaw;
		}
		float step = Mth.clamp(delta, -VIEW_TURN_RATE_DEGREES, VIEW_TURN_RATE_DEGREES);
		yaw += step;
		bodyYaw.put(entityId, yaw);
		return yaw;
	}

	/**
	 * 脱板 / 换板：清掉这个实体的身体朝向与转向推导状态。
	 *
	 * @param clientSide {@code true} ＝ 客户端调用（{@code SkisClient}）、{@code false} ＝ 服务端调用
	 *                   （{@link SkiGlide}）；决定清哪一端的表 —— 单机里两端共用同一个 entity id，
	 *                   客户端清表不能把服务端的失控 / 冷却（以及服务端那份转向推导状态）一起抹掉
	 */
	public static void forgetBody(int entityId, boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).remove(entityId);
		// 转向推导的三份状态一起清：脱板 / 换板之后不能拿着上一段弯的入弯方向继续算
		table(TURN_HEADING_CLIENT, TURN_HEADING_SERVER, clientSide).remove(entityId);
		table(TURN_YAW0_CLIENT, TURN_YAW0_SERVER, clientSide).remove(entityId);
		table(TURN_LAST_YAW_CLIENT, TURN_LAST_YAW_SERVER, clientSide).remove(entityId);
		// 空中状态（含失控计时）只清本端那张，见 SkiAir#forgetClient 的注释
		if (clientSide) {
			SkiAir.forgetClient(entityId);
		} else {
			SkiAir.forgetServer(entityId);
		}
	}

	/**
	 * 整表清空：换世界 / 重连 / 换维度时由客户端（{@code SkisClient}）调用 —— 那些场合所有 id 都作废，
	 * 留着只会在 id 被复用时把上一段的身体朝向当成「已经锁好」。
	 *
	 * @param clientSide 清哪一端那套表；客户端调用传 {@code true}
	 */
	public static void forgetBodyAll(boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).clear();
		table(TURN_HEADING_CLIENT, TURN_HEADING_SERVER, clientSide).clear();
		table(TURN_YAW0_CLIENT, TURN_YAW0_SERVER, clientSide).clear();
		table(TURN_LAST_YAW_CLIENT, TURN_LAST_YAW_SERVER, clientSide).clear();
	}

	// ---------------------------------------------------------------- 参数

	/** 双板：每下落 1 格加多少水平速度。 */
	public static double fallGainPerBlock() {
		return FALL_GAIN_PER_BLOCK;
	}

	/** 双板：速度上限（格/tick）。 */
	public static double maxSpeed() {
		return MAX_SPEED;
	}

	/** 双板这一份起跳 / 空翻数值（{@link SkiAir} 用；两块板各一份，互不影响）。 */
	public static SkiAir.Tuning airTuning() {
		return AIR_TUNING;
	}

	/** 双板：单 tick 下落加速上限。 */
	public static double fallGainPerTickMax() {
		return FALL_GAIN_PER_TICK_MAX;
	}

	/** 双板：动量衰减（越接近 1 越滑）。 */
	public static double momentumDecay() {
		return MOMENTUM_DECAY;
	}

	/** 双板：<b>穿板贴地时的重力倍数</b>（原版 0.08 格/tick² × 这个值；见 {@link #GRAVITY_MULTIPLIER}）。 */
	public static double gravityMultiplier() {
		return GRAVITY_MULTIPLIER;
	}

	/** 双板：<b>穿板腾空时的重力倍数</b>（原版 0.08 格/tick² × 这个值；见 {@link #AIR_GRAVITY_MULTIPLIER}）。 */
	public static double airGravityMultiplier() {
		return AIR_GRAVITY_MULTIPLIER;
	}

	/** 双板：按 S 刹车时每 tick 保留的比例（见 {@link #BRAKE_DECAY}）。 */
	public static double brakeDecay() {
		return BRAKE_DECAY;
	}

	/** 速度方向与<b>身体前方</b>（水平）的点积 &gt; {@link #BRAKE_MIN_ALIGN} 就算「还在往前滑」。 */
	public static boolean isMovingForward(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			return false;
		}
		// 用身体朝向而不是视角：双板现在视角可以独立于滑行方向，拿视角判「在往前滑」会在转过身后失效
		// 只在服务端跑（ServerPlayer）-> 读服务端那张表
		float yaw = bodyYaw(player.getId(), player.getYRot(), false) * Mth.DEG_TO_RAD;
		Vec3 front = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		return horizontal.normalize().dot(front) > BRAKE_MIN_ALIGN;
	}

	// ---------------------------------------------------------------- 落地对齐（双板专用）

	/**
	 * <b>这一 tick 要不要做「贴地对齐」</b>（双板专用，由 {@link SkiGlide} 的双板分支调用）：
	 *
	 * <ol>
	 *   <li>{@code player.onGround()} —— 只有贴地才掰方向（空中改速度方向会把人「拽」着走）；</li>
	 *   <li><b>没按 S</b>：{@code input} 为 <b>null 时放行</b>（拿不到输入不该关掉对齐），
	 *       {@code input.backward()} 为 true 直接返回 false —— 刹车 / 倒滑这一 tick 不拨方向；</li>
	 *   <li>水平速度<b>平方 &gt; 1.0E-4</b> —— 几乎停下就不值得掰方向。</li>
	 * </ol>
	 */
	public static boolean shouldAlignOnGround(ServerPlayer player, Input input) {
		if (SkiAir.isStunned(player.getId())) {
			return false;   // 硬着陆失控：落地对齐也跳闸（不另开写速度的点）
		}
		if (!player.onGround()) {
			return false;
		}
		if (input != null && input.backward()) {
			return false;
		}
		Vec3 motion = player.getDeltaMovement();
		return motion.x * motion.x + motion.z * motion.z > 1.0E-4D;
	}

	/**
	 * <b>贴地对齐</b>：把水平速度的<b>方向</b>朝身体前方（{@code BODY_YAW}，拿不到锁时用玩家 yaw 兜底）
	 * 转 {@code min(|残差|, ALIGN_DEGREES_PER_TICK)} 度 —— <b>只改方向、大小一点不动</b>
	 * （绕 Y 旋转水平分量，{@code y} 原样保留），写回 {@code setDeltaMovement} 并置
	 * {@code hurtMarked = true}（不置会被客户端的移动预测抹掉）。
	 *
	 * <p>残差用 {@link Mth#wrapDegrees(float)} 算（−180…180），所以「已经转过身」时也是走<b>短的那条</b>弧、
	 * 不会绕远路；每 tick 只转 {@link #ALIGN_DEGREES_PER_TICK} 9° ≈ 180°/s，落地后一两 tick 就朝上。
	 * 方向角与 {@code BODY_YAW} 同一套参数化：{@code 方向角 = atan2(−x, z)}，yaw 增大 ＝ 向右转
	 * （与 {@code SnowboardControl#rotateVelocity} 的旋转公式一致）。
	 *
	 * <p>判定在 {@link #shouldAlignOnGround}；本方法只做旋转，不判条件、也不动动量。
	 */
	public static void alignVelocity(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		double speedSqr = motion.x * motion.x + motion.z * motion.z;
		if (speedSqr <= 1.0E-4D) {
			return;   // 没速度可掰
		}
		double current = Math.toDegrees(Math.atan2(-motion.x, motion.z));
		float yaw = bodyYaw(player.getId(), player.getYRot(), false);   // 只在服务端跑 -> 服务端那张表
		float residual = Mth.wrapDegrees((float) (yaw - current));
		double step = Mth.clamp(residual, -ALIGN_DEGREES_PER_TICK, ALIGN_DEGREES_PER_TICK);
		double rad = Math.toRadians(step);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		player.setDeltaMovement(motion.x * cos - motion.z * sin, motion.y, motion.z * cos + motion.x * sin);
		player.hurtMarked = true;
	}

	// ---------------------------------------------------------------- 转向时的速度方向（双板专用）

	/**
	 * <b>转向期间速度方向由身体朝向推导</b>（双板专用，由 {@link SkiGlide} 的双板分支在整 tick
	 * <b>最后一个</b>写速度的位置调用）：把水平速度方向<b>直接写成</b>
	 * 「{@code TURN_HEADING}（入弯时的速度方向角）+ 身体累计转角」，大小与 {@code y} 都不动。
	 *
	 * <p><b>贴地才重写速度方向</b>：{@code !player.onGround()} 时本方法<b>直接返回</b>，只转身体 / 视角，
	 * 速度方向由惯性保持（空中转身不掰速度 —— 否则下落加速会沿新朝向叠加，人物在空中一转身，落点就跟着偏）；
	 * 空中这一 tick 连同 {@code TURN_HEADING} / {@code TURN_YAW0} 两份入弯记录一起清掉，
	 * 免得落地那一 tick 把空中累计的转角一次性算进速度方向、猛地一扭。
	 *
	 * <p>为什么要这一道：{@link #alignVelocity} 是 9°/tick 的限幅趋近，而且只在「贴地 + 没按 S」时跑
	 * （{@link #shouldAlignOnGround}），空中 / 刹车时速度方向<b>一点都不转</b>；{@code GlideSteering.steer}
	 * 那条 0.2 的 lerp 又是另一套节奏。于是同一段转里模型转了 180°、速度才转 150°，两条路各按各的速率走。
	 * 改成推导式之后，两者之差只剩<b>入弯那一刻固有的「身体−速度」偏角</b>，整段弯不变。
	 *
	 * <p>口径（每 tick 一次）：
	 * <ul>
	 *   <li><b>判定在不在转向</b>：拿 {@code BODY_YAW 现在} 与上一次调用时记下的 {@code TURN_LAST_YAW}
	 *       比 —— {@link #turnBody} / {@link #turnTowardView} 这一 tick 真的转过才算（A / D 按下一定转；
	 *       按 W 且朝视角还有残差才转）；没转就把 {@code TURN_HEADING} / {@code TURN_YAW0} 一起清掉；</li>
	 *   <li><b>入弯那一 tick</b>：记下当前速度方向角（{@code atan2(-x, z)}，与 {@link #alignVelocity}
	 *       同一套参数化）与当前 bodyYaw，本 tick 不改速度（off = 0）；</li>
	 *   <li><b>持续期间</b>：{@code off = wrapDegrees(bodyYawNow − TURN_YAW0)}，
	 *       期望方向角 {@code = wrapDegrees(TURN_HEADING + off)}，水平分量按这个角重写；</li>
	 *   <li>与当前速度差 &gt; {@code 1e-4} 才 {@code setDeltaMovement} 并 {@code hurtMarked = true}
	 *       （客户端才吃得到）。</li>
	 * </ul>
	 *
	 * <p>只改方向，不改大小、不改 y，也不碰动量；{@link #alignVelocity} / {@code GlideSteering.steer} /
	 * {@code desiredDirection} 的判据一个没动 —— 转向期间它们写的方向被这里覆盖只是<b>自然结果</b>。
	 * <b>与单板的 {@code SnowboardControl#alignCarveHeading} 各写一套，不共用表、不共用方法。</b>
	 */
	public static void alignTurnHeading(ServerPlayer player) {
		int entityId = player.getId();
		// 只在服务端跑（SkiGlide）-> 全部走服务端那套表；这三份都是「写一次值」型的推导状态，
		// 单机里也只有这一次写入，所以不属于翻倍清单
		IdMap<Double> headings = table(TURN_HEADING_CLIENT, TURN_HEADING_SERVER, false);
		IdMap<Float> yaw0s = table(TURN_YAW0_CLIENT, TURN_YAW0_SERVER, false);
		IdMap<Float> lastYaws = table(TURN_LAST_YAW_CLIENT, TURN_LAST_YAW_SERVER, false);
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		double speed = horizontal.length();

		if (!player.onGround()) {
			// 空中：只转身体与视角，速度方向不动。两份入弯记录一起清 —— 否则空中转完再落地，
			// 会在落地那一 tick 把空中累计的转角一次性算进速度方向，速度猛地一扭。
			// 下落加速（trackDescent）是沿当前速度方向叠加的，速度方向若跟着身体走，
			// 下落加速就会沿「新朝向」叠加 —— 这就是「空中转身改了速度方向」的观感来源。
			headings.remove(entityId);
			yaw0s.remove(entityId);
			return;
		}

		float yawNow = bodyYaw(entityId, player.getYRot(), false);
		Float lastYaw = lastYaws.put(entityId, yawNow);   // 记下本 tick 结束时的朝向，供下一 tick 比对
		boolean turning = lastYaw != null
				&& Math.abs(Mth.wrapDegrees(yawNow - lastYaw)) > TURN_EPS_DEGREES;

		if (!turning) {
			// 这一 tick 身体没动：退出转向，入弯记录一起清，下一段弯重新记
			headings.remove(entityId);
			yaw0s.remove(entityId);
			return;
		}
		if (speed < 1.0E-6D) {
			return;   // 速度几乎为 0：没有方向可推导（身体照旧转，下一 tick 有速度了再记 / 再锁）
		}

		Double heading = headings.get(entityId);
		Float yaw0 = yaw0s.get(entityId);
		if (heading == null || yaw0 == null) {
			// 入弯这一 tick：记下速度方向角与当时的 bodyYaw。本 tick 期望方向 == 当前方向，不必写。
			headings.put(entityId, Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z)));
			yaw0s.put(entityId, yawNow);
			return;
		}

		double off = Mth.wrapDegrees(yawNow - yaw0);                  // 身体从入弯到现在累计转了多少度
		double wantDeg = Mth.wrapDegrees((float) (heading + off));    // 期望方向角（度，atan2(-x, z) 口径）
		double wantRad = Math.toRadians(wantDeg);
		double wantX = -Math.sin(wantRad) * speed;
		double wantZ = Math.cos(wantRad) * speed;
		if (Math.abs(wantX - horizontal.x) <= 1.0E-4D && Math.abs(wantZ - horizontal.z) <= 1.0E-4D) {
			return;   // 已经在期望方向上（没被拽偏）就不写，省一次同步
		}
		player.setDeltaMovement(wantX, motion.y, wantZ);
		player.hurtMarked = true;
	}
}
