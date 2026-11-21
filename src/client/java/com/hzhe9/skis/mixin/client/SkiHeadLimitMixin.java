package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.hzhe9.skis.client.ShoulderSurfingCompat;
import com.hzhe9.skis.client.SkiViewSmoothing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>鼠标转视角时「记账」</b>：把这一帧真正转过的角度记进 {@code SkiViewSmoothing} 的唯一状态
 * {@code headOffset}。<b>两块板都<b>不限位</b></b>（双板原先那条 {@code AlpineSkiControl.VIEW_LIMIT_DEGREES}
 * 90° 已删除，单板本来就没有）—— 所以这里既不撤角度、也不写回 yRot，只折回整圈。
 *
 * <p><b>±90° 只剩头模型</b>：{@code client/SkisClient} 每 tick 写
 * {@code yHeadRot = 身体朝向 + SnowboardControl.headModelOffset(视 yaw − 身体朝向)}，内部<b>先
 * {@code Mth.wrapDegrees} 折叠、再夹 ±90°</b>（无状态：每 tick 现算，没有任何跨 tick 累加量）。
 *
 * <p>拦的是鼠标转视角的入口 {@code Entity#turn(double, double)}：
 * <ul>
 *   <li><b>HEAD</b> 记下转之前的 yaw；</li>
 *   <li><b>TAIL</b> 把「这一帧真正转了多少」记进 {@link SkiViewSmoothing} 的 {@code headOffset}（只折回整圈）。</li>
 * </ul>
 *
 * <p><b>这里从不拿身体朝向当基准把 yRot 夹一遍</b>。旧写法在鼠标这次（TAIL）按「上一帧的 bodyRender」夹、
 * 相机那次（{@code CameraViewSmoothingMixin}）按「本帧的 bodyRender」再夹一次 —— 两个基准差一帧，
 * 身体又在转（双板 A/D 4.0°/tick、单板 carve 满速 16°/tick），视角顶在限位上时每帧被拉回再推出去，
 * 就是「贴到极限时画面一卡一抽」的来源。不限位之后这条路径连前提都没有了。
 */
@Mixin(Entity.class)
public abstract class SkiHeadLimitMixin {
	@Inject(method = "turn", at = @At("HEAD"))
	private void skis$beforeTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		if (player.isPassenger() || !SkisMod.isSkiMotionActive(player)) {
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
		if (!SkisMod.isSkiMotionActive(player)) {
			return;   // 没穿板 / 创造飞行：不限位（飞行时视角完全归原版）
		}
		if (ShoulderSurfingCompat.isActive()) {
			return;   // 越肩视角：不限位 —— 一个字节都不写回 yRot
		}
		SkiViewSmoothing.turnEnd(player);
	}
}
