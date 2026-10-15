package com.hermes.skis;

import com.hermes.skis.item.SkiPoleItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
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
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 现代滑雪板 / 滑雪杖（26.2 Fabric）——「右键冲刺」变体版。
 *
 * <p>滑雪板：装备在脚部（靴子槽）——右键从手里穿上；穿着站在雪/冰上自动进入滑行状态，逻辑见 {@link SkiGlide}。
 * <p>滑雪杖：拿在手里的实体长杖，手臂平时不动；右键撑杖 = 摆臂一次 + 一段逐渐衰减的冲刺（{@link SkiBoost}）。
 * <p>滑雪头盔：普通物品（有图标 / 显示名 / 配方，**不可穿戴**），不参与滑行物理。
 */
public class SkisMod implements ModInitializer {
	public static final String MOD_ID = "skis";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> MODERN_SKI_KEY = itemKey("modern_ski");
	public static final ResourceKey<Item> SKI_POLE_KEY = itemKey("ski_pole");
	public static final ResourceKey<Item> SKI_HELMET_KEY = itemKey("ski_helmet");
	public static final ResourceKey<Item> SNOWBOARD_KEY = itemKey("snowboard");

	/** 脚部外观资源：assets/skis/equipment/modern_ski.json（人形护甲层贴图）。 */
	public static final ResourceKey<EquipmentAsset> SKI_EQUIPMENT_ASSET =
			ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath(MOD_ID, "modern_ski"));

	private static final Identifier SKI_ARMOR_MODIFIER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ski_armor");
	private static final Identifier SKI_SPEED_MODIFIER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ski_speed");

	/**
	 * 6 种外观款式（纯装饰，数值一律相同）。顺序即创造栏顺序：
	 * 木纹（基础款）→ 石墨 / 等高线 / 拉丝（现代）→ 霓虹 / 豹纹（图案）。
	 * 成品只带两个组件：{@code minecraft:item_model}（换图标与脚上贴图）与
	 * {@code minecraft:custom_name}（翻译键，中英各自显示）。基础款不带 item_model，走物品默认模型。
	 */
	public static final String[] VARIANTS = {"wood", "graphite", "topo", "brushed", "neon", "leopard"};

	/** 造一个指定款式的成品（创造栏用；合成表里由配方的 components 实现同样效果）。 */
	private static ItemStack variantStack(Item item, String base, String variant) {
		ItemStack stack = new ItemStack(item);
		if (!"wood".equals(variant)) {
			stack.set(DataComponents.ITEM_MODEL,
					Identifier.fromNamespaceAndPath(MOD_ID, "item/" + base + "_" + variant));
		}
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.skis." + base + "." + variant));
		return stack;
	}

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

	/**
	 * 滑雪保护头盔：**普通物品** —— 只有图标、显示名、配方与创造页条目，
	 * 不带 {@code EQUIPPABLE} / 属性修正，不参与滑行物理（能不能真戴在头上不在本轮范围）。
	 */
	public static final Item SKI_HELMET = registerItem(SKI_HELMET_KEY,
			new Item(new Item.Properties().stacksTo(1).setId(SKI_HELMET_KEY)));

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

	@Override
	public void onInitialize() {
		SkiGlide.register();
		SkiBoost.register();
		SnowLanding.register();   // 穿板落到雪面上免摔伤

		// 独立创造标签页（mod 常规做法；不再往原版「工具与实用设施」页里塞）
		CreativeModeTab tab = FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.skis"))
				.icon(() -> new ItemStack(MODERN_SKI))
				.displayItems((params, output) -> {
					for (String variant : VARIANTS) {
						output.accept(variantStack(MODERN_SKI, "modern_ski", variant));
						output.accept(variantStack(SNOWBOARD, "snowboard", variant));
					}
					output.accept(new ItemStack(SKI_POLE));
					output.accept(new ItemStack(SKI_HELMET));
				})
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
				Identifier.fromNamespaceAndPath(MOD_ID, "ski"), tab);

		LOGGER.info("[skis] 现代滑雪板 / 滑雪单板（脚部装备）/ 滑雪杖（手持实体）/ 滑雪头盔（装饰物品）已加载（右键冲刺变体）");
	}

	/** 判断物品栈是不是我们的滑雪板（26.2 的 ItemStack 只剩 is(Predicate)，直接比物品实例）。 */
	public static boolean isModernSki(ItemStack stack) {
		return stack.getItem() == MODERN_SKI;
	}

	/** 脚上的滑雪装备：双板或单板——滑行物理、摆姿都按这个判定。 */
	public static boolean isSkiFeet(ItemStack stack) {
		return stack.getItem() == MODERN_SKI || stack.getItem() == SNOWBOARD;
	}

	private static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, path));
	}

	private static Item registerItem(ResourceKey<Item> key, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}
}
