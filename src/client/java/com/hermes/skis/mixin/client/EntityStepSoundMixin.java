package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisMod;
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
 * 穿着滑雪板时不发脚步音效（滑行不该有脚步声）。
 *
 * <p>掐的是 {@code Entity#vibrationAndSoundEffectsFromBlock} 里对 {@code walkingStepSound} 的调用，
 * 而不是那个方法本身：PresenceFootsteps 是在 {@code walkingStepSound} 的 HEAD 注入
 * （cancel 原版音效、改放自己的脚步声），所以只有让这个调用根本不发生，
 * 才能把原版脚步和 PresenceFootsteps 的脚步声一起静音。
 *
 * <p>用 MixinExtras 的 {@code @WrapOperation}（加载器自带），它比 {@code @Redirect} 好在：
 * 保留原调用入口可随时 {@code original.call(...)}，不需要 {@code @Shadow} 私有方法。
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
				&& SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;
		}
		original.call(self, pos, state);
	}
}
