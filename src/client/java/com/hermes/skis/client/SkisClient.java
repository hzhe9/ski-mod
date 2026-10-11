package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import com.hermes.skis.SnowboardControl;
import com.hermes.skis.SwingHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class SkisClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 脚上的板：双板 SkiBoardModel、单板 SnowboardModel，各自一套层与贴图
		ModelLayerRegistry.registerModelLayer(SkiBoardModel.LAYER, SkiBoardModel::createLayerDefinition);
		ModelLayerRegistry.registerModelLayer(SnowboardModel.LAYER, SnowboardModel::createLayerDefinition);
		ArmorRenderer.register(
				context -> new BoardArmorRenderer(
						new SkiBoardModel(context.bakeLayer(SkiBoardModel.LAYER)), SkiBoardModel.TEXTURE),
				SkisMod.MODERN_SKI
		);
		ArmorRenderer.register(
				context -> new BoardArmorRenderer(
						new SnowboardModel(context.bakeLayer(SnowboardModel.LAYER)), SnowboardModel.TEXTURE),
				SkisMod.SNOWBOARD
		);

		// 每 tick：推进「一次摆臂」的动画计时（手臂本身不做持续摆动）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SkiState.tick();
			if (client.level == null) {
				return;
			}
			// 单板：人板一体 —— 锁住身体朝向（头 / 视角随便转，身体不跟着转）
			for (Player player : client.level.players()) {
				if (!SnowboardControl.isSnowboard(player)) {
					SnowboardControl.forget(player.getId());
					continue;
				}
				SnowboardControl.ensureLocked(player.getId(), player.getYRot());
				float yaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
				player.yBodyRot = yaw;
				player.yBodyRotO = yaw;   // 上一 tick 一起写，避免插值把身体拉回视角方向
			}
			// 单板：完全站定 —— 水平速度清零（坡上 / 冰上也滑不动）
			LocalPlayer self = client.player;
			if (self != null && SnowboardControl.isSnowboard(self)) {
				Vec3 motion = self.getDeltaMovement();
				self.setDeltaMovement(0.0D, motion.y, 0.0D);
			}
		});

		// 物品那边右键时通过这个钩子让客户端播放一次摆臂
		SwingHooks.setListener(player -> SkiState.triggerSwing(player.getId()));
	}
}
