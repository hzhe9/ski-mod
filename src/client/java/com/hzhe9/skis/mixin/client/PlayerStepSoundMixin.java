package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second safety net: hook {@code Player#playStepSound} directly.
 *
 * <p>{@link EntityStepSoundMixin} already stops the "stepped on a block" event from firing (silencing the
 * PresenceFootsteps hook on walkingStepSound along with it); here the Player step-sound method itself is
 * turned off as well, covering any other call path.
 */
@Mixin(Player.class)
public abstract class PlayerStepSoundMixin {
	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void skis$silenceStep(BlockPos pos, BlockState state, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.MODERN_SKI) {
			ci.cancel();
		}
	}
}
