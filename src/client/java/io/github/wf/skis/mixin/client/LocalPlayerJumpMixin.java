package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkiAir;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>起跳冲量写进实体 tick 里（{@code travel()} 之前）</b> —— 原版是「这一 tick 写速度 → 这一 tick 立刻位移」
 * （{@code LivingEntity#aiStep}：jump 段先调 {@code jumpFromGround()}，同方法后面才 {@code travel(input)}）。
 *
 * <p>以前 {@code SkiAir.tryJump} 挂在客户端 {@code END_CLIENT_TICK}：那时玩家已经 tick / 位移完，
 * 冲量要到<b>下一 tick</b> 才生效；而服务端的 {@code hurtMarked → ClientboundSetEntityMotionPacket}
 * （{@code Entity#lerpMotion} 是<b>整份覆盖</b> {@code deltaMovement}）常常在下一 tick 开始前就到了 ——
 * 穿板滑行时按空格看着"完全没反应"就是这个。
 *
 * <p>所以起跳挪到 {@code LocalPlayer#aiStep} 的 {@code HEAD}：它在 {@code super.aiStep()}
 * （＝{@code LivingEntity#aiStep}，起跳 / travel 都在里面）之前，冲量与位移同 tick。
 *
 * <p>HEAD 处读到的是<b>上一 tick 的键态</b>（{@code input.tick()} 在 aiStep 内部才跑）——
 * 可接受：{@link SkiAir#tryJump} 本来就有 {@code JUMP_BUFFER_TICKS}（5 tick）输入缓冲，晚一 tick 也照发。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerJumpMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void skis$jumpBeforeTravel(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		// 26.2 的 input / keyPresses 永不为 null（ClientInput 构造把 keyPresses 初始化成 Input.EMPTY）
		boolean holdingJump = self.input.keyPresses.jump();
		SkiAir.tryJump(self, SkiAir.tuning(self), holdingJump, self.onGround());
	}
}
