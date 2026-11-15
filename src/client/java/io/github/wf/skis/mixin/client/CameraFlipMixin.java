package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkisMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>第一人称：相机随空翻一起翻滚</b>（两块板都有；第三人称那条链在 {@code EntityTiltMixin}，这里不动它）。
 *
 * <p><b>为什么挂在 {@code Camera#alignWithEntity(float)} 的 TAIL</b>：那正是「相机的位置与朝向
 * 这一帧已经算完」的最后一刻 —— 原版在方法体里 {@code setRotation(getViewYRot(pt), getViewXRot(pt))}
 * 把朝向写进 {@code rotation} 四元数（{@code setRotation} 里 {@code rotationYXZ(π − yaw, −xRot, 0)}
 * 并把 {@code matrixPropertiesDirty} 打脏，{@code getViewRotationMatrix} / {@code extractRenderState}
 * 随后都读它）。TAIL 再 {@code setRotation} 一次，位置不变、朝向多叠一层，后面的视锥 / 投影 / HUD
 * 全都拿到新朝向；同类的 {@code CameraTiltMixin}（单板 W / S 的位移修正）用的是这个方法里
 * {@code setPosition} 的参数，与这里互不覆盖 —— 本旋转是<b>叠加在它之后</b>的。
 *
 * <p><b>符号与 {@code EntityTiltMixin} 同一套</b>：那边 {@code poseStack.mulPose(Axis.XP.rotationDegrees(flip))}，
 * 即「绕玩家左右轴、正角＝身体向后翻」（局部 +z 指向背后，+X 旋转把 +Y 压向 +z）。
 * 相机这边 {@code xRot} 正值＝俯视，所以「身体向后翻 φ 度」对应 {@code xRot −= φ}：
 * φ=90 时相机朝天、φ=180 时倒过来朝身后、φ=270 时朝地 —— 与模型逐帧同向，不会一正一反。
 *
 * <p><b>只在第一人称、只在穿本模组的板、角度为 0 时一个字都不写</b>（0 就 return，
 * 不调 {@code setRotation}，以免平白打脏矩阵缓存）。不碰 FOV、不碰鼠标灵敏度、不写视角状态。
 */
@Mixin(Camera.class)
public abstract class CameraFlipMixin {
	/** 重算朝向（原版 {@code setRotation} 会一并更新四元数与矩阵脏标记）。 */
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	/** 当前俯仰（度，正＝朝下）。 */
	@Shadow
	public abstract float xRot();

	/** 当前偏航（度）。 */
	@Shadow
	public abstract float yRot();

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void skis$flipFirstPersonCamera(float partialTick, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (!client.options.getCameraType().isFirstPerson()) {
			return;                                  // 第三人称：EntityTiltMixin 已经转过 poseStack
		}
		Entity cameraEntity = ((Camera) (Object) this).entity();
		if (!(cameraEntity instanceof Player player)
				|| !SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;                                  // 没穿板（或相机跟的不是玩家）：原版视角
		}
		float flip = SkiAir.angleDegrees(player.getId(), partialTick);
		if (flip == 0.0F) {
			return;                                  // 没在翻：一个字都不写
		}
		this.setRotation(this.yRot(), this.xRot() - flip);
	}
}
