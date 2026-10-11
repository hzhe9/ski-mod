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
	/**
	 * 双板：腿（身体下半部分）往后倾出去的角度（弧度，负值＝往后）——
	 * 上半身由 {@link #GLIDE_LEAN} 前倾，合起来就是“上身压在前、下身留在后”的滑行姿势。
	 */
	private static final float SKI_LEG_TILT = -0.15F;
	/** 腿部枢轴的原始高度。 */
	private static final float LEG_PIVOT_Y = 12.0F;
	/** 划动中心角度（最高处与最低处由它加减幅度得到）。 */
	private static final float ROW_CENTER = -0.35F;
	/** 划动幅度：最高处 = ROW_CENTER - 幅度（前摆到底），最低处 = ROW_CENTER + 幅度（后摆到底）。 */
	private static final float ROW_AMPLITUDE = 0.95F;
	/** 最高处（举起到底）。 */
	private static final float ARM_TOP = ROW_CENTER - ROW_AMPLITUDE;
	/** 静止时手杖停的位置：不要举到最高，往下放一点。 */
	private static final float ARM_REST = -0.55F;
	/** 右键动画里“先举到最高”这一段占的比例，剩下的时间才往下摆、再收回。 */
	private static final float RAISE_FRACTION = 0.25F;   // 举起段占比（用户定 25%）
	/** 最低处：一次撑杖摆到的地方。 */
	private static final float ARM_BOTTOM = ROW_CENTER + ROW_AMPLITUDE;
	/** 下摆占整段动画的比例，剩下的时间收回最高处。 */
	private static final float DOWN_FRACTION = 0.6F;
	/** 滑行前倾。 */
	private static final float GLIDE_LEAN = 0.05F;
	/** 手臂向外的滚转角（正负对称，保证两根滑雪杖平行、不向内倾斜）。 */
	private static final float ARM_OUTWARD_ROLL = 0.10F;
	/** 单板滑行时两只手的前后角（弧度）：0 = 垂在身侧，负值 = 向前抬。不要前抬，保持 0。 */
	private static final float FREE_ARM_XROT = 0.0F;
	/**
	 * 单板滑行时两只手**向两侧微微抬起**的角度（绕前后轴，弧度）——
	 * 右臂取 +、左臂取 -，于是两只手对称地离开身体侧面。0.28 rad ≈ 16°，微微抬起。
	 */
	private static final float FREE_ARM_ROLL = 0.28F;
	/**
	 * <b>双板</b>空手（没拿滑雪杖）时两臂**向前微微抬起**的角度（弧度）——
	 * 负值 = 向前抬（与 {@link #ARM_TOP} 同一个方向约定）。0.25 rad ≈ 14°。
	 */
	private static final float SKI_FREE_ARM_XROT = -0.25F;
	/** 双板空手时两臂**向两侧微微抬起**的角度（弧度，右臂 + / 左臂 -）。 */
	private static final float SKI_FREE_ARM_ROLL = 0.28F;

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
			placeLeg(self.rightLeg, -SkiStance.BOARD_LEG_SPREAD, GLIDE_KNEE_BEND);
			placeLeg(self.leftLeg, SkiStance.BOARD_LEG_SPREAD, GLIDE_KNEE_BEND);
		} else {
			// 双板：两腿只横向拉开、保持平行；下半身往后倾出去一点（SKI_LEG_TILT）
			placeLeg(self.rightLeg, -SkiStance.legOffsetX(), SKI_LEG_TILT);
			placeLeg(self.leftLeg, SkiStance.legOffsetX(), SKI_LEG_TILT);
		}

		// 手臂：只有拿着滑雪杖的那只手才抬起来摆（左手持杖抬左手、右手持杖抬右手；只穿板不抬）
		if (!state.isUsingItem) {
			float armRot = ARM_REST;   // 默认：杖放低一点（不举到最高）
			if (state instanceof AvatarRenderState avatar) {
				int left = SkiState.swingLeft(avatar.id);
				if (left > 0) {
					// 一次撑杖：0 → RAISE_FRACTION 先举到最高，然后往下摆，最后收回 ARM_REST
					float progress = (SkiState.swingTicks() - left) / (float) SkiState.swingTicks();   // 0 → 1
					float downEnd = RAISE_FRACTION + (1.0F - RAISE_FRACTION) * DOWN_FRACTION;
					if (progress < RAISE_FRACTION) {
						float t = progress / RAISE_FRACTION;
						armRot = Mth.lerp(t * t * (3.0F - 2.0F * t), ARM_REST, ARM_TOP);   // 举起（平滑）
					} else if (progress < downEnd) {
						float t = (progress - RAISE_FRACTION) / (downEnd - RAISE_FRACTION);
						armRot = Mth.lerp(t * t, ARM_TOP, ARM_BOTTOM);   // 下摆：前半程慢、过半后加速扎下去（点杖）
					} else {
						float t = (progress - downEnd) / (1.0F - downEnd);
						armRot = Mth.lerp(t * t * (3.0F - 2.0F * t), ARM_BOTTOM, ARM_REST);   // 收回
					}
				}
			}
			// 手端稍稍向外（正负对称）→ 垂下来的杖是平行线，不会向内倾、穿到腿上
			if (state.rightHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.rightArm, armRot, ARM_OUTWARD_ROLL);
			} else {
				// 没拿杖的手：抬起（双板＝向前 + 向两侧，单板＝只向两侧），否则会走原版的走路摆臂
				poseArm(self.rightArm, freeArmXrot(board), freeArmRoll(board));
			}
			if (state.leftHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.leftArm, armRot, -ARM_OUTWARD_ROLL);
			} else {
				poseArm(self.leftArm, freeArmXrot(board), -freeArmRoll(board));
			}
		}

		// 身体前倾（潜行时保持原版的弯腰）。
		// 单板按 W / S 的“整个人 + 板一起倾”不在模型里做 —— 板是独立提交的模型，只改部件它不跟，
		// 统一在 EntityTiltMixin 里转整个 poseStack。
		if (!state.isCrouching) {
			self.body.xRot = GLIDE_LEAN;
		}
	}

	/** 空手时两臂的前后角：单板不前抬（0），双板向前微抬。 */
	private static float freeArmXrot(boolean board) {
		return board ? FREE_ARM_XROT : SKI_FREE_ARM_XROT;
	}

	/** 空手时两臂向两侧抬起的角度（右臂取正、左臂取负）。 */
	private static float freeArmRoll(boolean board) {
		return board ? FREE_ARM_ROLL : SKI_FREE_ARM_ROLL;
	}

	/** 把一条手臂摆成撑杖姿势：xRot 是划动角，zRot 控制手端朝外还是朝内。 */
	private static void poseArm(ModelPart arm, float xRot, float zRot) {
		arm.xRot = xRot;
		arm.yRot = 0.0F;
		arm.zRot = zRot;
	}

	/** 把一条腿摆到「向外平移后的位置」，朝向全部归零（平行）；xRot 决定前后倾。 */
	private static void placeLeg(ModelPart leg, float x, float xRot) {
		leg.x = x;
		leg.y = LEG_PIVOT_Y;
		leg.z = 0.0F;
		leg.xRot = xRot;
		leg.yRot = 0.0F;
		leg.zRot = 0.0F;
	}

}
