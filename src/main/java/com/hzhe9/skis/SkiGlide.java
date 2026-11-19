package com.hzhe9.skis;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 穿着滑雪板站在雪 / 冰上时的滑行惯性：每 0.5 秒检查一次，正在移动就沿移动方向补一点推进，
 * 做出「滑」的手感。
 *
 * <p>不给任何药水效果（早先那版的速度 buff 已按需求去掉）。
 */
public final class SkiGlide {
	private static final int REFRESH_INTERVAL_TICKS = 10;
	private static final double MIN_MOTION_SQR = 0.008D;
	private static final double GLIDE_PUSH = 0.055D;

	private SkiGlide() {
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(SkiGlide::onLevelTick);
	}

	private static void onLevelTick(ServerLevel level) {
		if (level.getGameTime() % REFRESH_INTERVAL_TICKS != 0L) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
			if (!SkisMod.isModernSki(feet) || !isOnSnowOrIce(level, player)) {
				continue;
			}
			Vec3 motion = player.getDeltaMovement();
			if (motion.horizontalDistanceSqr() > MIN_MOTION_SQR) {
				Vec3 direction = new Vec3(motion.x, 0.0D, motion.z).normalize();
				player.addDeltaMovement(direction.scale(GLIDE_PUSH));
			}
		}
	}

	/** 脚下（或身位）方块属于雪 / 冰标签即为雪面。 */
	private static boolean isOnSnowOrIce(ServerLevel level, ServerPlayer player) {
		BlockPos standing = player.blockPosition();
		BlockState below = level.getBlockState(standing.below());
		if (below.is(BlockTags.SNOW) || below.is(BlockTags.ICE)) {
			return true;
		}
		return level.getBlockState(standing).is(BlockTags.SNOW);
	}
}
