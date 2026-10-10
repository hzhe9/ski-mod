package com.hermes.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
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
 * <p>不需要药水效果。
 */
public final class SkiGlide {
	private static final int REFRESH_INTERVAL_TICKS = 10;
	private static final double MIN_MOTION_SQR = 0.008D;
	private static final double GLIDE_PUSH = 0.055D;

	/** 每累计下落这么多格算一次，按次均匀给速度。 */
	private static final double STEP_BLOCKS = 1.0D;
	/** 每下落 1 格加多少水平速度。 */
	private static final double GAIN_PER_BLOCK = 0.05D;
	/** 单 tick 最多加多少（防止悬崖一瞬间给爆）。 */
	private static final double MAX_GAIN_PER_TICK = 0.3D;

	/** 动量每 tick 保留的比例：越接近 1 越滑、越慢停（0.985 ≈ 2 秒掉一半）。 */
	private static final double MOMENTUM_DECAY = 0.985D;
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
					forget(player.getId());
					continue;
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
			}
		}

		double accumulated = DESCEND_ACCUM.getOrDefault(id, 0.0D);
		if (accumulated < STEP_BLOCKS) {
			return;
		}

		double steps = Math.floor(accumulated / STEP_BLOCKS);
		DESCEND_ACCUM.put(id, accumulated - steps * STEP_BLOCKS);

		double gain = Math.min(steps * GAIN_PER_BLOCK, MAX_GAIN_PER_TICK);
		Vec3 direction = steerDirection(player);
		player.addDeltaMovement(direction.scale(gain));
		clampToMaxSpeed(player);
		player.hurtMarked = true;   // 让客户端也吃到这份加速，不然会被客户端的移动预测抹掉
	}

	/** 把水平速度压到 {@link SkiControl#MAX_GLIDE_SPEED} 以内（垂直分量不动）。 */
	private static void clampToMaxSpeed(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();
		if (speed > SkiControl.MAX_GLIDE_SPEED) {
			double keep = SkiControl.MAX_GLIDE_SPEED / speed;
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

		double momentum = Math.max(MOMENTUM.getOrDefault(id, 0.0D), speed);
		momentum *= player.horizontalCollision ? MOMENTUM_DECAY_ON_HIT : MOMENTUM_DECAY;
		momentum = Math.min(momentum, SkiControl.MAX_GLIDE_SPEED);   // 速度上限

		if (momentum < MOMENTUM_MIN) {
			MOMENTUM.remove(id);
			return;
		}
		MOMENTUM.put(id, momentum);

		if (speed >= momentum) {
			return;   // 玩家自己滑得更快，不插手
		}
		Vec3 direction = steerDirection(player);   // 托速度时也允许用 WASD 转向，不然会像被锁住一样
		player.setDeltaMovement(direction.x * momentum, motion.y, direction.z * momentum);
		player.hurtMarked = true;
	}

	/** 雪 / 冰面上的推进：正在移动就沿移动方向补一点。 */
	private static void glidePush(ServerPlayer player) {
		if (!isOnSnowOrIce(player)) {
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() > MIN_MOTION_SQR) {
			Vec3 direction = new Vec3(motion.x, 0.0D, motion.z).normalize();
			player.addDeltaMovement(direction.scale(GLIDE_PUSH));
			player.hurtMarked = true;
		}
	}

	/** 加速方向：在滑行方向的基础上，按玩家按键转向（离地太高则锁死方向）。 */
	private static Vec3 steerDirection(ServerPlayer player) {
		return SkiControl.steer(travelDirection(player), player);
	}
	private static Vec3 travelDirection(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() > MIN_MOTION_SQR) {
			return new Vec3(motion.x, 0.0D, motion.z).normalize();
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

	private static void forget(int id) {
		LAST_Y.remove(id);
		DESCEND_ACCUM.remove(id);
		MOMENTUM.remove(id);
	}

	private static void forgetAll() {
		LAST_Y.clear();
		DESCEND_ACCUM.clear();
		MOMENTUM.clear();
	}
}
