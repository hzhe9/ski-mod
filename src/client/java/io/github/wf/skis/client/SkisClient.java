package io.github.wf.skis.client;

import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkiFlip;

import io.github.wf.skis.AlpineSkiControl;
import io.github.wf.skis.SnowboardControl;
import io.github.wf.skis.SwingHooks;
import java.util.function.Function;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

public class SkisClient implements ClientModInitializer {
	/**
	 * 上一次 tick 见到的 {@link ClientLevel}（换世界 / 换维度 / 断线重连都会换实例）。
	 *
	 * <p>这就是「换世界清理点」的触发方式：这里没有可靠的单次事件覆盖维度切换
	 * （{@code ClientPlayConnectionEvents.JOIN} 只在连上服务器时发），所以每 tick 比一次实例，
	 * 变了就把所有「按 entity id 记」的客户端状态整表丢掉（见 {@link #resetWorldState()}）。
	 */
	private static ClientLevel trackedLevel;

	@Override
	public void onInitializeClient() {
		registerRenderers();
		registerTickHandler();

		// 物品那边右键时通过这个钩子让客户端播放一次摆臂
		SwingHooks.setListener(player -> SkiState.triggerSwing(player.getId()));

		// First Person Model（若装了）：空翻期间关掉它的第一人称身体 ——
		// 它把玩家实体按「世界坐标」搬一个 offset，而我们绕腰翻，翻到 ≈90° 时身体会被搬进镜头。
		// 反射探测、对方不在时零影响（见 FirstPersonModelCompat 的 javadoc）。
		FirstPersonModelCompat.install();

		// 连上服务器 / 单机开世界：最早的确定时点，先把上一份世界的残留丢掉
		// （那一刻 client.level 可能还是 null，所以同一个方法还挂在每 tick 的 level 实例比对上）。
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			trackedLevel = client.level;
			resetWorldState();
		});
	}

	/** 装脚部外观渲染器：两块板各一套模型层与贴图。 */
	private static void registerRenderers() {
		// 脚上的板：双板 AlpineSkiModel、单板 SnowboardModel，各自一套层与贴图
		ModelLayerRegistry.registerModelLayer(AlpineSkiModel.LAYER, AlpineSkiModel::createLayerDefinition);
		ModelLayerRegistry.registerModelLayer(SnowboardModel.LAYER, SnowboardModel::createLayerDefinition);
		registerBoard(SkisMod.MODERN_SKI, AlpineSkiModel.LAYER, AlpineSkiModel.TEXTURE,
				AlpineSkiModel::new, "item/modern_ski", "ski_board");
		registerBoard(SkisMod.SNOWBOARD, SnowboardModel.LAYER, SnowboardModel.TEXTURE,
				SnowboardModel::new, "item/snowboard", "snowboard");
	}

	/** 每 tick 的客户端驱动：换世界清理 → 粒子 / 声音 → 每个玩家的运动 → 本机姿态。 */
	private static void registerTickHandler() {
		// 每 tick：推进「一次摆臂」的动画计时（手臂本身不做持续摆动）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// 换世界 / 换维度 / 重连：先把上一份世界里的 id 状态整表丢掉（见 resetWorldState）
			if (client.level != trackedLevel) {
				trackedLevel = client.level;
				resetWorldState();
			}
			SkiState.tick();
			// 视角限位（单板 / 双板同一套）的客户端状态：脱板 / 退出世界 / 换人时清掉，
			// 重新穿板时按当时的视角重新起算（SkiViewSmoothing 内部还会按板块种类再判一次）。
			LocalPlayer self = client.player;
			if (client.level == null || self == null || !SkisMod.isSkiMotionActive(self)) {
				SkiViewSmoothing.reset();
			}
			if (client.level == null) {
				return;
			}
			// 滑行时从板两侧溅起的雪粒子（纯客户端观感）+ 切雪声（间隔与音量随速度）
			SnowSpray.tick(client);
			GlideSounds.tick(client);
			tickMotion(client, self);
			// 单板：记录 W / S 的俯仰姿态（+1 前倾 / -1 后倾），模型那边读它来摆身体
			tickAttitude(self);
		});
	}

	/**
	 * 每个玩家这一 tick 的运动结算（远端玩家只写朝向、不写速度）。
	 */
	private static void tickMotion(Minecraft client, LocalPlayer self) {
		// 双板：A / D 只转**身体**（AlpineSkiControl.BODY_YAW），W 时身体再朝视角追 4.8°/tick；视角由鼠标转、并被限在身体 ±90°
		// （client/SkiViewSmoothing 的单一 headOffset 状态，原 client/SkiSteering 已删）。
		for (Player player : client.level.players()) {
			tickPlayerMotion(player, self);
		}
	}

	/** 一个玩家：状态清理 → 本机空翻 / 落地 → 朝向（身体 + 头）。 */
	private static void tickPlayerMotion(Player player, LocalPlayer self) {
		if (!SkisMod.isSkiMotionActive(player)) {
			// 没穿板 / 创造飞行 / 旁观：这一 tick 一条运动逻辑都不跑（速度方向、身体朝向、空翻 / 落地状态机），
			// 本 tick 的残留状态整份清掉 —— 飞行期间不写速度、也不留半个状态机；
			// 落地 / 退出飞行后下一 tick 判据自然为真，各表从零重新起算。
			// 传 true ＝ 只清客户端那一端的表：单机里服务端与客户端共用 entity id，
			// 客户端清表不能把服务端的失控 / 起跳冷却一起抹掉（见 SkiAir#forgetClient）。
			SnowboardControl.forget(player.getId(), true);
			AlpineSkiControl.forgetBody(player.getId(), true);
			return;
		}
		// 起跳力度 / 后空翻 / 落地：空翻角度与「有没有按住空格」只有本机知道（服务端读不到这个输入），
		// 所以状态机只对本机玩家跑；落地那一 tick 把最终角度发给服务端（同一份判定在那边结算 + 降速）。
		// 26.2 里 self.input 与 keyPresses 都不会为 null（ClientInput 构造把 keyPresses 初始化成
		// Input.EMPTY，LocalPlayer 构造总是 new ClientInput(...)）—— 所以这里只判「是不是本机」。
		Input keys = player == self ? self.input.keyPresses : null;
		if (player == self) {
			boolean holdingJump = keys != null && keys.jump();
			SkiAir.tickClient(player, SkiAir.tuning(player), holdingJump,
					(angle, clean) -> ClientPlayNetworking.send(new SkiFlip.Payload((float) angle)));
			// 起跳**不在这里**：END_CLIENT_TICK 时玩家已经 tick / 位移完，冲量要晚一 tick 才生效，
			// 而服务端的 SetEntityMotion 包常在下一 tick 开始前把 deltaMovement 整份覆盖掉（穿板按空格"没反应"）。
			// 起跳挪到实体 tick 内、travel 之前：client mixin LocalPlayerJumpMixin（LocalPlayer#aiStep 的 HEAD）。
			// 这里只留空翻角度 / 落地判定 / 落地发包这一套。
		}
		tickBodyAndHead(player, self, keys);
	}

	/** 两块板各自的朝向规则 + 写回身体朝向 + 越肩视角下的头。 */
	private static void tickBodyAndHead(Player player, LocalPlayer self, Input keys) {
		boolean board = SnowboardControl.isSnowboard(player);
		float yaw;
		if (board) {
			// ——— 单板：carve（前倾 + 横移 → 身体朝向持续转），与服务端同一套规则 ———
			// 客户端这一端一律传 true：写 / 读客户端那张表（服务端写它自己那张，见 SnowboardControl#table）。
			SnowboardControl.ensureLocked(player.getId(), player.getYRot(), true);
			SnowboardControl.applyCarve(player, keys, player.getDeltaMovement().horizontalDistance());
			// 速度方向朝板轴限幅回正 —— 只对本机玩家跑：位置是**客户端权威**，本机速度才是
			// 真正用于位移的那个；服务端那份写入要算出误差才发 SetEntityMotion，客户端撞墙
			// 把方向裁歪时服务端没有误差、不发包，所以这里必须自己再掰一次（同一份实现）。
			// 非本机玩家的速度由服务端同步，本机不改。
			if (player == self) {
				SnowboardControl.alignCarveHeading(player, keys);
			}
			yaw = SnowboardControl.bodyYaw(player.getId(), player.getYRot(), true);
		} else {
			// ——— 双板：A / D 转身体 4.0°/tick，W 再朝视角方向补 4.8°/tick ———
			// 视角本身仍由鼠标管（限位见 SkiViewSmoothing），这里只写身体朝向，与服务端同一条规则
			AlpineSkiControl.ensureLocked(player.getId(), player.getYRot(), true);
			AlpineSkiControl.turnBody(player.getId(), player.getYRot(), SnowboardControl.sideSign(keys), true);
			// 越肩视角下跳过：相机一自由，按住 W 身体就会追着相机转（越肩那边自己会转视角）
			if (keys != null && keys.forward() && !ShoulderSurfingCompat.isActive()) {
				AlpineSkiControl.turnTowardView(player.getId(), player.getYRot(), player.getYRot(), true);
			}
			// yaw 必须在 turnTowardView 之后取
			yaw = AlpineSkiControl.bodyYaw(player.getId(), player.getYRot(), true);
		}
		// 把身体写成锁定的朝向 —— 转视角只转头，不写这一句会被原版拉着跟视角转。
		// <b>只写本机玩家</b>：远端玩家的 yBodyRot 由服务端同步，客户端每 tick 的写入基本是白做；
		// 双板那句「连 yBodyRotO 一起对齐」还会抹掉远端的转动插值（单板分支刻意保留插值）。
		if (player == self) {
			player.yBodyRot = yaw;
			if (!board) {
				// 双板：连 yBodyRotO 一起对齐。每 tick 4.0° 的步长也不小，留它在上一帧会让模型比按键慢一拍、转身发飘。
				player.yBodyRotO = yaw;
			}
			// 单板：yBodyRotO 故意不覆盖 —— baseTick 每 tick 把它记成「上一 tick 的 yBodyRot」，
			// 留着它第三人称里身体的转动才是插值过去的（覆盖掉就会每 tick 跳 2°）。
		}
		// ——— 越肩视角：头模型朝横移方向 ———
		// 相机自由后「视角」不再代表朝向，头改跟身体走：双板 = 正前方（身体朝向 + 0），
		// 单板 = 身体朝向 + 带符号的头偏角（朝哪侧滑就朝哪侧；幅度 75 / 前倾 90 / 后倾 60，
		// 翻转与幅度变化都由 SnowboardControl.tickHead 平滑推进，不瞬跳）。
		// 只写本地玩家（别人的头不该被本机的越肩状态改造）；
		// yHeadRotO 不写成等于当前值 —— 原版 tickHeadTurn 每 tick 会把它记成上一 tick 的头朝向，
		// 留着插值头才是平滑转过去的，覆盖成同值会硬跳。
		if (player == self) {
			if (board) {
				// 偏角状态每 tick 都推进（不在越肩时也保持热态：开关越肩不会突然从 0° 扫过来）
				SnowboardControl.tickHead(player, keys);
			}
			if (ShoulderSurfingCompat.isActive()) {
				player.setYHeadRot(yaw + (board ? SnowboardControl.headOffset(player.getId(), true) : 0.0F));
			}
		}
	}

	/** 单板：记录 W / S 的俯仰姿态（+1 前倾 / -1 后倾），模型那边读它来摆身体。 */
	private static void tickAttitude(LocalPlayer self) {
		if (self != null) {
			if (!SkisMod.isSkiMotionActive(self) || !SnowboardControl.isSnowboard(self)) {
				// 没穿板 / 创造飞行：姿态回中立（飞行时按 W 不该让模型前倾）
				SnowboardControl.setTiltTarget(self.getId(), 0, true);
				SnowboardControl.tickTilt(self.getId(), true);
			} else {
				Input keys = self.input.keyPresses;
				// W 与 S 同按（快速切换时的一帧残留）判中立，免得“单按 S 却跳成前倾”
				boolean forward = keys != null && keys.forward();
				boolean backward = keys != null && keys.backward();
				int tilt = forward == backward ? 0 : (forward ? 1 : -1);
				SnowboardControl.setTiltTarget(self.getId(), tilt, true);
				SnowboardControl.tickTilt(self.getId(), true);
				// 注意：这里不「每 tick 硬夹」视角。视角限位只有一处权威状态
				// （client/SkiViewSmoothing 的 headOffset），按帧写、按帧判断，不会有两处基准互相拉回。
			}
		}
	}

	/**
	 * <b>换世界 / 重连 / 换维度的唯一清理点</b>：把所有「按 entity id 记」的客户端状态整表丢掉。
	 *
	 * <p>为什么必须有：换维度 / 重进世界时玩家的 entity id 会变，旧 id 的记录再也回不来；
	 * 更要命的是<b>读数还在、写入没了</b>的窗口 —— 比如 {@code SnowSpray} 的「上一 tick 速度 + 上一 tick
	 * 贴地」跨维度活下来，到了新维度第一 tick 就是「上一 tick 很快、这一 tick 骤降」，凭空炸一团雪。
	 * 这些表都只在实体被 tick 到、且<b>在滑</b>时才维护，所以「状态整体作废」只能在这里显式做。
	 *
	 * <p>清的六份：雪粒子四张表、摆臂倒计时、两块板的朝向 / 姿态 / 压刃、空中状态（客户端那张）、
	 * 视角限位的插值基准。
	 */
	private static void resetWorldState() {
		SnowSpray.forgetAll();
		SkiState.clear();
		SnowboardControl.forgetAll(true);
		AlpineSkiControl.forgetBodyAll(true);
		SkiAir.forgetAllClient();
		SkiViewSmoothing.reset();
	}

	/**
	 * 给一块板注册脚部外观渲染器（{@link ArmorRenderer}）：两块板只有参数不同，装配逐字相同 ——
	 * 烘焙该层的模型、带基础款贴图、再用物品模型 id 前缀去挑款式后缀（见 {@link BoardArmorRenderer}）。
	 *
	 * @param item             这块板对应的物品（{@code SkisMod.MODERN_SKI} / {@code SkisMod.SNOWBOARD}）
	 * @param layer            模型层（决定 {@code bakeLayer} 取哪一份几何）
	 * @param texture          基础款贴图（wood）
	 * @param modelFactory     由烘焙好的 {@link ModelPart} 造模型（{@code AlpineSkiModel::new} / {@code SnowboardModel::new}）
	 * @param itemModelPrefix  物品模型 id 前缀，用来取款式后缀：{@code "item/modern_ski"} / {@code "item/snowboard"}
	 * @param textureBase      脚上贴图前缀：{@code "ski_board"} / {@code "snowboard"}
	 */
	private static void registerBoard(Item item, ModelLayerLocation layer, Identifier texture,
			Function<ModelPart, BoardModel> modelFactory, String itemModelPrefix, String textureBase) {
		ArmorRenderer.register(
				context -> new BoardArmorRenderer(modelFactory.apply(context.bakeLayer(layer)), texture,
						itemModelPrefix, textureBase),
				item);
	}
}
