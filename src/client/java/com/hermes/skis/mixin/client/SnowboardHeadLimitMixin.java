package com.hermes.skis.mixin.client;

import com.hermes.skis.SnowboardControl;
import com.hermes.skis.client.SkiSteering;
import com.hermes.skis.client.SkiViewSmoothing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 单板：头（视角）相对<b>锁定的身体朝向</b>最多左右各 135 度（合计 270），扭不到背后去。
 *
 * <p>拦的是鼠标转视角的入口 {@code Entity#turn(double, double)} 的尾巴：转完把 yaw 夹回
 * {@code 身体朝向 ± 135}。身体朝向锁定不动，所以视角扫不到身后；脱下板就恢复自由。
 *
 * <p><b>基准用「插值后的身体朝向」</b>（{@code SkiViewSmoothing.renderBodyYaw}）：这种夹法每帧都会跑
 * （鼠标是每帧转的，不是每 tick），基准若用 tick 上的身体朝向，carve 把身体转过去之后这里会先夹到
 * 「新朝向 + 135」，下一帧再被按帧夹的那次往回拉 —— 镜头就在原地抖 2°。
 * 两边同一个基准，相机每帧看到的都是同一个值，不会互相拉回。
 */
@Mixin(Entity.class)
public abstract class SnowboardHeadLimitMixin {
	@Inject(method = "turn", at = @At("TAIL"))
	private void skis$limitHeadTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player) || !SnowboardControl.isSnowboard(player)) {
			return;
		}
		float bodyYaw = SkiViewSmoothing.renderBodyYaw(player.getId(), player.getYRot());
		float delta = Mth.wrapDegrees(player.getYRot() - bodyYaw);
		float limit = SnowboardControl.VIEW_LIMIT_DEGREES;
		if (Math.abs(delta) > limit) {
			SkiSteering.turnView(player, Mth.clamp(delta, -limit, limit) - delta);
		}
	}
}
