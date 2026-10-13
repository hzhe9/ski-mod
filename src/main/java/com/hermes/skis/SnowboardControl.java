package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 穿上单板时锁定的身体朝向（度）。 */
	private static final Map<Integer, Float> BODY_YAW = new ConcurrentHashMap<>();
	/** 实体 id → 俯仰姿态（当前值，平滑后；正 = 前倾 / 负 = 后倾），供模型与相机读取。 */
	private static final Map<Integer, Float> TILT_VALUE = new ConcurrentHashMap<>();
	/** 实体 id → 俯仰姿态的目标值（+1 前倾 / 0 直立 / -1 后倾），由按键刷新。 */
	private static final Map<Integer, Integer> TILT_TARGET = new ConcurrentHashMap<>();

	/** 前倾 / 后倾时<b>整个人连同板</b>绕脚底倾斜的角度（度）。 */
	private static final float TILT_DEGREES = 15.0F;
	/** 头（视角）相对身体朝向能转的最大角度：左右各 {@code 135} 度，合计 270 —— 扭不到背后去。 */
	public static final float VIEW_LIMIT_DEGREES = 135.0F;

	/**
	 * <b>carve（切雪转向）</b>：前倾（W）＋ 横移（A / D）同时按住时，身体朝向每 tick 朝那一侧转多少度。
	 * 2.0 度/tick ＝ 180 度/秒 —— 一直按着就会在雪地上画圈。
	 */
	private static final float TURN_RATE_DEGREES = 2.0F;
	// 注意：W / S <b>只转向、不加力</b>（用户明确：“w 和 s 只是对当前的速度进行一个转向，不是添加速度向量”）。
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
	 * 单板：每下落 1 格加多少水平速度（只取左右分量、体感弱，所以比双板大）。
	 * 0.11 → 0.13（用户 2026-10-13 要求「加大 0.02」）。
	 */
	private static final double FALL_GAIN_PER_BLOCK = 0.13D;
	/** 单板：单 tick 下落加速上限。 */
	private static final double FALL_GAIN_PER_TICK_MAX = 0.3D;   // 与双板统一（用户定 0.3）
	/** 单板：动量每 tick 保留比例（比双板滑得多 —— 切雪转向时速度不该被地面摩擦啃掉）。 */
	private static final double MOMENTUM_DECAY = 0.99D;   // 用户 2026-10-11 定 0.99（与双板同值）
	/**
	 * <b>转向期间</b>（carve 生效时）的动量保留比例 —— 比平时更滑。
	 *
	 * <p>{@link #MOMENTUM_DECAY} 0.99 是平时的「有效摩擦力」（每 tick 掉 1%，1 秒 -18%）；
	 * 转向时改用这个值：0.997 ≈ 每 tick 掉 0.3%（1 秒 -6%，一次 180° 的弯只掉 6%）。
	 * 改成 <b>1.0</b> ＝ 转向全程完全不掉速；改回 0.99 ＝ 与直行同摩擦。
	 */
	private static final double CARVE_MOMENTUM_DECAY = 0.997D;
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
	/** 这个摆动每 tick 最多转多少度（平滑趋近的速度上限）。 */
	private static final double VEER_MAX_DEGREES = 4.0D;
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
		if (BODY_YAW.size() > MAX_ENTRIES) {
			BODY_YAW.clear();
		}
	}

	public static void forget(int entityId) {
		BODY_YAW.remove(entityId);
		TILT_VALUE.remove(entityId);
		TILT_TARGET.remove(entityId);
	}

	public static void forgetAll() {
		BODY_YAW.clear();
		TILT_VALUE.clear();
		TILT_TARGET.clear();
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

	/** 单板：每下落 1 格加多少水平速度。 */
	public static double fallGainPerBlock() {
		return FALL_GAIN_PER_BLOCK;
	}

	/** 单板：单 tick 下落加速上限。 */
	public static double fallGainPerTickMax() {
		return FALL_GAIN_PER_TICK_MAX;
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
	public static boolean isCarving(Input input, double speed) {
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
	 * <b>carve 一步（只转向、不加力）</b>：把锁定的身体朝向朝那一侧转一个 tick 的量，并让
	 * <b>速度方向跟着转同样的角度</b>（不是拨到侧向、也不补任何推力）。
	 * 服务端与客户端各跑一次，两边朝向与速度方向保持一致。
	 *
	 * <p>腾空也照跑（从高处落下时要能带着速度拐弯）。
	 *
	 * @return 本 tick 转过的角度（0 表示没在 carve）
	 */
	public static float applyCarve(Entity entity, Input input, double speed) {
		if (input == null || (!input.forward() && !input.backward())) {
			// 只在“压刃”时切雪：光按 A / D 是纯横移，不转向
			return 0.0F;
		}
		int entityId = entity.getId();
		double side = sideSign(input);   // A / D 的侧压：+1 左 / -1 右 / 0 没按
		double turn;
		if (side != 0.0D) {
			turn = input.backward() ? -side : side;   // 有侧压：后倾＝后刃（反向）
		} else {
			// 只用前 / 后倾：把滑行方向朝 **身体前方**（W）/ **身体后方**（S）摆。
			// 这样“往左滑”和“往右滑”按同一个键效果才一致 —— 之前用固定左 / 右转的符号，
			// 结果是往左滑按 W 朝前、往右滑按 W 朝后（用户报的正是这个）。
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
			// 在 F(θ) = (-sinθ, cosθ) 的参数化下，方向角 = atan2(-x, z)；yaw 增大＝左转
			double current = Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z));
			double goal = Math.toDegrees(Math.atan2(-target.x, target.z));
			double diff = Mth.wrapDegrees((float) (goal - current));
			// 平滑趋近：步长与剩余角度成正比（快到位时自动变慢），而不是“固定 2°/tick、到了就死停”——
			// 后者在目标附近会变成一顿一顿的（用户报“单板前后转向一卡一卡”）。
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
	 * <p>注意别写成“把速度方向直接拨到侧向”：那样往左滑时一按 S 速度会瞬间跳到右侧（用户实测反馈）。
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

	/** 沿<b>锁定的身体朝向</b>的左 / 右方向（sign 见 {@link #sideSign}）—— 下落加速跟左右走时用。 */
	public static Vec3 sideLocked(int entityId, float fallbackYaw, double sign) {
		float rad = bodyYaw(entityId, fallbackYaw) * Mth.DEG_TO_RAD;
		return new Vec3(Mth.cos(rad) * sign, 0.0D, Mth.sin(rad) * sign);
	}
}
