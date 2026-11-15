package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SnowboardControl;
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
			float tilt = SnowboardControl.tiltValue(avatar.id);
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
