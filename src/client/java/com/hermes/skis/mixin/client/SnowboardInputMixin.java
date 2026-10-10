package com.hermes.skis.mixin.client;

import com.hermes.skis.SkisMod;
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
 * 单板滑行手感：踩单板时把<b>移动方向相对视线转过 {@link #TRAVEL_YAW_DEGREES}°</b>。
 *
 * <p>真实单板是身体朝一边、板朝前，滑行动作上表现为“往身体的左右滑”。MC 里玩家身体永远朝视角方向，
 * 所以要做到“身体朝前、实际横着滑”，只能把输入向量转过来 —— 改的是
 * {@code ClientInput#getMoveVector()}（x = 左右、y = 前后），客户端自己的物理照常跑，
 * 服务端跟着客户端走，不会出现“和客户端抢速度”。
 *
 * <p>角度：90° = 完全横着滑（按 W 往身体的右侧滑，正对板头方向）；45/30° = 打斜滑（carve）；
 * 0° = 恢复正常前进。注意模型那张板也转了 {@code SkiStance.BOARD_YAW_DEGREES}，默认两边都是 +90°
 * —— 让“板头指向”和“按 W 滑出去的方向”保持一致，改一个最好另一个也跟着改。
 */
@Mixin(ClientInput.class)
public class SnowboardInputMixin {
	/** 滑行方向相对视线转过的角度（度）：90 = 横着滑、0 = 正常前进。 */
	private static final float TRAVEL_YAW_DEGREES = 90.0F;

	@Inject(method = "getMoveVector", at = @At("RETURN"), cancellable = true)
	private void skis$rotateForSnowboard(CallbackInfoReturnable<Vec2> cir) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.getItemBySlot(EquipmentSlot.FEET).getItem() != SkisMod.SNOWBOARD) {
			return;
		}
		if (player.isPassenger() || player.getAbilities().flying) {
			return;   // 骑乘 / 创造飞行时不动输入
		}

		Vec2 move = cir.getReturnValue();
		if (move == null || (move.x == 0.0F && move.y == 0.0F)) {
			return;
		}

		float radians = TRAVEL_YAW_DEGREES * Mth.DEG_TO_RAD;
		float cos = Mth.cos(radians);
		float sin = Mth.sin(radians);
		cir.setReturnValue(new Vec2(move.x * cos - move.y * sin, move.x * sin + move.y * cos));
	}
}
