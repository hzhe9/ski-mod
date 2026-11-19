package com.hzhe9.skis.item;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ski Pole: right-click to plant the pole on the ground — pushes forward with a slight lift.
 * While already gliding (horizontal velocity underfoot) the push carries further:
 * the faster you glide, the further the pole pushes you.
 */
public class SkiPoleItem extends Item {
	private static final int COOLDOWN_TICKS = 16;      // 0.8 seconds
	private static final double PUSH_GLIDING = 0.85D;
	private static final double PUSH_IDLE = 0.45D;
	private static final double LIFT_GLIDING = 0.32D;
	private static final double LIFT_IDLE = 0.22D;
	/** Squared horizontal velocity threshold for "currently gliding". */
	private static final double GLIDING_MOTION_SQR = 0.010D;

	public SkiPoleItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (player.getCooldowns().isOnCooldown(stack)) {
			return InteractionResult.FAIL;
		}

		boolean gliding = isGliding(player);

		if (level.isClientSide()) {
			spawnSnowPuff(level, player, gliding ? 8 : 4);
		} else {
			Vec3 look = player.getLookAngle();
			double push = gliding ? PUSH_GLIDING : PUSH_IDLE;
			player.push(look.x * push, gliding ? LIFT_GLIDING : LIFT_IDLE, look.z * push);
			player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.SNOW_BREAK, SoundSource.PLAYERS,
					0.7F, gliding ? 1.7F : 1.2F);
			player.sendOverlayMessage(Component.literal(gliding
					? "§b[Ski Pole] §fPushed off — accelerating!"
					: "§b[Ski Pole] §7Pushed off the ground"));
		}

		return InteractionResult.SUCCESS;
	}

	/** Gliding = there is horizontal velocity underfoot (no longer relies on a speed potion effect). */
	private static boolean isGliding(Player player) {
		return player.getDeltaMovement().horizontalDistanceSqr() > GLIDING_MOTION_SQR;
	}

	private static void spawnSnowPuff(Level level, Player player, int count) {
		for (int i = 0; i < count; i++) {
			double dx = (level.getRandom().nextDouble() - 0.5D) * 0.6D;
			double dz = (level.getRandom().nextDouble() - 0.5D) * 0.6D;
			level.addParticle(ParticleTypes.SNOWFLAKE,
					player.getX() + dx, player.getY() + 0.1D, player.getZ() + dz,
					dx * 0.3D, 0.1D, dz * 0.3D);
		}
	}
}
