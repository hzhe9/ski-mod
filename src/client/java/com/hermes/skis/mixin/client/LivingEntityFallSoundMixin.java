package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisMod;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 穿着滑雪板落地时不发落地声（{@code LivingEntity#playBlockFallSound} 就是那声闷响）。
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
