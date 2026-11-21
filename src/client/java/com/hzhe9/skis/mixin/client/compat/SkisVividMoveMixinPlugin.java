package com.hzhe9.skis.mixin.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * Vivid Move 兼容配置的开关：<b>只有装着 Vivid Move 时这套 mixin 才参与</b>。
 *
 * <p>没装时 {@link #shouldApplyMixin} 一律返回 {@code false} —— 目标类根本不被触碰，
 * 不产生 “target class not found”、不报错、对启动与性能零影响。
 *
 * <p>这套兼容<b>没有 compileOnly 依赖</b>（对方是 All Rights Reserved，jar 不供别人构建）：
 * 注入目标全部用 {@code targets = "..."} 字符串 + {@code remap = false}，
 * 编译期不出现任何 {@code com.vividmove.*} 类型。
 */
public class SkisVividMoveMixinPlugin implements IMixinConfigPlugin {
	private static final String VIVID_MOVE_MOD_ID = "vivid_move";

	@Override
	public void onLoad(String mixinPackage) {
		// 无需初始化；判定放在 shouldApplyMixin 里每次现问（模组加载顺序无关）。
	}

	@Override
	public String getRefMapperConfig() {
		return null;   // 目标类不在映射表里（remap = false），不需要 refmap
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return FabricLoader.getInstance().isModLoaded(VIVID_MOVE_MOD_ID);
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
