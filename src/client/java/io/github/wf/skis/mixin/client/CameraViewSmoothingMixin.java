package io.github.wf.skis.mixin.client;

import io.github.wf.skis.client.SkiViewSmoothing;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>每帧、在相机读视角朝向之前，把视角限位补上</b>（单板 / 双板同一套）。
 *
 * <p>为什么挂在这里而不是渲染事件：{@code LocalPlayer#getViewYRot(float)} 不是乘客时直接返回裸
 * {@code getYRot()}（丢掉 partialTick），而 {@code Camera#alignWithEntity(float)} 用的正是它 ——
 * <b>HEAD 就是「本帧的视角即将被读走」的最后一刻</b>，且参数 {@code partialTick} 与相机随后用的是同一个，
 * 写入立刻生效、零延迟。Fabric API 0.161.0+26.2 里 {@code WorldRenderEvents} 已被
 * {@code LevelRenderEvents} 取代，而且那是在 {@code Camera#update} 之后才发（改成它会晚一帧）。
 *
 * <p>这是整个模组里<b>唯一</b>一处按帧写视角的地方（{@code SkiViewSmoothing.frame}）：
 * 目标 = 插值后的身体朝向 + 唯一的 {@code headOffset}，只在这里写一次，所以不会和鼠标那次（
 * {@code SkiHeadLimitMixin}）互相拉回。
 *
 * <p>只对「相机跟的就是本地玩家」时动手：旁观别人 / 骑乘别人时不做任何事。
 */
@Mixin(Camera.class)
public abstract class CameraViewSmoothingMixin {
	/** 相机跟随的实体（{@code Camera} 的原字段：私有、无 getter，只能影子进来）。 */
	@Shadow
	private Entity entity;

	@Inject(method = "alignWithEntity(F)V", at = @At("HEAD"))
	private void skis$smoothView(float partialTick, CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || this.entity != player) {
			return;
		}
		SkiViewSmoothing.frame(player, partialTick);
	}
}
