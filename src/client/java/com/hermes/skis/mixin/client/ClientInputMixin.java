package com.hermes.skis.mixin.client;

import com.hermes.skis.SnowboardControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 穿着单板时「完全站定」：移动向量一律清零（前后左右都没反应），空格也不能跳。
 *
 * <p>掐的是客户端输入本身（{@code ClientInput#getMoveVector} 的返回值），
 * 所以原版走路 / 疾跑 / 潜行加速全都不发生 —— 比在服务端压制速度干净，也不会和客户端移动预测互抢。
 */
@Mixin(ClientInput.class)
public abstract class ClientInputMixin {
	@Inject(method = "getMoveVector", at = @At("RETURN"), cancellable = true)
	private void skis$freezeMovement(CallbackInfoReturnable<Vec2> cir) {
		if (SnowboardControl.isSnowboard(Minecraft.getInstance().player)) {
			cir.setReturnValue(Vec2.ZERO);
		}
	}

	@Inject(method = "makeJump", at = @At("HEAD"), cancellable = true)
	private void skis$noJump(CallbackInfo ci) {
		if (SnowboardControl.isSnowboard(Minecraft.getInstance().player)) {
			ci.cancel();
		}
	}
}
