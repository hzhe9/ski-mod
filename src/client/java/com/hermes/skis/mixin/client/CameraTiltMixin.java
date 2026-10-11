package com.hermes.skis.mixin.client;

import com.hermes.skis.SnowboardControl;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 单板：前后倾斜时让 <b>相机跟着头部（眼睛）的位置走</b>。
 *
 * <p>人绕脚底倾斜 θ 之后，眼睛的位置也从竖直的 {@code (0, 眼高, 0)} 转到了
 * {@code (前移 眼高·sinθ, 高度 眼高·cosθ)}，而原版相机的眼高永远是竖直的 ——
 * 于是“人倾了、镜头还留在原地”。这里在 {@code Camera#setup} 里把眼高那一段换成旋转后的向量，
 * 与 {@code EntityTiltMixin} 用同一个角度常量。
 *
 * <p>位置与朝向一起跟着头走：只动位置的话前移 0.4 格几乎看不出来，必须连视线俯仰一起跟上。
 */
@Mixin(Camera.class)
public abstract class CameraTiltMixin {
	/** 相机当前跟随的实体（Camera 的原字段，私有且没有 getter，只能影子进来）。 */
	@Shadow
	private Entity entity;

	@ModifyArg(
			method = "alignWithEntity",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/Camera;setPosition(Lnet/minecraft/world/phys/Vec3;)V"
			)
	)
	private Vec3 skis$followTiltedHead(Vec3 pos) {
		if (!(this.entity instanceof Player player) || !SnowboardControl.isSnowboard(player)) {
			return pos;
		}
		int tilt = SnowboardControl.tilt(player.getId());
		if (tilt == 0) {
			return pos;
		}
		double eye = player.getEyeHeight();
		double rad = Math.toRadians(tilt * SnowboardControl.tiltDegrees());
		double forward = eye * Math.sin(rad);        // 前倾时眼睛向前移这么多格
		double dy = eye * (Math.cos(rad) - 1.0D);    // 高度略降
		Vec3 front = SnowboardControl.front(player.getId(), player.getYRot());   // 沿锁定的身体朝向
		return pos.add(front.x * forward, dy, front.z * forward);
	}

	/**
	 * 视角的<b>朝向</b>也跟着倾：头随身体前倾 θ，视线同样前倾 θ（xRot 为正＝低头）。
	 * 与上面的位置偏移合起来，等于把相机整块挂到了倾斜后的头上。
	 */
	@ModifyArg(
			method = "alignWithEntity",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/Camera;setRotation(FF)V"
			),
			index = 1
	)
	private float skis$tiltView(float xRot) {
		if (!(this.entity instanceof Player player) || !SnowboardControl.isSnowboard(player)) {
			return xRot;
		}
		int tilt = SnowboardControl.tilt(player.getId());
		return tilt == 0 ? xRot : xRot + tilt * SnowboardControl.tiltDegrees();
	}
}
