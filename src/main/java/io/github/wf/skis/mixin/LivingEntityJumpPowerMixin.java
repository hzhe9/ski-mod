package io.github.wf.skis.mixin;

import io.github.wf.skis.SkiAir;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>穿板起跳力度随速度放大</b>：改原版 {@code LivingEntity#getJumpPower()F} 的返回值。
 *
 * <p>为什么是这个方法：{@code jumpFromGround()} 只取一次跳跃力度（{@code getJumpPower()} →
 * {@code getJumpPower(1.0F)} ＝ {@code JUMP_STRENGTH × 跳跃系数 + 跳跃提升}），随后
 * {@code setDeltaMovement(x, max(power, vy), z)}。在这里换值＝<b>还是原版跳跃路径</b>：
 * 空格、连跳、跳跃提升药水之后的消费方（撞头 / 落地）全都照旧，没有新按键、也没有拦 {@code makeJump}。
 *
 * <p>只在<b>玩家 + 穿着本模组的板</b>时生效（{@link SkiAir#jumpPower} 对其它情况返回 −1，原版值原样交回）。
 * 这是 common mixin（{@code skis.mixins.json}，两端都加载）：服务端算的是受力，客户端算的是本地预测，
 * 两边各自用本地的水平速度算同一条公式，不走网络包。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpPowerMixin {
	@Inject(method = "getJumpPower()F", at = @At("RETURN"), cancellable = true)
	private void skis$speedScaledJumpPower(CallbackInfoReturnable<Float> cir) {
		if (!((Object) this instanceof Player player)) {
			return;   // 生物 / 其它实体：原版跳跃力度
		}
		float power = SkiAir.jumpPower(player);
		if (power < 0.0F) {
			return;   // 没穿本模组的板：原版跳跃力度
		}
		cir.setReturnValue(power);
	}
}
