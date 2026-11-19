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
 * Stance while wearing the ski board:
 *
 * <ul>
 *   <li>Legs: no walking leg swing and no splay — the left and right legs are only translated
 *       outwards by {@link SkiStance#LEG_SPREAD} (the board model on the feet uses the same
 *       amount, so it separates with them and stays parallel);</li>
 *   <li>Arms: stroke in front of the body in the same phase (pole pushing / rowing feel), with the
 *       amplitude following the movement speed;</li>
 *   <li><b>Airborne</b>: the arms hold the pose of the last frame before leaving the ground (no
 *       swinging, no return to the default pose) and resume swinging after landing;</li>
 *   <li>Body: a slight forward lean.</li>
 * </ul>
 *
 * "On ground" is not read from the render state but from {@link SkiState} (the client tick records it
 * from the real entity every tick) — render state instances are rebuilt every frame, and fields hung on
 * them are not guaranteed to be seen by setupAnim (this was the root cause of the earlier flip-flopping
 * between the two behaviours).
 *
 * Swimming / elytra / riding keep the vanilla pose.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	/** Knee bend angle (radians). */
	private static final float GLIDE_KNEE_BEND = 0.08F;
	/** Original height of the leg pivot. */
	private static final float LEG_PIVOT_Y = 12.0F;
	/** Arm stroke centre (lift angle in front of the body; the more negative, the higher the lift). */
	private static final float ROW_CENTER = -0.70F;
	/** Arm stroke amplitude. */
	private static final float ROW_AMPLITUDE = 0.95F;
	/** Stroke frequency (multiplied onto walkAnimationPos; smaller is slower). */
	private static final float ROW_PHASE = 0.4F;
	/** Gliding forward lean. */
	private static final float GLIDE_LEAN = 0.05F;
	/** Outward roll of the arms (symmetric in sign, keeping the two ski poles parallel and not tilting inwards). */
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

		// Legs: spread sideways only, kept parallel
		placeLeg(self.rightLeg, -SkiStance.legOffsetX());
		placeLeg(self.leftLeg, SkiStance.legOffsetX());

		// Arms: stroke in phase; while airborne or "just landed and not yet stable", reuse the
		// amplitude of the last frame before take-off (frozen)
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
			// Hands tilt slightly outwards (symmetric) → the two hanging poles are parallel lines that
			// do not tilt inwards or clip into the legs
			self.rightArm.yRot = 0.0F;
			self.leftArm.yRot = 0.0F;
			self.rightArm.zRot = ARM_OUTWARD_ROLL;
			self.leftArm.zRot = -ARM_OUTWARD_ROLL;
		}

		// Forward lean of the body (keep the vanilla crouch bend while sneaking)
		if (!state.isCrouching) {
			self.body.xRot = GLIDE_LEAN;
		}
	}

	/** Places one leg at the "outward-translated position" with all rotations zeroed (parallel). */
	private static void placeLeg(ModelPart leg, float x) {
		leg.x = x;
		leg.y = LEG_PIVOT_Y;
		leg.z = 0.0F;
		leg.xRot = GLIDE_KNEE_BEND;
		leg.yRot = 0.0F;
		leg.zRot = 0.0F;
	}
}
