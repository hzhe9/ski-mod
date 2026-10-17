package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import java.util.HashMap;
import java.util.Map;

import com.hermes.skis.SkisControl;
import com.hermes.skis.SnowboardControl;
import com.hermes.skis.SwingHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
			if (client.level == null || self == null
					|| !SkisMod.isSkiFeet(self.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))) {
				SkiViewSmoothing.reset();
			}
			if (client.level == null) {
				return;
			}
			// 滑行时从板两侧溅起的雪粒子（纯客户端观感）+ 切雪声（间隔与音量随速度）
			SnowSpray.tick(client);
			GlideSounds.tick(client);
			// 双板：A / D 只转**身体**（SkisControl.BODY_YAW）；视角由鼠标转、并被限在身体 ±135°
			// （client/SkiViewSmoothing 的单一 headOffset 状态，原 client/SkiSteering 已删）。
			for (Player player : client.level.players()) {
				if (!SkisMod.isSkiFeet(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))) {
					SnowboardControl.forget(player.getId());
					SkisControl.forgetBody(player.getId());
					continue;
				}
				// 单板 / 双板各自一套，互不干涉
				Input keys = player == client.player && client.player != null
						? client.player.input.keyPresses : null;
				boolean board = SnowboardControl.isSnowboard(player);
				float yaw;
				if (board) {
					// ——— 单板：carve（前倾 + 横移 → 身体朝向持续转），与服务端同一套规则 ———
					SnowboardControl.ensureLocked(player.getId(), player.getYRot());
					SnowboardControl.applyCarve(player, keys, player.getDeltaMovement().horizontalDistance());
					yaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
				} else {
					// ——— 双板：A / D 只转身体，视角一点都不跟（与服务端同一份按键、同一个速率）———
					SkisControl.ensureLocked(player.getId(), player.getYRot());
					SkisControl.turnBody(player.getId(), player.getYRot(), SnowboardControl.sideSign(keys));
					yaw = SkisControl.bodyYaw(player.getId(), player.getYRot());
				}
				// 把身体写成锁定的朝向 —— 转视角只转头，不写这一句会被原版拉着跟视角转。
				player.yBodyRot = yaw;
				if (!board) {
					// 双板：连 yBodyRotO 一起对齐。每 tick 8° 的步长不小，让它插值反而拖泥带水（用户要干脆的转身）。
					player.yBodyRotO = yaw;
				}
				// 单板：yBodyRotO 故意不覆盖 —— baseTick 每 tick 把它记成「上一 tick 的 yBodyRot」，
				// 留着它第三人称里身体的转动才是插值过去的（覆盖掉就会每 tick 跳 2°）。
			}
			// 单板：记录 W / S 的俯仰姿态（+1 前倾 / -1 后倾），模型那边读它来摆身体
			if (self != null) {
				if (!SnowboardControl.isSnowboard(self)) {
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
	}
}
