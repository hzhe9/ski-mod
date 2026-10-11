package com.hermes.skis.mixin.client;

import com.hermes.skis.SnowboardControl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 单板：头（视角）相对<b>锁定的身体朝向</b>最多左右各 90 度（合计 180），扭不到背后去。
 *
 * <p>拦的是鼠标转视角的入口 {@code Entity#turn(double, double)} 的尾巴：转完把 yaw 夹回
 * {@code 身体朝向 ± 90}。身体朝向锁定不动，所以视角扫不到身后；脱下板就恢复自由。
 */
@Mixin(Entity.class)
public abstract class SnowboardHeadLimitMixin {
	@Inject(method = "turn", at = @At("TAIL"))
	private void skis$limitHeadTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player) || !SnowboardControl.isSnowboard(player)) {
			return;
		}
		float bodyYaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
		float delta = Mth.wrapDegrees(player.getYRot() - bodyYaw);
		float limit = SnowboardControl.VIEW_LIMIT_DEGREES;
		if (Math.abs(delta) > limit) {
			player.setYRot(bodyYaw + Mth.clamp(delta, -limit, limit));
		}
	}
}
