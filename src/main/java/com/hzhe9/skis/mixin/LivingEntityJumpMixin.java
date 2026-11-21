package com.hzhe9.skis.mixin;

import com.hzhe9.skis.SkiAir;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>穿板时取消原版起跳</b>：在 {@code LivingEntity#jumpFromGround()V} 的 HEAD 处整段 return。
 *
 * <p>为什么必须取消：原版起跳的调用方是 {@code LivingEntity#aiStep()}，条件是
 * 「{@code this.jumping}（{@code LocalPlayer#tick} 里由 {@code input.keyPresses.jump()} 写入）
 * <b>且（{@code onGround()} 或在水里）</b>」—— 穿板滑行时 {@code onGround} 在上坡 / 起伏 / 贴面走
 * 但没严格落地时经常为假，于是「按空格没反应」。
 *
 * <p>现在起跳只由 {@link SkiAir#tryJump} 发（贴地 / 逗留窗两条之一，**没有「空中向下打射线补跳」那条**），两端各一次、都放在
 * 实体 tick 之前（客户端 {@code LocalPlayerJumpMixin} 注入 {@code LocalPlayer#aiStep} 的 HEAD，
 * 服务端 {@code SkiGlide} 的 {@code START_SERVER_TICK}），
 * 原版这条路径被这里 cancel 掉，<b>同一个 tick 不会有两处各写一次 {@code deltaMovement.y}</b>
 * （原版 {@code jumpFromGround} 写的是 {@code Math.max(power, vy)}）。
 *
 * <p>注入点选 {@code jumpFromGround} 而不是 {@code LocalPlayer} 里的某一行：它是原版唯一写
 * 「起跳力度 → {@code deltaMovement.y}」的地方，一处 cancel 就够。但<b>只拦玩家</b> ——
 * 方法体第一句就是 {@code instanceof Player} 早退，<b>生物 AI / 坐骑的起跳本模组完全不管</b>
 * （靴子槽只有玩家能穿，那两条路也不会带着板）。没穿板时原样放行。
 *
 * <p>common mixin（{@code skis.mixins.json}，两端都加载）：客户端与服务端看到的是同一份起跳条件，
 * 不会出现「客户端跳了、服务端没跳」的半边。服务端用 {@code ServerPlayer#getLastClientInput()}
 * （上一份同步上来的输入）判断「想跳」，不引入任何自定义包。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {
	@Inject(method = "jumpFromGround()V", at = @At("HEAD"), cancellable = true)
	private void skis$skipVanillaJumpWhenBoarding(CallbackInfo ci) {
		if (!((Object) this instanceof Player player)) {
			return;   // 生物 / 其它实体：原版起跳
		}
		if (SkiAir.tuning(player) == null) {
			return;   // 没穿本模组的板：原版起跳
		}
		ci.cancel();  // 穿板：起跳只走 SkiAir.tryJump（两端各自的实体 tick 之前）
	}
}
