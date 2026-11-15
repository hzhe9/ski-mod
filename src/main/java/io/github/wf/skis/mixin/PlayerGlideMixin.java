package io.github.wf.skis.mixin;

import io.github.wf.skis.SkisMod;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>穿板时禁止鞘翅</b>：让 {@code Player#canGlide()Z} 返回 false。
 *
 * <p>一处就够，因为 {@code canGlide()} 在原版是「鞘翅的门槛」，两个方向都读它：
 * <ul>
 *   <li><b>开始飞</b>：{@code Player#tryToStartFallFlying()} 先查 {@code isFallFlying()}、再查
 *       {@code canGlide()}、最后 {@code startFallFlying()} —— {@code canGlide()} 为 false 就进不去；</li>
 *   <li><b>已经在飞</b>：{@code LivingEntity#aiStep()} 里 {@code if (isFallFlying()) updateFallFlying()}，
 *       而 {@code updateFallFlying()} 在<b>非客户端</b>分支开头就 {@code if (!canGlide()) { setSharedFlag(7, false); return; }}
 *       —— 也就是服务端<b>每 tick</b> 重新验一次资格，不合格当场清掉 FallFlying 标记（并同步给客户端）。
 *       所以「先飞起来、在空中再穿上板」也是同一 tick 掉下来，不需要另外拦 {@code startFallFlying()}。</li>
 * </ul>
 *
 * <p>common mixin（两端都加载）：客户端也返回 false，客户端就不会先自己起一段滑翔再被服务端纠正，
 * 视觉上没有半 tick 的「张翅膀」闪一下。
 */
@Mixin(Player.class)
public abstract class PlayerGlideMixin {
	@Inject(method = "canGlide()Z", at = @At("RETURN"), cancellable = true)
	private void skis$noGlideWhenBoarded(CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player)) {
			return;
		}
		if (SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			cir.setReturnValue(false);   // 穿板：不是可滑翔状态
		}
	}
}
