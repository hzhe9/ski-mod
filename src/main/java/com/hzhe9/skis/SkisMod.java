package com.hzhe9.skis;

import com.hzhe9.skis.item.SkiPoleItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modern Skis / Ski Pole (26.2 Fabric).
 *
 * <p>Skis: equipped in the feet slot (boots) — right-click to put them on from the hand; wearing
 * them while standing on snow/ice automatically enters the gliding state (movement speed bonus plus
 * a bit of forward inertia), see {@link SkiGlide} for the logic.
 * <p>Ski Pole: a physical long pole held in the hand; right-click to plant it and push forward.
 */
public class SkisMod implements ModInitializer {
	public static final String MOD_ID = "skis";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> MODERN_SKI_KEY = itemKey("modern_ski");
	public static final ResourceKey<Item> SKI_POLE_KEY = itemKey("ski_pole");

	/** Foot appearance asset: assets/skis/equipment/modern_ski.json (humanoid armor layer texture). */
	public static final ResourceKey<EquipmentAsset> SKI_EQUIPMENT_ASSET =
			ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.fromNamespaceAndPath(MOD_ID, "modern_ski"));

	private static final Identifier SKI_ARMOR_MODIFIER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ski_armor");
	private static final Identifier SKI_SPEED_MODIFIER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ski_speed");

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

	@Override
	public void onInitialize() {
		SkiGlide.register();

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(new ItemStack(MODERN_SKI), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			output.accept(new ItemStack(SKI_POLE), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
		});

		LOGGER.info("[skis] Modern Skis (foot equipment) / Ski Pole (held item) loaded");
	}

	/** Checks whether the stack is our ski board (26.2's ItemStack only has is(Predicate), so compare item instances directly). */
	public static boolean isModernSki(ItemStack stack) {
		return stack.getItem() == MODERN_SKI;
	}

	private static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, path));
	}

	private static Item registerItem(ResourceKey<Item> key, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}
}
