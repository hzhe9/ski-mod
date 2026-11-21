package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.AlpineSkiControl;
import com.hzhe9.skis.SkisMod;
import com.hzhe9.skis.SnowboardControl;
import com.hzhe9.skis.client.ShoulderSurfingCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
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
 *       看着像绕圈而不是原地转向）；<b>前后一个字都不清</b> —— W 与 A / D 同时按，两个都要生效
 *       （W 照旧前进、A / D 照旧转身体；转向与「按 W 追视角」的互斥只发生在 {@code turnTowardView}
 *       的调用点，见 {@code SkisClient} / {@code SkiGlide}）。</li>
 *   <li><b>单板 A / D</b>：沿<b>身体朝向</b>的左右平移 —— 把「左右输入」绕视角旋转 (身体朝向 − 视角) 后交回原版，
 *       原版再按视角解释它，结果就是沿身体左右走；身体朝向不会因此改变（原版侧移不改 yRot）。
 *       <b>越肩视角开着时这里整段跳过</b>（原样交回）：对方模组会把本方法返回的向量再旋一次并写回
 *       {@code ClientInput#moveVector}，本方法的返回值到不了原版手里 —— 那条路改在
 *       {@code LocalPlayerBoardInputMixin}（{@code LocalPlayer#modifyInput} 的 RETURN）做，
 *       理由见那里与下面 {@code ShoulderSurfingCompat#isActive()} 处的注释。</li>
 *   <li><b>W / S</b>：一律没反应（前后分量直接丢掉）。</li>
 * </ul>
 *
 * <p>改输入而不是改速度：原版走路 / 服务端同步全走同一条路，不会和客户端移动预测互抢。
 *
 * <p>这里的 δ 用 {@code 身体朝向 − 视角}：双板的视角可以转（第三人称不限位、第一人称夹 ±90°，
 * 见 {@code client/SkiViewSmoothing}），所以 δ 不恒为 0 —— 视角怎么转，W / S 都还是沿身体前后。
 * <b>清掉 {@code move.x} 不会影响 A / D 的转向</b>：转向读的是 {@code SnowboardControl.sideSign(Input)}
 * （原始 {@code keyPresses}，见 {@code SkisClient} / {@code SkiGlide}），不是这里返回的向量 ——
 * 本方法只决定「位移往哪儿走」，不决定「按了哪个键」。所以 W 与 A / D 同按时前进与转向各自独立生效。
 * <b>越肩视角下「视角」不再等于 {@code player.getYRot()}</b>（对方模组自己每 tick 写 yRot，
 * 真正跟鼠标的是相机）：单板那条改到 {@code LocalPlayerBoardInputMixin}，δ 在那里按
 * <b>这一 tick 的 {@code getYRot()}</b> 现算 —— 那也是 {@code travel()} 会用的同一个基准。
 *
 * <p><b>空格照旧（单板也能跳）</b>：单板没有前后滑行、全靠原版移动横着走，拦掉跳跃它就再也离不了地；
 * 所以「按空格跳出去」是穿板时唯一的主动离地方式（其余情况由 {@code SkiGlide} 贴着坡面走）。
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
		// 没穿板 / 创造飞行：滑板不改输入，原版 move vector 原样交回。
		// 创造飞行必须走这一条 —— 单板会把 W / S 清零、双板会把 A / D 清零，飞行时那就是「WASD 全废」。
		if (!SkisMod.isSkiMotionActive(player)) {
			return;
		}
		if (!SnowboardControl.isSnowboard(player)) {
			// ——— 双板 ———
			// A / D 只转身体（AlpineSkiControl.turnBody），横向分量在这里清掉；前后分量按 δ = 身体朝向 − 视角
			// 旋到视角坐标系 —— 原版是按视角解释输入的，转完之后 W / S 就是沿身体前后走
			// （身体转过身后按 W 仍朝身体前方溜，而不是朝镜头前方）。δ = 0 时退化成 (0, move.y)。
			// F(身体) = cos δ·F(视角) − sin δ·L(视角)：所以输入写成 (−y·sin δ, y·cos δ)。
			float delta = (AlpineSkiControl.bodyYaw(player.getId(), player.getYRot(), true) - player.getYRot())
					* Mth.DEG_TO_RAD;
			cir.setReturnValue(new Vec2(-move.y * Mth.sin(delta), move.y * Mth.cos(delta)));
			return;
		}
		// ——— 越肩视角：交给 LocalPlayerBoardInputMixin 在 modifyInput 里做 ———
		// 为什么不能在这里算：ShoulderSurfing 的 InputHandler#updateMovementInput 会把**本方法返回的
		// 这个向量**再旋一次（R(玩家 yRot − 相机 yRot)）然后用 setMoveVector 写回 ClientInput.moveVector
		// —— 它在 LocalPlayer#aiStep 里紧跟 ClientInput#tick 之后跑，于是①本方法每 tick 被调两次
		// （它读一次、原版 applyInput 再读一次），②原版最终消费的是它那份「按相机 yaw 换算」的值。
		// 所以这里返回什么都没用（会被再旋一次），而且它把 A/D 换算成「面朝按键方向跑」后 x ≈ 0，
		// 会被下面那句 `move.x == 0 → Vec2.ZERO` 直接抹成 0 —— 越肩开着时单板 A/D 完全没反应。
		// 现在越肩开着就把输入原样交回，单板的横移改在 modifyInput 的 RETURN（原版最后一次读它之后、
		// xxa/zza 落地之前）按身体朝向重算，见 LocalPlayerBoardInputMixin 的 javadoc。
		if (ShoulderSurfingCompat.isActive()) {
			return;
		}
		if (move.x == 0.0F) {
			cir.setReturnValue(Vec2.ZERO);   // 前后（以及没按方向键）一律不动
			return;
		}
		// L(身体朝向) = cos δ·L(视角) + sin δ·F(视角)，δ = 身体朝向 − 视角；
		// 所以把输入写成 (x·cos δ, x·sin δ)，原版就会把「按 A/D」解释成沿身体左右。
		float delta = (SnowboardControl.bodyYaw(player.getId(), player.getYRot(), true) - player.getYRot())
				* Mth.DEG_TO_RAD;
		cir.setReturnValue(new Vec2(move.x * Mth.cos(delta), move.x * Mth.sin(delta)));
	}
}
