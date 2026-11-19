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
 * Cancels the walking camera bob (bobView) while wearing the ski board.
 *
 * <p>The vanilla walk bob is computed in GameRenderer#bobView from
 * {@code backwardsInterpolatedWalkDistance} / {@code bob} in the CameraRenderState (both derived from the
 * player's walk distance); cancelling the whole section while gliding keeps the view from bobbing up and down.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void skis$noWalkBob(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getItemBySlot(EquipmentSlot.FEET).getItem() == SkisMod.MODERN_SKI) {
			ci.cancel();
		}
	}
}
