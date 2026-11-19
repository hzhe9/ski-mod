package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No step sound while wearing the ski board (gliding should not sound like walking).
 *
 * <p>This targets the call to {@code walkingStepSound} inside
 * {@code Entity#vibrationAndSoundEffectsFromBlock} rather than the method itself: PresenceFootsteps
 * injects at the HEAD of {@code walkingStepSound} (cancelling the vanilla sound and playing its own
 * footstep instead), so only by keeping this call from happening at all can both the vanilla footsteps
 * and the PresenceFootsteps sounds be silenced.
 *
 * <p>It uses MixinExtras' {@code @WrapOperation} (bundled with the loader), which beats
 * {@code @Redirect} because the original call site stays available for {@code original.call(...)} at
 * any time and no {@code @Shadow} private method is needed.
 */
@Mixin(Entity.class)
public abstract class EntityStepSoundMixin {
	@WrapOperation(
			method = "vibrationAndSoundEffectsFromBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Entity;walkingStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
			)
	)
	private void skis$silenceStepSound(Entity self, BlockPos pos, BlockState state, Operation<Void> original) {
		if (self instanceof Player player
				&& player.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.MODERN_SKI) {
			return;
		}
		original.call(self, pos, state);
	}
}
