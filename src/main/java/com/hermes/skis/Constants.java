package com.hermes.skis;

/**
 * 模组的固定标识。
 *
 * <p>注册 id 的命名空间（{@code skis:modern_ski} 这些）、资源路径前缀（{@code textures/entity/...}）
 * 与日志名都取这一个常量：改它会断掉旧存档里的物品 / 配方 / 模型引用，所以只允许出现一次。
 */
public final class Constants {
	public static final String MOD_ID = "skis";

	private Constants() {
	}
}
