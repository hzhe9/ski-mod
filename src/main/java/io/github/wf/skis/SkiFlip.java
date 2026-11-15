package io.github.wf.skis;

import io.github.wf.skis.util.IdMap;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>空翻落地包</b>（C2S，只传一个 float＝落地瞬间的最终角度）。
 *
 * <p>为什么需要它：空翻角度只由「玩家这一 tick 有没有按住空格」决定，而<b>服务端拿不到这个输入</b>
 * （客户端 {@code input.keyPresses} 只在本机）。所以角度在客户端累积、观感也在客户端，落地那一刻把最终角度
 * 交给服务端，由服务端用<b>同一份</b> {@link SkiAir#isCleanLanding} 判定并施罚
 * （降速 + {@code hurtMarked}，不 setPos）。
 *
 * <p>注册用的是 26.2 的标准通道：{@link PayloadTypeRegistry#serverboundPlay()} ＋
 * {@link ServerPlayNetworking#registerGlobalReceiver}，两者都在<b>主源码集</b>里（客户端也会跑到，
 * 客户端发包要的编解码表就是这里登记的）。
 *
 * <p><b>合法性</b>（网络来的数一律当不可信，任何一条不过就<b>丢弃、不结算</b>）：
 * <ol>
 *   <li>角度：非有限值 / 负值 / 超过 {@link SkiAir#MAX_NET_ANGLE_DEGREES}（10 圈）—— {@code float} 过不了这条就直接丢；</li>
 *   <li>这一刻脚上得有本模组的板（{@link SkiAir#tuning} 不为 {@code null}）；</li>
 *   <li><b>必须真落地</b>：{@code player.onGround()} 为假就丢 —— 包是「落地那一刻」发出来的，
 *       人还在空中说明这一份不是落地（或客户端在乱发）；</li>
 *   <li><b>频率</b>：同一个 id 两次结算至少隔该板的 {@code CRASH_STUN_TICKS}（20 tick ＝ 1 秒）。
 *       否则一个只要角度合法就能连发的客户端可以无 cd 地自我扣血 + 降速（只害自己，但仍是稳定可制造的状态）；
 *       时间戳记在 {@link #LAST_SETTLED_TICK}，断线即清。</li>
 * </ol>
 */
public final class SkiFlip {
	/** 通道名：{@code skis:flip_landing}。 */
	public static final String CHANNEL_PATH = "flip_landing";

	/**
	 * 玩家 id → 上一次真正结算落地的游戏时间（tick）：频率门用，同一个 id 至少隔
	 * {@code CRASH_STUN_TICKS} 才再结算一次。用 {@link IdMap} 而不是裸 Map，容量超限时淘汰旧的、不会无上限涨。
	 */
	private static final IdMap<Long> LAST_SETTLED_TICK = new IdMap<>();

	/** 落地包体：只有一个角度（度，客户端已按 360 取模成 [0,360)）。 */
	public record Payload(float angleDegrees) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Payload> TYPE =
				new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, CHANNEL_PATH));

		/** 一个 float：写 {@code writeFloat}、读 {@code readFloat}。 */
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
				CustomPacketPayload.codec(
						(payload, buf) -> buf.writeFloat(payload.angleDegrees()),
						buf -> new Payload(buf.readFloat()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private SkiFlip() {
	}

	/** 主初始化里调一次：登记包类型 + 服务端接收器（客户端也走这里，编解码表是双端共用的）。 */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			float angle = payload.angleDegrees();
			if (!Float.isFinite(angle) || angle < 0.0F || angle > (float) SkiAir.MAX_NET_ANGLE_DEGREES) {
				return;   // 非法角度：丢弃，不结算
			}
			SkiAir.Tuning tuning = SkiAir.tuning(player);
			if (tuning == null) {
				return;   // 这一刻脚上没有本模组的板：不结算
			}
			if (!player.onGround()) {
				return;   // 没落地：这一份不是「落地那一刻」的包（或客户端在乱发），丢弃
			}
			long now = player.level().getGameTime();
			Long last = LAST_SETTLED_TICK.get(player.getId());
			if (last != null) {
				long elapsed = now - last;
				// elapsed < 0 ＝ 换维度 / 世界时间回退，那种旧时间戳不算数（放行）
				if (elapsed >= 0L && elapsed < tuning.crashStunTicks()) {
					return;   // 频率门：同一个 id 两次结算至少隔 CRASH_STUN_TICKS
				}
			}
			LAST_SETTLED_TICK.put(player.getId(), now);
			SkiAir.applyLanding(player, angle, tuning);
		});

		// 断线就忘掉时间戳，别让复用的 id 继承它（否则新玩家落地后 1 秒内的第一次结算会被吞掉）
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SETTLED_TICK.remove(handler.getPlayer().getId()));
	}
}
