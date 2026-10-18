package com.hermes.skis.mixin.client.compat;

import com.hermes.skis.SkisMod;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * <b>可选兼容：Vivid Move（灵动）的 PAL 骨骼动画层</b>。
 *
 * <p>为什么需要这一处：{@code playerAnimationsEnabled} 只挡「最终姿势」那一层，
 * Vivid Move 的移动/战斗/动作动画是由 PAL 的 {@code AnimationController} 直接写四肢与躯干的
 * （{@code PlayerModelMixin#setupPlayerAnimation} 在 {@code HumanoidModel.setupAnim} 尾部套骨骼）。
 * 那个控制器是 {@code VividMoveAnimations$PhaseAwarePlayerAnimationController}，
 * 它的 {@code isActive()} 才决定 PAL 要不要动这个玩家的骨骼。
 *
 * <p>PAL 的调用链（javap 已核实）：
 * <ul>
 *   <li>{@code AnimationStack#setupAnim} / {@code #get3DTransform} <b>只对 isActive() 为真的层</b>调用 → 不改骨骼；</li>
 *   <li>{@code AnimationStack#tick} 不看 isActive → 动画继续走帧，脱板恢复时不会跳帧；</li>
 *   <li>{@code AvatarAnimManager#isActive()} 为假时 {@code PlayerModelMixin} 走 {@code pal$resetAll}（空实现），
 *       原版与本模组写好的 ModelPart 原样保留 —— 正是我们要的结果。</li>
 * </ul>
 *
 * <p>Vivid Move 自己就在这个方法里用同样的手法做兼容（持非 FULL 掩码的物品 / BetterCombat 时返回 false），
 * 所以这里沿用同一语义，覆盖它的全部身体控制器（移动、战斗、动作、鞘翅落地、弩、火把）。
 * 它只让 PAL 不写骨骼，不影响 Vivid Move 的相机、眼睛（{@code PalHeadAnimation} 是另一层，保持活跃）、
 * 拖尾、血迹与打击反馈。
 */
@Mixin(targets = "com.vividmove.fabric.VividMoveAnimations$PhaseAwarePlayerAnimationController", remap = false)
public class VividMoveAnimationControllerMixin {
	/** 目标类的私有字段 {@code compatibilityPlayer}；用反射读，字段改名/消失时静默失效、不崩。 */
	@Unique
	private static Field skis$compatPlayerField;
	@Unique
	private static boolean skis$compatPlayerFieldResolved;

	@Inject(method = "isActive()Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void skis$inactiveOnSkis(CallbackInfoReturnable<Boolean> cir) {
		try {
			AbstractClientPlayer player = skis$playerOf(this);
			if (player != null && SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
				cir.setReturnValue(false);
			}
		} catch (Throwable ignored) {
			// 每帧调用的路径：异常一律吞掉，退回 Vivid Move 自己的判定
		}
	}

	@Unique
	private static AbstractClientPlayer skis$playerOf(Object controller) {
		Field field = skis$compatPlayerField;
		if (!skis$compatPlayerFieldResolved) {
			skis$compatPlayerFieldResolved = true;
			try {
				field = controller.getClass().getDeclaredField("compatibilityPlayer");
				field.setAccessible(true);
				skis$compatPlayerField = field;
			} catch (Throwable ignored) {
				skis$compatPlayerField = null;
				return null;
			}
		}
		if (field == null) {
			return null;
		}
		try {
			return (AbstractClientPlayer) field.get(controller);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
