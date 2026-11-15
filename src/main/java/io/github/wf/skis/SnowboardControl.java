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
 *   <li><b>carve</b>：A / D 侧压 → 身体朝向持续转（{@link #applyCarve}）＋ 速度方向跟着转同样的角度；
 *       固定半径 {@link #TURN_RADIUS_BLOCKS} 4 格 → 满速算出 14.43°/tick、夹到
 *       {@link #TURN_MAX_DEGREES_PER_TICK} <b>12.0°/tick</b>（下限 {@link #TURN_MIN_DEGREES_PER_TICK} 2.0）；
 *       没按 A / D 但已经在滑时，光前 / 后倾也会转（后倾＝后刃，方向相反），摆动上限
 *       {@link #VEER_MAX_DEGREES} 4.8°/tick。W / S 只转向、<b>不加力</b>。</li>
 *   <li><b>速度方向自动回正</b>：只要还贴着地（离地 1.5 格内），水平速度方向每 tick 朝<b>板轴</b>
 *       （身体左右轴，也就是单板真正的行进方向）掰 ≤12.5°，见 {@link #alignCarveHeading} —— 不需要在 carve 里，
 *       也没有「入弯基准」这种状态。</li>
 * </ul>
 *
 * <p>滑行物理（下落加速、低摩擦动量、雪面推进）与双板同款，在 {@link SkiGlide} 里统一施加。
 */
public final class SnowboardControl {
	/** <b>客户端</b>那张表：实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final IdMap<Float> BODY_YAW_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义（为什么分两份见 {@link #table}）。 */
	private static final IdMap<Float> BODY_YAW_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：实体 id → 俯仰姿态（当前值，平滑后；正 = 前倾 / 负 = 后倾），供模型与相机读取。 */
	private static final IdMap<Float> TILT_VALUE_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义（{@link #tickTilt} 只在客户端跑，见 {@link #table}）。 */
	private static final IdMap<Float> TILT_VALUE_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：实体 id → 俯仰姿态的目标值（+1 前倾 / 0 直立 / -1 后倾），由按键刷新。 */
	private static final IdMap<Integer> TILT_TARGET_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Integer> TILT_TARGET_SERVER = new IdMap<>();
	/**
	 * <b>客户端</b>那张表：实体 id → <b>上一次判定的行进侧</b>（<b>板轴符号</b>，与 {@link #sideLocked} 的 sign
	 * 同一套约定：+1 = 身体左轴 / -1 = 右轴）。速度的板轴分量和 A / D 都给不出方向时锁存它。
	 *
	 * <p>头朝向（{@link #headSide}）与速度回正（{@link #alignCarveHeading}）共用这一份判定，
	 * 所以「头往哪边扭」和「速度往哪边掰」永远是同一侧，不会互相矛盾。
	 *
	 * <p>这张表是<b>幂等写值型</b>（{@code boardSide} 算出一个 ±1 再 put），不是每 tick 累加的增量，
	 * 所以拆表时<b>不需要翻倍</b>。
	 */
	private static final IdMap<Integer> BOARD_SIDE_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Integer> BOARD_SIDE_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：实体 id → 当前头偏角（平滑后的实际值，度；正 = 朝身体右侧、负 = 朝左），越肩时写 {@code yHeadRot}。 */
	private static final IdMap<Float> HEAD_OFFSET_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义（{@link #tickHead} 只在客户端跑，见 {@link #table}）。 */
	private static final IdMap<Float> HEAD_OFFSET_SERVER = new IdMap<>();
	/**
	 * <b>服务端</b>那张表：实体 id → <b>本次 carve 的入口水平速度</b>（格/tick），见 {@link #guardCarveSpeed}。
	 * 退出 carve 就清掉 —— 下一段弯重新记一次入口速度。
	 */
	private static final IdMap<Double> CARVE_SPEED_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义（{@link #guardCarveSpeed} 只在服务端跑，见 {@link #table}）。 */
	private static final IdMap<Double> CARVE_SPEED_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：实体 id → 本次 carve <b>已经持续的 tick 数</b>（入口那一 tick 记 0），配 {@link #CARVE_SPEED_SERVER} 算速度下限。 */
	private static final IdMap<Integer> CARVE_TICKS_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Integer> CARVE_TICKS_CLIENT = new IdMap<>();

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
	 * <b>carve（切雪转向）的固定转弯半径</b>（格）：角速度不再是常数，而是由<b>本 tick 真正用于位移的速度</b>
	 * 决定 —— 每 tick 身体转 {@code toDegrees(vTravel / 4.0)} 度，一直按着就是在雪地上画同一个圆。
	 *
	 * <p>满速 0.55 格/tick 时算出来 <b>14.43°/tick</b>，被 {@link #TURN_MAX_DEGREES_PER_TICK} 12.0 夹住
	 * （≈240°/s，半圈 15 tick ≈ 0.75 s）；中速 0.30 格/tick 时 <b>7.87°/tick</b>，极慢 0.05 格/tick 时触底 2.0。
	 * 慢滑自然转得慢、快滑自然转得快，不会出现「慢滑也按满速角速度剃头」那种手感。
	 *
	 * <p><b>半径 4 而不是 8 —— 这个常量按「每端各跑一次」标定</b>（见 {@link #table}）：拆表前单机里
	 * 客户端与服务端各往同一张 {@code BODY_YAW} 加一次同样的增量（等效半径 8 的 2 倍角速度），
	 * 拆表后每端只写自己那张表，半径取 4 才能在单机里逐 tick 复现同样的转角；
	 * 专用服务器也随之从「一半」变成和单机一样。
	 */
	private static final double TURN_RADIUS_BLOCKS = 4.0D;
	/**
	 * 每 tick 身体转角的<b>下限</b>（度）：低速时 {@code vTravel / 半径} 会掉到 2° 以下，而
	 * {@code client/SnowSpray} 判「在转向」的门槛是 {@code TURN_DEGREES_PER_TICK} 1.0°/tick ——
	 * 低于它弯外侧的喷雪与板端簇加成就失效了。所以角速度封底 2.0（＝拆表前单机里每 tick 实际生效的 1.0 ×2）。
	 */
	private static final double TURN_MIN_DEGREES_PER_TICK = 2.0D;
	/**
	 * 每 tick 身体转角的<b>上限</b>（度）：满速算出 14.43°/tick，这里夹到 <b>12.0</b>（拆表前单机里每 tick
	 * 实际生效的 6.0 ×2；半径 4 格下 12° 对应 0.84 格/tick，高于单板上限 {@link #MAX_SPEED} 0.55，
	 * 所以只在满速附近封顶）。
	 *
	 * <p><b>{@link #ALIGN_MAX_DEGREES_PER_TICK} 12.5 必须严格大于它</b> —— 见那个常量的注释。
	 */
	private static final double TURN_MAX_DEGREES_PER_TICK = 12.0D;
	/**
	 * <b>限幅回正</b>（{@link #alignCarveHeading}）每 tick 最多把速度方向朝<b>板轴</b>掰多少度。
	 *
	 * <p><b>必须严格大于每 tick 最大身体转角</b>（{@link #TURN_MAX_DEGREES_PER_TICK} 12.0），否则速度方向
	 * 每 tick 只能追上身体的一部分、残量越累越多，速度方向会永久落在身体后面。
	 *
	 * <p>取 <b>12.5 ＝ 12.0 + 0.5 余量</b>：拆表前这条不变量在专用服务器上<b>刚好取等</b>（6.0 vs 6.0）、
	 * 在单机里根本<b>没成立</b>（6.0 vs 12.0 —— 身体朝向被两端各写一次，掰正步长却只有一个 6.0）。
	 * 本批拆表 + 翻倍之后两边都满足，12.5°/tick 也意味着 180° 的偏角最多 15 tick（0.75 s）掰正 ——
	 * 观感仍是「顺着板的方向自己滑回来了」。
	 *
	 * <p>它作用在<b>实体自己的速度方向</b>上（每端每实体各一次、不写共享表），所以<b>不属于翻倍清单</b>。
	 */
	private static final float ALIGN_MAX_DEGREES_PER_TICK = 12.5F;
	/** 与板轴的角差小于它就当作已经对上（死区：不写、不发包，防抖也防每 tick 多发一份同步）。 */
	private static final float ALIGN_DEAD_ZONE_DEGREES = 0.05F;
	/**
	 * <b>回正只在近地时生效</b>：{@code onGround()} 为真，<b>或</b>脚底往下这么多格内有碰撞方块。
	 *
	 * <p>1.5 格是个「还贴着雪面」的宽容值 —— 滑下坡、掠过起伏时 {@code onGround} 常常一两 tick 是假，
	 * 但这时速度方向当然还该回正；真腾空（跳跃 / 坠落）就让它保持惯性，别在空中把方向掰弯。
	 * 探针复用 {@link SkiGlide#groundDistance}（向下 {@code range + 0.1} 格的碰撞射线，两端都能用）。
	 */
	private static final double ALIGN_GROUND_RANGE = 1.5D;
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
	 * <p>这里的 g 分两段：<b>首 tick 用贴地档 0.104</b>（0.08 × {@link #GRAVITY_MULTIPLIER} 1.3），
	 * <b>第二 tick 起</b>换腾空档 <b>0.072</b>（0.08 × {@link #AIR_GRAVITY_MULTIPLIER} 0.9）。
	 * <b>只是表的上限依据</b>：代码不拿它反推力度（表才是唯一事实源），改上限要整张表重算。
	 */
	private static final double JUMP_MAX_HEIGHT = 1.5D;
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
	 * 与双板各一份：调这里不动双板。表长（{@link AirTuningSpec#JUMP_CURVE_SAMPLES}）、插值口径与摔落结算
	 * 等通用语义见 {@link AirTuningSpec}。
	 */
	private static final double[] JUMP_POWER_BY_SPEED_LUT = {
			0.208D, 0.302D, 0.348D, 0.377D, 0.400D, 0.417D, 0.430D, 0.442D, 0.452D, 0.461D, 0.470D
	};
	/** <b>单板：离地逗留窗</b>（土狼时间）：离地后 <b>6 tick</b>（0.3 s）内按空格仍算「刚离地」，照样起跳（语义见 {@link AirTuningSpec}）。 */
	private static final int COYOTE_TICKS = 6;
	/** <b>单板：输入缓冲</b>（tick）：按下空格起 <b>5 tick</b>（0.25 s）内条件成立就自动发出。 */
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
	 * 一圈 360° 要 <b>22.5 tick</b> ＝ <b>1.125 s</b>）；计数与静止段的完整口径见 {@link AirTuningSpec}。
	 * <b>与双板同值（两板都是 16.0°/tick ＝ 22.5 tick ＝ 1.125 s）</b>，各一个常量、改一块不动另一块。
	 */
	private static final double FLIP_DEGREES_PER_TICK = 16.0D;
	/** <b>单板</b>：连续按住空格 <b>4</b> tick <b>之后</b>才开始累加角度（判据严格大于；短按＝纯跳跃、一点不转）。 */
	private static final int FLIP_HOLD_DELAY_TICKS = 4;
	/**
	 * <b>单板</b>：落地时与<b>直立</b>（0°/360°/720°…）的角距在 ±35° 内＝干净落地；超出＝摔倒（多翻几圈只要停在
	 * 直立附近也算净落）。
	 */
	private static final double FLIP_LANDING_TOLERANCE_DEGREES = 35.0D;
	/** <b>单板</b>：摔倒时水平速度保留的比例（0.35 ＝ 掉 65%）。 */
	private static final double CRASH_SPEED_MULTIPLIER = 0.35D;
	/** <b>单板</b>：摔倒后失控多少 tick（20 tick ＝ 1 s）：转向 / 压刃 / carve / 追视角全部跳过。 */
	private static final int CRASH_STUN_TICKS = 20;
	/** <b>单板</b>：摔倒扣血的上限（4.0 ＝ 偏差 180° 扣 2 颗心、90° 扣 1 颗心）；为什么用 {@code generic()} 而不是 {@code fall()} 见 {@link AirTuningSpec}。 */
	private static final double CRASH_DAMAGE_MAX = 4.0D;

	/**
	 * 单板这一份起跳 / 空翻数值（状态机在 {@link SkiAir}，数值留在这里，容器、表长校验与
	 * {@link SkiAir.Tuning} 的装配在 {@link AirTuningSpec}）。
	 */
	private static final SkiAir.Tuning AIR_TUNING = new AirTuningSpec(
			JUMP_POWER_BY_SPEED_LUT, JUMP_MAX_HEIGHT,
			COYOTE_TICKS, JUMP_BUFFER_TICKS, JUMP_COOLDOWN_TICKS, MAX_SPEED,
			FLIP_MIN_SPEED, FLIP_DEGREES_PER_TICK, FLIP_HOLD_DELAY_TICKS, FLIP_LANDING_TOLERANCE_DEGREES,
			CRASH_SPEED_MULTIPLIER, CRASH_STUN_TICKS, CRASH_DAMAGE_MAX).toTuning();

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
	/** 单板：动量每 tick 保留比例（与双板同值；转向另有 {@link #CARVE_MOMENTUM_DECAY} 1.0 的零损失档）。 */
	private static final double MOMENTUM_DECAY = 0.998D;   // 每 tick 掉 0.2%（1 秒 −4%），与双板同值
	/**
	 * <b>转向期间</b>（carve 生效时）的动量保留比例 —— <b>1.0 ＝ 转向零损失</b>。
	 *
	 * <p>用户口径「转向只改变方向、不影响速度大小」：本模组的转向本来就是<b>纯旋转</b>
	 * （{@link #alignCarveHeading} 只旋转水平速度方向，大小与 y 都不动），所以这一段不该再叠一层摩擦。
	 * {@code 1.0} 同时管两处：
	 * <ul>
	 *   <li>转向期间的<b>动量托底</b>（{@code SkiGlide} 用本值做动量保留）不再衰减；</li>
	 *   <li>{@link #guardCarveSpeed} 的速度下限 ＝ 入场速度<b>不衰减</b>（{@code × 1.0^n}）。</li>
	 * </ul>
	 *
	 * <p><b>不会失控增长</b>：上限仍有 {@link #MAX_SPEED} 0.55 夹住（{@code guardCarveSpeed} 的 target 取
	 * {@code min(..., MAX_SPEED)}，动量结算那侧同样夹）—— 这里只保证「不往下掉」，不往上加。
	 * 直行仍走 {@link #MOMENTUM_DECAY} 0.998（那是<b>滑行摩擦</b>，不是转向摩擦，改了平地就永远不减速）；
	 * 想让转向与直行同摩擦就填 0.998，想留一点点损耗就填 0.999。
	 */
	private static final double CARVE_MOMENTUM_DECAY = 1.0D;
	/**
	 * 单板的下落加速<b>结算步长</b>（格）：每累计下降这么多格结算一次。
	 *
	 * <p>双板同为 0.5 格一次（见 {@link SkiGlide#STEP_BLOCKS}）—— 每降半格结算一次，单次给
	 * {@link #FALL_GAIN_PER_BLOCK} × 0.5 = 0.075，下坡降没半格就有第一份加速（不用攒满一整格）。
	 * 每格总量仍是 {@link #FALL_GAIN_PER_BLOCK} 0.15/格。
	 */
	private static final double DESCENT_STEP_BLOCKS = 0.5D;
	/**
	 * 只用 W / S 把方向朝前 / 后摆时的平滑系数：每 tick 吃掉剩余角度的这个比例。
	 *
	 * <p><b>0.58 ≈ 1 − (1 − 0.35)² = 0.5775，按「每端各跑一次」标定</b>（见 {@link #table}）：
	 * 拆表前单机里客户端与服务端各吃一次 0.35（两次复合 ＝ 每 tick 吃掉 57.75%），拆表后每端只写自己那张表，
	 * 取 0.58 才能让单机每 tick 的收敛量与拆表前一致，专用服务器也随之从 35% 提到 57.75%。
	 */
	private static final double VEER_SMOOTHING = 0.58D;
	/**
	 * 这个摆动每 tick 最多转多少度（平滑趋近的速度上限）。
	 * <b>4.8</b>°/tick ＝ 96°/s（转满 180° 需 37.5 tick ≈ 1.9 s）；拆表前单机里两端各 2.4（合计 4.8），
	 * 这一项是<b>饱和步长</b>、两次复合正好等于一次 4.8，所以 ×2 是精确等价的。
	 */
	private static final double VEER_MAX_DEGREES = 4.8D;
	/** 光靠前 / 后倾（没按 A / D）也能切雪的最小速度：滑得比这快就够压刃了。 */
	private static final double CARVE_MIN_SPEED = 0.05D;
	/** 俯仰姿态每 tick 向目标靠拢的比例（0.25 ≈ 十来个 tick 到位）—— 倾斜不是瞬间跳过去的。 */
	private static final float TILT_LERP = 0.25F;
	/** 与目标的差距小于它就当作到位。 */
	private static final float TILT_EPSILON = 0.01F;

	private SnowboardControl() {
	}

	/**
	 * 按端选一份表（照 {@link SkiAir} 的 {@code CLIENT_STATES} / {@code SERVER_STATES} 的做法）。
	 *
	 * <p><b>为什么每张表都要两份</b>：单机（集成服务端与客户端在同一个 JVM）里 {@code LocalPlayer} 与
	 * {@code ServerPlayer} <b>共用同一个 entity id</b>，而「每 tick 加一个增量」的方法
	 * （{@link #applyCarve}、{@link AlpineSkiControl#turnBody} …）是<b>两端各调一次</b>。共用一张表时
	 * 同一份增量会被加两次 —— 单机的转向 / carve 速率因此是专用服务器的 2 倍。
	 * {@link SkiAir} 早就为同一个坑拆过表，这里照它的样式把剩下的表全部拆开。
	 *
	 * <p><b>常量按「每端各跑一次」标定</b>：拆开之后单机每 tick 只有一次写入，所以每个<b>累加型</b>的
	 * 每 tick 步长都在本批里 ×2（`TURN_*`、`VEER_*`、`AlpineSkiControl.TURN_RATE_DEGREES` /
	 * `VIEW_TURN_RATE_DEGREES`），这样单机手感与拆表前逐 tick 一致，专用服务器也终于和单机一样快。
	 * <b>幂等写值型</b>（{@link #BOARD_SIDE_CLIENT}）、<b>只在一端跑</b>（{@code TILT_*}、{@code HEAD_OFFSET}、
	 * {@code CARVE_*}）的表<b>不翻倍</b> —— 翻倍会让倾斜 / 头偏一次快一倍。
	 *
	 * @param clientSide {@code true} ＝ 客户端那一份、{@code false} ＝ 服务端那一份
	 */
	private static <T> IdMap<T> table(IdMap<T> clientTable, IdMap<T> serverTable, boolean clientSide) {
		return clientSide ? clientTable : serverTable;
	}

	/** 这个实体是不是穿着单板（脚部槽）。 */
	public static boolean isSnowboard(Entity entity) {
		return entity instanceof LivingEntity living
				&& living.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.SNOWBOARD;
	}

	/**
	 * 锁住身体朝向：第一次调用时记下当时的 yaw，之后不再改变。
	 *
	 * @param clientSide {@code true} ＝ 客户端（{@code SkisClient}）、{@code false} ＝ 服务端（{@link SkiGlide}）；
	 *                   两端各锁自己那张表，见 {@link #table}
	 */
	public static void ensureLocked(int entityId, float yaw, boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).putIfAbsent(entityId, yaw);
	}

	/**
	 * 脱板 / 换板：清掉这个实体的板姿态状态。
	 *
	 * @param clientSide {@code true} ＝ 客户端调用（{@code SkisClient}）、{@code false} ＝ 服务端调用
	 *                   （{@link SkiGlide}）；只看它决定清 {@link SkiAir} 的哪一张表 —— 单机里两端共用
	 *                   同一个 entity id，客户端清表不能把服务端的失控 / 冷却一起抹掉
	 */
	public static void forget(int entityId, boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).remove(entityId);
		table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide).remove(entityId);
		table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide).remove(entityId);
		table(BOARD_SIDE_CLIENT, BOARD_SIDE_SERVER, clientSide).remove(entityId);
		table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide).remove(entityId);
		table(CARVE_SPEED_CLIENT, CARVE_SPEED_SERVER, clientSide).remove(entityId);
		table(CARVE_TICKS_CLIENT, CARVE_TICKS_SERVER, clientSide).remove(entityId);
		// 空中状态（含失控计时）只清本端那张，见 SkiAir#forgetClient 的注释
		if (clientSide) {
			SkiAir.forgetClient(entityId);
		} else {
			SkiAir.forgetServer(entityId);
		}
	}

	/**
	 * 整表清空：换世界 / 重连 / 换维度时由客户端（{@code SkisClient}）调用 —— 那些场合所有 id 都作废，
	 * 留着只会在 id 被复用时把上一段的压刃 / 俯仰状态带进新维度。
	 *
	 * @param clientSide 清哪一端那套表；客户端调用传 {@code true}（服务端那张表不归客户端管）
	 */
	public static void forgetAll(boolean clientSide) {
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).clear();
		table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide).clear();
		table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide).clear();
		table(BOARD_SIDE_CLIENT, BOARD_SIDE_SERVER, clientSide).clear();
		table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide).clear();
		table(CARVE_SPEED_CLIENT, CARVE_SPEED_SERVER, clientSide).clear();
		table(CARVE_TICKS_CLIENT, CARVE_TICKS_SERVER, clientSide).clear();
	}

	/**
	 * 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。
	 *
	 * @param clientSide 读哪一端那张表（{@code SkisClient} / 渲染层传 {@code true}，{@link SkiGlide} 传 {@code false}）
	 */
	public static float bodyYaw(int entityId, float fallback, boolean clientSide) {
		return table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).getOrDefault(entityId, fallback);
	}

	/** 客户端每 tick 记录俯仰姿态<b>目标</b>：+1 前倾 / 0 直立 / -1 后倾。 */
	public static void setTiltTarget(int entityId, int target, boolean clientSide) {
		table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide).put(entityId, target);
	}

	/**
	 * 客户端每 tick 推进一步平滑：当前值按 {@link #TILT_LERP} 向目标靠拢（所以倾斜是渐进的）。
	 *
	 * <p>{@link #TILT_LERP} <b>不翻倍</b>：这个方法只被 {@code SkisClient} 调（客户端独有），
	 * 服务端从不推进倾斜，所以拆表前后都是每 tick 一次。
	 */
	public static void tickTilt(int entityId, boolean clientSide) {
		IdMap<Integer> targets = table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide);
		IdMap<Float> values = table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide);
		int target = targets.getOrDefault(entityId, 0);
		float current = values.getOrDefault(entityId, 0.0F);
		float next = current + (target - current) * TILT_LERP;
		if (Math.abs(target - next) < TILT_EPSILON) {
			next = target;
		}
		if (next == 0.0F) {
			values.remove(entityId);
		} else {
			values.put(entityId, next);
		}
	}

	/** 当前俯仰姿态（平滑后的浮点值：1 = 完全前倾，-1 = 完全后倾），模型与相机用它。 */
	public static float tiltValue(int entityId, boolean clientSide) {
		return table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide).getOrDefault(entityId, 0.0F);
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
	 * 这一 tick 沿<b>板轴（身体左右轴）</b>往哪一侧走：<b>+1 = 身体左轴</b>、<b>-1 = 身体右轴</b> ——
	 * 就是 {@link #sideLocked} 的 {@code sign}，也是单板实际的行进方向（单板横着滑，板轴就是行进方向）。
	 *
	 * <p><b>只此一处判定</b>：头朝向（{@link #headSide}）与速度回正（{@link #alignCarveHeading}）都走它，
	 * 所以「头往哪边扭」与「速度往哪边掰」永远是同一侧；另写一套就会互相矛盾（头朝右、速度却朝左拽）。
	 *
	 * <p>判定顺序：① <b>实际横移方向</b> —— 水平速度投到身体左轴 {@link #sideLocked} 上，
	 * 分量 ≥ {@link #HEAD_SIDE_MIN_SPEED} 就用它（往身体左侧滑 → +1）；
	 * ② 分量太小就看 <b>A / D</b>（{@link #sideSign}，A = 左 → +1）；
	 * ③ 两者都拿不到就<b>锁存</b>上一次的判定（停下来也不能自己换边）。
	 */
	public static int boardSide(Entity entity, Input input) {
		int entityId = entity.getId();
		boolean clientSide = entity.level().isClientSide();   // 带 Entity 的调用点直接取端
		Vec3 motion = entity.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		// 身体左轴（sideLocked 的 sign=+1），与 sideLocked / A 键同一个「左」的约定
		double lateral = horizontal.dot(sideLocked(entityId, entity.getYRot(), 1.0D, clientSide));
		double press = sideSign(input);
		IdMap<Integer> sides = table(BOARD_SIDE_CLIENT, BOARD_SIDE_SERVER, clientSide);
		int side;
		if (Math.abs(lateral) >= HEAD_SIDE_MIN_SPEED) {
			side = lateral > 0.0D ? 1 : -1;   // 往身体左侧滑 → +1（左轴）
		} else if (press != 0.0D) {
			side = press > 0.0D ? 1 : -1;
		} else {
			return sides.getOrDefault(entityId, -1);   // 锁存（默认身体右轴，与旧的「头默认朝右」同侧）
		}
		sides.put(entityId, side);
		return side;
	}

	/**
	 * 头朝哪一侧：<b>+1 = 朝身体右侧（yaw 增大，与 D 同向）</b>、<b>-1 = 朝左侧（yaw 减小）</b>。
	 *
	 * <p>就是 {@link #boardSide} 反过来（板轴在身体左侧 → 头朝左看）—— 判定不在这里另写一份，
	 * 否则头朝向与速度回正的方向会各说各话。
	 */
	public static int headSide(Entity entity, Input input) {
		return -boardSide(entity, input);
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
		boolean clientSide = entity.level().isClientSide();   // 带 Entity 的调用点直接取端
		IdMap<Float> offsets = table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide);
		float target = headSide(entity, input) * headMagnitude(input);
		float current = offsets.getOrDefault(entityId, target);
		float step = Mth.clamp((target - current) * HEAD_OFFSET_LERP,
				-HEAD_OFFSET_MAX_STEP_DEGREES, HEAD_OFFSET_MAX_STEP_DEGREES);
		float next = current + step;
		if (Math.abs(target - next) < HEAD_OFFSET_EPSILON) {
			next = target;
		}
		offsets.put(entityId, next);
	}

	/**
	 * 当前头偏角（度；正 = 朝身体右侧、负 = 朝左）—— 越肩视角下 {@code yHeadRot = 身体朝向 + 它}。
	 *
	 * @param clientSide 读哪一端那张表（越肩视角是纯客户端观感，调用点一律传 {@code true}）
	 */
	public static float headOffset(int entityId, boolean clientSide) {
		return table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide).getOrDefault(entityId, 0.0F);
	}

	/** 给定 yaw 的前方（水平单位向量）；yaw = 0 时朝 +z，与 {@link GlideSteering} 的约定一致。 */
	public static Vec3 front(float yaw) {
		float rad = yaw * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(rad), 0.0D, Mth.cos(rad));
	}

	/**
	 * 沿<b>锁定的身体朝向</b>的前方 —— 倾斜的偏移方向、静止时的滑行方向都用它。
	 *
	 * <p><b>别用视角朝向</b>（{@code player.getYRot()}）：那是鼠标看的那个方向，玩家一转视野它就变，
	 * 偏移会绕着玩家转圈（症状：转视野时相机甩到身体外面去）。身体朝上是锁死的，才是不动的那个基准。
	 */
	public static Vec3 frontLocked(int entityId, float fallbackYaw, boolean clientSide) {
		return front(bodyYaw(entityId, fallbackYaw, clientSide));
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

	/** 单板：<b>转向期间</b>的动量衰减（{@code 1.0} ＝ 转向零损失，见 {@link #CARVE_MOMENTUM_DECAY}）。 */
	public static double carveMomentumDecay() {
		return CARVE_MOMENTUM_DECAY;
	}

	/**
	 * 这一 tick 是不是在<b>转向</b>（与 {@link #applyCarve} 的前置条件同一套判定）：压着刃（W / S）
	 * 且转得动 —— 有侧压（A / D）一定在转，没侧压时要有滑速才转得动。
	 *
	 * <p>转向期间用 {@link #CARVE_MOMENTUM_DECAY}（现值 {@code 1.0} ＝ 零损失）托住动量，而且反向侧压算「换刃」不算刹车
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
	 * <p>用的是与动量同一个常数（现值 {@code 1.0}）：下限<b>恒等于入场速度、不衰减</b>，所以平地转向
	 * 完全不吃摩擦 —— 用户口径「转向只改变方向、不影响速度大小」。它的意义是把<b>被截断后的恢复</b>
	 * 锚定在入场速度上，而不是锚在被截断的那个值上（撞墙被砍半之后，下一个自由 tick 就回到入场速度）。
	 */
	public static void guardCarveSpeed(ServerPlayer player) {
		int entityId = player.getId();
		Input keys = player.getLastClientInput();
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();
		// 只在服务端跑（SkiGlide）—— 固定取服务端那张表；这两张表不属于翻倍清单（单机里也只有一次写入）
		IdMap<Double> carveSpeed = table(CARVE_SPEED_CLIENT, CARVE_SPEED_SERVER, false);
		IdMap<Integer> carveTicks = table(CARVE_TICKS_CLIENT, CARVE_TICKS_SERVER, false);

		if (!isCarving(entityId, keys, speed)) {
			carveSpeed.remove(entityId);
			carveTicks.remove(entityId);
			return;
		}
		Double entry = carveSpeed.get(entityId);
		if (entry == null) {
			// 进入 carve 这一 tick：只记入口速度（下限就等于当前速度，不必动速度）
			carveSpeed.put(entityId, speed);
			carveTicks.put(entityId, 0);
			return;
		}
		int elapsed = carveTicks.getOrDefault(entityId, 0);
		carveTicks.put(entityId, elapsed + 1);
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
	 * <b>速度方向自动回正到板轴</b>（整 tick 最后一个写速度的点）：每 tick 把水平速度方向朝
	 * <b>板轴（身体左 / 右轴，{@link #sideLocked}）</b>掰一步，每步最多 {@link #ALIGN_MAX_DEGREES_PER_TICK} 12.5°。
	 *
	 * <p><b>目标量就是板轴</b>（与身体左右轴平行的那个单位向量，哪一侧由 {@link #boardSide} 定 ——
	 * 与头朝向同一个判定）：单板是横着滑的，板轴就是实际行进方向，所以「速度方向回到板轴」＝
	 * 「顺着板的方向滑」。这里<b>没有任何入弯基准</b>（旧版那两张入弯方向角 / 入弯朝向的表已删）：
	 * 不做状态机，也不要求正在 carve —— 只要还贴着地，每一 tick 都在往回拉，偏多少收多少。
	 *
	 * <p><b>生效条件</b>（全部成立才掰，否则原样返回）：
	 * <ul>
	 *   <li>穿着单板（调用点只在单板分支，另用 {@link #isSnowboard} 兜一层）；</li>
	 *   <li>不在失控里（{@link SkiAir#isStunned}）—— 摔了就不掰，让惯性自己滑停；</li>
	 *   <li>水平速度 &gt; 1e-4（没有方向可掰）；</li>
	 *   <li><b>近地</b>：{@code onGround()} <b>或</b>脚底往下 {@link #ALIGN_GROUND_RANGE} 1.5 格内有碰撞方块
	 *       —— 滑下坡 / 掠过起伏时 {@code onGround} 会抖，1.5 格内一律照拉；真腾空就保持惯性。</li>
	 * </ul>
	 *
	 * <p><b>怎么掰</b>：用 {@link Mth#wrapDegrees} 取「板轴方向角 − 当前速度方向角」的最短角差
	 * （两边都按 {@code atan2(z, x)} 口径），差 ≤ {@link #ALIGN_DEAD_ZONE_DEGREES} 0.05° 就当作对上、
	 * 不写也不发包；否则把角差夹到 ±12.5° 后用 {@link #rotateVelocity} <b>旋转</b>水平速度
	 * （大小与 y 都不动、{@code hurtMarked = true}）—— 不是一步写成目标角，所以偏差可见、可追、不瞬移。
	 *
	 * <p><b>撞墙 tick 也照拉</b>（不再因 {@code horizontalCollision} 提前返回）：限幅 12.5°/tick 本身够温和，
	 * 撞墙那一 tick 顶多把被碰撞逐轴裁过的方向往板轴掰 12.5°，方向不会没完没了地歪着。
	 *
	 * <p><b>服务端与客户端各跑一遍</b>（{@link SkiGlide} 的单板分支 + {@code client/SkisClient} 的
	 * {@code END_CLIENT_TICK}，客户端只对<b>本机玩家</b>跑）：26.2 里玩家位置是<b>客户端权威</b>
	 * （{@code Player#isClientAuthoritative()}），真正决定位移的是客户端自己的速度；服务端的速度写入
	 * 要靠 {@code hurtMarked} → {@code ClientboundSetEntityMotionPacket} 才传得下去，而这个包只在服务端
	 * 算出误差 &gt; 死区时才发 —— 客户端撞墙把方向裁歪后服务端那边没有误差、不发包，歪方向就永远没人掰。
	 * 所以这条规则必须两端各跑一次，只靠服务端发包在无误差时不会触发。
	 *
	 * <p>为什么每 tick 都要这一道：同一 tick 里还有别的写者（回落加速沿当前方向加长、动量托底、
	 * 碰撞裁剪）会把方向拽偏，这一道是最后一个写速度的地方，收的就是这些残留。
	 *
	 * <p>步长 <b>必须严格大于每 tick 最大身体转角</b>（{@link #TURN_MAX_DEGREES_PER_TICK} 12.0）：身体转得比
	 * 速度掰得快时，速度方向会永久落在身体后面追不上。12.5 &gt; 12.0，见那个常量的注释。
	 */
	public static void alignCarveHeading(Entity entity, Input keys) {
		int entityId = entity.getId();
		boolean clientSide = entity.level().isClientSide();   // 带 Entity 的调用点直接取端
		if (!isSnowboard(entity) || SkiAir.isStunned(entityId)) {
			return;
		}
		Vec3 motion = entity.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.length() <= 1.0E-4D) {
			return;   // 没速度可掰
		}
		if (!entity.onGround() && SkiGlide.groundDistance(entity, ALIGN_GROUND_RANGE) > ALIGN_GROUND_RANGE) {
			return;   // 真腾空了：速度方向由惯性保持，不在空中把路线掰弯
		}

		Vec3 axis = sideLocked(entityId, entity.getYRot(), boardSide(entity, keys), clientSide);
		double curRad = Math.atan2(horizontal.z, horizontal.x);
		double wantRad = Math.atan2(axis.z, axis.x);   // 板轴方向角（同一套 atan2(z, x) 口径）
		float errDeg = Mth.wrapDegrees((float) (Math.toDegrees(wantRad) - Math.toDegrees(curRad)));
		if (Math.abs(errDeg) <= ALIGN_DEAD_ZONE_DEGREES) {
			return;   // 死区：差不到 0.05° 就别写、别发包（既防抖，也防每 tick 多发一份同步）
		}
		float stepDeg = Mth.clamp(errDeg, -ALIGN_MAX_DEGREES_PER_TICK, ALIGN_MAX_DEGREES_PER_TICK);
		rotateVelocity(entity, stepDeg);   // 按限幅后的角度**旋转**速度方向（大小与 y 都不动）
	}

	/**
	 * <b>carve 一步（只转向、不加力）</b>：把锁定的身体朝向朝那一侧转一个 tick 的量，并让
	 * <b>速度方向跟着转同样的角度</b>（不是拨到侧向、也不补任何推力）。
	 * 服务端与客户端各跑一次，两边朝向与速度方向保持一致。
	 *
	 * <p>腾空也照跑（从高处落下时要能带着速度拐弯）。
	 *
	 * <p>每 tick 转多少由<b>固定转弯半径</b> {@link #TURN_RADIUS_BLOCKS} 4 格给定
	 * （{@link #turnDegreesPerTick}，满速算出 14.43°/tick、被上限夹到 12.0，下限 2.0）。
	 *
	 * <p><b>累加型，常量已按「每端各跑一次」×2</b>：拆表前单机里两端各往同一张 {@code BODY_YAW} 加一次
	 * 同一份增量（等效 2 倍角速度），拆表后每端只写自己那张表，所以 {@code TURN_*} / {@code VEER_*}
	 * 都翻倍，单机每 tick 的转角与拆表前逐 tick 相同（见 {@link #table}）。
	 *
	 * <p>这里<b>不登记任何「入弯基准」</b>（旧版那两张入弯方向角 / 入弯朝向的表已删）：
	 * 速度方向最终对不对，由 {@link #alignCarveHeading} 在 tick 末尾每 tick 朝<b>板轴</b>限幅拉回来，
	 * 与「从哪个方向进的弯」无关 —— 所以撞墙、蹭墙、从任意角度起弯都不用特殊处理。
	 *
	 * <p><b>撞墙那一 tick 也照转速度</b>（不再判 {@code horizontalCollision}）：身体转多少速度就转多少，
	 * 这一 tick 顶多多歪一点，下一个 tick 由 {@link #alignCarveHeading} 按 12.5°/tick 收回去。
	 *
	 * @return 本 tick 转过的角度（0 表示没在 carve）
	 */
	public static float applyCarve(Entity entity, Input input, double speed) {
		int entityId = entity.getId();
		boolean clientSide = entity.level().isClientSide();   // 带 Entity 的调用点直接取端
		if (!isCarving(entityId, input, speed)) {
			// 只在“压刃”时切雪：光按 A / D 是纯横移，不转向。
			return 0.0F;
		}
		double side = sideSign(input);   // A / D 的侧压：+1 左 / -1 右 / 0 没按
		double turn;
		if (side != 0.0D) {
			// 有侧压：每 tick 转角由「固定转弯半径」定（{@link #turnDegreesPerTick}），
			// 后倾（S）＝后刃（反向）。
			turn = (input.backward() ? -side : side) * turnDegreesPerTick(entity, speed);
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
			Vec3 front = frontLocked(entityId, entity.getYRot(), clientSide);
			Vec3 target = input.forward() ? front : front.scale(-1.0D);
			// 在 F(θ) = (-sinθ, cosθ) 的参数化下，方向角 = atan2(-x, z)；yaw 增大 ＝ 向右转
			double current = Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z));
			double goal = Math.toDegrees(Math.atan2(-target.x, target.z));
			double diff = Mth.wrapDegrees((float) (goal - current));
			// 平滑趋近：步长与剩余角度成正比（快到位时自动变慢），而不是“固定步长、到了就死停”——
			// 后者在目标附近会变成一顿一顿的：固定步长 + 到目标死停，残差小时每 tick 都在越过与停下之间跳。
			// 结果直接就是**角度**（不再按 TURN_RATE 折算成倍数：那个常量已经删了）。
			turn = Mth.clamp(diff * VEER_SMOOTHING, -VEER_MAX_DEGREES, VEER_MAX_DEGREES);
		}
		float delta = (float) turn;
		table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide)
				.computeIfPresent(entityId, (id, yaw) -> yaw + delta);
		rotateVelocity(entity, delta);   // 撞墙这一 tick 也照转：偏角由 alignCarveHeading 每 tick 收回
		return delta;
	}

	/**
	 * <b>每 tick 身体转角</b>（度）＝ {@code toDegrees(vTravel / TURN_RADIUS_BLOCKS)}，夹在
	 * [{@link #TURN_MIN_DEGREES_PER_TICK}, {@link #TURN_MAX_DEGREES_PER_TICK}]。
	 *
	 * <p><b>传进来的 {@code speed} 不是位移速度</b>：{@code SkiGlide} / {@code SkisClient} 给的是
	 * 「本 tick move 之后、又被摩擦乘过」的水平速度 —— {@code LivingEntity#travelInAir} 末尾会
	 * {@code setDeltaMovement(x * f, y * g, z * f)}，贴地 {@code f = 方块摩擦 × 0.91}、空中 {@code f = 0.91}。
	 * 所以先除回去还原真正用于位移的 {@code vTravel}：不还原的话雪面（摩擦 0.6 → 0.546）上算出来的
	 * 角速度只有该有的 55%，转弯半径会凭空大近一倍。
	 *
	 * <p>还原用的摩擦：{@code entity.level().getBlockState(entity.getBlockPosBelowThatAffectsMyMovement())}
	 * 的 {@code getBlock().getFriction()}（雪 0.6、冰 0.98、石头 0.6、灵魂沙 0.6）。算出来的 factor
	 * 不在 (0, 1] 就认为拿不到方块摩擦，退回用原始 speed（宁可角速度偏小，也不要除出个爆表值）。
	 *
	 * <p><b>摩擦换算保持不变，倍率由 {@link #TURN_RADIUS_BLOCKS} 承担</b>：半径取 4 格 ＝ 拆表前 8 格 ×2，
	 * 因为两者都只乘在同一个除法上，等效于把每 tick 的转角整体 ×2（clamp 的两端也同步 ×2，
	 * 所以 {@code 2 × clamp(x, 1, 6) ≡ clamp(2x, 2, 12)} 逐点相等）。
	 * <p>实际值（雪面，摩擦 0.6 → factor 0.546）：满速 0.55 → 算 14.43 → 夹到 <b>12.0</b>；
	 * 0.30 → <b>7.87</b>；0.14 → <b>3.67</b>；0.05 → 夹到下限 <b>2.0</b>。
	 */
	private static double turnDegreesPerTick(Entity entity, double speed) {
		double factor = 0.91D;
		if (entity.onGround()) {
			var below = entity.level().getBlockState(entity.getBlockPosBelowThatAffectsMyMovement());
			factor = below.getBlock().getFriction() * 0.91D;
		}
		double vTravel = factor > 0.0D && factor <= 1.0D ? speed / factor : speed;
		return Mth.clamp(Math.toDegrees(vTravel / TURN_RADIUS_BLOCKS),
				TURN_MIN_DEGREES_PER_TICK, TURN_MAX_DEGREES_PER_TICK);
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
	public static Vec3 sideLocked(int entityId, float fallbackYaw, double sign, boolean clientSide) {
		float rad = bodyYaw(entityId, fallbackYaw, clientSide) * Mth.DEG_TO_RAD;
		return new Vec3(Mth.cos(rad) * sign, 0.0D, Mth.sin(rad) * sign);
	}
}
