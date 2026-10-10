package com.hermes.skis;

import net.minecraft.world.entity.player.Player;

/**
 * 客户端动画钩子。
 *
 * <p>{@code src/main} 源集编译时看不到 {@code src/client} 源集（依赖方向是 client → main），
 * 所以物品（main 里的通用代码）不能直接调用客户端的动画类。这里留一个接口，
 * 由客户端初始化时（{@code SkisClient}）填上实现：客户端有实现，专用服务端保持 null，
 * {@link #triggerSwing(Player)} 自然就变成空操作。
 */
public final class SwingHooks {
	public interface Listener {
		void onSwing(Player player);
	}

	private static Listener listener;

	private SwingHooks() {
	}

	public static void setListener(Listener value) {
		listener = value;
	}

	/** 右键撑杖时调用：让客户端播放一次摆臂（服务端为 null 时什么都不做）。 */
	public static void triggerSwing(Player player) {
		if (listener != null) {
			listener.onSwing(player);
		}
	}
}
