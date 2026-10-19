package io.github.wf.skis.mixin.client;

import io.github.wf.skis.client.GlideAudioGate;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 穿着滑雪板摔落在<b>雪 / 冰面上</b>时：<b>不响落地声</b>、也不结算摔落。
 *
 * <p>两个注入点，缺一不可（都是实测踩出来的）：
 * <ul>
 *   <li>{@code LivingEntity#causeFallDamage} 的 <b>HEAD 直接取消</b> —— 那声闷响就在这里：源码里只要算出的
 *       伤害 &gt; 0，就会依次 {@code playSound(getFallDamageSound(i))}（玩家是 {@code PLAYER_BIG_FALL} 那声落地闷响）
 *       → {@code playBlockFallSound()} → {@code hurt(…)}。
 *       ⚠️ 只在 {@code hurtServer} 上免伤（{@link io.github.wf.skis.SnowLanding}）<b>拦不住声音</b> ——
 *       声音在伤害之前就已经播出去了，症状就是「没伤害了、但还听得见落地声」。</li>
 *   <li>{@code LivingEntity#playBlockFallSound} 兜一层：脚下方块自己那条落地声也一起静音。</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallSoundMixin {
	/** 摔落结算整段取消：不响落地声、也不受伤（判定与免伤同一条：雪 / 冰面 + 脚上是我们的板）。 */
	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void skis$cancelSnowLanding(double fallDistance, float multiplier, DamageSource source,
			CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (GlideAudioGate.isBoardedOnSnow(self)) {
			self.resetFallDistance();
			cir.setReturnValue(false);
		}
	}

	/** 落地闷响（脚下方块自己的声音）。 */
	@Inject(method = "playBlockFallSound", at = @At("HEAD"), cancellable = true)
	private void skis$silenceLanding(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (GlideAudioGate.isBoarded(self)) {
			ci.cancel();
		}
	}
}
