package com.hermes.skis.mixin.client;

import com.hermes.skis.SnowboardControl;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 单板：按 W / S 时 <b>视角也跟着前后倾</b>。
 *
 * <p>{@code Camera#setRotation(yRot, xRot)} 里 xRot 就是相机的俯仰（与身体倾斜同一个轴：绕左右轴），
 * 这里直接给第二个参数加一个偏移量，和 {@code EntityTiltMixin} 用同一个角度 ——
 * 身体倾多少，视线就跟着倾多少；鼠标转视角照旧。
 */
@Mixin(Camera.class)
public abstract class CameraTiltMixin {
	@ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, index = 2)
	private float skis$cameraTilt(float xRot) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !SnowboardControl.isSnowboard(player)) {
			return xRot;
		}
		int tilt = SnowboardControl.tilt(player.getId());
		return tilt == 0 ? xRot : xRot + tilt * SnowboardControl.tiltDegrees();
	}
}
