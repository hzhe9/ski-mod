package com.hermes.skis.item;

import com.hermes.skis.SkiBoost;
import com.hermes.skis.SwingHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 滑雪杖（变体版）：右键撑杖 = 摆臂一次 + 一段「逐渐减慢」的冲刺（类似滑翔时用烟花）。
 *
 * <p>手臂平时不动；右键才摆一下，并且可以连着右键反复触发（每次刷新冲刺）。
 */
public class SkiPoleItem extends Item {
	/** 0.5 秒，可以连着撑。 */
	private static final int COOLDOWN_TICKS = 10;

	public SkiPoleItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (player.getCooldowns().isOnCooldown(stack)) {
			return InteractionResult.FAIL;
		}

		if (level.isClientSide()) {
			// 只负责画面：让客户端播放一次摆臂（实现由 SkisClient 注册，服务端为空操作）
			SwingHooks.triggerSwing(player);
		} else {
			// 服务端：冲量 + 逐渐衰减的尾焰推力
			SkiBoost.burst(player);
			player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.SNOW_BREAK, SoundSource.PLAYERS, 0.7F, 1.6F);
			}

		return InteractionResult.SUCCESS;
	}
}
