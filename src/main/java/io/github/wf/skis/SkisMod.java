package io.github.wf.skis;

import io.github.wf.skis.registry.ModCreativeTabs;
import io.github.wf.skis.registry.ModItems;

import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 现代滑雪板 / 滑雪杖（26.2 Fabric）——「右键冲刺」变体版的入口。
 *
 * <p>这个类只做两件事：
 * <ol>
 *   <li>{@link #onInitialize()} 里把各子系统挂上：滑行物理（{@link SkiGlide}）、撑杖冲刺（{@link SkiBoost}）、
 *       落地免伤（{@link SnowLanding}）、创造页（{@link ModCreativeTabs}）；</li>
 *   <li>保留旧调用面：{@code MOD_ID} / {@code LOGGER} / 三个物品常量 / 两个判定方法。
 *       Mixin、渲染与模型类一共十几处直接读这些名字，转发一层比逐处改引用安全 ——
 *       物品定义本身在 {@link ModItems}（注册时机仍在类加载时，与拆分前一致）。</li>
 * </ol>
 *
 * <p>滑雪板装备在脚部（靴子槽）——右键从手里穿上；穿着站在雪 / 冰上自动进入滑行状态。
 * 滑雪杖：拿在手里的实体长杖，手臂平时不动；右键撑杖 = 摆臂一次 + 一段逐渐衰减的冲刺。
 */
public class SkisMod implements ModInitializer {
	public static final String MOD_ID = Constants.MOD_ID;
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 物品定义的实体在 {@link ModItems}；这里转发，保持 mixin / 渲染侧的 {@code SkisMod.MODERN_SKI} 可用。 */
	public static final Item MODERN_SKI = ModItems.MODERN_SKI;
	public static final Item SNOWBOARD = ModItems.SNOWBOARD;
	public static final Item SKI_POLE = ModItems.SKI_POLE;

	@Override
	public void onInitialize() {
		SkiGlide.register();
		SkiBoost.register();
		SnowLanding.register();   // 穿板落到雪面上免摔伤

		ModCreativeTabs.register();

		LOGGER.info("[skis] 现代滑雪板 / 滑雪单板（脚部装备）/ 滑雪杖（手持实体）已加载（右键冲刺变体）");
	}

	/** 判断物品栈是不是我们的滑雪板（转发到 {@link ModItems#isModernSki}）。 */
	public static boolean isModernSki(ItemStack stack) {
		return ModItems.isModernSki(stack);
	}

	/** 脚上的滑雪装备：双板或单板——滑行物理、摆姿都按这个判定（转发到 {@link ModItems#isSkiFeet}）。 */
	public static boolean isSkiFeet(ItemStack stack) {
		return ModItems.isSkiFeet(stack);
	}
}
