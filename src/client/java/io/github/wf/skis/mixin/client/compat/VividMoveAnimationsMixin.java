package io.github.wf.skis.mixin.client.compat;

import io.github.wf.skis.SkisMod;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>可选兼容：Vivid Move（灵动）</b>—— 穿着本模组的板时关掉它的全身玩家动画。
 *
 * <p>Vivid Move 的姿势门闸是 {@code VividMoveAnimations.playerAnimationsEnabled(AbstractClientPlayer)}：
 * 它控制下蹲混合、走路腿部 IK（{@code SimulatedLegIkClient}）、落地压缩、手臂物品变换、
 * 盔甲缩放、渲染身体朝向等一整套「最终姿势」处理。这里在 HEAD 直接把它按成 {@code false}，
 * 穿板玩家的身体就只留下本模组 {@code HumanoidModelMixin} 写的滑行站姿。
 *
 * <p>注意：这个门闸<b>不覆盖</b> PAL（player_animation_library）骨骼动画本身 ——
 * 那条路径在 {@code VividMoveAnimations$PhaseAwarePlayerAnimationController#isActive()} 里，
 * 由 {@link VividMoveAnimationControllerMixin} 补上第二处（同一份 mixin 配置）。
 *
 * <p>只影响姿势/渲染：相机、眼睛、拖尾、血迹、打击反馈都不走这个门闸。
 */
@Mixin(targets = "com.vividmove.fabric.VividMoveAnimations", remap = false)
public class VividMoveAnimationsMixin {
	@Inject(
		method = "playerAnimationsEnabled(Lnet/minecraft/client/player/AbstractClientPlayer;)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private static void skis$disableAnimationsOnSkis(AbstractClientPlayer player, CallbackInfoReturnable<Boolean> cir) {
		try {
			if (player != null && SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
				cir.setReturnValue(false);
			}
		} catch (Throwable ignored) {
			// 每帧调用的路径：任何异常都不许冒出去，交回 Vivid Move 自己的判定
		}
	}
}
