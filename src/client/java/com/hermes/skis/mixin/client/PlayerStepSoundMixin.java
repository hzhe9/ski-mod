package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 双保险：直接从 {@code Player#playStepSound} 下手。
 *
 * <p>{@link EntityStepSoundMixin} 已经让「踩到方块」这个事件不再触发（连 PresenceFootsteps
 * 挂在 walkingStepSound 上的钩子一起哑），这里再把 Player 的脚步方法本身关掉，
 * 覆盖任何其他调用路径。
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
