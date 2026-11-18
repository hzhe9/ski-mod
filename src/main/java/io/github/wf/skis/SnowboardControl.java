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
 *       固定半径 {@link #TURN_RADIUS_BLOCKS} 3 格 → 满速算出 19.23°/tick、夹到
 *       {@link #TURN_MAX_DEGREES_PER_TICK} <b>16.0°/tick</b>（下限 {@link #TURN_MIN_DEGREES_PER_TICK} 3.0）；
 *       没按 A / D 但已经在滑时，光前 / 后倾也会转（后倾＝后刃，方向相反），摆动上限
 *       {@link #VEER_MAX_DEGREES} 6.4°/tick。W / S 只转向、<b>不加力</b>。</li>
 *   <li><b>速度方向自动回正</b>：只要处于<b>可改速度方向</b>的状态（贴地，或脚底 1.0 格内有方块且
 *       {@code vy ≤ 0} —— 见 {@link #isSpeedAlignable}），水平速度方向每 tick 朝<b>板轴</b>
 *       （身体左右轴，也就是单板真正的行进方向）按 {@code max(9.0°/tick, |本 tick 身体转角|)} 掰，
 *       硬上限 45°，见 {@link #alignCarveHeading} —— 不需要在 carve 里，也没有「入弯基准」这种状态。
 *       起跳上升段 / 真腾空 / 长距离坠落速度方向一点不动（身体 / 模型照转），落地后才开始追 ——
 *       与双板同一条口径、同一个基准速度。</li>
 * </ul>
 *
 * <p><b>板轴是「有符号轴」，所以空中转超过 90° 在视觉上表现为「转到极限」（C）</b>：
 * {@code boardSide} 只能在「身体左轴 / 右轴」里二选一（±1，见 {@link #boardSide}），
 * {@link #alignCarveHeading} 的目标量因此只有 2 个朝向、{@code wrapDegrees} 之后误差恒在 (−90°, 90°]，
 * <b>180° 与 0° 在这个表示法里不可区分</b>。空中转板时 {@code BODY_YAW} 照常累加（没有 clamp）、
 * 第三人称 F5 下模型会一直转，但超过 90° 之后「往哪一侧落」这件事已经由符号决定了 —— 也就是说
 * <b>≥90° 的空中旋转在视觉上表现为转到极限，因为板轴是有符号轴</b>，这与限位无关（视角本来就<b>不</b>限位，
 * 头模型的 ±{@link #HEAD_MODEL_LIMIT_DEGREES} 135° 夹的只是模型）。
 * 不要为了让「>90° 有意义」去掉符号 —— 那会同时废掉头朝向（{@link #headSide}）与回正方向。
 *
 * <p><b>视角与头模型是两件事（本轮 III）</b>：单板<b>没有视角限位</b>（{@code client/SkiViewSmoothing} 对单板
 * 不夹 —— 鼠标能转满一圈，背后也能看），但<b>头模型（渲染出来的那颗头）相对身体朝向硬夹在
 * ±{@link #HEAD_MODEL_LIMIT_DEGREES} 135°</b>（{@link #headModelOffset}）：视角继续转、头的模型不跟
 * （停在 135 不动，<b>不翻到另一侧</b>）—— 因为夹的是<b>连续的</b>视角偏角，不是 {@code wrapDegrees} 之后
 * 那个 (−180°, 180°] 的值，所以转过 135° 之后既不会掉头也不会回抽。双板的 ±90° 视角限位不受影响。
 *
 * <p>滑行物理（下落加速、低摩擦动量、雪面推进）与双板同款，在 {@link SkiGlide} 里统一施加。
 */
public final class SnowboardControl {
	/** <b>客户端</b>那张表：实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final IdMap<Float> BODY_YAW_CLIENT = new IdMap<>();
	/**
	 * <b>服务端</b>那张表：同一份语义，<b>但值是客户端交上来的</b>。
	 *
	 * <p>板轴只有客户端一份权威（{@link SnowboardControl#acceptClientAxis}）：本机每 tick 把锁定的轴
	 * 通过 {@link SnowboardAxis.Payload} 发上来，这里被<b>覆盖式</b>写入。它仍是 {@link #table} 里那张
	 * 「服务端表」（{@code sideLocked} / {@code boardSide} / 远端玩家的板子都读它），只是不再自己积分出
	 * 一份独立的数 —— 两端因此永远是同一个轴。包还没到的那些 tick（延迟、丢包、客户端没装模组）仍由
	 * {@link #applyCarve} 本地推进兜底，一旦收到包整体覆盖，所以误差不会累积。
	 */
	private static final IdMap<Float> BODY_YAW_SERVER = new IdMap<>();
	/**
	 * <b>只在服务端写</b>：实体 id → 上一次从客户端收到板轴的游戏时间（tick）。
	 *
	 * <p>用途只有一处：收到包的那一 tick <b>服务端不再自己往 {@link #BODY_YAW_SERVER} 上加一步</b>
	 * （见 {@link #applyCarve}）。否则「客户端值 + 服务端自己那一步」会比客户端领先一个 tick，下一份包
	 * 再把它拉回去 —— 轴会在两个值之间锯齿轮动，而{@link #alignCarveHeading} 每 tick 都按这个轴掰速度，
	 * 锯齿会直接变成可见的抖。
	 */
	private static final IdMap<Long> CLIENT_AXIS_TICK = new IdMap<>();
	/** <b>客户端</b>那张表：实体 id → 俯仰姿态（当前值，平滑后；正 = 前倾 / 负 = 后倾），供模型与相机读取。 */
	private static final IdMap<Float> TILT_VALUE_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义（{@link #tickTilt} 只在客户端跑，见 {@link #table}）。 */
	private static final IdMap<Float> TILT_VALUE_SERVER = new IdMap<>();
	/**
	 * <b>客户端</b>那张表：实体 id → 俯仰姿态的<b>上一 tick 值</b>（帧间插值的起点）。
	 *
	 * <p>{@link #tickTilt} 每 tick 在推进之前把当时的 {@link #TILT_VALUE_CLIENT} 抄过来，所以
	 * 「上一 tick 值 → 本 tick 值」就是这一段要插的那一小步。渲染层（{@code CameraTiltMixin}）用
	 * {@code Mth.lerp(partialTick, prev, cur)} 把它摊到每一帧 —— 不插值的话相机位置只能按 tick 跳，
	 * 满速 carve 时每 tick 跳 17.4 cm（见 {@link #tiltValue(int, float, boolean)}）。
	 */
	private static final IdMap<Float> TILT_PREV_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Float> TILT_PREV_SERVER = new IdMap<>();
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
	/**
	 * <b>只在服务端写</b>（照 {@link #CLIENT_AXIS_TICK} 的做法）：实体 id → <b>上一 tick 结束时的水平速度模长</b>
	 * （格/tick）。{@link #guardCarveSpeed} 每次跑完都记一份。
	 *
	 * <p>用途只有一处：<b>撞墙那一 tick 的补速上限</b>。{@code Entity#move} 的逐轴裁剪发生在实体 tick 里，
	 * 等这里看到速度时被裁掉的那部分已经没了 —— 想知道「撞之前有多快」，只能靠上一 tick 记下的这个值。
	 */
	private static final IdMap<Double> CARVE_PRE_HIT_SPEED = new IdMap<>();
	/**
	 * <b>服务端</b>那张表：实体 id → <b>本 tick 身体转过的角度</b>（度，带符号；{@link #applyCarve} 每 tick 写一次，
	 * 没在 carve 写 0）。
	 *
	 * <p>只给 {@link #alignCarveHeading} 算落地回正步长用：步长取
	 * {@code max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK, |这个值|)} —— 速度每 tick 至少要追上身体转过的量，
	 * 否则会重现「速度永久落在身体后面」的老 bug。两端各一张（见 {@link #table}）。
	 */
	private static final IdMap<Float> TURN_STEP_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：同一份语义（{@link #applyCarve} 在客户端也跑，见 {@link #table}）。 */
	private static final IdMap<Float> TURN_STEP_CLIENT = new IdMap<>();
	/**
	 * <b>客户端</b>那张表：实体 id → <b>只用 W / S 时锁存下来的每 tick 摆角</b>（度，带符号）。
	 *
	 * <p>见 {@link #veerStep}：<b>只按 W / S（没按 A / D）时不再每 tick 拿「速度方向到身体前/后」的实时
	 * 误差去算</b>，而是<b>在按键组合变化的那一 tick 算一次、之后照这个值每 tick 转</b>。原因见那个方法的
	 * javadoc：实时误差在<b>空中</b>会随身体转动归零（速度冻住、身体在转），转到 90° 就自己停住。
	 *
	 * <p><b>幂等写值型</b>（按键一变就覆盖一次），不是每 tick 累加，所以不在翻倍清单里。
	 */
	private static final IdMap<Float> VEER_STEP_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Float> VEER_STEP_SERVER = new IdMap<>();
	/** <b>客户端</b>那张表：实体 id → 锁存 {@link #VEER_STEP_CLIENT} 时的<b>按键签名</b>（见 {@link #keySignature}）。 */
	private static final IdMap<Integer> VEER_KEYS_CLIENT = new IdMap<>();
	/** <b>服务端</b>那张表：同一份语义，见 {@link #table}。 */
	private static final IdMap<Integer> VEER_KEYS_SERVER = new IdMap<>();

	/** 前倾 / 后倾时<b>整个人连同板</b>绕脚底倾斜的角度（度）。 */
	private static final float TILT_DEGREES = 15.0F;
	/**
	 * <b>渲染头（头模型）相对身体朝向的硬上限</b>（度，左右各一份）：头模型最多扭到 <b>135°</b>，
	 * 视角再过也停在 135（<b>不掉头、不回抽、不再跟</b>）。
	 *
	 * <p><b>单板的视角本身没有限位</b>（{@code client/SkiViewSmoothing} 对单板不夹）—— 用户口径：
	 * 「只把头<b>模型</b>固定在左右 135 度，视角还能继续转，头不跟着转」。
	 * 与双板 {@link AlpineSkiControl#VIEW_LIMIT_DEGREES} 90°（那一条限的是<b>视角</b>，不是头模型）各自独立。
	 *
	 * <p><b>夹之前必须先折</b>（见 {@link #headModelOffset}）：入参是任意大的角度（转几圈就是几百度），
	 * 先 {@code Mth.wrapDegrees} 折到 (−180°, 180°] 再 {@code Mth.clamp}。夹在<b>未折叠</b>的连续累加量上
	 * 是本轮回归的根因 —— 累加量过 135 之后被钉死在 135，而它本身不会再回到真实夹角，
	 * 于是转完一圈要反向转回去整整一圈才「解冻」（用户看到的是「头朝背后、怎么转都不动」）。
	 */
	public static final float HEAD_MODEL_LIMIT_DEGREES = 135.0F;
	/**
	 * 越肩视角模式下，单板头相对<b>身体朝向</b>的偏角幅度：<b>默认（没按 W / S，或 W 与 S 同按）</b>
	 * 为 {@code 75} 度。符号不在这里 —— 朝哪一侧滑就朝哪一侧（见 {@link #tickHead}）：
	 * yaw 增大 = 向右，所以朝右滑用 {@code +75}、朝左滑用 {@code -75}。
	 */
	public static final float SNOWBOARD_HEAD_NEUTRAL_DEGREES = 75.0F;
	/** 按住 <b>W（前倾）</b>时的头偏角幅度：{@code 135} 度 —— 人俯下去，头再往外扭一点，扭到头模型限位为止。 */
	public static final float SNOWBOARD_HEAD_FORWARD_DEGREES = 135.0F;
	/** 按住 <b>S（后倾）</b>时的头偏角幅度：{@code 60} 度 —— 人后仰，头收回一点。 */
	public static final float SNOWBOARD_HEAD_BACKWARD_DEGREES = 60.0F;
	/** 当前头偏角每 tick 吃掉「与目标差距」的多少（比例项：快到位时自动变慢，不会在目标附近抖）。 */
	private static final float HEAD_OFFSET_LERP = 0.45F;
	/**
	 * 每 tick 最多挪多少度（限幅项）。比例项单独用会在 210° 的左右翻转时起步冲到 60°/tick，
	 * 所以翻转靠这一项匀速扫过去：{@code 9} → 210° 约 23 tick（≈1.17 s），经过 0°（正前方），
	 * 看得出「头从右往左扭过去」；幅度变化（60° / 60°）够小，只吃比例项，约 9–12 tick。
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
	 * <b>换边</b>所需的侧向分量（格/tick）：<b>已经判定某一侧</b>之后，反向分量要涨到这么高才允许翻符号，
	 * 否则一直停在锁存的那一侧（滞后）。
	 *
	 * <p><b>为什么需要它（本轮 II 的第二个病灶）</b>：{@code boardSide} 靠「速度 × 身体左轴」定符号，
	 * 而<b>空中速度是冻住的</b>；身体转到 <b>90°</b>（板轴与速度垂直）时那个侧向分量正好<b>过零</b>——
	 * 于是每一 tick 都在零附近翻符号：{@code boardSide} 翻 → {@code headSide} 翻 → {@link #tickHead} 的
	 * 目标瞬间反 180°（视觉上就是「卡住 + 头乱摆」），{@link #alignCarveHeading} 的目标轴也跟着翻。
	 * 0.20 ≈ 满速 0.55 的 36%：翻转要求「确实已经滑到另一侧去了」，在 0 附近抖不动。
	 * 侧向分量 <b>只用来初始化</b>（首次判定仍用 {@link #HEAD_SIDE_MIN_SPEED} 0.05）。
	 */
	private static final double HEAD_SIDE_SWITCH_MIN_SPEED = 0.20D;

	/**
	 * <b>carve（切雪转向）的固定转弯半径</b>（格）：角速度不再是常数，而是由<b>本 tick 真正用于位移的速度</b>
	 * 决定 —— 每 tick 身体转 {@code toDegrees(vTravel / 3.0)} 度，一直按着就是在雪地上画同一个圆。
	 *
	 * <p>满速 0.55 格/tick 时算出来 <b>19.23°/tick</b>，被 {@link #TURN_MAX_DEGREES_PER_TICK} 16.0 夹住
	 * （≈320°/s，半圈 11.25 tick ≈ 0.56 s）；中速 0.30 格/tick 时 <b>10.49°/tick</b>，极慢 0.05 格/tick 时触底 3.0。
	 * 慢滑自然转得慢、快滑自然转得快，不会出现「慢滑也按满速角速度剃头」那种手感。
	 *
	 * <p><b>半径 3 而不是 2 —— 从 2 倍档回调到约 1.5 倍档</b>（倍率的来历见 {@link #table}）：
	 * ①「状态表按端拆分」把同一份增量从加两次变成加一次 → 8.0 减半到 4.0；
	 * ②{@code af42725} 又把板轴改成<b>客户端权威并同步</b>，两端从此共用同一根轴、每 tick 只前进一次，
	 * 于是再减半到 <b>2.0</b>，把「同步之前的手感」按 1:1 复原。
	 * 但 2.0 这档实测<b>转得太快</b>（满速 24°/tick ≈ 480°/s，半圈 0.375 s），所以这次在 2.0 与 4.0 之间
	 * 取 <b>3.0</b>：角速度 ＝ 上一版 ×2/3、＝「同步前那批值」的 <b>4/3 ≈ 1.33 倍</b>
	 * （下限那端是精确 1.5 倍：3.0 / 2.0；上限 16.0 / 12.0 ＝ 1.33）。
	 * 想再要精确的 1.5 倍档就把半径取 4.0 / 1.5 ≈ <b>2.67</b> 格，同时下限/上限取 3.0 / 18.0。
	 */
	private static final double TURN_RADIUS_BLOCKS = 3.0D;
	/**
	 * 每 tick 身体转角的<b>下限</b>（度）：低速时 {@code vTravel / 半径} 会掉到 3° 以下，而
	 * {@code client/SnowSpray} 判「在转向」的门槛是 {@code TURN_DEGREES_PER_TICK} 1.0°/tick ——
	 * 低于它弯外侧的喷雪与板端簇加成就失效了。所以角速度封底 <b>3.0</b>（相对 1.0°/tick 的门槛仍留 2.0° 余量）。
	 *
	 * <p><b>这一项曾经是「初始速度低时转向非常慢」的病灶</b>：同步前两端各转 2.0°/tick，玩家每 tick 拿到
	 * <b>4.0°</b>；{@code af42725} 之后只剩一次积分，同时也把下限写成 4.0 复原。这次随半径一起回调到
	 * <b>3.0</b>（＝「同步前那批值」2.0 的 <b>1.5 倍</b>，与「1.5 倍档」的口号逐字对齐）——
	 * 低速段 90° 由 22.5 tick 变成 30 tick（1.5 s），仍然远快于「按 1.0°/tick 的封底」那种爬行。
	 */
	private static final double TURN_MIN_DEGREES_PER_TICK = 3.0D;
	/**
	 * 每 tick 身体转角的<b>上限</b>（度）：满速算出 19.23°/tick，这里夹到 <b>16.0</b>（≈320°/s，半圈
	 * 11.25 tick ≈ 0.56 s；半径 3 格下 16° 对应 0.84 格/tick，高于单板上限 {@link #MAX_SPEED} 0.55，
	 * 所以只在满速附近封顶）。
	 *
	 * <p>它是「2 倍档 24.0」与「同步前 12.0」之间的回调值（<b>16.0 ＝ 12.0 × 4/3</b>，与半径同比例）。
	 * <p><b>它不是回正步长的来源</b>：{@link #alignCarveHeading} 的步长是
	 * {@code max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK 9.0, |本 tick 身体转角|)}（见那个方法），
	 * 所以「速度追不上身体」那条老问题由构造保证 —— 身体转角上限就是本常量，步长自动跟到 16.0，
	 * 仍远小于 {@link #ALIGN_MAX_STEP_DEGREES} 45.0。
	 */
	private static final double TURN_MAX_DEGREES_PER_TICK = 16.0D;
	/**
	 * <b>落地回正的单步硬上限</b>（度）：{@link #alignCarveHeading} 每 tick 最多把速度方向朝<b>板轴</b>掰多少度。
	 *
	 * <p><b>实际步长不是这个值</b>，而是 {@code max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK 9.0, |本 tick 身体转角|)}
	 * —— 与双板同一个基准速度（9°/tick），再让「速度不能落后于身体」这条硬性条件兜底（见
	 * {@link #alignCarveHeading} 的 javadoc，那里写着为什么不能写死 9.0）。本常量只兜一个
	 * <b>单次不越过半圈</b>的上限：45° &lt; 90°，所以一次旋转永远走短弧、不会绕远路。
	 *
	 * <p>正常滑行碰不到它 —— 身体转角上限 {@link #TURN_MAX_DEGREES_PER_TICK} 24.0 &lt; 45（旧版那条
	 * 「回正步长必须严格大于 12.0」的不变量（12.5）在新方案下<b>由构造保证</b>：步长取自本 tick 的实际转角，
	 * 恒 ≥ 身体转角，不再需要一条「余量」）。
	 *
	 * <p>它作用在<b>实体自己的速度方向</b>上（每端每实体各一次、不写共享表），所以<b>不属于翻倍清单</b>。
	 */
	private static final float ALIGN_MAX_STEP_DEGREES = 45.0F;
	/** 与板轴的角差小于它就当作已经对上（死区：不写、不发包，防抖也防每 tick 多发一份同步）。 */
	private static final float ALIGN_DEAD_ZONE_DEGREES = 0.05F;
	/**
	 * <b>可改速度方向的「近地」容差</b>（格）：{@code onGround()} 为假时，脚底往下这么多格内有碰撞方块
	 * <b>且</b> {@code vy ≤ 0}，仍算「贴着坡面在滑」，照旧回正（判据见 {@link #isSpeedAlignable}）。
	 *
	 * <p><b>为什么要它</b>：滑下坡时 {@code onGround()} 会每几 tick 抖成 false（实体 tick 里先位移再落地，
	 * 斜面上下坠那一 tick 就没踩着方块）。只认 {@code onGround} 的话回正跟着断续 —— 坡上方向一顿一顿，
	 * 像每隔几 tick 松一次手。这层宽容兜的就是抖动那几 tick，<b>不是</b>用来在空中转速度的。
	 *
	 * <p><b>为什么是 1.0 而不是 1.5</b>：满速起跳（力度 0.470）的离地高度逐 tick 是
	 * <b>0.47 / 0.83 / 1.11 / 1.31 / 1.45 / 1.50</b>（顶点）—— 1.5 格把整段上升都盖住，1.0 格只盖住前
	 * 两 tick，窗口小一半多。真正把上升段挡在外面的其实是下面的 {@code vy ≤ 0}（前两 tick 靠它，第三 tick
	 * 起靠距离），1.0 只是让这段窗口尽可能短；反过来也不能更小 —— 坡面抖动的实测距离常在 0.5–0.7 格，
	 * 收到 0.5 就会漏掉一部分抖动 tick，回正又开始断续。
	 *
	 * <p><b>为什么还要 {@code vy ≤ 0}</b>：这是把「近地」和「空中」分开的那一刀。只看距离时，起跳第一 tick
	 * 与抛物线顶点附近都在 1.0 格内 —— 但那时 {@code vy > 0}，人在<b>上升</b>，速度方向必须由惯性保持，
	 * 否则每 tick 收一点，空中轨迹就成了弧线、落点被拽偏。{@code vy ≤ 0}（下落 / 平飞）才是「贴着坡面
	 * 往下跑」的形状，也正是 {@code onGround} 抖动时唯一可能出现的形状（坡面向下时不产生向上的速度）。
	 */
	private static final double NEAR_GROUND_RANGE = 1.0D;
	// 注意：W / S <b>只转向、不加力</b> —— W / S 改的是速度的方向，不往速度向量上加量。
	// 所以这里没有 carve 推力常量 —— 滑行的速度快慢只由下落加速与地面推进决定，前后倾只改方向。
	/**
	 * <b>撞墙那一 tick 的补速倍率上限</b>（倍）：{@link #guardCarveSpeed} 在 {@code horizontalCollision}
	 * 为真的那一 tick，最多把<b>裁剪后剩下的</b>水平速度放大这么多。
	 *
	 * <p>含义 ＝「不把被墙裁掉的那块加回来」：{@code move()} 逐轴裁剪后只剩 {@code |v|·sin α × 摩擦}
	 * （α ＝ 速度与墙面法线的夹角），要补回满速需要的倍率是 {@code 1 / (0.546 · sin α)} ——
	 * <b>30° 需 3.67、60° 需 2.12、85° 需 1.15，而 5° 需 21</b>。取 4.0 恰好覆盖到 α ≥ 27.3°：
	 * 弯里蹭墙（30°–60°，上一轮修过的「不阶跌」）照样补满，近正面撞（α ≈ 5°）则按裁剪真实掉速
	 * （0.048 → 0.19，改前是 11.5 倍弹回 0.55）。<b>别往下调到 3.6 以下</b>：30° 那一档立刻变回
	 * 上一轮那个 0.30 ↔ 0.55 的阶跌。
	 */
	private static final double CARVE_HIT_BOOST_MAX = 4.0D;
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
	 * <b>单板</b>：落地时与<b>直立</b>（0°/360°/720°…）的角距在 ±45° 内＝干净落地；超出＝摔倒（多翻几圈只要停在
	 * 直立附近也算净落）。<b>±45° 与双板同值</b>（两块板各一个常量，改一块不动另一块）。
	 */
	private static final double FLIP_LANDING_TOLERANCE_DEGREES = 45.0D;
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
	 * <p><b>0.5467 ＝ 0.82 × 2/3，与 carve 那次回调同比（见 {@link #VEER_MAX_DEGREES}）</b>：
	 * 它同样是「每端各跑一次」时代的复合值 —— ①两端各吃 0.35 → 复合 1 − (1 − 0.35)² = 0.5775 ≈ 0.58；
	 * ②{@code af42725} 板轴改成客户端权威并同步后每 tick 只收敛一次，再复合一次
	 * 1 − (1 − 0.58)² = 0.8236 → 0.82。这次 carve 的每 tick 转角回调到 2/3（{@link #TURN_RADIUS_BLOCKS}），
	 * 摆动这一路也同比 ×2/3 → <b>0.5467</b>。
	 *
	 * <p><b>为什么比例项也要跟着缩</b>：每 tick 的摆角 ＝ {@code clamp(剩余角 × 本值, ±VEER_MAX)}，
	 * 两个量都直接乘在每 tick 转角上。<b>只缩饱和步长</b>会让曲线在「大残角」段取新上限、在「小残角」
	 * 段还是老速度 → 尾巴追得比头快，收敛形状变了（末段猛、前段钝）；两个同比缩 ⇒
	 * 新的每 tick 摆角逐点等于旧值的 2/3（{@code k·clamp(x,lo,hi) ≡ clamp(kx,klo,khi)}），形状完全不变。
	 *
	 * <p><b>本轮起它只在「按键组合变化」的那一 tick 参与一次</b>（{@link #veerStep} 把它算出的结果<b>锁存</b>）：
	 * 每 tick 都不再重算，所以它现在决定的是「按下 W / S 那一刻的起步摆角」，起手形状与以前逐位相同
	 * （满速时剩余角恒为 90°，{@code 90 × 0.5467 = 49.2} → 夹到 {@link #VEER_MAX_DEGREES} 6.4）。
	 */
	private static final double VEER_SMOOTHING = 0.5467D;
	/**
	 * 这个摆动每 tick 最多转多少度（平滑趋近的速度上限）。
	 * <b>6.4</b>°/tick ＝ 128°/s（转满 180° 需 28.1 tick ≈ 1.4 s）；同步前两端各 4.8（合计 9.6），
	 * 这一项是<b>饱和步长</b>、两次复合正好等于一次 9.6，所以当年 ×2 是精确等价的。
	 *
	 * <p>这次与 carve 同比回调：<b>9.6 × 2/3 ＝ 6.4</b>，保持 {@code VEER_MAX / TURN_MAX = 6.4 / 16.0 = 0.4}
	 * 这个构造比例与 2 倍档时代（9.6 / 24.0）完全一致 —— 「只压刃不按 A/D 的摆动」始终是
	 * 「侧压 carve」的 40%，两条路的相对手感不变。
	 */
	private static final double VEER_MAX_DEGREES = 6.4D;
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
	 * <b>{@code af42725}（板轴客户端权威并同步）之后单板的这几个量又翻了一次倍</b>：两端从此共用同一根轴、
	 * 每 tick 只积分一次，而同步前服务端那份旋转会经 motion 包回到客户端，玩家感受到的是两次之和。
	 * 所以单板的 {@code TURN_*} / {@code VEER_*} 在原值上再 ×2（半径 4.0 → 2.0、下限 2.0 → 4.0、
	 * 上限 12.0 → 24.0、{@code VEER_MAX} 4.8 → 9.6、{@code VEER_SMOOTHING} 0.58 → 0.82），
	 * <b>只动单板</b> —— 双板的 {@code TURN_RATE_DEGREES} 4.0 / {@code VIEW_TURN_RATE_DEGREES} 4.8
	 * 与 {@code ALIGN_DEGREES_PER_TICK} 9.0 保持拆表那批的值不变。
	 * <b>第三次是回调（不是翻倍）</b>：2 倍档的 24°/tick 实测转得过快，{@code TURN_*} / {@code VEER_*}
	 * 一起缩到 2 倍档的 2/3（半径 2.0 → 3.0、下限 4.0 → 3.0、上限 24.0 → 16.0、{@code VEER_MAX} 9.6 → 6.4、
	 * {@code VEER_SMOOTHING} 0.82 → 0.5467），落点见 {@link #TURN_RADIUS_BLOCKS}。
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
		table(TILT_PREV_CLIENT, TILT_PREV_SERVER, clientSide).remove(entityId);
		table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide).remove(entityId);
		table(BOARD_SIDE_CLIENT, BOARD_SIDE_SERVER, clientSide).remove(entityId);
		table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide).remove(entityId);
		table(CARVE_SPEED_CLIENT, CARVE_SPEED_SERVER, clientSide).remove(entityId);
		table(CARVE_TICKS_CLIENT, CARVE_TICKS_SERVER, clientSide).remove(entityId);
		table(TURN_STEP_CLIENT, TURN_STEP_SERVER, clientSide).remove(entityId);
		table(VEER_STEP_CLIENT, VEER_STEP_SERVER, clientSide).remove(entityId);
		table(VEER_KEYS_CLIENT, VEER_KEYS_SERVER, clientSide).remove(entityId);
		if (!clientSide) {
			// 「上次收到客户端板轴」的时间戳跟着服务端那张表一起清：单机里两端共用 id，
			// 客户端清表不能顺手把服务端的时间戳也抹掉（那只影响服务端要不要再加一步自己那步）
			CLIENT_AXIS_TICK.remove(entityId);
			// 「撞墙前的速度」只在服务端维护（见 CARVE_PRE_HIT_SPEED）
			CARVE_PRE_HIT_SPEED.remove(entityId);
		}
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
		table(TILT_PREV_CLIENT, TILT_PREV_SERVER, clientSide).clear();
		table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide).clear();
		table(BOARD_SIDE_CLIENT, BOARD_SIDE_SERVER, clientSide).clear();
		table(HEAD_OFFSET_CLIENT, HEAD_OFFSET_SERVER, clientSide).clear();
		table(CARVE_SPEED_CLIENT, CARVE_SPEED_SERVER, clientSide).clear();
		table(CARVE_TICKS_CLIENT, CARVE_TICKS_SERVER, clientSide).clear();
		table(TURN_STEP_CLIENT, TURN_STEP_SERVER, clientSide).clear();
		table(VEER_STEP_CLIENT, VEER_STEP_SERVER, clientSide).clear();
		table(VEER_KEYS_CLIENT, VEER_KEYS_SERVER, clientSide).clear();
		if (!clientSide) {
			CARVE_PRE_HIT_SPEED.clear();
		}
	}

	/**
	 * 锁定的身体朝向；还没锁（刚穿上的那一 tick）就用当前朝向兜底。
	 *
	 * @param clientSide 读哪一端那张表（{@code SkisClient} / 渲染层传 {@code true}，{@link SkiGlide} 传 {@code false}）
	 */
	public static float bodyYaw(int entityId, float fallback, boolean clientSide) {
		return table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide).getOrDefault(entityId, fallback);
	}

	/**
	 * <b>服务端</b>收到客户端交上来的板轴（{@link SnowboardAxis.Payload} 的接收器调这里）：
	 * <b>覆盖式</b>写入 {@link #BODY_YAW_SERVER}，并记下这一 tick「已经有一份权威值」。
	 *
	 * <p>为什么必须覆盖而不是平均 / 混合：两端各自积分出的轴会以 2–4°/tick 分叉（转角取决于本端速度、
	 * {@code onGround()} 与脚下方块摩擦），差值是<b>隐藏状态</b>，没有任何一项会把它拉回 0；这正是
	 * 「板轴与位移方向永远差一个固定角」的根因。覆盖之后两端读同一个数，差值恒为 0。
	 *
	 * <p>取模到 (−180°, 180°]：客户端那份是累加值（可以长到几千度），两端只按 {@code sin / cos} 用它，
	 * 取模不影响方向，但能把服务端这张表的数值限制在有界范围内（网络来的数不无限增长）。
	 *
	 * @param gameTime 收到的这一 tick（{@code level.getGameTime()}），配 {@link #CLIENT_AXIS_TICK} 用
	 */
	public static void acceptClientAxis(int entityId, float bodyYaw, long gameTime) {
		BODY_YAW_SERVER.put(entityId, Mth.wrapDegrees(bodyYaw));
		CLIENT_AXIS_TICK.put(entityId, gameTime);
	}

	/** 这个实体这一 tick 是不是刚收到过客户端交上来的板轴（是就不再自己累加，见 {@link #applyCarve}）。 */
	private static boolean clientAxisThisTick(int entityId, long gameTime) {
		Long last = CLIENT_AXIS_TICK.get(entityId);
		return last != null && last == gameTime;
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
	 *
	 * <p><b>同时把「推进之前的值」抄进 {@link #TILT_PREV_CLIENT}</b>：渲染层靠这一对
	 * （prev → cur）把倾斜摊到每一帧（{@link #tiltValue(int, float, boolean)}）。写在这里而不是
	 * 渲染层，是为了让 prev 的语义永远是「上一 tick 的值」，与 {@code yRotO → yRot} 同一口径 ——
	 * 渲染层每帧都会跑、tick 却是 20 Hz，渲染层自己存 prev 就变成「上一帧」，插值会抖。
	 */
	public static void tickTilt(int entityId, boolean clientSide) {
		IdMap<Integer> targets = table(TILT_TARGET_CLIENT, TILT_TARGET_SERVER, clientSide);
		IdMap<Float> values = table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide);
		IdMap<Float> prevs = table(TILT_PREV_CLIENT, TILT_PREV_SERVER, clientSide);
		int target = targets.getOrDefault(entityId, 0);
		float current = values.getOrDefault(entityId, 0.0F);
		prevs.put(entityId, current);   // 本段帧间插值的起点（= 上一 tick 的值）
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

	/** 当前俯仰姿态（平滑后的浮点值：1 = 完全前倾，-1 = 完全后倾），模型用它（按 tick 取）。 */
	public static float tiltValue(int entityId, boolean clientSide) {
		return table(TILT_VALUE_CLIENT, TILT_VALUE_SERVER, clientSide).getOrDefault(entityId, 0.0F);
	}

	/**
	 * 俯仰姿态的<b>上一 tick 值</b>（{@link #tickTilt} 每 tick 在推进之前写下）。没有记录时退回当前值
	 * （＝这一段不插值，刚穿板那一 tick 不会从 0 扫过来）。
	 */
	public static float tiltValuePrev(int entityId, boolean clientSide) {
		float current = tiltValue(entityId, clientSide);
		return table(TILT_PREV_CLIENT, TILT_PREV_SERVER, clientSide).getOrDefault(entityId, current);
	}

	/**
	 * <b>帧间插值后的</b>俯仰姿态：{@code Mth.lerp(partialTick, prev, cur)}。
	 *
	 * <p>相机位置（{@code CameraTiltMixin}）必须用这一档：眼睛的偏移是
	 * {@code 眼高 × sin(倾斜角 × 15°)}（满倾 0.4193 格），倾斜角每 tick 最多变
	 * {@link #TILT_LERP} 25% 的残差 —— 不插值的话起步那十来个 tick 相机每 tick 跳最多 0.103 格
	 * （10.3 cm @20 Hz），再叠上身体在转带来的 17.4 cm/tick。
	 *
	 * @param partialTick 本帧的 tick 内插值 0..1（相机那边就是 {@code alignWithEntity} 收到的那个）
	 */
	public static float tiltValue(int entityId, float partialTick, boolean clientSide) {
		float current = tiltValue(entityId, clientSide);
		if (partialTick >= 1.0F) {
			return current;
		}
		return Mth.lerp(partialTick, tiltValuePrev(entityId, clientSide), current);
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
	 *
	 * <p><b>已判定之后换边要更强的分量（滞后，本轮 II）</b>：反向分量得涨到
	 * {@link #HEAD_SIDE_SWITCH_MIN_SPEED} 0.20 才允许翻符号。原来只要 0.05 就翻，而空中速度是冻住的 ——
	 * 身体转到 90°（板轴与速度垂直）时那个侧向分量<b>正好过零</b>，于是 {@code boardSide} 每 tick 在
	 * 零附近翻符号，{@link #headSide} 跟着翻，{@link #tickHead} 的目标每 tick 反 180°（「卡在 90° 还抖」）。
	 * 现在侧向分量只用于<b>初始化</b>，过零不再改判。
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
		Integer held = sides.get(entityId);
		int side;
		if (Math.abs(lateral) >= HEAD_SIDE_MIN_SPEED) {
			int observed = lateral > 0.0D ? 1 : -1;   // 往身体左侧滑 → +1（左轴）
			// 滞后的那一刀：已经判定过、方向又反了时，反向分量必须超过更高的门槛才换边。
			// 跨过 90°（板轴与速度垂直）时分量恰好过零 —— 那里不换边，头朝向因此不跳。
			boolean switching = held == null || observed == held
					|| Math.abs(lateral) >= HEAD_SIDE_SWITCH_MIN_SPEED;
			side = switching ? observed : held;
		} else if (press != 0.0D) {
			side = press > 0.0D ? 1 : -1;
		} else {
			return held != null ? held : -1;   // 锁存（默认身体右轴，与旧的「头默认朝右」同侧）
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
	 * <p>比例项让快到位时自动变慢（不会在目标附近一顿一顿），限幅项把 <b>210°</b> 的左右翻转压成匀速扫过，
	 * 中途经过 <b>0°（正前方）</b> —— 所以看到的是「头从右边慢慢扭到左边」，不是瞬间翻面。
	 * 幅度变化（75↔135、75↔60）幅度小，只吃比例项，也是渐进的。
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

	/**
	 * <b>头模型（渲染出来的头）相对身体朝向的 yaw</b>（度）：把视角相对身体朝向的偏角
	 * <b>先折到 (−180°, 180°]、再夹到 ±{@link #HEAD_MODEL_LIMIT_DEGREES} 135°</b>。
	 *
	 * <p>调用点：{@code client/SkisClient} 每 tick 对本机玩家写 {@code yHeadRot = 身体朝向 + 本方法(...)}，
	 * 入参是<b>当场算出来的</b> {@code wrapDegrees(当前视 yaw − 身体朝向)}，且<b>只在非越肩视角 / 非乘客 /
	 * 非睡眠</b>时写（越肩下头朝横移方向，另有一套）。渲染侧拿到的就是
	 * {@code wrapDegrees(yHeadRot − yBodyRot)}（{@code LivingEntityRenderer#extractRenderState} 的
	 * {@code state.yRot}），落进 {@code HumanoidModel#setupAnim} 的 {@code head.yRot}。
	 *
	 * <p><b>无状态是硬要求</b>：这个角度<b>不能</b>拿跨 tick 的累加量来夹。上一版把
	 * {@code SkiViewSmoothing} 那个「不绕圈」的连续偏角直接夹在 ±135 上，结果是：转过一圈后累加量
	 * 已经是 495、585…，夹出来恒为 135，而累加量自己不会回到真实夹角 —— 头模型看上去「朝背后 + 跟身体
	 * 冻在一起」，玩家必须反向转回整整 {@code 累加量 − 135} 度（几圈）才解冻。改成每 tick 从
	 * 「当前视 yaw − 身体朝向」重算、先折后夹，就没有任何跨 tick 状态可以漂。
	 *
	 * <p><b>先折后夹的代价（可接受）</b>：视角正对背后那一瞬（相对角 ±180°）头模型从 +135 翻到 −135 ——
	 * 那是「头最多只能扭 135°」这条规则的必然结果，也只发生在<b>背后</b>这一点；其余角度下模型都
	 * 严格等于折叠后的相对角，转到哪跟到哪。正好 180° 归到 +180（夹成 +135），免得 {@code wrapDegrees}
	 * 的 {@code [-180, 180)} 把它判成 −180 → −135。
	 *
	 * @param viewOffsetDegrees 视角相对身体朝向的偏角（度；任意大小都行，转几圈就是几百度，内部先折）
	 * @return 头模型相对身体朝向的 yaw（度，恒在 ±135）
	 */
	public static float headModelOffset(float viewOffsetDegrees) {
		if (!Float.isFinite(viewOffsetDegrees)) {
			return viewOffsetDegrees;   // 非有限值（NaN / ∞）：调用方自己跳过，别把它写进朝向
		}
		float relative = Mth.wrapDegrees(viewOffsetDegrees);   // (-180, 180]
		if (relative == -180.0F) {
			relative = 180.0F;          // 正好半圈：算正面朝后（夹成 +135），不判成 -180
		}
		return Mth.clamp(relative, -HEAD_MODEL_LIMIT_DEGREES, HEAD_MODEL_LIMIT_DEGREES);
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
	 *       当前水平速度 &lt; target 时就把水平速度<b>缩放</b>到 target（方向沿用当前方向、y 不动）并
	 *       {@code hurtMarked = true}；<b>撞墙那一 tick 也补</b>（见下面「撞墙那一 tick 为什么也补」）；</li>
	 *   <li><b>退出</b>（{@link #isCarving} 变假，比如松开 W / S）：两个表一起清掉，下一段弯重新记入口速度。</li>
	 * </ul>
	 *
	 * <p><b>只做下限、不封顶</b>：下坡 / 下落加速自己涨上去的速度不会被它拉回去 —— 撞墙那一 tick 动量被砍半之后，
	 * 下一个自由 tick 就能回到这条曲线上（旧行为是从被砍半的动量继续衰减，整段弯都回不来）。
	 * <p>用的是与动量同一个常数（现值 {@code 1.0}）：下限<b>恒等于入场速度、不衰减</b>，所以平地转向
	 * 完全不吃摩擦 —— 用户口径「转向只改变方向、不影响速度大小」。它的意义是把<b>被截断后的恢复</b>
	 * 锚定在入场速度上，而不是锚在被截断的那个值上（撞墙被砍半之后，下一个自由 tick 就回到入场速度）。
	 *
	 * <p><b>撞墙那一 tick 为什么也补（A-2）</b>：以前这里 {@code horizontalCollision} 为真就直接
	 * {@code return}，于是弯里「每 N tick 蹭一次方块」的那几 tick 上<b>两道补速同时跳闸</b> ——
	 * 这道下限不补，{@code alignCarveHeading} 也跳过（它顶墙时本来就不该掰方向），那一 tick 的速度就停在
	 * 摩擦后的 {@code 0.55 × 0.546 ≈ 0.30}（−45%），下一 tick 才被补回入场速度：弯中因此出现
	 * 0.30 ↔ 0.55 的<b>阶跌</b>（波形见 {@code docs/tuning.md}）。
	 *
	 * <p>现在撞墙 tick 也补，<b>但不会顶着墙推进</b>：补速是<b>沿当前方向等比缩放</b>，而
	 * {@code Entity#move} 在这一 tick 已经把朝墙的那一维裁成了 0（逐轴裁剪），所以缩放出来的分量全在
	 * 「碰撞后仍允许的方向」上，朝墙分量恒为 0 —— 等价于「撞墙把速度<b>改了向</b>，而不是<b>丢了速</b>」。
	 * 上限仍是 {@link #MAX_SPEED}（target 取 {@code min(..., MAX_SPEED)}），不会冲上去。
	 * 回正（{@link #alignCarveHeading}）仍然跳过撞墙 tick —— 那一步是<b>改方向</b>，被同 tick 的裁剪吃掉才叫白转。
	 *
	 * <p><b>撞墙 tick 的补速封顶（本轮 I）</b>：等比缩放的倍率 {@code keep = target / speed} 以前<b>没有上限</b>，
	 * 而近正面撞墙时 {@code move()} 把朝墙分量裁到近零（α ≈ 5° 时只剩 {@code |v|·sin α ≈ 0.048}），
	 * 于是 {@code keep ≈ 0.55 / 0.048 ≈ 11.5} —— 一 tick 内把人「弹」回满速沿墙滑出去。现在撞墙那一 tick
	 * 的 target 再夹两道：
	 * <ol>
	 *   <li>{@code ≤ CARVE_PRE_HIT_SPEED} —— <b>撞墙前那一 tick 的实测速度模长</b>（“补速不得超过撞前速度”）；</li>
	 *   <li>{@code ≤ speed × }{@link #CARVE_HIT_BOOST_MAX} 4.0 —— 单次最多把<b>剩下的</b>速度放大 4 倍。</li>
	 * </ol>
	 * 第二条是「不把朝墙分量加回来」的量化口径：只剩 {@code |v|·sin α × 摩擦} 时，要补回满速需要的倍率是
	 * {@code 1 / (0.546 · sin α)} —— 30° 需 3.67、60° 需 2.12、85° 需 1.15，而 5° 需 21。
	 * 取 <b>4.0</b> 恰好覆盖到 <b>α ≥ 27.3°</b>（30° 那一档留 2.7° 余量，所以上一轮修掉的「弯中 0.30 ↔ 0.55
	 * 阶跌」不会回退 —— 弯里蹭墙的入射角就在 30°–60°），更陡的正面撞则<b>按墙的裁剪掉速</b>（5° 时只补到
	 * {@code 4 × 0.048 = 0.19}，撞完确实慢下来）。
	 *
	 * <p>非撞墙 tick <b>没有任何新上限</b>：这时没有东西被裁掉，{@code target} 就是「入场速度」这条下限，
	 * 恢复撞墙丢掉的速度靠的正是它。
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
			CARVE_PRE_HIT_SPEED.remove(entityId);
			return;
		}
		Double entry = carveSpeed.get(entityId);
		if (entry == null) {
			// 进入 carve 这一 tick：只记入口速度（下限就等于当前速度，不必动速度）
			carveSpeed.put(entityId, speed);
			carveTicks.put(entityId, 0);
			CARVE_PRE_HIT_SPEED.put(entityId, speed);
			return;
		}
		int elapsed = carveTicks.getOrDefault(entityId, 0);
		carveTicks.put(entityId, elapsed + 1);
		// 撞墙 tick 不再早退（见 javadoc「撞墙那一 tick 为什么也补」）：move() 已经把朝墙的分量裁成 0，
		// 沿剩下的方向缩放不会把人往墙里推，反而正是「撞墙改向、不丢速」那一半。
		double target = Math.min(entry * Math.pow(CARVE_MOMENTUM_DECAY, elapsed), MAX_SPEED);
		if (player.horizontalCollision) {
			// 撞墙这一 tick 才封顶：① 不超过撞前速度（上一 tick 的实测值）；② 单次最多放大 4 倍
			//（= 不把被墙裁掉的那块加回来，见 javadoc）。两道都只作用在「被裁过」的这一 tick 上。
			double preHit = CARVE_PRE_HIT_SPEED.getOrDefault(entityId, speed);
			target = Math.min(target, Math.min(preHit, speed * CARVE_HIT_BOOST_MAX));
		}
		if (speed < target && speed > 1.0E-6D) {
			double keep = target / speed;
			player.setDeltaMovement(motion.x * keep, motion.y, motion.z * keep);
			player.hurtMarked = true;
		}
		// 记「本 tick 结束时的速度」＝ 下一 tick 的撞前速度。必须放在补速之后：撞墙 tick 补回来的那一段
		// 下一 tick 还要算在「撞前」里，否则封顶会一格一格往下压（0.19 → 0.19² 那种）。
		CARVE_PRE_HIT_SPEED.put(entityId, player.getDeltaMovement().horizontalDistance());
	}

	/**
	 * <b>这一 tick 能不能改速度方向</b>（单板专用）：单板的 {@link #alignCarveHeading}（回正）与
	 * {@link #applyCarve}（carve 时让速度跟着身体转）<b>共用这一处判定</b>：两处各写一份一定会漂移。
	 *
	 * <p>判据（按顺序）：
	 * <ol>
	 *   <li>{@code entity.onGround()} → <b>能</b>（正常贴地滑行）；</li>
	 *   <li>否则：{@link SkiGlide#groundDistance}(entity, {@link #NEAR_GROUND_RANGE} 1.0) ≤ 1.0
	 *       <b>且</b> {@code entity.getDeltaMovement().y ≤ 0}（下落或平飞）→ <b>能</b>；</li>
	 *   <li>其余 → <b>不能</b>。</li>
	 * </ol>
	 *
	 * <p><b>第 2 条兜的是「坡面 onGround 抖动」</b>：滑下坡时 {@code onGround()} 会每几 tick 抖成 false
	 * （实体 tick 里先位移再落地，斜面上下坠那一 tick 就没踩着方块）。没有这层宽容，回正会跟着断续 ——
	 * 坡上方向一顿一顿。它不是「在空中转速度」。
	 *
	 * <p><b>它不会把真腾空放进来</b>：
	 * <ul>
	 *   <li><b>起跳上升段 / 抛物线上升段</b>：{@code vy > 0} → 第 2 条直接不成立（哪怕离地只有半格）。
	 *       这两段改速度方向会让空中轨迹变成弧线、落点被拽偏；</li>
	 *   <li><b>跳下悬崖 / 长距离坠落</b>：下方 1.0 格内没有碰撞方块（{@link SkiGlide#groundDistance} 返回
	 *       {@code Double.MAX_VALUE}）→ 不成立；</li>
	 *   <li><b>抛物线落地前一 tick</b>：距离已在 1.0 格内且 {@code vy ≤ 0} → 成立 —— 这是<b>故意的</b>：
	 *       落地前一 tick 已经在往雪面下落，此时回正与「落地那一 tick 就回正」在视觉上没有区别，
	 *       却避免了落地瞬间方向猛地一顿。</li>
	 * </ul>
	 * 所以「空中不改速度方向」这条规则完好 —— 只是不再把坡面抖动那几 tick 误判成空中。
	 */
	public static boolean isSpeedAlignable(Entity entity) {
		if (entity.onGround()) {
			return true;
		}
		return entity.getDeltaMovement().y <= 0.0D
				&& SkiGlide.groundDistance(entity, NEAR_GROUND_RANGE) <= NEAR_GROUND_RANGE;
	}

	/**
	 * <b>速度方向自动回正到板轴</b>（整 tick 最后一个写速度的点）：每 tick 把水平速度方向朝
	 * <b>板轴（身体左 / 右轴，{@link #sideLocked}）</b>掰一步，步长与上限见下面「每 tick 的步长」。
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
	 *   <li><b>可改速度方向</b>：{@link #isSpeedAlignable} —— {@code onGround()} 为真，<b>或</b>脚底往下
	 *       {@link #NEAR_GROUND_RANGE} 1.0 格内有碰撞方块且 {@code vy ≤ 0}。前一种＝正常贴地；后一种兜的是
	 *       滑下坡时 {@code onGround} 每几 tick 抖成 false（不兜就会断续、坡上方向一顿一顿）。起跳上升段 /
	 *       真腾空 / 长距离坠落三种都不满足（详见那个方法），速度方向由惯性保持 —— 身体 / 模型照转
	 *       （{@link #applyCarve} 只转 {@code BODY_YAW}），滑行路线一点都不弯；</li>
	 *   <li><b>没在顶墙</b>：{@code horizontalCollision} 为假（理由见下）。</li>
	 * </ul>
	 *
	 * <p><b>怎么掰</b>：用 {@link Mth#wrapDegrees} 取「板轴方向角 − 当前速度方向角」的最短角差
	 * （两边都按 {@code atan2(z, x)} 口径），差 ≤ {@link #ALIGN_DEAD_ZONE_DEGREES} 0.05° 就当作对上、
	 * 不写也不发包；否则把角差夹到 ±{@code 步长} 后用 {@link #rotateVelocity} <b>旋转</b>水平速度
	 * （大小与 y 都不动、{@code hurtMarked = true}）—— 不是一步写成目标角，所以偏差可见、可追、不瞬移。
	 *
	 * <p><b>撞墙那一 tick 不掰</b>（{@code horizontalCollision} 为真就原样返回）：{@code Entity#move} 对碰撞
	 * 方向做<b>逐轴裁剪</b> —— 这一 tick 刚掰过去的那点转角会在同一 tick 的 {@code move()} 里被整个削掉，
	 * 位移一点没变，却把「板轴 vs 实际位移」的夹角白白拉开一次（法线垂直于板轴时最坏 90°：沿轴分量被清掉、
	 * 只剩垂直分量）。更糟的是它还会打断 {@code SkiGlide} 的 {@code HIT_TICKS} 连续撞墙计数（板轴被掰歪后
	 * 下一 tick 的速度方向不再撞同一条法线），{@code MOMENTUM_HIT_TICKS_BEFORE_DECAY} 3 tick 的门槛永远攒不满，
	 * 顶墙连该有的减速都收不到。所以顶墙期间让板轴与速度都保持原样，离开墙那一 tick 再按正常步长收。
	 * 现在<b>两道都跳</b>：{@link #applyCarve} 的「身体与速度同量旋转」在顶墙 tick 也一并跳过（A-1），
	 * 只剩身体 / 模型转 —— 这样「撞墙那一 tick」三项（转速度、补速、回正）不再一半生效一半不生效。
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
	 * <p><b>每 tick 的步长 ＝ {@code max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK 9.0, |本 tick 身体转角|)}，
	 * 再夹到 {@link #ALIGN_MAX_STEP_DEGREES} 45°</b> —— <b>与双板同一个基准速度</b>：双板落地后是把速度方向
	 * 朝身体前方按 {@code AlpineSkiControl.ALIGN_DEGREES_PER_TICK} 9.0°/tick 掰（{@code alignVelocity}），
	 * 单板这里用同一个 9.0 当基准，只是目标量换成<b>板轴</b>（左 / 右，由 {@link #boardSide} 定），
	 * 不是身体前后。
	 *
	 * <p><b>为什么不能写死 9.0（为什么是 max 而不是常量）</b>：单板的身体每 tick 最多转
	 * {@link #TURN_MAX_DEGREES_PER_TICK} 16.0°（carve 按固定转弯半径算，满速就顶到 16.0），而 carve 贴地时
	 * <b>同一份速度</b>已经被按身体转角转过一次（{@link #applyCarve}）；回正再把它朝板轴追一步。若步长恒为
	 * 9.0 &lt; 16.0，每 tick 都留下 16.0 − 9.0 的残量、越累越多，速度方向会<b>永久</b>落在身体后面
	 * （旧版是靠「回正上限 12.5 必须严格大于 12.0」这种两个常量的大小关系来堵）。取
	 * {@code max(9.0, |本 tick 身体转角|)} 之后，速度每 tick 至少追上身体转过的量，残量不累积 ——
	 * 不变量由构造保证。直行 / 没在 carve 时本 tick 转角是 0，步长就退回 9.0，与双板落地对齐完全一样。
	 *
	 * <p><b>为什么上限是 45°</b>：残差最多 180°，单步必须 &lt; 90° 才能保证走短弧；45° 同时远大于实际可能的
	 * 步长（身体转角上限 24.0），所以正常滑行永远碰不到它，纯粹是兜底。
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
		if (!isSpeedAlignable(entity)) {
			return;   // 真腾空（起跳上升 / 跳崖 / 坠落）：速度方向由惯性保持，滑行路线一点都不弯
		}
		if (entity.horizontalCollision) {
			// 顶墙这一 tick 不掰：本 tick 掰出去的角度会被同 tick 的 move() 逐轴裁剪吃掉，
			// 位移不变、只把「板轴 vs 位移」的夹角白白拉开一次（最坏 90°），还会打断连续撞墙计数。
			return;
		}

		Vec3 axis = sideLocked(entityId, entity.getYRot(), boardSide(entity, keys), clientSide);
		double curRad = Math.atan2(horizontal.z, horizontal.x);
		double wantRad = Math.atan2(axis.z, axis.x);   // 板轴方向角（同一套 atan2(z, x) 口径）
		float errDeg = Mth.wrapDegrees((float) (Math.toDegrees(wantRad) - Math.toDegrees(curRad)));
		if (Math.abs(errDeg) <= ALIGN_DEAD_ZONE_DEGREES) {
			return;   // 死区：差不到 0.05° 就别写、别发包（既防抖，也防每 tick 多发一份同步）
		}
		// 步长：与双板同一个基准 9.0°/tick，再让「速度不能落后于身体」兜底（max，不是写死 9.0，见本方法 javadoc），
		// 最后夹到单次不越过半圈的硬上限（45°）。本 tick 身体转角由 applyCarve 写进 TURN_STEP_* 表。
		float bodyTurn = Math.abs(table(TURN_STEP_CLIENT, TURN_STEP_SERVER, clientSide).getOrDefault(entityId, 0.0F));
		float maxStep = Math.min(ALIGN_MAX_STEP_DEGREES,
				(float) Math.max(AlpineSkiControl.ALIGN_DEGREES_PER_TICK, bodyTurn));
		float stepDeg = Mth.clamp(errDeg, -maxStep, maxStep);
		rotateVelocity(entity, stepDeg);   // 按限幅后的角度**旋转**速度方向（大小与 y 都不动）
	}

	/**
	 * <b>carve 一步（只转向、不加力）</b>：把锁定的身体朝向朝那一侧转一个 tick 的量，<b>贴地时</b>再让
	 * <b>速度方向跟着转同样的角度</b>（不是拨到侧向、也不补任何推力）；腾空只转身体 / 模型。
	 * 服务端与客户端各跑一次，两边朝向与速度方向保持一致。
	 *
	 * <p><b>真腾空时身体 / 模型照转、速度方向一点不动</b>：从高处落下时照样能转板（视角 / 身体都跟手），
	 * 但这一 tick 的 {@code delta} <b>只写进 {@code BODY_YAW}，不旋转速度</b> —— 空中改速度方向会把人
	 * 「拽」着走（落点跟着偏），这条口径与双板完全一致（{@code AlpineSkiControl} 空中只转身体 / 视角）。
	 * 落地后由 {@link #alignCarveHeading} 朝<b>板轴</b>按 {@code max(9.0, |本 tick 转角|)} 追赶，
	 * 「落地方向跟上」的手感与双板同一个基准速度。
	 *
	 * <p><b>「能不能转速度」与回正共用同一个判据 {@link #isSpeedAlignable}</b>（不在这里另写一份
	 * {@code onGround()}）：贴地，或脚底 {@link #NEAR_GROUND_RANGE} 1.0 格内有方块且 {@code vy ≤ 0}。
	 * 只认 {@code onGround()} 的话，滑下坡时它每几 tick 抖成 false，carve 的身体转角就会隔几 tick
	 * 没同步到速度上 —— 速度方向被回正拉着走一小步、又被身体甩开，坡上转起来一顿一顿。
	 *
	 * <p>每 tick 转多少由<b>固定转弯半径</b> {@link #TURN_RADIUS_BLOCKS} 3 格给定
	 * （{@link #turnDegreesPerTick}，满速算出 19.23°/tick、被上限夹到 16.0，下限 3.0）。
	 *
	 * <p><b>累加型，常量被翻过两次倍</b>：①拆表前单机里两端各往同一张 {@code BODY_YAW} 加一次
	 * 同一份增量（等效 2 倍角速度），拆表后每端只写自己那张表，所以当时 {@code TURN_*} / {@code VEER_*}
	 * 翻了一倍；②{@code af42725} 之后板轴只剩客户端一份、并同步给服务端，两端共用一根轴、每 tick 只积分
	 * 一次，同步前那份「服务端自己再转一次、经 motion 包回到客户端」的额外旋转没有了，于是又 ×2
	 * （半径 4.0 → 2.0 等）。两次翻倍都只有一个目的：让玩家每 tick 看到的总转角与上一次「两处同时生效」
	 * 时一致（见 {@link #table}）。
	 *
	 * <p><b>板轴谁说了算</b>：客户端。<b>客户端这一端</b>照旧往 {@link #BODY_YAW_CLIENT} 累加（它是权威）；
	 * <b>服务端这一端</b>在「收到客户端板轴的那一 tick」<b>不再累加自己的</b> —— 那份值已经是最终值
	 * （{@link #acceptClientAxis}），再加一步会比客户端领先一个 tick、下一份包又把它拉回来，轴会在两个值之间
	 * 锯齿，而 {@link #alignCarveHeading} 每 tick 都按这个轴掰速度，锯齿会变成可见的抖。其它 tick
	 * （包还没到 / 延迟 / 丢包 / 客户端没装模组）仍按本地推进兜底，收到下一份包整体覆盖，误差不累积。
	 *
	 * <p><b>速度旋转照旧两端各转一次</b>：{@code delta} 只影响本端那份速度，两端各自算出的 {@code delta}
	 * 稍有差别也没关系 —— {@link #alignCarveHeading} 每 tick 会把速度方向朝（同一根）板轴收，差量被吃掉。
	 *
	 * <p>这里<b>不登记任何「入弯基准」</b>（旧版那两张入弯方向角 / 入弯朝向的表已删）：
	 * 速度方向最终对不对，由 {@link #alignCarveHeading} 在 tick 末尾每 tick 朝<b>板轴</b>限幅拉回来，
	 * 与「从哪个方向进的弯」无关 —— 所以撞墙、蹭墙、从任意角度起弯都不用特殊处理。
	 *
	 * <p><b>撞墙那一 tick 不转速度方向（A-1）</b>，判据与回正是同一道（{@code horizontalCollision}）：
	 * 身体照转（{@code BODY_YAW} 是模型 / 板轴，不受位移裁剪影响），但速度方向这一步跳过。
	 * 理由与 {@link #alignCarveHeading} 顶墙跳过完全一样 —— 这一 tick 转出去的分量会在<b>同一个</b>
	 * {@code move()} 里被逐轴裁掉（α = 速度与墙面法线的夹角，存活模长 ≈ {@code |v|·sin α}：擦边 α≈80°
	 * 只掉 1.5%，正面 α≈20° 掉 94%），位移一点没变、速度却少了一块；而这一 tick 的补速以前也被
	 * {@link #guardCarveSpeed} 一起跳闸 → 弯里出现一次 −45% 的阶跌。现在两道一起对齐：
	 * <b>撞墙 tick 只有身体转</b>，速度方向等离开墙那一 tick 由回正按步长追上（≤ {@link #ALIGN_MAX_STEP_DEGREES}）。
	 *
	 * @return 本 tick 转过的角度（0 表示没在 carve）
	 */
	public static float applyCarve(Entity entity, Input input, double speed) {
		int entityId = entity.getId();
		boolean clientSide = entity.level().isClientSide();   // 带 Entity 的调用点直接取端
		IdMap<Float> turnStep = table(TURN_STEP_CLIENT, TURN_STEP_SERVER, clientSide);
		// 本 tick 的身体转角记账：默认 0（没在 carve / 提前返回都在这里归零），
		// alignCarveHeading 用它算落地回正的步长下限（见 TURN_STEP_* 表）。
		turnStep.put(entityId, 0.0F);
		if (!isCarving(entityId, input, speed)) {
			// 只在“压刃”时切雪：光按 A / D 是纯横移，不转向。
			// 逻辑上这一段没有 carve，所以 W / S 的摆角锁存也要作废（下一次按下重新按当时的几何算）。
			forgetVeerLatch(entityId, clientSide);
			return 0.0F;
		}
		double side = sideSign(input);   // A / D 的侧压：+1 左 / -1 右 / 0 没按
		double turn;
		if (side != 0.0D) {
			// 有侧压：每 tick 转角由「固定转弯半径」定（{@link #turnDegreesPerTick}），
			// 后倾（S）＝后刃（反向）。有侧压时没有 W / S 摆角锁存的事，一并清掉 ——
			// 松开 A / D 回到「只按 W / S」时要按那时的几何重新锁存，不能沿用上一次的。
			forgetVeerLatch(entityId, clientSide);
			turn = (input.backward() ? -side : side) * turnDegreesPerTick(entity, speed);
		} else {
			// 只用前 / 后倾：把滑行方向朝 **身体前方**（W）/ **身体后方**（S）摆。
			// 这样“往左滑”和“往右滑”按同一个键效果才一致 —— 之前用固定左 / 右转的符号，
			// 结果是往左滑按 W 朝前、往右滑按 W 朝后 —— 同一个键在两侧滑行时给出相反效果。
			//
			// <b>本轮 II：每 tick 的摆角不再实时算，而是「按键组合一变就锁存一份」</b>（见 veerStep）。
			// 实时算的写法在**空中**会自己停住：速度方向冻住、身体在转，于是「速度方向 → 身体前/后」
			// 的剩余角随身体转动单调变小，转到 90°（板轴与速度垂直）时剩 0 —— 而且 wrapDegrees 在
			// ±180 那里的跳变会让它先撞上 180 → 每 tick 反向一次 → 就停在 90° 且抖。
			if (speed < CARVE_MIN_SPEED) {
				return 0.0F;
			}
			Vec3 motion = entity.getDeltaMovement();
			Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
			if (horizontal.lengthSqr() < 1.0E-6D) {
				return 0.0F;
			}
			turn = veerStep(entity, entityId, clientSide, input, horizontal);
		}
		float delta = (float) turn;
		// 本 tick 身体真转了多少（含「服务端收到客户端板轴那一 tick 不再自己加」的情况 ——
		// 记的是**请求**转角，作为回正步长的下限偏保守，宁可追上一点也不落后）。
		turnStep.put(entityId, delta);
		// 服务端：客户端这一 tick 刚把板轴交上来 —— 那份就是最终值，别再自己加一步（见本方法 javadoc）。
		// 客户端（clientSide）永远自己累加：它是权威，而单机里两端共用 entity id，不能拿服务端的时间戳把自己挡住。
		if (clientSide || !clientAxisThisTick(entityId, entity.level().getGameTime())) {
			table(BODY_YAW_CLIENT, BODY_YAW_SERVER, clientSide)
					.computeIfPresent(entityId, (id, yaw) -> yaw + delta);
		}
		if (isSpeedAlignable(entity) && !entity.horizontalCollision) {
			// 可改速度方向（贴地，或坡面 onGround 抖动那一 tick）且这一 tick 没顶墙：身体与速度同量旋转。
			// 撞墙 tick 跳过（A-1，理由见本方法 javadoc）：转出去的分量会被同 tick 的 move() 裁掉，纯白转 + 白掉速。
			rotateVelocity(entity, delta);
		}
		// 真腾空：这里什么都不做 —— 身体 / 模型已经转过了（BODY_YAW），速度方向由惯性保持，
		// 落地后由 alignCarveHeading 朝板轴追赶（与双板「空中只转身体、速度不动」同一条口径）。
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
	 * <p><b>摩擦换算保持不变，倍率由 {@link #TURN_RADIUS_BLOCKS} 承担</b>：半径取 3 格（2 倍档 2.0 回调到
	 * 约 1.5 倍档的结果，见该常量的 javadoc），因为三者都只乘在同一个除法 / 同一个 clamp 上，等效于把每 tick
	 * 的转角整体同比缩放（clamp 的两端也同步缩放，所以 {@code k × clamp(x, lo, hi) ≡ clamp(kx, klo, khi)} 逐点相等）。
	 * <p>实际值（雪面，摩擦 0.6 → factor 0.546）：满速 0.55 → 算 19.23 → 夹到 <b>16.0</b>；
	 * 0.30 → <b>10.49</b>；0.14 → <b>4.90</b>；0.10 → <b>3.50</b>；0.05 → 夹到下限 <b>3.0</b>。
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
	 * <b>只用 W / S（没按 A / D）时每 tick 摆多少度</b>（带符号）：<b>只在按键组合变化的那一 tick 算一次，
	 * 之后每 tick 照同一个值转</b>（锁存在 {@link #VEER_STEP_CLIENT} / {@link #VEER_STEP_SERVER}，
	 * 配 {@link #keySignature} 判断「按键变了没有」）。
	 *
	 * <p><b>为什么要锁存（本轮 II 的真因）</b>：旧写法每 tick 都用<b>实时</b>剩余角
	 * {@code wrapDegrees(身体前/后方向角 − 速度方向角)} 算步长。贴地时速度与身体同量旋转，剩余角恒定 → 能一直转；
	 * 但<b>空中速度是冻住的</b>（{@link #isSpeedAlignable} 为假），身体每 tick 转一步、剩余角就少一步：
	 * 从板轴（与身体前/后差 90°）起转，<b>转过 90° 时剩余角归零</b>，摆角也归零 —— 板就停在 90°。
	 * 而且在停下之前会先撞上 {@code wrapDegrees} 的 ±180 边界（剩余角从 +90 一路涨到 180 再跳到 −180），
	 * 于是每 tick 反向一次 → 身体在两个值之间来回跳（用户看到的「卡住 + 画面卡顿」）。
	 *
	 * <p><b>锁存之后</b>：方向与幅度都固定在「按下那一刻的几何」上（满速时剩余角 90° →
	 * {@code clamp(90 × VEER_SMOOTHING 0.5467, ±6.4) = 6.4°/tick}），身体因此<b>平滑、单调地连续转过 90°、
	 * 180°</b>，不会每 tick 反向；角速度上限仍是 {@link #VEER_MAX_DEGREES} 6.4（远低于
	 * {@link #TURN_MAX_DEGREES_PER_TICK} 16.0，A / D 那条路一点没变）。
	 * 贴地时实时剩余角本来就恒定 90°，所以<b>贴地手感与锁存前逐 tick 相同</b>。
	 *
	 * @param horizontal 当前水平速度（非零，调用方已判）
	 */
	private static double veerStep(Entity entity, int entityId, boolean clientSide, Input input, Vec3 horizontal) {
		IdMap<Float> steps = table(VEER_STEP_CLIENT, VEER_STEP_SERVER, clientSide);
		IdMap<Integer> signatures = table(VEER_KEYS_CLIENT, VEER_KEYS_SERVER, clientSide);
		int signature = keySignature(input);
		Integer latchedKeys = signatures.get(entityId);
		Float latchedStep = steps.get(entityId);
		if (latchedKeys != null && latchedStep != null && latchedKeys == signature) {
			return latchedStep;   // 按键没变：照锁存的那一步转（这就是「跨越 90° 不卡」的全部）
		}
		Vec3 front = frontLocked(entityId, entity.getYRot(), clientSide);
		Vec3 target = input.forward() ? front : front.scale(-1.0D);
		// 在 F(θ) = (-sinθ, cosθ) 的参数化下，方向角 = atan2(-x, z)；yaw 增大 ＝ 向右转
		double current = Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z));
		double goal = Math.toDegrees(Math.atan2(-target.x, target.z));
		double diff = Mth.wrapDegrees((float) (goal - current));
		// 起手摆角：与旧写法逐位相同（剩角 × 平滑系数，夹到 ±VEER_MAX）。之后不再重算。
		double turn = Mth.clamp(diff * VEER_SMOOTHING, -VEER_MAX_DEGREES, VEER_MAX_DEGREES);
		signatures.put(entityId, signature);
		steps.put(entityId, (float) turn);
		return turn;
	}

	/** 作废这个实体的 W / S 摆角锁存（没在 carve / 按了 A / D 时调，见 {@link #veerStep}）。 */
	private static void forgetVeerLatch(int entityId, boolean clientSide) {
		table(VEER_STEP_CLIENT, VEER_STEP_SERVER, clientSide).remove(entityId);
		table(VEER_KEYS_CLIENT, VEER_KEYS_SERVER, clientSide).remove(entityId);
	}

	/**
	 * 按键组合的签名（W / S / A / D 各一位）：{@link #veerStep} 用它判断「锁存作废了没有」。
	 * 只要按下的键组合变了（换键、松开再按、加 / 减侧压）就重新按当时的几何锁存一次。
	 */
	private static int keySignature(Input input) {
		if (input == null) {
			return 0;
		}
		int signature = 0;
		if (input.forward()) {
			signature |= 1;
		}
		if (input.backward()) {
			signature |= 2;
		}
		if (input.left()) {
			signature |= 4;
		}
		if (input.right()) {
			signature |= 8;
		}
		return signature;
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
