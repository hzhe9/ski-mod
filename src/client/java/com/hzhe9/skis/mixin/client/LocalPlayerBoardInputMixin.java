package com.hzhe9.skis.mixin.client;

import com.hzhe9.skis.SkisMod;
import com.hzhe9.skis.SnowboardControl;
import com.hzhe9.skis.client.ShoulderSurfingCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>越肩视角（Shoulder Surfing）开着时，单板 A / D 的横移基准</b> —— 只改单板，双板一行不动。
 *
 * <p><b>为什么不能在 {@code ClientInputMixin} 那条路上改</b>（根因，全部由对方 jar 静态核实）：
 * 对方 {@code com.github.exopandora.shouldersurfing.client.InputHandler#updateMovementInput(ClientInput)}
 * 挂在它自己的 {@code LocalPlayerMixin} 上，注入点是 {@code LocalPlayer#aiStep} 里
 * {@code ClientInput#tick()} 调用之后的 {@code AFTER}（{@code javap -v} 的
 * {@code @At(value=INVOKE, target="Lnet/minecraft/client/player/ClientInput;tick()V", shift=AFTER)}）。
 * 它做的事（{@code javap -p -c} 的 {@code updateMovementInput}）：
 * <ol>
 *   <li>先 {@code clientInput.getMoveVector()} —— <b>我们的 mixin 就是在这次读里改向量</b>；</li>
 *   <li>把读到的向量按<b>相机 yaw</b> 旋到世界（{@code Vec2f.rotateDegrees(camera.getYRot())}），
 *       由它的方向算出目标朝向并 {@code LocalPlayer#setYRot}（「面朝按键方向跑」，
 *       所以 {@code player.getYRot()} 已经不是鼠标视角，而是对方每 tick 自己写进去的值）；</li>
 *   <li>再 {@code v.rotateDegrees(Mth.degreesDifference(playerYRot, cameraYaw))} 并
 *       {@code ((ClientInputAccessor) clientInput).setMoveVector(...)} <b>写回
 *       {@code ClientInput#moveVector}</b>。</li>
 * </ol>
 * 后果两条，都能解释「单板按 A / D 完全没反应」：
 * <ul>
 *   <li><b>我们的返回值到不了原版</b>：原版 {@code LocalPlayer#applyInput} 读的是被对方改写过的
 *       {@code moveVector}（而且我们的 {@code getMoveVector} 注入每 tick 会跑两次）；对方的换算基准是
 *       <b>相机</b>，不是 {@code player.getYRot()}。</li>
 *   <li><b>A / D 被换算成「前后」</b>：对方让玩家面朝按键方向，A / D 在玩家坐标系里就变成 {@code (0, ±1)}
 *       —— 横向分量 ≈ 0，正好撞上 {@code ClientInputMixin} 里「{@code move.x == 0} 一律返回
 *       {@link Vec2#ZERO}」那条单板规则，于是输入被清零，位移一点都没有。</li>
 * </ul>
 *
 * <p><b>改法</b>：在 {@code LocalPlayer#modifyInput(Vec2)} 的 {@code RETURN} 上接管。这个方法是原版
 * {@code applyInput} 里<b>最后一次</b>碰移动向量的地方（它的返回值直接写进 {@code xxa} / {@code zza}，
 * 之后 {@code travel()} 才按 {@code getYRot()} 把 {@code (xxa, zza)} 转进世界），对方的换算已经做完、
 * 也不可能再改它 —— 在这里给出结果就等于给了原版最终答案。
 *
 * <p><b>为什么这里可以用 {@code player.getYRot()}</b>：{@code travel()} 用的就是这个瞬时值，
 * 我们又在这一 tick 现算 δ ＝ 身体朝向 − 它，所以无论对方把 {@code yRot} 拧到哪儿
 * （面朝按键方向、缓动追赶、相机耦合），「世界方向 ＝ 身体左右」都成立 —— 与
 * {@code ClientInputMixin} 在没装越肩时的口径逐字相同，只是搬到了对方改不到的位置。
 *
 * <p><b>不改越肩时的行为</b>：{@link ShoulderSurfingCompat#isActive()} 为假（没装 / 没开）时本注入
 * 直接返回，{@code ClientInputMixin} 那条老路照旧 —— 两条路互不重叠。
 * 幅度取原版算出来的模长（纯 A / D 按一下＝单位向量 × 原版 0.98 的缩放），只有它被清成 0 时才退回
 * {@code 0.98}（＝原版纯横移的模长），免得「被对方抹掉」这种情况连速度都没了。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerBoardInputMixin {
	/** 原版 {@code modifyInput} 对单位输入乘的系数（{@code Vec2.scale(0.98f)}）。 */
	private static final float VANILLA_INPUT_SCALE = 0.98F;

	@Inject(method = "modifyInput", at = @At("RETURN"), cancellable = true)
	private void skis$boardLateralUnderShoulderSurfing(Vec2 move, CallbackInfoReturnable<Vec2> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		// 只在越肩开着 + 穿单板 + 滑行生效（创造飞行 / 旁观 / 双板一律不碰，双板走原来那条）
		if (!ShoulderSurfingCompat.isActive() || !SkisMod.isSkiMotionActive(self)
				|| !SnowboardControl.isSnowboard(self)) {
			return;
		}
		Input keys = self.input.keyPresses;
		double side = SnowboardControl.sideSign(keys);   // A = +1 / D = -1 / 没按或同按 = 0
		if (side == 0.0D) {
			cir.setReturnValue(Vec2.ZERO);   // 与 ClientInputMixin 同一条：单板只认 A / D，W / S 一律不动
			return;
		}
		Vec2 vanilla = cir.getReturnValue();
		float magnitude = vanilla == null ? 0.0F : vanilla.length();
		if (magnitude <= 1.0E-4F) {
			magnitude = VANILLA_INPUT_SCALE;   // 被对方抹成 0 也照样给一份正常横移（正是本 mixin 要修的 bug）
		}
		float delta = Mth.wrapDegrees(SnowboardControl.bodyYaw(self.getId(), self.getYRot(), true)
				- self.getYRot()) * Mth.DEG_TO_RAD;
		// travel() 会把 (xxa, zza) 按 getYRot() 旋进世界，所以这里先退回身体坐标系：
		// 世界方向 = R(yRot) · R(δ) = R(身体朝向)，即沿板轴左右。
		float amount = (float) side * magnitude;
		cir.setReturnValue(new Vec2(amount * Mth.cos(delta), amount * Mth.sin(delta)));
	}
}
