package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 穿着滑雪板时的滑行物理（每 tick 一次）：
 *
 * <ul>
 *   <li><b>滑行惯性（低摩擦）</b>：原版玩家在地面每 tick 会掉掉约 45% 的水平速度（10 tick 就基本停住），
 *       所以这里自己维护一份“动量”，按 {@link #MOMENTUM_DECAY}（默认每 tick 保留 98.5%）慢慢衰减，
 *       并保证玩家速度不低于它——被撑出去、被下落加速加出来的速度能滑很久才停；</li>
 *   <li><b>下落加速</b>：每累计下降 1 格高度就换一点水平速度（踩地滑下坡、空中掉落都算）；</li>
 *   <li><b>雪面推进</b>：在雪 / 冰上时每 0.5 秒沿移动方向补一点推进。</li>
 * </ul>
 *
 * <p><b>单板只保留“下落加速”这一条</b>：低摩擦动量不捕获玩家自己走出来的速度、也不做雪面推进，
 * 免得平地走路 / 站坡上都会"莫名往前"。下落加速也只在真的有横向移动（速度或按着 A / D）时才给，方向沿那一侧。
 * W / S 只改姿态（前倾 / 后倾）与相机，<b>不给任何推进</b>。
 *
 * <p>不需要药水效果。
 */
public final class SkiGlide {
	private static final int REFRESH_INTERVAL_TICKS = 10;
	private static final double MIN_MOTION_SQR = 0.008D;
	private static final double GLIDE_PUSH = 0.055D;

	/** 下落加速曲线的指数：1 = 线性、0.5 = 开方；0.7 = 略缓的边际递减（越小衰减越猛）。 */
	private static final double FALL_GAIN_EXPONENT = 1.0D;   // 1.0 = 线性（用户要求先去掉衰减，试手感）
	/** 双板下坡时每 tick 额外补多少向下的速度（贴地，别弹跳）。 */
	private static final double STICK_DOWN = 0.08D;
	/** 贴地时的下落速度上限（别再快了，免得落地太硬）。 */
	private static final double STICK_DOWN_MAX = 0.8D;
	/** 只有脚底离地不超过这么多格时才贴地（更远＝真的在坠落，交给原版重力）。 */
	private static final double STICK_MAX_DROP = 1.0D;   // 脚底离地不超过 1 格才贴地（用户定）
	/** 滑上一格台阶时扣掉多少水平速度（格/tick，用户定 0.04）。 */
	private static final double STEP_UP_PENALTY = 0.04D;
	/** 判定“上了一格台阶”的最小抬升高度（格）：半砖 / 整格都算，普通 0.6 步高不算。 */
	private static final double STEP_UP_MIN_HEIGHT = 0.5D;
	/** 每累计下落这么多格算一次，按次均匀给速度。 */
	private static final double STEP_BLOCKS = 1.0D;
	// 各板的滑行数值不在这里 —— 双板在 {@link SkisControl}，单板在 {@link SnowboardControl}，
	// 两边完全独立，调一个不会动到另一个。这里只留两板共用的“物理骨架”常量。
	/** 撞到方块（横向碰撞）时的额外衰减，避免一直顶着墙滑。 */
	private static final double MOMENTUM_DECAY_ON_HIT = 0.5D;
	/** 动量低于这个值就丢掉。 */
	private static final double MOMENTUM_MIN = 0.02D;

	private static final int MAX_ENTRIES = 64;

	/** 玩家实体 id → 上一 tick 的 Y。 */
	private static final Map<Integer, Double> LAST_Y = new ConcurrentHashMap<>();
	/** 玩家实体 id → 已累计但还没结算的下落高度。 */
	private static final Map<Integer, Double> DESCEND_ACCUM = new ConcurrentHashMap<>();
	/** 玩家实体 id → 当前滑行动量（水平速度）。 */
	private static final Map<Integer, Double> MOMENTUM = new ConcurrentHashMap<>();

	private SkiGlide() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!isSkiing(player)) {
					setStepHeight(player, 0.6D);   // 恢复原版台阶高度
					forgetPhysics(player.getId());
					SnowboardControl.forget(player.getId());
					SkisControl.forget(player.getId());
					continue;
				}
				if (SnowboardControl.isSnowboard(player)) {
					// ——— 单板 ———
					SnowboardControl.ensureLocked(player.getId(), player.getYRot());
					carve(player);
				}
				// 双板不做任何朝向锁定：朝向＝视角（用户 2026-10 定案回到最开始的状态）
				// 穿板时能直接滑上 1 格高的地面（原版玩家只能上 0.6 格）
				setStepHeight(player, 1.0D);
				if (!SnowboardControl.isSnowboard(player)) {
					stickToGround(player);   // 双板：下坡贴着地面滑，不一跳一跳地弹下去
				}
				trackDescent(player);
				applyMomentum(player);
				if (player.level().getGameTime() % REFRESH_INTERVAL_TICKS == 0L) {
					glidePush(player);
				}
			}
			if (LAST_Y.size() > MAX_ENTRIES) {
				forgetAll();
			}
		});
	}

	/** 穿着滑雪板（双板 / 单板）、且不是在创造飞行 / 骑乘等状态下才算滑行。 */
	private static boolean isSkiing(ServerPlayer player) {
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return false;
		}
		return !player.getAbilities().flying && !player.isPassenger();
	}

	/** 下落加速：比较每 tick 的 Y 变化，累计下降高度；每满 STEP_BLOCKS 格就沿当前方向加一点速度。 */
	private static void trackDescent(ServerPlayer player) {
		Integer id = player.getId();
		double y = player.getY();

		Double lastY = LAST_Y.put(id, y);
		if (lastY != null) {
			double down = lastY - y;
			if (down > 0.0D) {
				DESCEND_ACCUM.merge(id, down, Double::sum);
			} else if (-down >= STEP_UP_MIN_HEIGHT && player.onGround()) {
				stepUpPenalty(player);   // 滑上去一格：按每格 STEP_UP_PENALTY 减速
			}
		}

		double accumulated = DESCEND_ACCUM.getOrDefault(id, 0.0D);
		boolean board = SnowboardControl.isSnowboard(player);
		// 结算步长按板取：双板 1.0 格一次，单板 0.1 格一次（开始下坡就立刻有反应）
		double step = board ? SnowboardControl.descentStepBlocks() : STEP_BLOCKS;
		if (accumulated < step) {
			return;
		}

		double steps = Math.floor(accumulated / step);
		DESCEND_ACCUM.put(id, accumulated - steps * step);

		// 各取各的（双板 SkisControl / 单板 SnowboardControl），互不影响
		// 两板同一种曲线：基数 × (累计格数)^FALL_GAIN_EXPONENT
		// 指数 < 1 时才边际递减；现在用户要求先去掉衰减试手感，所以 FALL_GAIN_EXPONENT = 1（线性）。
		// ⚠ 乘 step 是为了让“步长细”不等于“给得多”：总量仍是「每下降 1 格 = 基数」。
		double cap = board ? SnowboardControl.fallGainPerTickMax() : SkisControl.fallGainPerTickMax();
		double base = board ? SnowboardControl.fallGainPerBlock() : SkisControl.fallGainPerBlock();
		double gain = Math.min(Math.pow(steps, FALL_GAIN_EXPONENT) * base * step, cap);
		Vec3 direction = descentDirection(player);
		if (direction == null) {
			return;   // 单板：没有横向速度也没按 A / D —— 不给加速（否则就是“莫名往前”）
		}
		player.addDeltaMovement(direction.scale(gain));
		MOMENTUM.merge(id, player.getDeltaMovement().horizontalDistance(), Math::max);   // 让加速出来的速度被动量托住
		clampToMaxSpeed(player);
		player.hurtMarked = true;   // 让客户端也吃到这份加速，不然会被客户端的移动预测抹掉
	}

	/** 把水平速度压到 {@link SkiControl#MAX_GLIDE_SPEED} 以内（垂直分量不动）。 */
	/**
	 * 设置台阶高度。26.2 里它不是 {@code setMaxUpStep}，而是属性 {@link Attributes#STEP_HEIGHT}
	 * （原版玩家默认 0.6 格）。
	 */
	private static void setStepHeight(ServerPlayer player, double value) {
		AttributeInstance attribute = player.getAttribute(Attributes.STEP_HEIGHT);
		if (attribute != null) {
			attribute.setBaseValue(value);
		}
	}

	/**
	 * 滑上一格台阶的减速：每上一格扣 {@link #STEP_UP_PENALTY} 格/tick。
	 *
	 * <p>动量要一起扣 —— 不然下一步 {@code applyMomentum} 又用旧的动量把速度托回来，等于没减。
	 */
	private static void stepUpPenalty(ServerPlayer player) {
		Integer id = player.getId();
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();
		if (speed > 1.0E-4D) {
			double factor = Math.max(0.0D, (speed - STEP_UP_PENALTY) / speed);
			player.setDeltaMovement(motion.x * factor, motion.y, motion.z * factor);
			player.hurtMarked = true;
		}
		double momentum = MOMENTUM.getOrDefault(id, 0.0D) - STEP_UP_PENALTY;
		if (momentum < MOMENTUM_MIN) {
			MOMENTUM.remove(id);
		} else {
			MOMENTUM.put(id, momentum);
		}
	}

	/**
	 * 双板下坡贴地：没在地面、又在往下掉、而且<b>没按跳跃键</b>时，额外补一点向下的速度 ——
	 * 于是下坡是“贴着坡面滑下去”，而不是被惯性抛起来、一格一格地弹。
	 *
	 * <p>按了跳跃键就完全不插手（腾空跳起来的功能照旧保留）；上升中也不管（那是自己跳的）。
	 */
	private static void stickToGround(ServerPlayer player) {
		if (player.onGround()) {
			return;
		}
		Input keys = player.getLastClientInput();
		if (keys != null && keys.jump()) {
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		if (motion.y >= 0.0D) {
			return;
		}
		// ⚠️ 只在“脚底下很近就是地面”（下坡 / 蹭着坡面）时才贴 ——
		// 否则从高处跳下会变成匀速下坠，把原版的重力加速弄没了（用户实测到了这一点）。
		if (SkiControl.groundDistance(player) > STICK_MAX_DROP) {
			return;
		}
		player.setDeltaMovement(motion.x, Math.max(motion.y - STICK_DOWN, -STICK_DOWN_MAX), motion.z);
		player.hurtMarked = true;
	}

	private static void clampToMaxSpeed(ServerPlayer player) {
		// 上限按板取（单板 / 双板各自一个值）
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();
		double max = SkiControl.maxGlideSpeed(player);
		if (speed > max) {
			double keep = max / speed;
			player.setDeltaMovement(motion.x * keep, motion.y, motion.z * keep);
		}
	}

	/**
	 * 低摩擦滑行：维护一份动量，每 tick 按 {@link #MOMENTUM_DECAY} 缓慢衰减，并保证玩家水平速度不低于它。
	 * 玩家自己（走路/冲刺/右键冲刺）比动量快时完全不插手。
	 */
	private static void applyMomentum(ServerPlayer player) {
		Integer id = player.getId();
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();

		double momentum = MOMENTUM.getOrDefault(id, 0.0D);
		Input keys = player.getLastClientInput();
		double decay;
		if (SnowboardControl.isSnowboard(player)) {
			// ——— 单板 ———
			double side = SnowboardControl.sideSign(keys);
			if (side != 0.0D) {
				// 分“同向 / 反向”两种按法：
				//   反向（向左滑时按 D）＝刹车 —— 原版横移把人往右挪、速度自然抵消，
				//   所以这里**不覆盖玩家速度、也不托动量**，只把动量对齐到当前实际速度（否则动量会托着旧速度，反着按等于白按）；
				//   同向（顺着滑行方向按）＝不与玩家作对，让动量**托住**已有速度 ——
				//   否则下落加速攒出来的速度每 tick 被地面摩擦（约 ×0.55）吃掉，滑多长都只剩原版横向移动的基础速度
				//   （用户报：“一直按住 A / D 下坡，速度叠加触发不了，一直是基础速度滑动”）。
				Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
				double along = horizontal.lengthSqr() < 1.0E-6D
						? 0.0D
						: horizontal.normalize().dot(SnowboardControl.sideLocked(id, player.getYRot(), 1.0D));
				if (along * side <= 0.0D) {
					MOMENTUM.put(id, speed);
					return;
				}
			}
			momentum = Math.max(momentum, speed);
			decay = SnowboardControl.momentumDecay();
		} else {
			// ——— 双板 ———
			momentum = Math.max(momentum, speed);
			decay = SkisControl.momentumDecay();
			if (keys != null && keys.backward()) {
				decay *= SkisControl.brakeDecay();   // 按 S ＝刹车：持续减速，松开后也不会被动量拉回原速
			}
		}
		momentum *= player.horizontalCollision ? MOMENTUM_DECAY_ON_HIT : decay;
		momentum = Math.min(momentum, SkiControl.maxGlideSpeed(player));   // 速度上限（按板）

		if (momentum < MOMENTUM_MIN) {
			MOMENTUM.remove(id);
			return;
		}
		MOMENTUM.put(id, momentum);

		if (speed >= momentum) {
			return;   // 玩家自己滑得更快，不插手
		}
		Vec3 direction = steerDirection(player);   // 托速度时也允许用 WASD 转向，不然会像被锁住一样
		if (direction == null) {
			if (motion.horizontalDistanceSqr() <= 1.0E-6D) {
				return;
			}
			direction = new Vec3(motion.x, 0.0D, motion.z).normalize();   // 没输入就沿原方向托
		}
		player.setDeltaMovement(direction.x * momentum, motion.y, direction.z * momentum);
		player.hurtMarked = true;
	}

	/**
	 * 单板 carve：前倾（W）＋ 横移（A / D）→ 身体朝向朝那一侧持续转（{@link SnowboardControl#applyCarve}），
	 * 同时沿身体前方补一点前冲力 —— 与横移合成“右前方 / 左前方”，一直按着就是在雪地上画圈。
	 */
	private static void carve(ServerPlayer player) {
		// 注意：腾空时也允许“转”（从高处落下时前后倾要能带着速度拐弯），
		// 只有“前冲”那一口力限制在贴地时给。
		Input input = player.getLastClientInput();
		double speed = player.getDeltaMovement().horizontalDistance();
		if (SnowboardControl.applyCarve(player, input, speed) == 0.0F) {
			return;
		}
		// W / S 只转向、不加力；转向过程中把已有的速度用动量托住（不然摩擦会把它吃掉）
		MOMENTUM.merge(player.getId(), player.getDeltaMovement().horizontalDistance(), Math::max);
		clampToMaxSpeed(player);
		player.hurtMarked = true;
	}

	/** 雪 / 冰面上的推进：正在移动就沿移动方向补一点。 */
	private static void glidePush(ServerPlayer player) {
		if (SnowboardControl.isSnowboard(player) || !isOnSnowOrIce(player)) {
			return;   // 单板不做雪面推进：那是“没人碰它自己往前”的来源之一
		}
		Vec3 motion = player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() > MIN_MOTION_SQR) {
			Vec3 direction = new Vec3(motion.x, 0.0D, motion.z).normalize();
			player.addDeltaMovement(direction.scale(GLIDE_PUSH));
			player.hurtMarked = true;
		}
	}

	/**
	 * 下落加速的方向（单板与双板同一套优先级）：
	 * 有横向速度 → 沿当前移动方向；按着 A / D → 沿那一侧；都没有 → 沿身体前方。
	 * 保证“只要在往下掉就总有方向可加”，不然一停下来就完全不加速。
	 */
	/**
	 * 下落加速的方向。
	 *
	 * <p><b>单板只有左右</b>（它是一维的横向运动）：把当前移动方向<b>投影到身体横轴</b>上，只取左右分量 ——
	 * 绝不给“往前”的加速；几乎没有左右分量时不加（宁可不动，也不要莫名往前）。
	 *
	 * <p>双板照旧：有横向速度就沿当前移动方向，否则按按键 / 视线定方向。
	 */
	private static Vec3 descentDirection(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);

		if (SnowboardControl.isSnowboard(player)) {
			Vec3 axis = SnowboardControl.sideLocked(player.getId(), player.getYRot(), 1.0D);   // 身体左侧单位向量
			double along = horizontal.lengthSqr() < 1.0E-6D ? 0.0D : horizontal.normalize().dot(axis);
			if (Math.abs(along) >= 0.02D) {
				return along > 0.0D ? axis : axis.scale(-1.0D);   // 正在往左 / 往右滑
			}
			double side = SnowboardControl.sideSign(player.getLastClientInput());
			return side == 0.0D ? null : axis.scale(side);        // 没滑但按着 A / D 也算有左右
		}

		if (horizontal.lengthSqr() > MIN_MOTION_SQR) {
			return horizontal.normalize();
		}
		return steerDirection(player);
	}

	/** 加速方向：在滑行方向的基础上，按玩家按键转向（离地太高则锁死方向）；单板可能返回 null＝不给加速。 */
	private static Vec3 steerDirection(ServerPlayer player) {
		Vec3 travel = travelDirection(player);
		return travel == null ? null : SkiControl.steer(travel, player);
	}
	private static Vec3 travelDirection(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() > MIN_MOTION_SQR) {
			return new Vec3(motion.x, 0.0D, motion.z).normalize();
		}
		if (SnowboardControl.isSnowboard(player)) {
			// 单板：**不在这里改方向** —— 没有速度就返回 null（不加力），有速度就走上面的“沿当前方向”。
			// 这样 A / D 和 W / S 的“方向变化”是同一套机制：都靠 carve 的每 tick 旋转，
			// 而不是按 A / D 时把速度一下子拨到侧向、按 W / S 时却慢慢转（用户反馈“两边不一样”）。
			return null;
		}
		Vec3 look = player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
		return horizontal.length() > 0.01D ? horizontal.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
	}

	/** 脚下（或身位）方块属于雪 / 冰标签即为雪面。 */
	private static boolean isOnSnowOrIce(ServerPlayer player) {
		var level = player.level();
		BlockPos standing = player.blockPosition();
		BlockState below = level.getBlockState(standing.below());
		if (below.is(BlockTags.SNOW) || below.is(BlockTags.ICE)) {
			return true;
		}
		return level.getBlockState(standing).is(BlockTags.SNOW);
	}

	private static void forgetPhysics(int id) {
	LAST_Y.remove(id);
	DESCEND_ACCUM.remove(id);
	MOMENTUM.remove(id);
	}

	private static void forgetAll() {
		LAST_Y.clear();
		DESCEND_ACCUM.clear();
		MOMENTUM.clear();
		SnowboardControl.forgetAll();
	}
}
