package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.client.SkiStance;
import io.github.wf.skis.client.SkiState;
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
 *       （脚上的板模型用同一个量，跟着分开、平行）；同时<b>角度微蹲</b> ——
 *       腿往后斜 {@link SkiStance#SQUAT_LEG_TILT}、躯干前压 {@link SkiStance#SQUAT_BODY_LEAN}、
 *       头略低 {@link SkiStance#SQUAT_HEAD_PITCH}。<b>不缩腿、不挪 y</b>（腿长与枢轴都是原版的）。</li>
 *   <li>双臂：<b>静止</b>固定在划动的中间位置，不随移动摆动；</li>
 *   <li>右键撑杖时播放<b>一次</b>摆臂（前摆再收回，约 0.6 秒），由 {@link SkiState} 的倒计时驱动；</li>
 *   <li>身体：轻微前倾。</li>
 * </ul>
 *
 * <p><b>为什么用角度而不是缩腿</b>：原版 biped 的腿是单节 box（枢轴往下 12 单位），没有膝关节，
 * 缩腿也弯不出膝、反而让脚离开板面；而腿后倾 + 上身前压 + 头低 三个角度就足够读成「微蹲」，
 * 且脚底位置与不穿板时完全一致（{@code yScale} 恒 1.0、{@code leg.y} 恒 12，一律不写）。
 *
 * 游泳 / 鞘翅 / 骑乘时保持原版姿势。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
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
	private static final float RAISE_FRACTION = 0.25F;   // 举起段占比（25%）：抬手只占前四分之一，余下留给下摆
	/** 最低处：一次撑杖摆到的地方。 */
	private static final float ARM_BOTTOM = ROW_CENTER + ROW_AMPLITUDE;
	/** 下摆占整段动画的比例，剩下的时间收回最高处。 */
	private static final float DOWN_FRACTION = 0.6F;
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
			// 单板：与双板同一套站姿 —— 两腿平行、向外岔开、下半身往后倾（SQUAT_LEG_TILT）
			// （横向偏移与 SnowboardModel 的鞋位同值，否则脚会与鞋错开）
			placeLeg(self.rightLeg, -SkiStance.BOARD_LEG_SPREAD, SkiStance.SQUAT_LEG_TILT);
			placeLeg(self.leftLeg, SkiStance.BOARD_LEG_SPREAD, SkiStance.SQUAT_LEG_TILT);
		} else {
			// 双板：两腿只横向拉开、保持平行；下半身往后倾出去一点（同一个 SQUAT_LEG_TILT）
			placeLeg(self.rightLeg, -SkiStance.legOffsetX(), SkiStance.SQUAT_LEG_TILT);
			placeLeg(self.leftLeg, SkiStance.legOffsetX(), SkiStance.SQUAT_LEG_TILT);
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

		// 上身与头：微蹲的另外两条角度（蹲行时保持原版的弯腰与头角，整段不覆盖）。
		// 单板按 W / S 的“整个人 + 板一起倾”不在模型里做 —— 板是独立提交的模型，只改部件它不跟，
		// 统一在 EntityTiltMixin 里转整个 poseStack。
		if (!state.isCrouching) {
			self.body.xRot = SkiStance.SQUAT_BODY_LEAN;    // 躯干前压（与腿的后倾配对＝屈身）
			self.head.xRot = SkiStance.SQUAT_HEAD_PITCH;   // 头随上身略前低（只在站姿生效分支里写）
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

	/**
	 * 把一条腿摆到「向外平移后的位置」，朝向全部归零（平行）；xRot 决定前后倾。
	 *
	 * <p><b>不缩腿、不挪 y</b>：枢轴恒在 {@link #LEG_PIVOT_Y}（12）、{@code yScale} 恒为 1.0 ——
	 * 原版 biped 是单节腿（枢轴往下 12 单位的一根 box），没有膝关节，缩腿弯不出膝、还会把脚
	 * 从板面上抬起来。微蹲的观感全部由角度表达（腿后倾 + 上身前压 + 头略低）。
	 */
	private static void placeLeg(ModelPart leg, float x, float xRot) {
		leg.x = x;
		leg.y = LEG_PIVOT_Y;
		leg.z = 0.0F;
		leg.xRot = xRot;
		leg.yRot = 0.0F;
		leg.zRot = 0.0F;
	}

}
