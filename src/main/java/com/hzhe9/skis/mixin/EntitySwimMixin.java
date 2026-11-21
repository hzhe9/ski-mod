package com.hzhe9.skis.mixin;

import com.hzhe9.skis.SkisMod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>穿板时禁止游泳</b>：{@code Entity#updateSwimming()V} 的 HEAD 整段 cancel（并先把游泳姿态清掉）。
 *
 * <p>原版这个方法每 tick（{@code Entity#baseTick}）按「冲刺 + 在水里 / 水下」重算 {@code swimming}，游泳姿态、
 * 眼睛高度、贴水面的推进都跟着这个标记走。穿板时不让它被置上，落水就是「站在水里往下沉」，
 * <b>但按住空格照样能上浮</b>（原版水物理：{@code LivingEntity#aiStep} 里 {@code jumping} 为真就调
 * {@code jumpInLiquid}，与 {@code swimming} 标记、与 {@code jumpFromGround} 都无关）——
 * 这里不额外加浮力、也不加溺亡机制。
 *
 * <p>先 {@code setSwimming(false)} 再 cancel：如果穿板那一刻已经在水里游着，标记得当场清掉，
 * 否则 ENTITY_DATA 里那份 swimming 会一直亮着（姿态、眼睛高度都不对），直到下次有人在别处改它。
 * 客户端也拦（common mixin）：本机玩家同样不再进入游泳姿态，与服务端看到的一致。
 */
@Mixin(Entity.class)
public abstract class EntitySwimMixin {
	@Inject(method = "updateSwimming()V", at = @At("HEAD"), cancellable = true)
	private void skis$noSwimmingWhenBoarded(CallbackInfo ci) {
		if (!((Object) this instanceof LivingEntity living)) {
			return;   // 只有 LivingEntity 有装备槽
		}
		if (!SkisMod.isSkiFeet(living.getItemBySlot(EquipmentSlot.FEET))) {
			return;
		}
		if (living.isSwimming()) {
			living.setSwimming(false);   // 已经在水里游着：当场清标记
		}
		ci.cancel();   // 穿板：本 tick 不重算游泳状态（也就不会进入游泳姿态）
	}
}
