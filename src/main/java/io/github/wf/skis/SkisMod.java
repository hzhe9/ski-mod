package io.github.wf.skis;

import io.github.wf.skis.registry.ModCreativeTabs;
import io.github.wf.skis.registry.ModItems;
import io.github.wf.skis.util.IdMap;

import net.fabricmc.api.ModInitializer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
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
 *   <li>保留旧调用面：{@code MOD_ID} / {@code LOGGER} / 三个物品常量 / {@link #isSkiFeet}。
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
		SkiFlip.register();       // 空翻落地包（C2S：客户端交最终角度，服务端结算）
		SnowboardAxis.register(); // 单板板轴包（C2S：客户端交锁定的板轴，服务端覆盖式采用）

		ModCreativeTabs.register();

		LOGGER.info("[skis] 现代滑雪板 / 滑雪单板（脚部装备）/ 滑雪杖（手持实体）已加载（右键冲刺变体）");
	}

	/** 脚上的滑雪装备：双板或单板——滑行物理、摆姿都按这个判定（转发到 {@link ModItems#isSkiFeet}）。 */
	public static boolean isSkiFeet(ItemStack stack) {
		return ModItems.isSkiFeet(stack);
	}

	/**
	 * <b>滑雪运动逻辑这一刻是否生效</b>：穿着我们的板，且<b>不在创造飞行里、不是旁观者</b>。
	 *
	 * <p>这是<b>唯一</b>一条「运动逻辑该不该跑」的判据，客户端与服务端共用（服务端滑行
	 * {@link SkiGlide}、客户端输入改写 {@code ClientInputMixin}、客户端每个 tick 的运动写入
	 * {@code SkisClient}、视角限位 {@code SkiHeadLimitMixin} / {@code SkiViewSmoothing}）——
	 * 同一个条件写四遍迟早走偏，所以只留这一处。
	 *
	 * <p>创造飞行（{@code Abilities#flying}）时返回 {@code false}：速度、身体朝向、空翻状态机、
	 * 撑杖冲刺一律不写，飞行完全按原版走。判据是<b>每 tick 现读</b>的，所以落地 / 退出飞行后
	 * 下一 tick 自然恢复 —— 调用方不需要额外记状态（残留的表由各自的 {@code forget} 清）。
	 *
	 * <p><b>旁观者</b>（{@code isSpectator}）也返回 {@code false}：旁观者保留背包（靴子槽里还有板），
	 * 但它不参与任何运动 / 碰撞 —— 不排除的话它每 tick 会被写 {@code STEP_HEIGHT} / {@code GRAVITY}，
	 * 客户端还会给它算身体朝向、扬雪、切雪声。死亡玩家不用在这里判：靴子那时已经掉了。
	 *
	 * <p>骑乘<b>不</b>在这里判：那是 {@link SkiGlide} 自己的一条附加条件（它会把人请下车）。
	 */
	public static boolean isSkiMotionActive(Player player) {
		return player != null
				&& isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))
				&& !player.getAbilities().flying
				&& !player.isSpectator();
	}

	/** 穿板禁骑提示的去重间隔（tick）：同一个 id 最短 20 tick 提示一次，见 {@link #notifyNoRiding}。 */
	private static final int NO_RIDING_NOTICE_INTERVAL_TICKS = 20;

	/** 玩家 id → 上次「穿板不能上车」提示的实体 tick 数（{@code Entity#tickCount}）。 */
	private static final IdMap<Integer> NO_RIDING_NOTICE_TICK = new IdMap<>();

	/**
	 * 给玩家发一条 action bar「穿板不能上车」。
	 *
	 * <p><b>为什么要去重</b>：模组 / 连按会反复尝试上车，逐次发一条 action bar 就是刷屏，看着像坏了。
	 * 同一个 id 最短 {@link #NO_RIDING_NOTICE_INTERVAL_TICKS} tick 才有下一句。
	 */
	public static void notifyNoRiding(ServerPlayer player) {
		int tick = player.tickCount;
		Integer last = NO_RIDING_NOTICE_TICK.get(player.getId());
		if (last != null && tick - last >= 0 && tick - last < NO_RIDING_NOTICE_INTERVAL_TICKS) {
			return;
		}
		NO_RIDING_NOTICE_TICK.put(player.getId(), tick);
		player.sendOverlayMessage(Component.translatable("message.skis.no_riding"));
	}
}
