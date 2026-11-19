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
 * Gliding inertia while wearing the skis and standing on snow / ice: checked every 0.5 seconds;
 * if the player is moving, a bit of push is added along the movement direction to give the
 * "sliding" feel.
 *
 * <p>No potion effects are applied (the speed buff of the earlier version was removed on request).
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

	/** Snow surface = the block underfoot (or at the body position) is in the snow / ice tags. */
	private static boolean isOnSnowOrIce(ServerLevel level, ServerPlayer player) {
		BlockPos standing = player.blockPosition();
		BlockState below = level.getBlockState(standing.below());
		if (below.is(BlockTags.SNOW) || below.is(BlockTags.ICE)) {
			return true;
		}
		return level.getBlockState(standing).is(BlockTags.SNOW);
	}
}
