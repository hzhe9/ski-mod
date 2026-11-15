package io.github.wf.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 撑杖冲刺（变体版）：右键把速度抬高一截，然后在 {@link #DURATION_TICKS} 内平滑回到
 * <b>右键当时的那个速度</b>——像滑翔时用烟花那样“先冲出去、再慢慢滑停”，
 * 结束时正好是原速，不会比原速慢（终值就是 from，由构造保证）。
 *
 * <p>冲刺期间方向不锁死：按 WASD 会让冲刺方向逐渐转到你要去的方向，松开方向键则沿当前方向继续滑。
 * 转向规则统一在 {@link SkiControl}（离地超过一格时锁方向）。连着右键 = 以“当时的速度”为基准再抬一次。
 *
 * <p>可调旋钮：{@link #ADD}（抬高的量）、{@link #MIN_PEAK}（原地撑的起步下限）、
 * {@link #DURATION_TICKS}（持续多久）、{@link #CURVE}（衰减曲线，越大越“先猛后缓”）。
 */
public final class SkiBoost {
	/** 每次右键在“当前速度”之上抬高的量（格/tick）。 */
	private static final double ADD = 0.06D;   // 单次抬升量：原 0.1 一撑就窜出去，0.06 更像一次撑地的推力
	/** 起步速度下限：站着原地撑也能冲出去。 */
	private static final double MIN_PEAK = 0.32D;   // 与 ADD 一起下调，杖的冲刺不再那么猛
	/** 冲刺持续 tick 数（60 tick = 3 秒，后段缓慢减速）。 */
	private static final int DURATION_TICKS = 60;
	/** 衰减曲线指数：1.0 = 匀速线性缓降（后段一样慢）；越大越“先猛后缓”。 */
	private static final double CURVE = 1.0D;
	/** 地图上残留状态的清理阈值。 */
	private static final int MAX_ENTRIES = 64;

	/** 玩家实体 id → 冲刺状态。 */
	private static final Map<Integer, Boost> ACTIVE = new ConcurrentHashMap<>();
	/** 玩家实体 id → 还差几 tick 才真正发力（按右键后要先举杖、再点下去）。 */
	private static final Map<Integer, Integer> PENDING = new ConcurrentHashMap<>();
	/**
	 * 按右键到真正加速之间的延迟（tick）。
	 *
	 * <p>动画总长 {@code SkiState.SWING_TICKS} = 12 tick，这里等 5 tick 再给速度 ——
	 * 举手到点地的那一小段过去之后发力，动作和冲劲才对得上。
	 */
	private static final int DELAY_TICKS = 5;

	/** direction 是当前冲刺方向（水平单位向量）；from 是右键当时的速度（要回到的目标）；peak 是抬到多快。 */
	private record Boost(Vec3 direction, double from, double peak, int ticksLeft) {
	}

	private SkiBoost() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ACTIVE.isEmpty() && PENDING.isEmpty()) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.getAbilities().flying) {
					// 创造飞行：已有冲刺 / 待出力一律清掉，也不发力 —— 飞行完全按原版走。
					PENDING.remove(player.getId());
					ACTIVE.remove(player.getId());
					continue;
				}
				// 先处理“举手 → 点下去”的延迟：时间到才真正发力
				Integer pending = PENDING.get(player.getId());
				if (pending != null) {
					if (pending <= 1) {
						PENDING.remove(player.getId());
						applyBurst(player);
					} else {
						PENDING.put(player.getId(), pending - 1);
					}
				}

				Boost boost = ACTIVE.get(player.getId());
				if (boost == null) {
					continue;
				}
				if (boost.ticksLeft() <= 0) {
					// 冲刺结束：不再插手，速度交还给玩家（此时已经回到 from）
					ACTIVE.remove(player.getId());
					continue;
				}

				double progress = 1.0D - boost.ticksLeft() / (double) DURATION_TICKS;   // 0 → 1
				double speed = Math.min(
						boost.from() + (boost.peak() - boost.from()) * Math.pow(1.0D - progress, CURVE),
						SkiControl.maxGlideSpeed(player));

				// 方向：按了 WASD 就朝那个方向转过去（离地超过一格则锁死），松手就沿当前方向继续滑
				Vec3 direction = SkiControl.steer(boost.direction(), player);

				Vec3 motion = player.getDeltaMovement();
				player.setDeltaMovement(direction.x * speed, motion.y, direction.z * speed);
				player.hurtMarked = true;   // 把速度同步给客户端，否则客户端会和你“抢”速度（橡皮筋）

				ACTIVE.put(player.getId(), new Boost(direction, boost.from(), boost.peak(), boost.ticksLeft() - 1));
			}
			if (ACTIVE.size() > MAX_ENTRIES) {
				ACTIVE.clear();
			}
			if (PENDING.size() > MAX_ENTRIES) {
				// PENDING 也是「id → 状态」的表：不封顶的话，连点 / 作弊客户端能把表撑大，
				// 而且退出游戏的玩家不会被 tick 到、那条记录永远不会自己被清（见下面的 DISCONNECT）。
				PENDING.clear();
			}
		});

		// 断线就把这个人的两份状态一起丢掉：两张表都只在「玩家在线且被 tick 到」时才递减 / 移除，
		// 退出的玩家会留下一条永久记录；id 一旦被新玩家复用，那个人会白拿一次冲刺。
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			int id = handler.getPlayer().getId();
			PENDING.remove(id);
			ACTIVE.remove(id);
		});
	}

	/**
	 * 右键一次：<b>先举手</b>，{@link #DELAY_TICKS} tick 之后（动画里杖点下去那一刻）才真正发力。
	 */
	public static void burst(Player player) {
		if (player.getAbilities().flying) {
			return;   // 创造飞行：不进 pending，也不给任何冲量（手感完全按原版飞行）
		}
		if (SnowboardControl.isSnowboard(player)) {
			return;   // 单板站定：撑杖也不给冲刺
		}
		PENDING.put(player.getId(), DELAY_TICKS);
	}

	/**
	 * 延迟结束后的实际发力：以当前速度为基准抬高一截，随后在 DURATION_TICKS 内回到这个基准速度。
	 * 峰值封顶在各板自己的速度上限（{@link SkiControl#maxGlideSpeed(ServerPlayer)}）。
	 */
	private static void applyBurst(ServerPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		double current = horizontal.length();

		Vec3 desired = SkiControl.desiredDirection(player);
		Vec3 direction = desired != null ? desired
				: (current > 0.01D ? horizontal.normalize() : horizontalLook(player));

		double peak = Math.min(Math.max(current + ADD, MIN_PEAK), SkiControl.maxGlideSpeed(player));
		// 垂直分量原样保留：撑杖只给水平推力，不会把人蹦起来（原先 +LIFT 会时不时跳一下）
		player.setDeltaMovement(direction.x * peak, motion.y, direction.z * peak);
		player.hurtMarked = true;

		ACTIVE.put(player.getId(), new Boost(direction, current, peak, DURATION_TICKS));
	}

	/** 站着撑杖时用视线方向（水平投影）当冲刺方向。 */
	private static Vec3 horizontalLook(Player player) {
		Vec3 look = player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
		return horizontal.length() > 0.01D ? horizontal.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
	}
}
