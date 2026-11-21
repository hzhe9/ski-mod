package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.hzhe9.skis.SkiAir;
import com.hzhe9.skis.SnowboardControl;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 单板：按 W / S 时 <b>整个人连同脚上的板</b> 一起前倾 / 后仰。
 *
 * <p>为什么在渲染层而不是模型里：脚上的板是 Fabric {@code ArmorRenderer} 独立提交的模型，
 * 只改 {@code HumanoidModel} 的部件它不会跟着；而 {@code setupRotations} 的 TAIL 正好是
 * “poseStack 已定位到实体（原点＝脚底）、朝向也转好、护甲层还没画”的位置 ——
 * {@code LivingEntityRenderer#submit} 里模型与所有 RenderLayer（含我们的板）共用这一个 poseStack。
 *
 * <p>绕的是局部 X 轴（玩家左右轴）＝前后倾；此处局部 +z 指向玩家背后，所以
 * <b>负角 = 前倾</b>（W），正角 = 后仰（S）。
 *
 * <p><b>插值粒度</b>：倾斜角读的是 {@code SnowboardControl.tiltValue(id)} —— 它由 {@code tickTilt}
 * 按 tick 平滑推进，<b>帧间不做 {@code partialTick} 插值</b>（有意：倾斜本身是 15° 的慢变化，
 * 每 tick 25% 的渐进已经够顺；空翻那条走 {@code SkiAir.angleDegrees(id, partialTick)} 才需要帧间插值，
 * 因为 16°/tick 太快、不插值会看到台阶）。掉帧 / 暂停时两者的相位会差半个 tick，这是已知且可接受的。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityTiltMixin {
	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void skis$boardTilt(
			LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale, CallbackInfo ci) {
		if (!(state instanceof AvatarRenderState avatar)
				|| !SkisMod.isSkiFeet(avatar.feetEquipment)) {
			return;
		}
		if (avatar.feetEquipment.getItem() == SkisMod.SNOWBOARD) {
			// 单板 W / S 的前后倾（双板没有这一项）
			float tilt = SnowboardControl.tiltValue(avatar.id, true);
			if (tilt != 0.0F) {
				poseStack.mulPose(Axis.XP.rotationDegrees(-tilt * SnowboardControl.tiltDegrees()));
			}
		}
		// 后空翻：整个人 + 板一起，绕腰部高度向后翻（两块板都有；角度按 partialTick 插值）
		float flip = SkiAir.angleDegrees(avatar.id, partialTick());
		if (flip == 0.0F) {
			return;
		}
		poseStack.translate(0.0D, SkiAir.FLIP_PIVOT_HEIGHT, 0.0D);
		poseStack.mulPose(Axis.XP.rotationDegrees(flip));
		poseStack.translate(0.0D, -SkiAir.FLIP_PIVOT_HEIGHT, 0.0D);
	}

	/** 本帧的 partialTick（与相机 / 实体插值用的是同一个）。 */
	private static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}
}
