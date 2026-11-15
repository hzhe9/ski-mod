package io.github.wf.skis.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkiFlip;

import io.github.wf.skis.SkisControl;
import io.github.wf.skis.SnowboardControl;
import io.github.wf.skis.SwingHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;

public class SkisClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 脚上的板：双板 SkiBoardModel、单板 SnowboardModel，各自一套层与贴图
		ModelLayerRegistry.registerModelLayer(SkiBoardModel.LAYER, SkiBoardModel::createLayerDefinition);
		ModelLayerRegistry.registerModelLayer(SnowboardModel.LAYER, SnowboardModel::createLayerDefinition);
		ArmorRenderer.register(
				context -> new BoardArmorRenderer(
						new SkiBoardModel(context.bakeLayer(SkiBoardModel.LAYER)), SkiBoardModel.TEXTURE,
						"item/modern_ski", "ski_board"),
				SkisMod.MODERN_SKI
		);
		ArmorRenderer.register(
				context -> new BoardArmorRenderer(
						new SnowboardModel(context.bakeLayer(SnowboardModel.LAYER)), SnowboardModel.TEXTURE,
						"item/snowboard", "snowboard"),
				SkisMod.SNOWBOARD
		);

		// 每 tick：推进「一次摆臂」的动画计时（手臂本身不做持续摆动）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SkiState.tick();
			// 视角限位（单板 / 双板同一套）的客户端状态：脱板 / 退出世界 / 换人时清掉，
			// 重新穿板时按当时的视角重新起算（SkiViewSmoothing 内部还会按板块种类再判一次）。
			LocalPlayer self = client.player;
			if (client.level == null || self == null || !SkisMod.isSkiMotionActive(self)) {
				SkiViewSmoothing.reset();
			}
			if (client.level == null) {
				return;
			}
			// 滑行时从板两侧溅起的雪粒子（纯客户端观感）+ 切雪声（间隔与音量随速度）
			SnowSpray.tick(client);
			GlideSounds.tick(client);
			// 双板：A / D 只转**身体**（SkisControl.BODY_YAW），W 时身体再朝视角追 2.4°/tick；视角由鼠标转、并被限在身体 ±90°
			// （client/SkiViewSmoothing 的单一 headOffset 状态，原 client/SkiSteering 已删）。
			for (Player player : client.level.players()) {
				if (!SkisMod.isSkiMotionActive(player)) {
					// 没穿板 / 创造飞行：这一 tick 一条运动逻辑都不跑（速度方向、身体朝向、空翻 / 落地状态机），
					// 本 tick 的残留状态整份清掉 —— 飞行期间不写速度、也不留半个状态机；
					// 落地 / 退出飞行后下一 tick 判据自然为真，各表从零重新起算。
					SnowboardControl.forget(player.getId());
					SkisControl.forgetBody(player.getId());
					// 空中状态 / 失控计时由上面两个 forget 内部的 SkiAir.forget 一起清，这里不再补第三遍
					continue;
				}
				// 起跳力度 / 后空翻 / 落地：空翻角度与「有没有按住空格」只有本机知道（服务端读不到这个输入），
				// 所以状态机只对本机玩家跑；落地那一 tick 把最终角度发给服务端（同一份判定在那边结算 + 降速）。
				// 26.2 里 self.input 与 keyPresses 都不会为 null（ClientInput 构造把 keyPresses 初始化成
				// Input.EMPTY，LocalPlayer 构造总是 new ClientInput(...)）—— 所以这里只判「是不是本机」。
				Input keys = player == self ? self.input.keyPresses : null;
				if (player == self) {
					boolean holdingJump = keys != null && keys.jump();
					SkiAir.tickClient(player, SkiAir.tuning(player), holdingJump,
							(angle, clean) -> ClientPlayNetworking.send(new SkiFlip.Payload((float) angle)));
					// 起跳**不在这里**：END_CLIENT_TICK 时玩家已经 tick / 位移完，冲量要晚一 tick 才生效，
					// 而服务端的 SetEntityMotion 包常在下一 tick 开始前把 deltaMovement 整份覆盖掉（穿板按空格"没反应"）。
					// 起跳挪到实体 tick 内、travel 之前：client mixin LocalPlayerJumpMixin（LocalPlayer#aiStep 的 HEAD）。
					// 这里只留空翻角度 / 落地判定 / 落地发包这一套。
				}
				boolean board = SnowboardControl.isSnowboard(player);
				float yaw;
				if (board) {
					// ——— 单板：carve（前倾 + 横移 → 身体朝向持续转），与服务端同一套规则 ———
					SnowboardControl.ensureLocked(player.getId(), player.getYRot());
					SnowboardControl.applyCarve(player, keys, player.getDeltaMovement().horizontalDistance());
					// 速度方向朝板轴限幅回正 —— 只对本机玩家跑：位置是**客户端权威**，本机速度才是
					// 真正用于位移的那个；服务端那份写入要算出误差才发 SetEntityMotion，客户端撞墙
					// 把方向裁歪时服务端没有误差、不发包，所以这里必须自己再掰一次（同一份实现）。
					// 非本机玩家的速度由服务端同步，本机不改。
					if (player == self) {
						SnowboardControl.alignCarveHeading(player, keys);
					}
					yaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
				} else {
					// ——— 双板：A / D 转身体 2.0°/tick，W 再朝视角方向补 2.4°/tick ———
					// 视角本身仍由鼠标管（限位见 SkiViewSmoothing），这里只写身体朝向，与服务端同一条规则
					SkisControl.ensureLocked(player.getId(), player.getYRot());
					SkisControl.turnBody(player.getId(), player.getYRot(), SnowboardControl.sideSign(keys));
					// 越肩视角下跳过：相机一自由，按住 W 身体就会追着相机转（越肩那边自己会转视角）
					if (keys != null && keys.forward() && !ShoulderSurfingCompat.isActive()) {
						SkisControl.turnTowardView(player.getId(), player.getYRot(), player.getYRot());
					}
					// yaw 必须在 turnTowardView 之后取
					yaw = SkisControl.bodyYaw(player.getId(), player.getYRot());
				}
				// 把身体写成锁定的朝向 —— 转视角只转头，不写这一句会被原版拉着跟视角转。
				player.yBodyRot = yaw;
				if (!board) {
					// 双板：连 yBodyRotO 一起对齐。每 tick 4.5° 的步长也不小，留它在上一帧会让模型比按键慢一拍、转身发飘。
					player.yBodyRotO = yaw;
				}
				// 单板：yBodyRotO 故意不覆盖 —— baseTick 每 tick 把它记成「上一 tick 的 yBodyRot」，
				// 留着它第三人称里身体的转动才是插值过去的（覆盖掉就会每 tick 跳 2°）。
				// ——— 越肩视角：头模型朝横移方向 ———
				// 相机自由后「视角」不再代表朝向，头改跟身体走：双板 = 正前方（身体朝向 + 0），
				// 单板 = 身体朝向 + 带符号的头偏角（朝哪侧滑就朝哪侧；幅度 75 / 前倾 90 / 后倾 60，
				// 翻转与幅度变化都由 SnowboardControl.tickHead 平滑推进，不瞬跳）。
				// 只写本地玩家（别人的头不该被本机的越肩状态改造）；
				// yHeadRotO 不写成等于当前值 —— 原版 tickHeadTurn 每 tick 会把它记成上一 tick 的头朝向，
				// 留着插值头才是平滑转过去的，覆盖成同值会硬跳。
				if (player == self) {
					if (board) {
						// 偏角状态每 tick 都推进（不在越肩时也保持热态：开关越肩不会突然从 0° 扫过来）
						SnowboardControl.tickHead(player, keys);
					}
					if (ShoulderSurfingCompat.isActive()) {
						player.setYHeadRot(yaw + (board ? SnowboardControl.headOffset(player.getId()) : 0.0F));
					}
				}
			}
			// 单板：记录 W / S 的俯仰姿态（+1 前倾 / -1 后倾），模型那边读它来摆身体
			if (self != null) {
				if (!SkisMod.isSkiMotionActive(self) || !SnowboardControl.isSnowboard(self)) {
					// 没穿板 / 创造飞行：姿态回中立（飞行时按 W 不该让模型前倾）
					SnowboardControl.setTiltTarget(self.getId(), 0);
					SnowboardControl.tickTilt(self.getId());
				} else {
					Input keys = self.input.keyPresses;
					// W 与 S 同按（快速切换时的一帧残留）判中立，免得“单按 S 却跳成前倾”
					boolean forward = keys != null && keys.forward();
					boolean backward = keys != null && keys.backward();
					int tilt = forward == backward ? 0 : (forward ? 1 : -1);
					SnowboardControl.setTiltTarget(self.getId(), tilt);
					SnowboardControl.tickTilt(self.getId());
					// 注意：这里不「每 tick 硬夹」视角。视角限位只有一处权威状态
					// （client/SkiViewSmoothing 的 headOffset），按帧写、按帧判断，不会有两处基准互相拉回。
				}
			}
		});

		// 物品那边右键时通过这个钩子让客户端播放一次摆臂
		SwingHooks.setListener(player -> SkiState.triggerSwing(player.getId()));

		// First Person Model（若装了）：空翻期间关掉它的第一人称身体 ——
		// 它把玩家实体按「世界坐标」搬一个 offset，而我们绕腰翻，翻到 ≈90° 时身体会被搬进镜头。
		// 反射探测、对方不在时零影响（见 FirstPersonModelCompat 的 javadoc）。
		FirstPersonModelCompat.install();
	}
}
