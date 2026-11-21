package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 穿着滑雪板时取消走路的镜头晃动（bobView）。
 *
 * <p>vanilla 的行走晃动是 GameRenderer#bobView 用 CameraRenderState 里的
 * {@code backwardsInterpolatedWalkDistance} / {@code bob}（来自玩家行走距离）算出来的；
 * 滑行时整段取消，画面就不会再一颠一颠。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void skis$noWalkBob(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			ci.cancel();
		}
	}
}
