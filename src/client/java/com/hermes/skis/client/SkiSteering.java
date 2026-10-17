package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import com.hermes.skis.SnowboardControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * <b>双板：A / D 当转向键</b>（纯客户端）。
 *
 * <p>双板的 A / D 不再参与平移（{@code mixin/client/ClientInputMixin} 把横向分量清掉了），
 * 它们只负责把<b>视角</b>（＝朝向，双板不锁身体朝向）每 tick 转
 * {@link #TURN_RATE_DEGREES} 度 —— 按住 A 左转、按住 D 右转，两个都不按就保持当前朝向。
 *
 * <p>只管「转视角」这一件事：速度方向跟不跟着转由服务端的 {@code SkiControl}
 * 决定（地上跟着摆过去、空中不跟），所以这里是纯客户端观感 / 输入，不需要同步任何状态 ——
 * 写完 {@code setYRot} 后游戏下一 tick 自己会把朝向同步给服务端。
 *
 * <p>单板不参与：它的 A / D 是沿身体左右平移（见 {@code SnowboardControl} 的 carve 那套）。
 */
public final class SkiSteering {
	/** 双板：按住 A / D 时每 tick 转多少度（4.0°/tick ≈ 80°/s，180° 约 2.25 s）。 */
	private static final float TURN_RATE_DEGREES = 4.0F;

	private SkiSteering() {
	}

	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		if (player.getAbilities().flying || player.isPassenger()) {
			return;   // 创造飞行 / 骑乘时不接管转向
		}
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;
		}
		if (SnowboardControl.isSnowboard(player)) {
			return;   // 单板不参与（A / D 是平移）
		}
		// 左键 +1（yaw 增大 = 左转，与 SkiControl 的 forwardX = -sin(yaw) 一致）、右键 -1
		float turn = (client.options.keyLeft.isDown() ? 1.0F : 0.0F)
				- (client.options.keyRight.isDown() ? 1.0F : 0.0F);
		if (turn == 0.0F) {
			return;
		}
		player.setYRot(player.getYRot() + turn * TURN_RATE_DEGREES);
		player.setYHeadRot(player.getYRot());   // 头一起转（第一人称镜头就是它）
	}
}
