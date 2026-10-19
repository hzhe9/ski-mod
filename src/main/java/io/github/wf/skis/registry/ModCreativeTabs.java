package io.github.wf.skis.registry;

import io.github.wf.skis.Constants;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * <b>独立创造标签页</b>「滑雪装备」（{@code itemGroup.skis}）。
 *
 * <p>模组自己一页，不往原版「工具与实用设施」里塞：内容 = 6 款双板 + 6 款单板（{@link ModItems#VARIANTS}
 * 的顺序）+ 1 根滑雪杖。每款板只差外观组件（{@code item_model} 与 {@code custom_name}），数值相同。
 *
 * <p>标签页 id {@code skis:ski} 与 {@code lang} 里的 {@code itemGroup.skis} 是一对，改名要一起改。
 */
public final class ModCreativeTabs {
	private ModCreativeTabs() {
	}

	public static void register() {
		CreativeModeTab tab = FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.skis"))
				.icon(() -> new ItemStack(ModItems.MODERN_SKI))
				.displayItems((params, output) -> {
					for (String variant : ModItems.VARIANTS) {
						output.accept(ModItems.variantStack(ModItems.MODERN_SKI, "modern_ski", variant));
						output.accept(ModItems.variantStack(ModItems.SNOWBOARD, "snowboard", variant));
					}
					output.accept(new ItemStack(ModItems.SKI_POLE));
				})
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
				Identifier.fromNamespaceAndPath(Constants.MOD_ID, "ski"), tab);
	}
}
