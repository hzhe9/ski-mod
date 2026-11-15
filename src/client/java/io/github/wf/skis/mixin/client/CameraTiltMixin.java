package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SnowboardControl;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 *
 * <p><b>每帧只算一次</b>：偏移在 {@code alignWithEntity} 入口（{@link #skis$primeEyeOffset}）算一次、
 * 写进 {@link Unique} 字段，四个注入点只读字段。原来四个注入点各算一遍 —— 每帧固定 4 个
 * {@code double[]} + 4 个 {@code Vec3}（{@code frontLocked} 内部还会 new 一个）纯为拿同一组数。
 * 数值逐位不变：四个点读的是同一份 {@code tilt} / {@code eye} / {@code front}。
 */
@Mixin(Camera.class)
public abstract class CameraTiltMixin {
	/** 相机当前跟随的实体（Camera 的原字段：私有、没有 getter，只能影子进来）。 */
	@Shadow
	private Entity entity;

	/** 本帧的倾斜偏移 {dx, dy, dz}（格）：眼睛相对「竖直眼高位置」的增量。 */
	@Unique
	private double skis$offsetX;
	@Unique
	private double skis$offsetY;
	@Unique
	private double skis$offsetZ;
	/** 本帧偏移是否适用（没穿单板 / 没倾斜 ⇒ false，四个注入点原样放行入参）。 */
	@Unique
	private boolean skis$offsetValid;

	/**
	 * 每帧的入口：{@code Camera#alignWithEntity} 是摆放相机位置的那次调用（两个 setPosition 重载都在它里面），
	 * 所以在这里算一次，下面四个注入点读同一份结果。
	 */
	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void skis$primeEyeOffset(float partialTick, CallbackInfo ci) {
		this.skis$eyeOffset();
	}

	/** 算出本帧偏移写进 {@link Unique} 字段；不适用时只把 {@link #skis$offsetValid} 置假（零分配）。 */
	@Unique
	private void skis$eyeOffset() {
		this.skis$offsetValid = false;
		if (!(this.entity instanceof Player player) || !SnowboardControl.isSnowboard(player)) {
			return;
		}
		float tilt = SnowboardControl.tiltValue(player.getId(), true);
		if (tilt == 0.0F) {
			return;
		}
		double eye = player.getEyeHeight();
		double rad = Math.toRadians(tilt * SnowboardControl.tiltDegrees());
		double forward = eye * Math.sin(rad);        // 前倾时眼睛向前移这么多格
		double dy = eye * (Math.cos(rad) - 1.0D);    // 高度略降
		Vec3 front = SnowboardControl.frontLocked(player.getId(), player.getYRot(), true);   // 沿锁定的身体朝向，不是视角
		this.skis$offsetX = front.x * forward;
		this.skis$offsetY = dy;
		this.skis$offsetZ = front.z * forward;
		this.skis$offsetValid = true;
	}

	// —— 普通分支（第一人称 / 第三人称）：setPosition(double, double, double) ——
	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 0
	)
	private double skis$eyeX(double x) {
		return this.skis$offsetValid ? x + this.skis$offsetX : x;
	}

	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 1
	)
	private double skis$eyeY(double y) {
		return this.skis$offsetValid ? y + this.skis$offsetY : y;
	}

	@ModifyArg(
			method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"),
			index = 2
	)
	private double skis$eyeZ(double z) {
		return this.skis$offsetValid ? z + this.skis$offsetZ : z;
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
		return this.skis$offsetValid
				? pos.add(this.skis$offsetX, this.skis$offsetY, this.skis$offsetZ)
				: pos;
	}
}
