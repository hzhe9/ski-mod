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
			if (client.level == null) {
				return;
			}
			// 滑行时从板两侧溅起的雪粒子（纯客户端观感）+ 切雪声（间隔与音量随速度）
			SnowSpray.tick(client);
			GlideSounds.tick(client);
			// 双板 A / D 转向（每 tick 4°）不在这里做：镜头每帧才读 yRot，
			// 见 client/SkiViewSmoothing（挂 Camera#alignWithEntity，按帧均摊）。
			// 单板：人板一体 —— 锁住身体朝向（头 / 视角随便转，身体不跟着转）
			for (Player player : client.level.players()) {
				if (!SkisMod.isSkiFeet(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))) {
					SnowboardControl.forget(player.getId());
					SkisControl.forget(player.getId());
					continue;
				}
				// 单板 / 双板各自一套，互不干涉
				float yaw;
				if (SnowboardControl.isSnowboard(player)) {
					// ——— 单板：carve（前倾 + 横移 → 身体朝向持续转），与服务端同一套规则 ———
					SnowboardControl.ensureLocked(player.getId(), player.getYRot());
					Input keys = player == client.player && client.player != null
							? client.player.input.keyPresses : null;
					SnowboardControl.applyCarve(player, keys, player.getDeltaMovement().horizontalDistance());
					yaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
				} else {
					// ——— 双板：什么都别管 —— 朝向就是视角（用户定案回到最开始的状态）———
					continue;
				}
				// 单板：把身体写成锁定朝向 —— 转视角只转头，不写这一句会被原版拉着跟视角转。
				// yBodyRotO 故意不覆盖：baseTick 每 tick 把它记成「上一 tick 的 yBodyRot」，
				// 留着它第三人称里身体的转动才是插值过去的（覆盖掉就会每 tick 跳 2°）。
				player.yBodyRot = yaw;
			}
			// 单板：记录 W / S 的俯仰姿态（+1 前倾 / -1 后倾），模型那边读它来摆身体
			LocalPlayer self = client.player;
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
					// 注意：这里不再「每 tick 硬夹」视角。carve 把身体转过去之后，视角是靠
					// client/SkiViewSmoothing 用**插值后的身体朝向**每帧夹一次的（硬夹会让镜头每 tick 跳 2°）。
				}
			}
		});

		// 物品那边右键时通过这个钩子让客户端播放一次摆臂
		SwingHooks.setListener(player -> SkiState.triggerSwing(player.getId()));
	}
}
