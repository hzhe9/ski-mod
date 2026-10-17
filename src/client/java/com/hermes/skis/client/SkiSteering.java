package com.hermes.skis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
 * <p><b>为什么写成「每帧」而不是「每 tick」</b>：{@code LocalPlayer#getViewYRot(float)} 在不是乘客时
 * 直接返回裸 {@code getYRot()}（把 partialTick 丢了），相机读的正是它 —— 所以按 tick 写 4°，
 * 在镜头里就是 20 Hz 一档一档地跳。{@link #frame} 由 {@link SkiViewSmoothing} 每帧调用，
 * 把这一 tick 的 {@code TURN_RATE_DEGREES} 按 {@code step}（0..1）拆到各帧上，总量不变。
 *
 * <p>单板不参与：它的 A / D 是沿身体左右平移（见 {@code SnowboardControl} 的 carve 那套）。
 */
public final class SkiSteering {
	/** 双板：按住 A / D 时每 tick 转多少度（4.0°/tick ≈ 80°/s，180° 约 2.25 s）。 */
	private static final float TURN_RATE_DEGREES = 4.0F;

	private SkiSteering() {
	}

	/**
	 * 每帧一步（由 {@link SkiViewSmoothing#frame} 调用）：这一帧该转的角度 = 每 tick 的角度 × {@code step}。
	 *
	 * @param step {@code partialTick} 的帧间增量（0..1，跨 tick 时已回绕补 1）
	 */
	public static void frame(LocalPlayer player, float step) {
		if (step <= 0.0F) {
			return;   // 暂停 / 同一帧被调了两次：别动
		}
		if (player.getAbilities().flying || player.isPassenger()) {
			return;   // 创造飞行 / 骑乘时不接管转向
		}
		// 左键 +1、右键 -1。MC 的水平朝向向量是 (-sin yaw, cos yaw)：yaw 0 朝 +z，yaw +90° 朝 -x，
		// 所以 <b>yaw 增大 = 向右转</b> —— 按 A（左）要 yaw 减小，按 D（右）要 yaw 增大。
		float turn = (Minecraft.getInstance().options.keyLeft.isDown() ? 1.0F : 0.0F)
				- (Minecraft.getInstance().options.keyRight.isDown() ? 1.0F : 0.0F);
		if (turn == 0.0F) {
			return;
		}
		turnView(player, -turn * TURN_RATE_DEGREES * step);
	}

	/**
	 * 转视角一个「增量」，<b>四个朝向字段一起挪同样的量</b>：
	 * {@code yRot}（朝向本体）+ {@code yRotO}（上一 tick 的朝向＝帧间插值起点）+
	 * {@code yHeadRot} / {@code yHeadRotO}（头）。
	 *
	 * <p>只动 {@code yRot} 的话 {@code yRotO} 会拖在后面（原版 {@code Entity#turn} 是两者同加），
	 * 任何走 {@code getYRot(partial)} / {@code Mth.rotLerp} 的地方（乘客分支、第三人称模型）都会被拖慢。
	 */
	public static void turnView(LocalPlayer player, float degrees) {
		if (degrees == 0.0F || !Float.isFinite(degrees)) {
			return;
		}
		player.setYRot(player.getYRot() + degrees);
		player.yRotO += degrees;
		player.setYHeadRot(player.getYHeadRot() + degrees);
		player.yHeadRotO += degrees;
	}
}
