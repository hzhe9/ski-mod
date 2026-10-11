package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisMod;
import com.hermes.skis.client.SkiStance;
import com.hermes.skis.client.SkiState;
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
 * 变体版（右键冲刺）姿态：
 *
 * <ul>
 *   <li>双腿：取消走路摆腿、不做外撇，只把左右腿各向外平移 {@link SkiStance#LEG_SPREAD}
 *       （脚上的板模型用同一个量，跟着分开、平行）；</li>
 *   <li>双臂：<b>静止</b>固定在划动的中间位置，不随移动摆动；</li>
 *   <li>右键撑杖时播放<b>一次</b>摆臂（前摆再收回，约 0.6 秒），由 {@link SkiState} 的倒计时驱动；</li>
 *   <li>身体：轻微前倾。</li>
 * </ul>
 *
 * 游泳 / 鞘翅 / 骑乘时保持原版姿势。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	/** 屈膝角度（弧度）。 */
	private static final float GLIDE_KNEE_BEND = 0.08F;
	/** 腿部枢轴的原始高度。 */
	private static final float LEG_PIVOT_Y = 12.0F;
	/** 划动中心角度（最高处与最低处由它加减幅度得到）。 */
	private static final float ROW_CENTER = -0.35F;
	/** 划动幅度：最高处 = ROW_CENTER - 幅度（前摆到底），最低处 = ROW_CENTER + 幅度（后摆到底）。 */
	private static final float ROW_AMPLITUDE = 0.95F;
	/** 最高处：手臂静止时停在的位置（准备撑杖）。 */
	private static final float ARM_TOP = ROW_CENTER - ROW_AMPLITUDE;
	/** 最低处：一次撑杖摆到的地方。 */
	private static final float ARM_BOTTOM = ROW_CENTER + ROW_AMPLITUDE;
	/** 下摆占整段动画的比例，剩下的时间收回最高处。 */
	private static final float DOWN_FRACTION = 0.6F;
	/** 滑行前倾。 */
	private static final float GLIDE_LEAN = 0.05F;
	/** 手臂向外的滚转角（正负对称，保证两根滑雪杖平行、不向内倾斜）。 */
	private static final float ARM_OUTWARD_ROLL = 0.10F;
	/** 单板滑行时空着的那只手的固定前抬角（弧度）：0 = 垂在身侧，负值 = 向前抬。 */
	private static final float FREE_ARM_XROT = -0.25F;
	/** 单板滑行时空着的那只手向外张开的滚转角。 */
	private static final float FREE_ARM_ROLL = 0.15F;

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void skis$applyGlideStance(HumanoidRenderState state, CallbackInfo ci) {
		if (!SkisMod.isSkiFeet(state.feetEquipment)) {
			return;
		}
		if (state.isVisuallySwimming || state.isFallFlying || state.isPassenger) {
			return;
		}

		HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;

		boolean board = state.feetEquipment.getItem() == SkisMod.SNOWBOARD;
		if (board) {
			// 单板：与双板一样「两腿平行、向外岔开」——不做鸭子站姿、不前后错位
			// （横向偏移与 SnowboardModel 的鞋位同值，否则脚会与鞋错开）
			placeLeg(self.rightLeg, -SkiStance.BOARD_LEG_SPREAD);
			placeLeg(self.leftLeg, SkiStance.BOARD_LEG_SPREAD);
		} else {
			// 双板：两腿只横向拉开、保持平行
			placeLeg(self.rightLeg, -SkiStance.legOffsetX());
			placeLeg(self.leftLeg, SkiStance.legOffsetX());
		}

		// 手臂：只有拿着滑雪杖的那只手才抬起来摆（左手持杖抬左手、右手持杖抬右手；只穿板不抬）
		if (!state.isUsingItem) {
			float armRot = ARM_TOP;
			if (state instanceof AvatarRenderState avatar) {
				int left = SkiState.swingLeft(avatar.id);
				if (left > 0) {
					float progress = (SkiState.swingTicks() - left) / (float) SkiState.swingTicks();   // 0 → 1
					if (progress < DOWN_FRACTION) {
						// 下摆：从最高处一口气压到最后处（先快后慢）
						float t = progress / DOWN_FRACTION;
						armRot = Mth.lerp(1.0F - (1.0F - t) * (1.0F - t), ARM_TOP, ARM_BOTTOM);
					} else {
						// 收回：平滑地回到最高处，衔接下一次撑杖
						float t = (progress - DOWN_FRACTION) / (1.0F - DOWN_FRACTION);
						armRot = Mth.lerp(t * t * (3.0F - 2.0F * t), ARM_BOTTOM, ARM_TOP);
					}
				}
			}
			// 手端稍稍向外（正负对称）→ 垂下来的杖是平行线，不会向内倾、穿到腿上
			if (state.rightHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.rightArm, armRot, ARM_OUTWARD_ROLL);
			} else if (board) {
				// 单板：没拿杖的手也要固定住，否则会走原版的走路摆臂（看起来像在走路）
				poseArm(self.rightArm, FREE_ARM_XROT, FREE_ARM_ROLL);
			}
			if (state.leftHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.leftArm, armRot, -ARM_OUTWARD_ROLL);
			} else if (board) {
				poseArm(self.leftArm, FREE_ARM_XROT, -FREE_ARM_ROLL);
			}
		}

		// 身体前倾（潜行时保持原版的弯腰）
		if (!state.isCrouching) {
			self.body.xRot = GLIDE_LEAN;
		}
	}

	/** 把一条手臂摆成撑杖姿势：xRot 是划动角，zRot 控制手端朝外还是朝内。 */
	private static void poseArm(ModelPart arm, float xRot, float zRot) {
		arm.xRot = xRot;
		arm.yRot = 0.0F;
		arm.zRot = zRot;
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
