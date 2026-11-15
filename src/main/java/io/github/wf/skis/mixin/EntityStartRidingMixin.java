package io.github.wf.skis.mixin;

import io.github.wf.skis.SkisMod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>穿板时禁止上车 / 骑生物</b>：拦 {@code Entity#startRiding(Entity, boolean, boolean)Z} 的 HEAD。
 *
 * <p>为什么钉在这个方法：{@code startRiding(Entity)} 是 {@code public final}，函数体只有一句
 * {@code return startRiding(entity, false, true)} —— 所以矿车 / 船 / 马 / 猪 / 坐垫类 mod / {@code /ride}
 * <b>全部</b>汇合到这一个带三个参数的重载上，一处 cancel 就够，不会漏掉某个调用方。
 *
 * <p>判据用 {@code this instanceof ServerPlayer}：客户端侧没有 {@code ServerPlayer}，所以这一处天然
 * 只在服务端生效（客户端的右键上车只是请求，服务端不放行就不会有下一 tick 的骑乘状态）。
 * 取消时给玩家一条 action bar 提示，否则「右键矿车没反应」看起来像 bug。
 */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {
	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
	private void skis$noRidingWhenBoarded(Entity vehicle, boolean force, boolean suppressMove,
			CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ServerPlayer player)) {
			return;   // 客户端没有 ServerPlayer：不在这里拦
		}
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;   // 没穿本模组的板：原版骑乘
		}
		cir.setReturnValue(false);   // 穿板：不上车（startRiding 的三参重载返回 false = 没骑上）
		player.sendOverlayMessage(Component.translatable("message.skis.no_riding"));
	}
}
