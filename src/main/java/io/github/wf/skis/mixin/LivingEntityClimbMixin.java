package io.github.wf.skis.mixin;

import io.github.wf.skis.SkisMod;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>穿板时禁止爬梯子 / 藤蔓 / 脚手架</b>：{@code LivingEntity#onClimbable()Z} 直接返回 false。
 *
 * <p>{@code onClimbable()} 是所有「能不能爬」判定的唯一来源：{@code LivingEntity#travel} 里的
 * {@code handleOnClimbable(...)} 靠它把竖向速度钳到 ±0.15 并允许贴墙上升，脚手架 / 藤蔓的缓慢下滑也读它。
 * 返回 false 之后穿着板贴上去就是普通贴墙下落，不会挂在上面。
 *
 * <p>只对玩家生效（只有玩家会把板穿在靴子槽）：生物 / 别的实体原样放行。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClimbMixin {
	@Inject(method = "onClimbable()Z", at = @At("HEAD"), cancellable = true)
	private void skis$noClimbWhenBoarded(CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player)) {
			return;
		}
		if (SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			cir.setReturnValue(false);   // 穿板：梯子 / 藤蔓 / 脚手架都爬不了
		}
	}
}
