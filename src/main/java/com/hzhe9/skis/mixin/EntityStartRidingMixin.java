package com.hzhe9.skis.mixin;

import com.hzhe9.skis.SkisMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>穿板时禁止玩家自己上车 / 骑生物</b>：拦 {@code Entity#startRiding(Entity, boolean, boolean)Z} 的 HEAD。
 *
 * <p>为什么钉在这个方法：{@code startRiding(Entity)} 是 {@code public final}，函数体只有一句
 * {@code return startRiding(entity, false, true)} —— 所以矿车 / 船 / 马 / 猪 / 坐垫类 mod 的「玩家自己右键上车」
 * <b>全部</b>汇合到这一个带三个参数的重载上，一处 cancel 就够，不会漏掉某个调用方。
 *
 * <p>判据用 {@code this instanceof ServerPlayer}：客户端侧没有 {@code ServerPlayer}，所以这一处天然
 * 只在服务端生效（客户端的右键上车只是请求，服务端不放行就不会有下一 tick 的骑乘状态）。
 *
 * <p><b>{@code force == true} 一律放行</b>：那是<b>服务端内部</b>路径 —— {@code /ride}、
 * 别的模组强制上车、以及「传送 / 换维度把乘客塞回载具」。这些不是玩家按右键，拦下来只会让它们失败
 * （还看不到理由）。玩家自己上车走的是 {@code force = false}，照拦。
 *
 * <p>取消时给玩家一条 action bar 提示（否则「右键矿车没反应」看起来像 bug）；提示对同一个 id 20 tick
 * 内只发一次，见 {@link SkisMod#notifyNoRiding}。
 */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {
	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
	private void skis$noRidingWhenBoarded(Entity vehicle, boolean force, boolean suppressMove,
			CallbackInfoReturnable<Boolean> cir) {
		if (force) {
			return;   // 服务端内部路径（/ride、强制上车、传送塞回载具）：不拦、也不提示
		}
		if (!((Object) this instanceof ServerPlayer player)) {
			return;   // 客户端没有 ServerPlayer：不在这里拦
		}
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;   // 没穿本模组的板：原版骑乘
		}
		cir.setReturnValue(false);   // 穿板：不上车（startRiding 的三参重载返回 false = 没骑上）
		SkisMod.notifyNoRiding(player);
	}
}
