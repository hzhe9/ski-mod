package com.hzhe9.skis.registry;

import com.hzhe9.skis.Constants;
import com.hzhe9.skis.item.SkiPoleItem;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

/**
 * <b>物品定义与注册</b>：双板（{@code modern_ski}）、单板（{@code snowboard}）、滑雪杖（{@code ski_pole}）。
 *
 * <p>两块板都是脚部装备（靴子槽）：右键从手里穿上，穿着站在雪 / 冰上自动进入滑行状态
 * （物理见 {@link com.hzhe9.skis.SkiGlide}）。滑雪杖是拿在手里的实体长杖
 * （右键行为见 {@link SkiPoleItem}）。数值全部是 {@code docs/tuning.md} 里那几行，改之前先看那份表。
 *
 * <p>注册 id 与资源文件名一经发布就不能改：旧存档的物品、配方、模型都按 id 引用它们。
 * 外观改名只改 {@code lang} 里的显示名。
 */
public final class ModItems {
	public static final ResourceKey<Item> MODERN_SKI_KEY = itemKey("modern_ski");
	public static final ResourceKey<Item> SKI_POLE_KEY = itemKey("ski_pole");
	public static final ResourceKey<Item> SNOWBOARD_KEY = itemKey("snowboard");

	/** 脚部外观资源：assets/skis/equipment/modern_ski.json（人形护甲层贴图）。 */
	public static final ResourceKey<EquipmentAsset> SKI_EQUIPMENT_ASSET =
			ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "modern_ski"));

	private static final Identifier SKI_ARMOR_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Constants.MOD_ID, "ski_armor");
	private static final Identifier SKI_SPEED_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Constants.MOD_ID, "ski_speed");

	/**
	 * 6 种外观款式（纯装饰，数值一律相同）。顺序即创造栏顺序：
	 * 木纹（基础款）→ 石墨 / 等高线 / 拉丝（现代）→ 霓虹 / 豹纹（图案）。
	 * 成品只带两个组件：{@code minecraft:item_model}（换图标与脚上贴图）与
	 * {@code minecraft:custom_name}（翻译键，中英各自显示）。基础款不带 item_model，走物品默认模型。
	 */
	public static final String[] VARIANTS = {"wood", "graphite", "topo", "brushed", "neon", "leopard"};

	public static final Item MODERN_SKI = registerItem(MODERN_SKI_KEY, new Item(new Item.Properties()
			.stacksTo(1)
			.setId(MODERN_SKI_KEY)
			.durability(390)
			.component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.FEET)
					.setEquipSound(SoundEvents.ARMOR_EQUIP_LEATHER)
					.setAsset(SKI_EQUIPMENT_ASSET)
					.build())
			.component(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
					.add(Attributes.ARMOR, new AttributeModifier(SKI_ARMOR_MODIFIER_ID, 2.0D,
							AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.FEET)
					.add(Attributes.MOVEMENT_SPEED, new AttributeModifier(SKI_SPEED_MODIFIER_ID, 0.010D,
							AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.FEET)
					.build())));

	public static final Item SKI_POLE = registerItem(SKI_POLE_KEY,
			new SkiPoleItem(new Item.Properties().stacksTo(1).setId(SKI_POLE_KEY)));

	/** 滑雪单板：同样装在脚部，物理与双板一致，只是外观与站姿不同（一只板、鸭子站姿）。 */
	public static final Item SNOWBOARD = registerItem(SNOWBOARD_KEY, new Item(new Item.Properties()
			.stacksTo(1)
			.setId(SNOWBOARD_KEY)
			.durability(390)
			.component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.FEET)
					.setEquipSound(SoundEvents.ARMOR_EQUIP_LEATHER)
					.setAsset(SKI_EQUIPMENT_ASSET)
					.build())
			.component(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
					.add(Attributes.ARMOR, new AttributeModifier(SKI_ARMOR_MODIFIER_ID, 2.0D,
							AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.FEET)
					.add(Attributes.MOVEMENT_SPEED, new AttributeModifier(SKI_SPEED_MODIFIER_ID, 0.010D,
							AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.FEET)
					.build())));

	private ModItems() {
	}

	/** 造一个指定款式的成品（创造栏用；合成表里由配方的 components 实现同样效果）。 */
	static ItemStack variantStack(Item item, String base, String variant) {
		ItemStack stack = new ItemStack(item);
		if (!"wood".equals(variant)) {
			stack.set(DataComponents.ITEM_MODEL,
					Identifier.fromNamespaceAndPath(Constants.MOD_ID, "item/" + base + "_" + variant));
		}
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.skis." + base + "." + variant));
		return stack;
	}

	/** 脚上的滑雪装备：双板或单板——滑行物理、摆姿都按这个判定。 */
	public static boolean isSkiFeet(ItemStack stack) {
		return stack.getItem() == MODERN_SKI || stack.getItem() == SNOWBOARD;
	}

	private static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Constants.MOD_ID, path));
	}

	private static Item registerItem(ResourceKey<Item> key, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}
}
