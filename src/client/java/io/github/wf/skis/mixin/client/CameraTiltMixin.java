package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SnowboardControl;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 单板：前后倾斜时把<b>相机整块挂到倾斜后的头上</b> —— 只跟<b>位置</b>，不跟<b>朝向</b>。
 *
 * <p>不跟位置的后果很具体：头（眼睛）绕脚底前移了，相机还在竖直眼高那点 —— 第一人称一低头
 * 就看见自己的后背（First-person Model 会渲染身体，而身体已经倾了）。
 *
 * <p><b>朝向刻意不跟</b>：本 mixin 只 {@code @ModifyArg} 了 {@code setPosition} 的两个重载，
 * <b>没有任何注入去改 {@code xRot}</b>。前倾时视线仍水平 —— 位置贴着倾斜后的头走、画面不跟着仰/俯，
 * 玩家看到的是「人俯下去、视野还是正前方」（否则鼠标与视角之间就多了一层随倾角变化的偏转，很难受）。
 *
 * <p>⚠ {@code Camera#alignWithEntity} 里有<b>两个</b> setPosition 重载，走哪个取决于分支：
 * 普通（含第一人称/第三人称）走 {@code setPosition(double, double, double)}，
 * 骑矿车分支才走 {@code setPosition(Vec3)} —— 两个都要拦，不然最常见的路径完全没效果。
 * 位置 = 竖直眼高向量绕脚底转 θ（前移 眼高·sinθ、高度 眼高·cosθ）。
 */
@Mixin(Camera.class)
public abstract class CameraTiltMixin {
	/** 相机当前跟随的实体（Camera 的原字段：私有、没有 getter，只能影子进来）。 */
	@Shadow
	private Entity entity;

	/** 倾斜后眼睛相对“竖直眼高位置”的偏移 {dx, dy, dz}（格）；不适用时返回 null。 */
	private double[] skis$eyeOffset() {
		if (!(this.entity instanceof Player player) || !SnowboardControl.isSnowboard(player)) {
			return null;
		}
		float tilt = SnowboardControl.tiltValue(player.getId(), true);
		if (tilt == 0.0F) {
			return null;
		}
		double eye = player.getEyeHeight();
		double rad = Math.toRadians(tilt * SnowboardControl.tiltDegrees());
		double forward = eye * Math.sin(rad);        // 前倾时眼睛向前移这么多格
		double dy = eye * (Math.cos(rad) - 1.0D);    // 高度略降
		Vec3 front = SnowboardControl.frontLocked(player.getId(), player.getYRot(), true);   // 沿锁定的身体朝向，不是视角
		return new double[] {front.x * forward, dy, front.z * forward};
	}

	// —— 普通分支（第一人称 / 第三人称）：setPosition(double, double, double) ——
	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 0
	)
	private double skis$eyeX(double x) {
		double[] offset = this.skis$eyeOffset();
		return offset == null ? x : x + offset[0];
	}

	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 1
	)
	private double skis$eyeY(double y) {
		double[] offset = this.skis$eyeOffset();
		return offset == null ? y : y + offset[1];
	}

	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 2
	)
	private double skis$eyeZ(double z) {
		double[] offset = this.skis$eyeOffset();
		return offset == null ? z : z + offset[2];
	}

	// —— 骑矿车分支：setPosition(Vec3) ——
	@ModifyArg(
			method = "alignWithEntity",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/Camera;setPosition(Lnet/minecraft/world/phys/Vec3;)V"
			)
	)
	private Vec3 skis$eyeVec(Vec3 pos) {
		double[] offset = this.skis$eyeOffset();
		return offset == null ? pos : pos.add(offset[0], offset[1], offset[2]);
	}
}
