package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No landing sound while wearing the ski board ({@code LivingEntity#playBlockFallSound} is that dull thud).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallSoundMixin {
	@Inject(method = "playBlockFallSound", at = @At("HEAD"), cancellable = true)
	private void skis$silenceLanding(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof Player player
				&& player.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.MODERN_SKI) {
			ci.cancel();
		}
	}
}
