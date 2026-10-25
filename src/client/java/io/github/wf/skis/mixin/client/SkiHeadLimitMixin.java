package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.client.ShoulderSurfingCompat;
import io.github.wf.skis.client.SkiViewSmoothing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>视角限位（单板 + 双板同一套实现、各自一个值）</b>：头（视角）相对<b>锁定的身体朝向</b>最多左右各
 * 90 度（双板 {@code SkisControl.VIEW_LIMIT_DEGREES}）/ 135 度（单板 {@code SnowboardControl.VIEW_LIMIT_DEGREES}），
 * 扭不到背后去。脱下板就恢复自由。
 *
 * <p>拦的是鼠标转视角的入口 {@code Entity#turn(double, double)}：
 * <ul>
 *   <li><b>HEAD</b> 记下转之前的 yaw；</li>
 *   <li><b>TAIL</b> 把「这一帧真正转了多少」记进 {@link SkiViewSmoothing} 的<b>唯一限位状态</b>
 *       {@code headOffset}（并夹一次），只撤掉超出限位的那一点。</li>
 * </ul>
 *
 * <p><b>这里不再用身体朝向当基准把 yRot 夹一遍</b>。旧写法在鼠标这次（TAIL）按「上一帧的 bodyRender」夹、
 * 相机那次（{@code CameraViewSmoothingMixin}）按「本帧的 bodyRender」再夹一次 —— 两个基准差一帧，
 * 身体又在转（单板 carve 2°/tick、双板 A/D 3.5°/tick），视角顶在限位上时每帧被拉回再推出去，
 * 就是「贴到极限时画面一卡一抽」的来源。现在改的是同一个标量、夹的是同一个限位，
 * 结构上不可能再互相拉回。
 */
@Mixin(Entity.class)
public abstract class SkiHeadLimitMixin {
	@Inject(method = "turn", at = @At("HEAD"))
	private void skis$beforeTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		if (player.isPassenger() || !SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;
		}
		if (ShoulderSurfingCompat.isActive()) {
			return;   // 越肩视角：相机自由，这一帧的鼠标转角不记账、也不撤
		}
		SkiViewSmoothing.turnStart(player);
	}

	@Inject(method = "turn", at = @At("TAIL"))
	private void skis$afterTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;
		}
		if (ShoulderSurfingCompat.isActive()) {
			return;   // 越肩视角：不限位 —— 一个字节都不写回 yRot
		}
		SkiViewSmoothing.turnEnd(player);
	}
}
