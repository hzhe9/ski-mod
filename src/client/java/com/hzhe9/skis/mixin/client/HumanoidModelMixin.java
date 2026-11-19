package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.hzhe9.skis.client.SkiStance;
import com.hzhe9.skis.client.SkiState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 穿着滑雪板时的姿态：
 *
 * <ul>
 *   <li>双腿：取消走路摆腿、不做外撇，只把左右腿各向外平移 {@link SkiStance#LEG_SPREAD}
 *       （脚上的板模型用同一个量，跟着分开、平行）；</li>
 *   <li>双臂：同相在体前划动（撑杖 / 划船感），幅度跟移动速度；</li>
 *   <li><b>滞空</b>：手臂停在离地前最后一帧的姿势不动（不摆、也不回默认位），落地后继续摆；</li>
 *   <li>身体：轻微前倾。</li>
 * </ul>
 *
 * 「是否在地面」不读渲染状态，而是 {@link SkiState}（客户端 tick 每 tick 从真实实体记录）——
 * 渲染状态实例每帧重建，挂在它上面的字段不保证被 setupAnim 看到（这是之前在两种表现之间来回跳的根因）。
 *
 * 游泳 / 鞘翅 / 骑乘时保持原版姿势。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	/** 屈膝角度（弧度）。 */
	private static final float GLIDE_KNEE_BEND = 0.08F;
	/** 腿部枢轴的原始高度。 */
	private static final float LEG_PIVOT_Y = 12.0F;
	/** 手臂划动中心（体前抬起角，越负抬得越高）。 */
	private static final float ROW_CENTER = -0.70F;
	/** 手臂划动幅度。 */
	private static final float ROW_AMPLITUDE = 0.95F;
	/** 划动频率（乘在 walkAnimationPos 上，越小越慢）。 */
	private static final float ROW_PHASE = 0.4F;
	/** 滑行前倾。 */
	private static final float GLIDE_LEAN = 0.05F;
	/** 手臂向外的滚转角（正负对称，保证两根滑雪杖平行、不向内倾斜）。 */
	private static final float ARM_OUTWARD_ROLL = 0.10F;

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void skis$applyGlideStance(HumanoidRenderState state, CallbackInfo ci) {
		if (state.feetEquipment.getItem() != SkisMod.MODERN_SKI) {
			return;
		}
		if (state.isVisuallySwimming || state.isFallFlying || state.isPassenger) {
			return;
		}

		HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;

		// 双腿：只横向拉开、保持平行
		placeLeg(self.rightLeg, -SkiStance.legOffsetX());
		placeLeg(self.leftLeg, SkiStance.legOffsetX());

		// 双臂：同相划动；滞空或“刚落地还没站稳”时沿用离地前最后一帧的幅度（停住）
		if (!state.isUsingItem) {
			float amplitude = Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F) / Math.max(0.5F, state.speedValue);
			float stroke = Mth.cos(state.walkAnimationPos * ROW_PHASE) * ROW_AMPLITUDE * amplitude;
			if (state instanceof AvatarRenderState avatar) {
				stroke = SkiState.stablyGrounded(avatar.id)
						? SkiState.rememberStroke(avatar.id, stroke)
						: SkiState.recallStroke(avatar.id, stroke);
			}
			self.rightArm.xRot = ROW_CENTER + stroke;
			self.leftArm.xRot = ROW_CENTER + stroke;
			// 手端稍稍向外（正负对称）→ 垂下来的两根杖是平行线，不会向内倾、穿到腿上
			self.rightArm.yRot = 0.0F;
			self.leftArm.yRot = 0.0F;
			self.rightArm.zRot = ARM_OUTWARD_ROLL;
			self.leftArm.zRot = -ARM_OUTWARD_ROLL;
		}

		// 身体前倾（潜行时保持原版的弯腰）
		if (!state.isCrouching) {
			self.body.xRot = GLIDE_LEAN;
		}
	}

	/** 把一条腿摆到「向外平移后的位置」，朝向全部归零（平行）。 */
	private static void placeLeg(ModelPart leg, float x) {
		leg.x = x;
		leg.y = LEG_PIVOT_Y;
		leg.z = 0.0F;
		leg.xRot = GLIDE_KNEE_BEND;
		leg.yRot = 0.0F;
		leg.zRot = 0.0F;
	}
}
