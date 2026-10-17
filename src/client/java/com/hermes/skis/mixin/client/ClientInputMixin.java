package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisControl;
import com.hermes.skis.SkisMod;
import com.hermes.skis.SnowboardControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 滑雪板（单板 / 双板各一套）的输入改写：
 *
 * <ul>
 *   <li><b>双板</b>：A / D <b>只用来转向</b>，左右分量直接清掉（否则按住 A 会“一边转一边横移”，
 *       看着像绕圈而不是原地转向）；前后照常。</li>
 *   <li><b>单板 A / D</b>：沿<b>身体朝向</b>的左右平移 —— 把「左右输入」绕视角旋转 (身体朝向 − 视角) 后交回原版，
 *       原版再按视角解释它，结果就是沿身体左右走；身体朝向不会因此改变（原版侧移不改 yRot）。</li>
 *   <li><b>W / S</b>：一律没反应（前后分量直接丢掉）。</li>
 * </ul>
 *
 * <p>改输入而不是改速度：原版走路 / 服务端同步全走同一条路，不会和客户端移动预测互抢。
 *
 * <p>这里的 δ 用 {@code 身体朝向 − 视角}：双板的视角可以转（限在身体 ±135° 内，见
 * {@code client/SkiViewSmoothing}），所以 δ 不恒为 0 —— 视角怎么转，W / S 都还是沿身体前后。
 *
 * <p><b>空格照旧（单板也能跳）</b>：原来这里把单板的 {@code makeJump} 取消了，2026-10-15 按用户要求
 * 去掉 —— 现在「按空格跳出去」是穿板时唯一的主动离地方式（其余情况由 {@code SkiGlide} 贴着坡面走）。
 */
@Mixin(ClientInput.class)
public abstract class ClientInputMixin {
	@Inject(method = "getMoveVector", at = @At("RETURN"), cancellable = true)
	private void skis$boardMove(CallbackInfoReturnable<Vec2> cir) {
		LocalPlayer player = Minecraft.getInstance().player;
		Vec2 move = cir.getReturnValue();
		if (player == null || move == null) {
			return;
		}
		if (!SnowboardControl.isSnowboard(player)) {
			if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
				return;   // 没穿板：原版移动原样交回
			}
			// ——— 双板 ———
			// A / D 只转身体（SkisControl.turnBody），横向分量在这里清掉；前后分量按 δ = 身体朝向 − 视角
			// 旋到视角坐标系 —— 原版是按视角解释输入的，转完之后 W / S 就是沿身体前后走
			// （身体转过身后按 W 仍朝身体前方溜，而不是朝镜头前方）。δ = 0 时退化成 (0, move.y)。
			// F(身体) = cos δ·F(视角) − sin δ·L(视角)：所以输入写成 (−y·sin δ, y·cos δ)。
			float delta = (SkisControl.bodyYaw(player.getId(), player.getYRot()) - player.getYRot())
					* Mth.DEG_TO_RAD;
			cir.setReturnValue(new Vec2(-move.y * Mth.sin(delta), move.y * Mth.cos(delta)));
			return;
		}
		if (move.x == 0.0F) {
			cir.setReturnValue(Vec2.ZERO);   // 前后（以及没按方向键）一律不动
			return;
		}
		// L(身体朝向) = cos δ·L(视角) + sin δ·F(视角)，δ = 身体朝向 − 视角；
		// 所以把输入写成 (x·cos δ, x·sin δ)，原版就会把「按 A/D」解释成沿身体左右。
		float delta = (SnowboardControl.bodyYaw(player.getId(), player.getYRot()) - player.getYRot())
				* Mth.DEG_TO_RAD;
		cir.setReturnValue(new Vec2(move.x * Mth.cos(delta), move.x * Mth.sin(delta)));
	}
}
