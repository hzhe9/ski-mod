package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * 单板（snowboard）的「人板一体」状态与手感常量：
 *
 * <ul>
 *   <li><b>身体朝向锁定</b>：穿上那一刻记下，客户端每 tick 写回 {@code yBodyRot} / {@code yBodyRotO}
 *       —— 头（视角）能随便转、身体不跟着转；</li>
 *   <li><b>只能左右平移</b>：A / D 由客户端 {@code ClientInputMixin} 换算到身体左右轴，前后分量一律丢掉；</li>
 *   <li><b>W 前倾 / S 后倾</b>：整个人连同脚上的板绕脚底一起倾（{@link #tiltDegrees()}，渲染层见
 *       {@code EntityTiltMixin}），相机位置也跟着（{@code CameraTiltMixin}）；倾斜是<b>渐进</b>的
 *       （{@link #tickTilt}）而不是瞬间跳到位。只改姿态，<b>不给推进</b>。</li>
 *   <li><b>carve</b>：A / D 侧压 → 身体朝向持续转（{@link #applyCarve}）＋ 沿身体前方补前冲；
 *       没按 A / D 但已经在滑时，光前 / 后倾也会转（后倾＝后刃，方向相反）。</li>
 * </ul>
 *
 * <p>滑行物理（下落加速、低摩擦动量、雪面推进）与双板同款，在 {@link SkiGlide} 里统一施加。
 */
public final class SnowboardControl {
	/** 实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final IdMap<Float> BODY_YAW = new IdMap<>();
	/** 实体 id → 俯仰姿态（当前值，平滑后；正 = 前倾 / 负 = 后倾），供模型与相机读取。 */
	private static final IdMap<Float> TILT_VALUE = new IdMap<>();
	/** 实体 id → 俯仰姿态的目标值（+1 前倾 / 0 直立 / -1 后倾），由按键刷新。 */
	private static final IdMap<Integer> TILT_TARGET = new IdMap<>();
	/** 实体 id → 头上一次被判定朝哪一侧（+1 身体右侧 / -1 左侧）；速度和按键都给不出方向时锁存它。 */
	private static final IdMap<Integer> HEAD_SIDE = new IdMap<>();
	/** 实体 id → 当前头偏角（平滑后的实际值，度；正 = 朝身体右侧、负 = 朝左），越肩时写 {@code yHeadRot}。 */
	private static final IdMap<Float> HEAD_OFFSET = new IdMap<>();
	/**
	 * 实体 id → <b>本次 carve 的入口水平速度</b>（格/tick），见 {@link #guardCarveSpeed}。
	 * 退出 carve 就清掉 —— 下一段弯重新记一次入口速度。
	 */
	private static final IdMap<Double> CARVE_SPEED = new IdMap<>();
	/** 实体 id → 本次 carve <b>已经持续的 tick 数</b>（入口那一 tick 记 0），配 {@link #CARVE_SPEED} 算速度下限。 */
	private static final IdMap<Integer> CARVE_TICKS = new IdMap<>();
	/**
	 * 实体 id → <b>入弯时的水平速度方向角</b>（弧度，{@code atan2(z, x)} 口径，见 {@link #alignCarveHeading}）。
	 *
	 * <p>转向期间速度方向<b>不再靠每 tick 增量维持</b>（那样会被回落加速 / 动量托底一路往回拽，
	 * 累计滞后），而是每 tick 用它 + 身体累计转角<b>直接算出来</b>。退出 carve 就清掉。
	 */
	private static final IdMap<Double> CARVE_HEADING = new IdMap<>();
	/**
	 * 实体 id → <b>入弯时的身体朝向</b>（度，{@code BODY_YAW} 的那个 yaw）。
	 * 期望速度方向 = {@link #CARVE_HEADING} + ({@code BODY_YAW 现在 − 它})，所以「身体转多少、速度就转多少」。
	 */
	private static final IdMap<Float> CARVE_YAW0 = new IdMap<>();

	/** 前倾 / 后倾时<b>整个人连同板</b>绕脚底倾斜的角度（度）。 */
	private static final float TILT_DEGREES = 15.0F;
	/** 头（视角）相对身体朝向能转的最大角度：左右各 {@code 135} 度，合计 270 —— 扭不到背后去。 */
	public static final float VIEW_LIMIT_DEGREES = 135.0F;
	/**
	 * <b>越肩视角模式</b>下，单板头相对<b>身体朝向</b>的偏角幅度：<b>默认（没按 W / S，或 W 与 S 同按）</b>
	 * 为 {@code 75} 度。符号不在这里 —— 朝哪一侧滑就朝哪一侧（见 {@link #tickHead}）：
	 * yaw 增大 = 向右，所以朝右滑用 {@code +75}、朝左滑用 {@code -75}。
	 */
	public static final float SNOWBOARD_HEAD_NEUTRAL_DEGREES = 75.0F;
	/** 按住 <b>W（前倾）</b>时的头偏角幅度：{@code 90} 度 —— 人俯下去，头再往外扭一点。 */
	public static final float SNOWBOARD_HEAD_FORWARD_DEGREES = 90.0F;
	/** 按住 <b>S（后倾）</b>时的头偏角幅度：{@code 60} 度 —— 人后仰，头收回一点。 */
	public static final float SNOWBOARD_HEAD_BACKWARD_DEGREES = 60.0F;
	/** 当前头偏角每 tick 吃掉「与目标差距」的多少（比例项：快到位时自动变慢，不会在目标附近抖）。 */
	private static final float HEAD_OFFSET_LERP = 0.45F;
	/**
	 * 每 tick 最多挪多少度（限幅项）。比例项单独用会在 150° 的左右翻转时起步冲到 60°/tick，
	 * 所以翻转靠这一项匀速扫过去：{@code 9} → 150° 约 25 tick（≈1.25 s），经过 0°（正前方），
	 * 看得出「头从右往左扭过去」；幅度变化（15° / 30°）够小，只吃比例项，约 9–12 tick。
	 */
	private static final float HEAD_OFFSET_MAX_STEP_DEGREES = 9.0F;
	/** 与目标的差距小于它就当作到位（收敛成干净的目标值，省掉无意义的末位抖动）。 */
	private static final float HEAD_OFFSET_EPSILON = 0.05F;
	/**
	 * 横向速度（水平速度投到身体左轴上的分量）超过它才算「正在往那一侧滑」；再小就当没在横移，
	 * 改看 A / D 输入，再没有就锁存上一次的判定（一停下来头不能自己回正）。
	 */
	private static final double HEAD_SIDE_MIN_SPEED = 0.05D;

	/**
	 * <b>carve（切雪转向）</b>：前倾（W）＋ 横移（A / D）同时按住时，身体朝向每 tick 朝那一侧转多少度。
	 * <b>1.2 度/tick ＝ 24 度/秒</b>，一直按着就会在雪地上画圈，转满 180° 需 150 tick ＝ 7.5 s。
	 * 2.0 → <b>1.2</b>（用户 2026-10-31「转向再慢一档」）：速度方向由 {@code alignCarveHeading} 从身体推导，
	 * 模型慢多少、速度就慢多少。
	 */
	private static final float TURN_RATE_DEGREES = 1.2F;
	// 注意：W / S <b>只转向、不加力</b> —— W / S 改的是速度的方向，不往速度向量上加量。
	// 所以这里没有 carve 推力常量 —— 滑行的速度快慢只由下落加速与地面推进决定，前后倾只改方向。
	/**
	 * 单板速度上限（格/tick）。
	 *
	 * <p>现实参照（1 格 = 1 米，格/tick × 72 = km/h）：原版步行 4.317 m/s ≈ 0.216 格/tick、
	 * 疾跑 5.612 m/s ≈ 0.281、飞行 10.89 ≈ 0.545、疾跑飞行 21.78 ≈ 1.089。
	 * 单板是横着切雪、可控性最差的那种，所以压到 <b>0.55</b>（≈ 11 m/s ≈ 40 km/h，比疾跑快一倍、仍低于创造飞行）。
	 */
	private static final double MAX_SPEED = 0.55D;

	/**
	 * <b>单板：跳高上限</b>（格）。满速起跳在离散物理（每 tick 先位移、再 {@code vy = (vy − g) × 0.98}）下
	 * 正好落在 <b>1.50</b> 格 —— 下面的 {@link #JUMP_POWER_BY_SPEED_LUT} 就是按它反解出来的。
	 *
	 * <p>这里的 g 分两段：<b>首 tick 用贴地档 0.104</b>（0.08 × {@link #GRAVITY_MULTIPLIER} 1.3）——
	 * 跳跃写在同一 tick 的实体 tick 内，而 {@code SkiGlide.applyGravity} 上一 tick 末尾按 {@code onGround()}
	 * 设的还是贴地档；<b>第二 tick 起</b>才换腾空档 <b>0.072</b>（0.08 × {@link #AIR_GRAVITY_MULTIPLIER} 0.9）。
	 * <b>只是表的上限依据</b>：代码不拿它反推力度（表才是唯一事实源），改上限要整张表重算。
	 */
	private static final double JUMP_MAX_HEIGHT = 1.5D;
	/** <b>单板</b>：速度→起法力查找表的点数（s = 0.0 / 0.1 / … / 1.0 共 11 点，步长 0.1）。 */
	private static final int JUMP_CURVE_SAMPLES = 11;
	/**
	 * <b>单板：速度 → 起跳力度</b>（格/tick）的 11 点查找表，下标 = s × 10（s = 0.0 / 0.1 / … / 1.0，
	 * s ＝ 水平速度 / {@link #MAX_SPEED}），两点之间由 {@link SkiAir.Tuning#jumpPower} <b>线性插值</b>。
	 *
	 * <p>表来自<b>对数曲线</b> {@code h(s) = 0.34 + (1.5 − 0.34) × ln(1 + 9s) / ln(10)}（h = 跳高，格）
	 * 在离散 tick 物理下反解力度（<b>首 tick 贴地档 0.104、第二 tick 起腾空 0.072</b> 格/tick²）：
	 * <b>前半段涨得快、越往后越平、末段增量趋于 0</b> —— 20% 速度已经拿到 57% 的高度，
	 * 满速恰好卡在 {@link #JUMP_MAX_HEIGHT} 1.50 格。
	 *
	 * <p>对应跳高（格）：0.34 / 0.66 / 0.86 / 1.00 / 1.11 / 1.20 / 1.28 / 1.34 / 1.40 / 1.45 / 1.50。
	 * 与双板各一份：调这里不动双板。
	 */
	private static final double[] JUMP_POWER_BY_SPEED_LUT = {
			0.208D, 0.302D, 0.348D, 0.377D, 0.400D, 0.417D, 0.430D, 0.442D, 0.452D, 0.461D, 0.470D
	};
	static {
		// 表长必须＝采样点数（插值步长 = 1 / (SAMPLES − 1) = 0.1）：长度写错在这里立刻炸，别带到游戏里
		if (JUMP_POWER_BY_SPEED_LUT.length != JUMP_CURVE_SAMPLES) {
			throw new IllegalStateException("JUMP_POWER_BY_SPEED_LUT 应有 " + JUMP_CURVE_SAMPLES + " 点");
		}
	}
	/**
	 * <b>单板：离地补跳的高度门槛</b>（格）：腾空时脚底到下方地面 ≤ 这个距离、{@code vy ≤ 0}、且按住空格，
	 * 就能再拿到一次起跳速度（力度照 {@link #JUMP_POWER_BY_SPEED_LUT}，与地面起跳同一个取值方法）。
	 * 只补竖直速度，<b>不碰空翻角度 / 连续按住计数 / 失控计时</b>。
	 * 1.0 ＝ 离地不到一格（过台阶、贴地小跳的余量）仍可再跳；0 ＝ 关掉。
	 */
	private static final double JUMP_GROUND_RANGE = 1.0D;
	/**
	 * <b>单板：离地后的「逗留窗」（土狼时间，coyote time）</b>：离开地面（{@code onGround} 由真变假）之后还有
	 * <b>6 tick</b>（0.3 s）之内按空格仍算「刚离地」，照样起跳 —— 滑行时 {@code onGround} 在下坡 / 起伏上
	 * 本来就会抖，不留这个窗口就会出现「明明还贴着雪面，按空格却不跳」。
	 */
	private static final int COYOTE_TICKS = 6;
	/**
	 * <b>单板：输入缓冲</b>（tick）：按下空格那一刻起 <b>5 tick</b>（0.25 s）内，只要起跳条件成立就自动发出，
	 * 不用重新按 —— 解决「提前一点按下去没反应」。
	 */
	private static final int JUMP_BUFFER_TICKS = 5;
	/** <b>单板：起跳冷却</b>（tick）：一次起跳后 <b>10 tick</b> 内不再因「按住空格」重复起跳（落地后由贴地条件自然放行）。 */
	private static final int JUMP_COOLDOWN_TICKS = 10;
	/**
	 * <b>单板</b>：空中<b>每 tick 实时判</b>的水平速度门槛（格/tick）：低于它这一 tick 就不转角（只是普通小跳）。
	 * <b>0.12 ≈ 单板上限 0.55 的 22%</b> —— 慢滑按空格仍然只是「小跳一下」，稍一起速就翻得动。
	 */
	private static final double FLIP_MIN_SPEED = 0.12D;
	/**
	 * <b>单板</b>：后空翻<b>按住空格</b>时每 tick 转多少度（<b>16.0</b>°/tick ＝ 320°/s，
	 * 一圈 360° 要 <b>22.5 tick</b> ＝ <b>1.125 s</b>）。
	 *
	 * <p>口径是「<b>按住多久就转多少</b>」：{@code holdTicks} 从<b>按下那一刻</b>起算、<b>只由松手归零</b>
	 * （落地 / 起跳成功 / 失控都不清），空中每 tick 实时判 {@code holdTicks > FLIP_HOLD_DELAY_TICKS}（4）
	 * ＋ 速度够 ＋ 没失控才加这个值 —— 前 4 个按住的 tick 角度恒 0（模型静止段），短按＝纯跳跃；
	 * 松手立刻定格（模型停在那偏角）、计数归零。角度不封顶，可以按住转多圈；
	 * 落地看与直立差多少（见 {@code FLIP_LANDING_TOLERANCE_DEGREES}）。
	 * <b>与双板同值（两板都是 16.0°/tick ＝ 22.5 tick ＝ 1.125 s）</b>，各一个常量、改一块不动另一块。
	 */
	private static final double FLIP_DEGREES_PER_TICK = 16.0D;
	/**
	 * <b>单板</b>：连续按住空格多少个 tick <b>之后</b>才开始累加角度（<b>4</b> tick ＝ 0.2 s）。
	 *
	 * <p>判据是 {@code holdTicks > 它}（<b>严格大于</b>）：按住的前 4 个 tick 角度恒为 <b>0</b> ——
	 * 这就是「静止段」（模型定住不动，之后才起翻）。计数从<b>按下那一刻</b>起算、<b>只由松手归零</b>
	 * （落地 / 起跳成功 / 失控都不清），所以站着按住很久再起跳＝一离地就接着转。
	 * 短按（≤ 4 tick）＝ 纯跳跃、一点不转。
	 */
	private static final int FLIP_HOLD_DELAY_TICKS = 4;
	/**
	 * <b>单板</b>：落地时与<b>直立</b>（0°/360°/720°…）的角距就是这个范围（±25°）＝干净落地；
	 * 超出＝摔倒（水平速度 ×{@code CRASH_SPEED_MULTIPLIER} ＋ 失控）。多翻几圈只要停在直立附近也算净落。
	 */
	private static final double FLIP_LANDING_TOLERANCE_DEGREES = 25.0D;
	/** <b>单板</b>：摔倒时水平速度保留的比例（0.35 ＝ 掉 65%）。 */
	private static final double CRASH_SPEED_MULTIPLIER = 0.35D;
	/** <b>单板</b>：摔倒后失控多少 tick（20 tick ＝ 1 s）：转向 / 压刃 / carve / 追视角全部跳过。 */
	private static final int CRASH_STUN_TICKS = 20;
	/**
	 * <b>单板</b>：摔倒扣血的上限（4.0 ＝ 偏差 180° 扣 2 颗心、90° 扣 1 颗心）。{@code > 0} 时服务端按
	 * 「与直立偏差 / 180 × 它」扣血，只在 {@code ServerPlayer} 分支、走 {@code hurtServer}。
	 *
	 * <p><b>为什么要用 {@code damageSources().generic()} 而不是 {@code fall()}</b>：本模组的
	 * {@code SnowLanding} 挂在 Fabric {@code ServerLivingEntityEvents.ALLOW_DAMAGE}（＝{@code LivingEntity#hurtServer}）上，
	 * 对 {@code DamageTypeTags.IS_FALL} 的伤害返回 {@code false} 直接取消（雪 / 冰面上、或 60 tick 内踩过雪）——
	 * 摔落类型的「摔倒扣血」在雪地上会被整份吃掉。{@code minecraft:generic} 不在 {@code is_fall} tag 里，
	 * 所以拦不到；同时它在 {@code bypasses_armor} tag 里，护甲不会把 4.0 点折掉，扣的就是标称值。
	 * 只走 {@code hurtServer} + 现有偏移公式，不动 {@code deltaMovement} 以外的任何东西（不 setPos）。
	 */
	private static final double CRASH_DAMAGE_MAX = 4.0D;

	/** 单板这一份起跳 / 空翻数值（状态机在 {@link SkiAir}，数值留在这里）。 */
	private static final SkiAir.Tuning AIR_TUNING = new SkiAir.Tuning(
			JUMP_POWER_BY_SPEED_LUT, JUMP_MAX_HEIGHT, JUMP_GROUND_RANGE,
			COYOTE_TICKS, JUMP_BUFFER_TICKS, JUMP_COOLDOWN_TICKS, MAX_SPEED,
			FLIP_MIN_SPEED, FLIP_DEGREES_PER_TICK, FLIP_HOLD_DELAY_TICKS, FLIP_LANDING_TOLERANCE_DEGREES,
			CRASH_SPEED_MULTIPLIER, CRASH_STUN_TICKS, CRASH_DAMAGE_MAX);

	/**
	 * 单板：每下落 1 格加多少水平速度（方向沿当前水平速度，不跟随身体朝向 —— 只加大小、不掰方向）。
	 * 0.11 → 0.13 → 0.15（两次各加 0.02）：单板基数一直比双板大，单次给的体感才够。
	 */
	private static final double FALL_GAIN_PER_BLOCK = 0.15D;
	/** 单板：单 tick 下落加速上限。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;   // 与双板统一：单次最多加 0.3，防悬崖一瞬间给爆
	/**
	 * <b>穿单板、<u>贴地</u>时的重力倍数</b>：把原版 {@code Attributes.GRAVITY}（0.08 格/tick²）乘上这个值，
	 * <b>只在 {@code onGround()} 时生效</b>（滑行贴地、落地压雪）。腾空时改用 {@link #AIR_GRAVITY_MULTIPLIER}。
	 * 1.0 = 原版；<b>1.3 = 0.104 格/tick²</b>（比平时重力更快，又不至于贴地太砸）。
	 * 跳高主体<b>不吃这个值</b>（空中按 {@link #AIR_GRAVITY_MULTIPLIER} 0.072 反解，只有起跳首 tick
	 * 仍读贴地档 0.104 —— 见 {@link #JUMP_MAX_HEIGHT}、{@link #JUMP_POWER_BY_SPEED_LUT}）。
	 */
	private static final double GRAVITY_MULTIPLIER = 1.3D;

	/**
	 * <b>穿单板、<u>腾空</u>时的重力倍数</b>（跳跃 / 下落全程）：0.08 × 0.9 = <b>0.072</b> 格/tick²，
	 * 比原版轻一点 —— 空中飘一点、滞空更长（空翻能按住的 tick 更多）。
	 * {@link #JUMP_POWER_BY_SPEED_LUT} 就是按这个重力反解的（起跳首 tick 例外，仍是贴地档 0.104）；
	 * 1.0 = 原版（0.08）。终端速度（{@code 49 × g}）3.92 → <b>3.53</b> 格/tick。
	 */
	private static final double AIR_GRAVITY_MULTIPLIER = 0.9D;
	/** 单板：动量每 tick 保留比例（比双板滑得多 —— 切雪转向时速度不该被地面摩擦啃掉）。 */
	private static final double MOMENTUM_DECAY = 0.998D;   // 每 tick 掉 0.2%（1 秒 −4%），与双板同值
	/**
	 * <b>转向期间</b>（carve 生效时）的动量保留比例 —— 比平时更滑（0.999 vs 0.998）。
	 *
	 * <p>平时用 {@link #MOMENTUM_DECAY} 0.998（每 tick 掉 0.2%，1 秒 −4%）；转向期间用这个 0.999
	 * （1 秒 −2%）—— 这是当初「减少转向过程中的摩擦」那条要求在 0.998 基线下的等价值。
	 * 想让转向与直行完全一样就改成 0.998；想「转向完全不掉速」就改成 1.0。
	 */
	private static final double CARVE_MOMENTUM_DECAY = 0.999D;
	/**
	 * 单板的下落加速<b>结算步长</b>（格）：每累计下降这么多格结算一次。
	 *
	 * <p>双板是 1.0（每满 1 格才给一次），单板用 0.5 —— 每降半格结算一次，单次给
	 * {@link #FALL_GAIN_PER_BLOCK} × 0.5 = 0.055，下坡降没半格就有第一份加速（不用攒满一整格）。
	 * 每格总量仍是 {@link #FALL_GAIN_PER_BLOCK} 0.11/格。
	 */
	private static final double DESCENT_STEP_BLOCKS = 0.5D;
	/** 只用 W / S 把方向朝前 / 后摆时的平滑系数：每 tick 吃掉剩余角度的这个比例。 */
	private static final double VEER_SMOOTHING = 0.35D;
	/**
	 * 这个摆动每 tick 最多转多少度（平滑趋近的速度上限）。
	 * <b>2.4</b>°/tick ＝ 48°/s（转满 180° 需 75 tick ＝ 3.75 s）；4.0 → <b>2.4</b>，与双板的 W 转向同速。
	 */
	private static final double VEER_MAX_DEGREES = 2.4D;
	/** 光靠前 / 后倾（没按 A / D）也能切雪的最小速度：滑得比这快就够压刃了。 */
	private static final double CARVE_MIN_SPEED = 0.05D;
	/** 俯仰姿态每 tick 向目标靠拢的比例（0.25 ≈ 十来个 tick 到位）—— 倾斜不是瞬间跳过去的。 */
	private static final float TILT_LERP = 0.25F;
	/** 与目标的差距小于它就当作到位。 */
	private static final float TILT_EPSILON = 0.01F;

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
	}

	public static void forget(int entityId) {
		BODY_YAW.remove(entityId);
		TILT_VALUE.remove(entityId);
		TILT_TARGET.remove(entityId);
		HEAD_SIDE.remove(entityId);
		HEAD_OFFSET.remove(entityId);
		CARVE_SPEED.remove(entityId);
		CARVE_TICKS.remove(entityId);
		CARVE_HEADING.remove(entityId);
		CARVE_YAW0.remove(entityId);
		SkiAir.forget(entityId);   // 空中状态（含失控计时）一起清
	}

	public static void forgetAll() {
		BODY_YAW.clear();
		TILT_VALUE.clear();
		TILT_TARGET.clear();
		HEAD_SIDE.clear();
		HEAD_OFFSET.clear();
		CARVE_SPEED.clear();
		CARVE_TICKS.clear();
		CARVE_HEADING.clear();
		CARVE_YAW0.clear();
	}

	/** 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。 */
	public static float bodyYaw(int entityId, float fallback) {
		return BODY_YAW.getOrDefault(entityId, fallback);
	}

	/** 客户端每 tick 记录俯仰姿态<b>目标</b>：+1 前倾 / 0 直立 / -1 后倾。 */
	public static void setTiltTarget(int entityId, int target) {
		TILT_TARGET.put(entityId, target);
	}

	/** 客户端每 tick 推进一步平滑：当前值按 {@link #TILT_LERP} 向目标靠拢（所以倾斜是渐进的）。 */
	public static void tickTilt(int entityId) {
		int target = TILT_TARGET.getOrDefault(entityId, 0);
		float current = TILT_VALUE.getOrDefault(entityId, 0.0F);
		float next = current + (target - current) * TILT_LERP;
		if (Math.abs(target - next) < TILT_EPSILON) {
			next = target;
		}
		if (next == 0.0F) {
			TILT_VALUE.remove(entityId);
		} else {
			TILT_VALUE.put(entityId, next);
		}
	}

	/** 当前俯仰姿态（平滑后的浮点值：1 = 完全前倾，-1 = 完全后倾），模型与相机用它。 */
	public static float tiltValue(int entityId) {
		return TILT_VALUE.getOrDefault(entityId, 0.0F);
	}

	/**
	 * 越肩视角下单板头的<b>目标幅度</b>（度，恒正）：按住 <b>W（前倾）</b> 用
	 * {@link #SNOWBOARD_HEAD_FORWARD_DEGREES}、按住 <b>S（后倾）</b> 用
	 * {@link #SNOWBOARD_HEAD_BACKWARD_DEGREES}，其余（都没按 / W 与 S 同按 / 拿不到按键）
	 * 用 {@link #SNOWBOARD_HEAD_NEUTRAL_DEGREES}。
	 */
	public static float headMagnitude(Input input) {
		if (input == null || input.forward() == input.backward()) {
			return SNOWBOARD_HEAD_NEUTRAL_DEGREES;
		}
		return input.forward() ? SNOWBOARD_HEAD_FORWARD_DEGREES : SNOWBOARD_HEAD_BACKWARD_DEGREES;
	}

	/**
	 * 头朝哪一侧：<b>+1 = 朝身体右侧（yaw 增大，与 D 同向）</b>、<b>-1 = 朝左侧（yaw 减小）</b>。
	 *
	 * <p>判定顺序：① <b>实际横移方向</b> —— 水平速度投到身体左轴 {@link #sideLocked} 上，往左滑给 -1；
	 * ② 侧向分量太小（&lt; {@link #HEAD_SIDE_MIN_SPEED}）时看最近按的 <b>A / D</b>（{@link #sideSign}，
	 * A = 左 → -1，D = 右 → +1）；③ 两者都拿不到就<b>锁存</b>上一次的判定 —— 一停下滑行头不能自己回正。
	 */
	public static int headSide(Entity entity, Input input) {
		int entityId = entity.getId();
		Vec3 motion = entity.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		// 身体左轴（sideLocked 的 sign=+1），与 sideLocked / A 键同一个「左」的约定
		double lateral = horizontal.dot(sideLocked(entityId, entity.getYRot(), 1.0D));
		double press = sideSign(input);
		int side;
		if (Math.abs(lateral) >= HEAD_SIDE_MIN_SPEED) {
			side = lateral > 0.0D ? -1 : 1;   // 往身体左侧滑 → 头朝左
		} else if (press != 0.0D) {
			side = press > 0.0D ? -1 : 1;
		} else {
			return HEAD_SIDE.getOrDefault(entityId, 1);   // 锁存（默认朝右，与旧的固定 +80 同侧）
		}
		HEAD_SIDE.put(entityId, side);
		return side;
	}

	/**
	 * 越肩视角下推进单板头的偏角（客户端每 tick 对本地玩家调一次）：目标 =
	 * {@link #headSide} × {@link #headMagnitude}，当前值按「比例 {@link #HEAD_OFFSET_LERP} ＋ 单步上限
	 * {@link #HEAD_OFFSET_MAX_STEP_DEGREES}」趋近目标。
	 *
	 * <p>比例项让快到位时自动变慢（不会在目标附近一顿一顿），限幅项把 150° 的左右翻转压成匀速扫过，
	 * 中途经过 <b>0°（正前方）</b> —— 所以看到的是「头从右边慢慢扭到左边」，不是瞬间翻面。
	 * 幅度变化（75↔90、75↔60）幅度小，只吃比例项，也是渐进的。
	 *
	 * <p>首次调用（表里没这个实体）直接取目标值：刚穿板 / 刚开越肩时不做一段无意义的入场扫动。
	 */
	public static void tickHead(Entity entity, Input input) {
		int entityId = entity.getId();
		float target = headSide(entity, input) * headMagnitude(input);
		float current = HEAD_OFFSET.getOrDefault(entityId, target);
		float step = Mth.clamp((target - current) * HEAD_OFFSET_LERP,
				-HEAD_OFFSET_MAX_STEP_DEGREES, HEAD_OFFSET_MAX_STEP_DEGREES);
		float next = current + step;
		if (Math.abs(target - next) < HEAD_OFFSET_EPSILON) {
			next = target;
		}
		HEAD_OFFSET.put(entityId, next);
	}

	/** 当前头偏角（度；正 = 朝身体右侧、负 = 朝左）—— 越肩视角下 {@code yHeadRot = 身体朝向 + 它}。 */
	public static float headOffset(int entityId) {
		return HEAD_OFFSET.getOrDefault(entityId, 0.0F);
	}

	/** 给定 yaw 的前方（水平单位向量）；yaw = 0 时朝 +z，与 {@link SkiControl} 的约定一致。 */
	public static Vec3 front(int entityId, float yaw) {
		float rad = yaw * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(rad), 0.0D, Mth.cos(rad));
	}

	/**
	 * 沿<b>锁定的身体朝向</b>的前方 —— 倾斜的偏移方向、静止时的滑行方向都用它。
	 *
	 * <p><b>别用视角朝向</b>（{@code player.getYRot()}）：那是鼠标看的那个方向，玩家一转视野它就变，
	 * 偏移会绕着玩家转圈（症状：转视野时相机甩到身体外面去）。身体朝上是锁死的，才是不动的那个基准。
	 */
	public static Vec3 frontLocked(int entityId, float fallbackYaw) {
		return front(entityId, bodyYaw(entityId, fallbackYaw));
	}

	public static float tiltDegrees() {
		return TILT_DEGREES;
	}

	/** 单板：速度上限（格/tick）。 */
	public static double maxSpeed() {
		return MAX_SPEED;
	}

	/** 单板这一份起跳 / 空翻数值（{@link SkiAir} 用；两块板各一份，互不影响）。 */
	public static SkiAir.Tuning airTuning() {
		return AIR_TUNING;
	}

	/** 单板：每下落 1 格加多少水平速度。 */
	public static double fallGainPerBlock() {
		return FALL_GAIN_PER_BLOCK;
	}

	/** 单板：单 tick 下落加速上限。 */
	public static double fallGainPerTickMax() {
		return FALL_GAIN_PER_TICK_MAX;
	}

	/** 单板：<b>穿板贴地时的重力倍数</b>（原版 0.08 格/tick² × 这个值；见 {@link #GRAVITY_MULTIPLIER}）。 */
	public static double gravityMultiplier() {
		return GRAVITY_MULTIPLIER;
	}

	/** 单板：<b>穿板腾空时的重力倍数</b>（原版 0.08 格/tick² × 这个值；见 {@link #AIR_GRAVITY_MULTIPLIER}）。 */
	public static double airGravityMultiplier() {
		return AIR_GRAVITY_MULTIPLIER;
	}

	/** 单板：动量衰减（越接近 1 越滑）。 */
	public static double momentumDecay() {
		return MOMENTUM_DECAY;
	}

	/** 单板：<b>转向期间</b>的动量衰减（比 {@link #momentumDecay()} 更滑，见 {@link #CARVE_MOMENTUM_DECAY}）。 */
	public static double carveMomentumDecay() {
		return CARVE_MOMENTUM_DECAY;
	}

	/**
	 * 这一 tick 是不是在<b>转向</b>（与 {@link #applyCarve} 的前置条件同一套判定）：压着刃（W / S）
	 * 且转得动 —— 有侧压（A / D）一定在转，没侧压时要有滑速才转得动。
	 *
	 * <p>转向期间用 {@link #CARVE_MOMENTUM_DECAY} 的摩擦，而且反向侧压算「换刃」不算刹车
	 * （见 {@link SkiGlide} 里的动量处理），否则刚起弯那一下速度会被原版摩擦吃掉一大截。
	 */
	public static boolean isCarving(int entityId, Input input, double speed) {
		if (SkiAir.isStunned(entityId)) {
			return false;   // 硬着陆失控期间压刃 / carve 整段跳过（转向入口统一在这里跳闸）
		}
		if (input == null || (!input.forward() && !input.backward())) {
			return false;
		}
		return sideSign(input) != 0.0D || speed >= CARVE_MIN_SPEED;
	}

	/** 单板：下落加速每累计这么多格结算一次（比双板的 1.0 格细）。 */
	public static double descentStepBlocks() {
		return DESCENT_STEP_BLOCKS;
	}

	/**
	 * <b>转向期间不掉速</b>：carve 持续期间给水平速度一个「按入口速度缓慢衰减」的下限。
	 *
	 * <p>口径（每 tick 在 {@code SkiGlide} 的动量结算<b>之后</b>调一次）：
	 * <ul>
	 *   <li><b>进入</b>（上一 tick 没在 carve、这一 tick {@link #isCarving} 为真）：记下
	 *       {@code entrySpeed = 当前水平速度}，本 tick 只是记账，不动速度；</li>
	 *   <li><b>持续期间</b>每 tick 算下限 {@code target = entrySpeed × CARVE_MOMENTUM_DECAY^持续 tick 数}；
	 *       只有<b>没撞到方块</b>（{@code horizontalCollision} 为假）且当前水平速度 &lt; target 时，
	 *       才把水平速度<b>缩放</b>到 target（方向沿用当前方向、y 不动）并 {@code hurtMarked = true}；</li>
	 *   <li><b>退出</b>（{@link #isCarving} 变假，比如松开 W / S）：两个表一起清掉，下一段弯重新记入口速度。</li>
	 * </ul>
	 *
	 * <p><b>只做下限、不封顶</b>：下坡 / 下落加速自己涨上去的速度不会被它拉回去 —— 撞墙那一 tick 动量被砍半之后，
	 * 下一个自由 tick 就能回到这条曲线上（旧行为是从被砍半的动量继续衰减，整段弯都回不来）。
	 * 用的是与动量同一个衰减常数，所以平地上这条下限与「动量托底」等价；它的意义是把<b>被截断后的恢复</b>
	 * 锚定在入口速度上，而不是锚在被截断的那个值上。
	 */
	public static void guardCarveSpeed(ServerPlayer player) {
		int entityId = player.getId();
		Input keys = player.getLastClientInput();
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();

		if (!isCarving(entityId, keys, speed)) {
			CARVE_SPEED.remove(entityId);
			CARVE_TICKS.remove(entityId);
			return;
		}
		Double entry = CARVE_SPEED.get(entityId);
		if (entry == null) {
			// 进入 carve 这一 tick：只记入口速度（下限就等于当前速度，不必动速度）
			CARVE_SPEED.put(entityId, speed);
			CARVE_TICKS.put(entityId, 0);
			return;
		}
		int elapsed = CARVE_TICKS.getOrDefault(entityId, 0);
		CARVE_TICKS.put(entityId, elapsed + 1);
		if (player.horizontalCollision) {
			return;   // 撞到方块这一 tick 不补（否则等于顶着墙往前推）
		}
		double target = Math.min(entry * Math.pow(CARVE_MOMENTUM_DECAY, elapsed), MAX_SPEED);
		if (speed >= target || speed <= 1.0E-6D) {
			return;   // 已经够快（下坡加速涨上去的也算）；速度几乎为 0 时没有方向可缩放
		}
		double keep = target / speed;
		player.setDeltaMovement(motion.x * keep, motion.y, motion.z * keep);
		player.hurtMarked = true;
	}

	/**
	 * <b>速度方向由身体朝向推导</b>：转向期间每 tick 把水平速度方向<b>直接写成</b>
	 * 「入弯时的速度方向角 + 身体累计转角」，大小与 y 都不动。
	 *
	 * <p>为什么不是「每 tick 转 delta」：同一个 tick 里还有别的写者会把速度方向拽回去 ——
	 * 回落加速沿<b>当前水平速度方向</b>加一份（{@code SkiGlide.descentDirection}，单板不再投影到体轴）、
	 * 动量托底又按当前方向重写一遍。速度方向每 tick 只跟着转一部分，累计下来永远落在身体朝向后面，
	 * 而且 W / S 两条路拽偏的方向相反（W 时速度转得比身体慢、S 时比身体快）—— 症状就是
	 * 「模型已经转了 180°，速度才转了 150°，W 和 S 的差距还不一样」。
	 *
	 * <p>改成推导式之后，速度方向与身体朝向之间只差一个<b>入弯时的固定偏角</b>，不再随时变长：
	 * <ul>
	 *   <li>入弯那一 tick（{@link #applyCarve}）记下 {@link #CARVE_HEADING}（{@code atan2(z, x)}，弧度）
	 *       与 {@link #CARVE_YAW0}（当时的 bodyYaw）；退出 carve 时清掉；</li>
	 *   <li>期望方向角 = {@code CARVE_HEADING + toRadians(wrapDegrees(bodyYawNow - CARVE_YAW0))}
	 *       —— {@code atan2(z, x)} 与 yaw 在同一个旋转方向上（都随 yaw 增量增大），所以直接相加；</li>
	 *   <li>变化超过 {@code 1e-4} 就 {@code setDeltaMovement} 并 {@code hurtMarked = true}（客户端才吃得到）。</li>
	 * </ul>
	 *
	 * <p>调用点是整 tick 里<b>最后一个</b>写速度的地方（{@code SkiGlide.register} 里 {@link #guardCarveSpeed}
	 * 之后），所以同 tick 内别的写者造成的偏角不会累下去；只改方向，不改大小、不改 y。
	 */
	public static void alignCarveHeading(ServerPlayer player) {
		int entityId = player.getId();
		Input keys = player.getLastClientInput();
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		double speed = horizontal.length();

		if (!isCarving(entityId, keys, speed)) {
			// 已经不在 carve：两份状态一起清掉，下一段弯重新记
			CARVE_HEADING.remove(entityId);
			CARVE_YAW0.remove(entityId);
			return;
		}
		Double heading = CARVE_HEADING.get(entityId);
		Float yaw0 = CARVE_YAW0.get(entityId);
		if (heading == null || yaw0 == null || speed < 1.0E-6D) {
			return;   // 没有入弯记录 / 没有方向可对齐
		}

		float yawNow = bodyYaw(entityId, player.getYRot());
		double turned = Mth.wrapDegrees(yawNow - yaw0);      // 身体从入弯到现在累计转了多少度
		double wantRad = heading + Math.toRadians(turned);    // 期望方向角（弧度，atan2(z, x) 口径）
		double wantX = Math.cos(wantRad) * speed;
		double wantZ = Math.sin(wantRad) * speed;
		if (Math.abs(wantX - horizontal.x) <= 1.0E-4D && Math.abs(wantZ - horizontal.z) <= 1.0E-4D) {
			return;   // 已经在期望方向上（没被拽偏）就不写，省一次同步
		}
		player.setDeltaMovement(wantX, motion.y, wantZ);
		player.hurtMarked = true;
	}

	/**
	 * <b>carve 一步（只转向、不加力）</b>：把锁定的身体朝向朝那一侧转一个 tick 的量，并让
	 * <b>速度方向跟着转同样的角度</b>（不是拨到侧向、也不补任何推力）。
	 * 服务端与客户端各跑一次，两边朝向与速度方向保持一致。
	 *
	 * <p>腾空也照跑（从高处落下时要能带着速度拐弯）。
	 *
	 * <p>另外，本方法兼作<b>入弯登记</b>：进入 carve 那一 tick 记下速度方向角与 bodyYaw
	 * （{@link #CARVE_HEADING} / {@link #CARVE_YAW0}），退出 carve 清掉；每 tick 的方向最终由
	 * {@link #alignCarveHeading} 在 tick 末尾按「入弯方向角 + 身体累计转角」重写 —— 这里的
	 * {@link #rotateVelocity} 只是让同 tick 内的后续计算（客户端尤其）方向先对上。
	 *
	 * @return 本 tick 转过的角度（0 表示没在 carve）
	 */
	public static float applyCarve(Entity entity, Input input, double speed) {
		int entityId = entity.getId();
		if (!isCarving(entityId, input, speed)) {
			// 只在“压刃”时切雪：光按 A / D 是纯横移，不转向。
			// 退出 carve 就把入弯记录清掉 —— 下一段弯重新记一次速度方向角与 bodyYaw。
			CARVE_HEADING.remove(entityId);
			CARVE_YAW0.remove(entityId);
			return 0.0F;
		}
		Vec3 entry = entity.getDeltaMovement();
		Vec3 entryHorizontal = new Vec3(entry.x, 0.0D, entry.z);
		if (entryHorizontal.lengthSqr() < 1.0E-6D) {
			// 速度几乎为 0：没有方向可记（下面照旧转身体，原地压刃也照样摆姿态）
			CARVE_HEADING.remove(entityId);
			CARVE_YAW0.remove(entityId);
		} else if (CARVE_HEADING.get(entityId) == null) {
			// 本 tick 开始 carve：记下入弯时的速度方向角（atan2(z, x)，弧度）与当时的 bodyYaw。
			// 之后每 tick 由 alignCarveHeading 用「它 + 身体累计转角」直接算出期望方向 ——
			// 不再靠每 tick 的增量，增量会被回落加速 / 动量托底一路拽回去，越滑越落后。
			CARVE_HEADING.put(entityId, Math.atan2(entryHorizontal.z, entryHorizontal.x));
			CARVE_YAW0.put(entityId, bodyYaw(entityId, entity.getYRot()));
		}
		double side = sideSign(input);   // A / D 的侧压：+1 左 / -1 右 / 0 没按
		double turn;
		if (side != 0.0D) {
			turn = input.backward() ? -side : side;   // 有侧压：后倾＝后刃（反向）
		} else {
			// 只用前 / 后倾：把滑行方向朝 **身体前方**（W）/ **身体后方**（S）摆。
			// 这样“往左滑”和“往右滑”按同一个键效果才一致 —— 之前用固定左 / 右转的符号，
			// 结果是往左滑按 W 朝前、往右滑按 W 朝后 —— 同一个键在两侧滑行时给出相反效果。
			if (speed < CARVE_MIN_SPEED) {
				return 0.0F;
			}
			Vec3 motion = entity.getDeltaMovement();
			Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
			if (horizontal.lengthSqr() < 1.0E-6D) {
				return 0.0F;
			}
			Vec3 front = frontLocked(entityId, entity.getYRot());
			Vec3 target = input.forward() ? front : front.scale(-1.0D);
			// 在 F(θ) = (-sinθ, cosθ) 的参数化下，方向角 = atan2(-x, z)；yaw 增大 ＝ 向右转
			double current = Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z));
			double goal = Math.toDegrees(Math.atan2(-target.x, target.z));
			double diff = Mth.wrapDegrees((float) (goal - current));
			// 平滑趋近：步长与剩余角度成正比（快到位时自动变慢），而不是“固定 2°/tick、到了就死停”——
			// 后者在目标附近会变成一顿一顿的：固定步长 + 到目标死停，残差小时每 tick 都在越过与停下之间跳。
			turn = Mth.clamp(diff * VEER_SMOOTHING, -VEER_MAX_DEGREES, VEER_MAX_DEGREES) / TURN_RATE_DEGREES;
		}
		float delta = (float) (turn * TURN_RATE_DEGREES);
		BODY_YAW.computeIfPresent(entityId, (id, yaw) -> yaw + delta);
		rotateVelocity(entity, delta);
		return delta;
	}

	/**
	 * 把速度的<b>方向</b>绕 Y 轴转 {@code degrees}（与身体朝向同一个角度、同一方向），大小不变。
	 *
	 * <p>注意别写成“把速度方向直接拨到侧向”：那样往左滑时一按 S 速度会瞬间跳到右侧 —— 方向与惯性脱节。
	 * 要的是**跟着朝向一起转**该转的角度 —— 朝向转多少它转多少，于是带着惯性的滑行路线慢慢弯过去。
	 */
	private static void rotateVelocity(Entity entity, float degrees) {
		Vec3 motion = entity.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			return;
		}
		double rad = Math.toRadians(degrees);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double x = horizontal.x * cos - horizontal.z * sin;
		double z = horizontal.z * cos + horizontal.x * sin;
		entity.setDeltaMovement(x, motion.y, z);
		entity.hurtMarked = true;
	}

	/** 玩家当前按的左右：+1 = 身体左侧，-1 = 身体右侧，0 = 没按。 */
	public static double sideSign(Input input) {
		if (input == null) {
			return 0.0D;
		}
		return (input.left() ? 1.0D : 0.0D) - (input.right() ? 1.0D : 0.0D);
	}

	/** 沿<b>锁定的身体朝向</b>的左 / 右方向（sign 见 {@link #sideSign}）—— 单板<b>几乎没有速度</b>时下落加速才按它给。 */
	public static Vec3 sideLocked(int entityId, float fallbackYaw, double sign) {
		float rad = bodyYaw(entityId, fallbackYaw) * Mth.DEG_TO_RAD;
		return new Vec3(Mth.cos(rad) * sign, 0.0D, Mth.sin(rad) * sign);
	}
}
