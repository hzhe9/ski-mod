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
	/** 头（视角）相对身体朝向能转的最大角度：左右各 {@code 90} 度，加起来 180 —— 扭不到背后去。 */
	public static final float VIEW_LIMIT_DEGREES = 90.0F;

	/**
	 * <b>carve（切雪转向）</b>：前倾（W）＋ 横移（A / D）同时按住时，身体朝向每 tick 朝那一侧转多少度。
	 * 2.0 度/tick ＝ 180 度/秒 —— 一直按着就会在雪地上画圈。
	 */
	private static final float TURN_RATE_DEGREES = 2.0F;
	/** carve 时每 tick 沿身体前方补多少“前冲力”（这就是“向前的动量”，与横移合成出右前方 / 左前方）。 */
	private static final double CARVE_PUSH = 0.02D;
	/** carve 前冲的速度上限：滑到这么快就不再补，免得按着 W + D 一路飙到极速（格/tick）。 */
	private static final double CARVE_MAX_SPEED = 0.5D;
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

	/**
	 * <b>carve 转向</b>：A / D 的侧压决定往哪转；<b>没按 A / D 时，只要滑动速度够，光前 / 后倾也能转</b>
	 * （前倾走一边、后倾走另一边）。<b>后倾＝后刃</b>：同样的侧压，转向与前倾相反。
	 * 服务端与客户端每 tick 各跑一次同样的规则，两边朝向保持一致（客户端管相机与模型、服务端管力）。
	 *
	 * @return 本 tick 转过的角度（0 表示没在 carve）
	 */
	public static float applyCarve(int entityId, Input input, double speed) {
		if (input == null || (!input.forward() && !input.backward())) {
			// 只在“压刃”时切雪：光按 A / D 是纯横移，不加前冲、也不转向
			return 0.0F;
		}
		double side = sideSign(input);   // A / D 的侧压：+1 左 / -1 右 / 0 没按
		if (side == 0.0D) {
			// 没按左右：只要滑得够快，光前 / 后倾也能压刃转向 —— 前倾走一边，后倾走另一边
			if (speed < CARVE_MIN_SPEED) {
				return 0.0F;
			}
			side = input.forward() ? 1.0D : -1.0D;
		} else if (input.backward()) {
			side = -side;   // 后倾＝后刃：同样的侧压，转向与前倾相反（“S 应该是向后”）
		}
		if (side == 0.0D) {
			return 0.0F;
		}
		float delta = (float) (side * TURN_RATE_DEGREES);   // +＝左转（yaw 增大），-＝右转
		BODY_YAW.computeIfPresent(entityId, (id, yaw) -> yaw + delta);
		return delta;
	}

	/**
	 * 把实体的水平速度绕 Y 轴转 {@code degrees}（与身体朝向同向）——
	 * 转向时速度要跟着拐，否则“从高处落下来带着的那份速度”不听使唤。
	 * 服务端与客户端都要调用（本地玩家的位置权威在客户端，光服务端转看不见效果）。
	 */
	public static void rotateVelocity(Entity entity, float degrees) {
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

	public static double carvePush() {
		return CARVE_PUSH;
	}

	public static double carveMaxSpeed() {
		return CARVE_MAX_SPEED;
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
