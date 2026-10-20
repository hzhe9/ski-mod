package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 穿着滑雪板时的滑行物理（每 tick 一次）：
 *
 * <ul>
 *   <li><b>滑行惯性（低摩擦）</b>：原版玩家在地面每 tick 会掉掉约 45% 的水平速度（10 tick 就基本停住），
 *       所以这里自己维护一份“动量”，按 {@link #MOMENTUM_DECAY}（默认每 tick 保留 98.5%）慢慢衰减，
 *       并保证玩家速度不低于它——被撑出去、被下落加速加出来的速度能滑很久才停；</li>
 *   <li><b>下落加速</b>：每累计下降 1 格高度就换一点水平速度（踩地滑下坡、空中掉落都算）；
 *   <li><b>重力加重</b>：穿着板时把原版重力（{@code Attributes.GRAVITY}）改成该板倍数的值 ——
 *       真的改重力，不是每 tick 补一份向下的速度（全程生效：上升段也一起变沉，跳得更矮、落得更快）；</li>
 *   <li><b>雪面推进</b>：在雪 / 冰上时每 0.5 秒沿移动方向补一点推进。
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
	private static final double FALL_GAIN_EXPONENT = 1.0D;   // 1.0 = 线性：加速只跟累计格数成正比，掉多少格换多少速度，可预期
	/** 滑上一格台阶时扣掉多少水平速度（格/tick，0.04 ≈ 满速的 5% —— 顿一下但不至于卡住）。 */
	private static final double STEP_UP_PENALTY = 0.04D;
	/** 判定“上了一格台阶”的最小抬升高度（格）：半砖 / 整格都算，普通 0.6 步高不算。 */
	private static final double STEP_UP_MIN_HEIGHT = 0.5D;
	/**
	 * 每累计下落这么多格算一次，按次均匀给速度（**双板**；单板用 {@link SnowboardControl#descentStepBlocks()} 的 0.5）。
	 *
	 * <p>双板也按每 0.5 格结算：单次 = 0.12 × 0.5 = 0.06，
	 * 每格总量仍是 0.12（乘 step 保证“步长细” ≠ “给得多”），只是下坡降没半格就有第一份。
	 */
	private static final double STEP_BLOCKS = 0.5D;
	// 各板的滑行数值不在这里 —— 双板在 {@link SkisControl}，单板在 {@link SnowboardControl}，
	// 两边完全独立，调一个不会动到另一个。这里只留两板共用的“物理骨架”常量。
	/** 撞到方块（横向碰撞）时的额外衰减，避免一直顶着墙滑。 */
	private static final double MOMENTUM_DECAY_ON_HIT = 0.5D;
	/** 动量低于这个值就丢掉。 */
	private static final double MOMENTUM_MIN = 0.02D;

	/** 玩家实体 id → 上一 tick 的 Y。 */
	private static final IdMap<Double> LAST_Y = new IdMap<>();
	/** 玩家实体 id → 已累计但还没结算的下落高度。 */
	private static final IdMap<Double> DESCEND_ACCUM = new IdMap<>();
	/** 玩家实体 id → 当前滑行动量（水平速度）。 */
	private static final IdMap<Double> MOMENTUM = new IdMap<>();

	private SkiGlide() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!isSkiing(player)) {
					setStepHeight(player, 0.6D);   // 恢复原版台阶高度
					resetGravity(player);          // 恢复原版重力
					forgetPhysics(player.getId());
					SnowboardControl.forget(player.getId());
					SkisControl.forgetBody(player.getId());
					continue;
				}
				if (SnowboardControl.isSnowboard(player)) {
					// ——— 单板 ———
					SnowboardControl.ensureLocked(player.getId(), player.getYRot());
					carve(player);
				} else {
					// ——— 双板：A / D 转身体（8°/tick）；W 再朝视角方向补 4°/tick。视角一律不动 ———
					SkisControl.ensureLocked(player.getId(), player.getYRot());
					Input input = player.getLastClientInput();
					double side = SnowboardControl.sideSign(input);
					if (side != 0.0D) {
						SkisControl.turnBody(player.getId(), player.getYRot(), side);
					}
					// 顺序：先 A/D 再 W；S（后退）不触发。只转身体，视角靠 SkiViewSmoothing 收敛
					if (input != null && input.forward()) {
						SkisControl.turnTowardView(player.getId(), player.getYRot(), player.getYRot());
					}
				}
				// 穿板时能直接滑上 1 格高的地面（原版玩家只能上 0.6 格）
				setStepHeight(player, 1.0D);
				applyGravity(player);   // 穿板时重力 ×倍数（单板 / 双板各一份，见 SnowboardControl / SkisControl）
				trackDescent(player);
				applyMomentum(player);
				if (player.level().getGameTime() % REFRESH_INTERVAL_TICKS == 0L) {
					glidePush(player);
				}
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
		// 结算步长按板取：两板现在同粒度 0.5 格一次（双板 STEP_BLOCKS、单板 DESCENT_STEP_BLOCKS，各自独立）
		double step = board ? SnowboardControl.descentStepBlocks() : STEP_BLOCKS;
		if (accumulated < step) {
			return;
		}

		double steps = Math.floor(accumulated / step);
		DESCEND_ACCUM.put(id, accumulated - steps * step);

		// 各取各的（双板 SkisControl / 单板 SnowboardControl），互不影响
		// 两板同一种曲线：基数 × (累计格数)^FALL_GAIN_EXPONENT
		// 指数 < 1 时才边际递减；当前取 1（线性）—— 下坡加速不随下落距离打折，手感可预期。
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

	/** 把水平速度压到 {@link SkiControl#maxGlideSpeed(ServerPlayer)} 以内（垂直分量不动）。 */
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
	 * <b>穿板时的重力</b>：26.2 里重力就是属性 {@link Attributes#GRAVITY}（默认 0.08 格/tick²），
	 * 这里把它改成「原版默认 × 该板的重力倍数」（单板 {@link SnowboardControl#gravityMultiplier()}、
	 * 双板 {@link SkisControl#gravityMultiplier()}，各自一个值）。
	 *
	 * <p>改的是真重力，不是每 tick 补一份向下的速度 —— 所以上升段一起变沉（跳得更矮、滞空更短），
	 * 全程生效。原版重力在穿板时太轻（下落像飘在空里），所以取「比平时更快」这一档。
	 * （试过「贴地 / 腾空两态 + 前馈地形跟随」的控制器，实测贴太紧、会瞬间吸到下一格，
	 * 已回退到这条重力方案 —— 别再自作主张加贴地控制。）
	 * 与 {@link #setStepHeight} 同一套做法：只在穿板时生效，脱板由 {@link #resetGravity} 还原。
	 */
	private static void applyGravity(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.GRAVITY);
		if (attribute == null) {
			return;
		}
		double multiplier = SnowboardControl.isSnowboard(player)
				? SnowboardControl.gravityMultiplier()
				: SkisControl.gravityMultiplier();
		attribute.setBaseValue(Attributes.GRAVITY.value().getDefaultValue() * multiplier);
	}

	/** 脱板（或没穿板）时把重力还原成原版默认值。 */
	private static void resetGravity(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.GRAVITY);
		if (attribute != null) {
			attribute.setBaseValue(Attributes.GRAVITY.value().getDefaultValue());
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
			// 正在转向（压着刃）时，反向侧压是「换刃转向」而不是刹车 ——
			// 走刹车分支（不托底、动量被对齐成当前速度）的话，起弯那一下速度会被原版摩擦＋反向输入吃掉一大截
			// （实测 0.5 → 0.165，占 67%），而且动量被清掉后整段弯都回不到原来的速度。
			boolean carving = SnowboardControl.isCarving(keys, speed);
			if (side != 0.0D && !carving) {
				// 分“同向 / 反向”两种按法：
				//   反向（向左滑时按 D）＝刹车 —— 原版横移把人往右挪、速度自然抵消，
				//   所以这里**不覆盖玩家速度、也不托动量**，只把动量对齐到当前实际速度（否则动量会托着旧速度，反着按等于白按）；
				//   同向（顺着滑行方向按）＝不与玩家作对，让动量**托住**已有速度 ——
				//   否则下落加速攒出来的速度每 tick 被地面摩擦（约 ×0.55）吃掉，滑多长都只剩原版横向移动的基础速度
				//   （症状就是：一直按住 A / D 下坡，速度叠加触发不了，一直是基础速度滑动）。
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
			// 转向期间用专门的（更小的）摩擦：转弯不该比直行还涩
			decay = carving ? SnowboardControl.carveMomentumDecay() : SnowboardControl.momentumDecay();
		} else {
			// ——— 双板 ———
			// 按 S 且还在往前滑 ＝ 刹车：动量与速度一起按 BRAKE_DECAY 衰减，方向不翻。
			// （以前的写法把 S 也当成「想往后走」，于是高速按 S 只会把速度方向拨反，一点没减下来。）
			momentum = Math.max(momentum, speed);
			boolean braking = keys != null && keys.backward() && SkisControl.isMovingForward(player);
			if (braking) {
				// 刹车：动量与当前速度一起按 BRAKE_DECAY 衰减，方向**不翻**
				momentum = Math.max(momentum, speed) * SkisControl.brakeDecay();
				Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
				if (horizontal.lengthSqr() > 1.0E-6D) {
					double keep = momentum / horizontal.length();
					player.setDeltaMovement(motion.x * keep, motion.y, motion.z * keep);
					player.hurtMarked = true;
				}
				MOMENTUM.put(id, momentum);
				return;   // 刹车这一 tick 不走下面「朝视角方向重写速度」那套（否则就又拨方向了）
			}
			// ——— 落地对齐（只在双板分支）：A / D 转完身落地，速度方向要快速朝身体前方掰 ———
			// 以前方向只在 speed < momentum 时经 SkiControl.steer（STEER_RATE 0.2 的 lerp）慢慢摆，
			// 玩家自己的速度不掉就直接 return，方向永远不跟 →「转完身落地不跟手」。
			// 现在贴地（且没按 S、还有速度）就先掰一步方向，判定与执行都在 SkisControl。
			if (SkisControl.shouldAlignOnGround(player, keys)) {
				SkisControl.alignVelocity(player);
				// 对齐只改方向、不改水平速度大小，但下面用的是 motion / speed —— 重读，别拿陈旧值往下走
				motion = player.getDeltaMovement();
				speed = motion.horizontalDistance();
			}
			decay = SkisControl.momentumDecay();
			// 没按 S（或已停下 / 在倒滑）时才走常规托底：动量原样托住速度。
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
			// 而不是按 A / D 时把速度一下子拨到侧向、按 W / S 时却慢慢转 —— 两种键位的转向手感必须一致。
			return null;
		}
		Vec3 look = player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
		return horizontal.length() > 0.01D ? horizontal.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
	}

	/** 脚下（或身位）方块属于雪 / 冰标签即为雪面（滑行、雪粒子共用这一条；落地另有更宽的判定）。 */
	public static boolean isOnSnowOrIce(Level level, BlockPos standing) {
		BlockState below = level.getBlockState(standing.below());
		if (below.is(BlockTags.SNOW) || below.is(BlockTags.ICE)) {
			return true;
		}
		return level.getBlockState(standing).is(BlockTags.SNOW);
	}

	/**
	 * <b>落地用的雪面判定（比 {@link #isOnSnowOrIce} 宽）</b>：雪层只有 0.125 格高，落点很容易跨在
	 * 雪层与它下面的石头上 —— 所以把脚底所在格、上一格、下两格都看一遍，任一是雪 / 冰就算砸在雪面上。
	 */
	public static boolean isSnowLanding(Level level, BlockPos standing) {
		return isSnowOrIceAt(level, standing)
				|| isSnowOrIceAt(level, standing.above())
				|| isSnowOrIceAt(level, standing.below())
				|| isSnowOrIceAt(level, standing.below(2));
	}

	private static boolean isSnowOrIceAt(Level level, BlockPos pos) {
		var state = level.getBlockState(pos);
		return state.is(BlockTags.SNOW) || state.is(BlockTags.ICE);
	}

	/**
	 * 脚底到下方地面的距离（格）；往下 {@code range + 0.1} 格内探不到东西就返回一个很大的值。
	 *
	 * <p>客户端与服务端都能用（{@code Level#clip} 两边都有）—— 雪粒子、滑行声、落地免伤都用它，
	 * 判定一律是「离地 ≤ range」而不是 {@code onGround}（滑下坡时人常常一两 tick 没踩着地）。
	 */
	public static double groundDistance(Entity entity, double range) {
		Level level = entity.level();
		Vec3 from = entity.position();
		Vec3 to = from.add(0.0D, -(range + 0.1D), 0.0D);
		BlockHitResult hit = level.clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return Double.MAX_VALUE;
		}
		return from.y - hit.getLocation().y;
	}

	/** 雪面的便捷重载：用玩家自己的位置。 */
	private static boolean isOnSnowOrIce(ServerPlayer player) {
		return isOnSnowOrIce(player.level(), player.blockPosition());
	}

	private static void forgetPhysics(int id) {
		LAST_Y.remove(id);
		DESCEND_ACCUM.remove(id);
		MOMENTUM.remove(id);
	}
}
