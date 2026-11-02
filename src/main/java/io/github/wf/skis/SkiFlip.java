package io.github.wf.skis;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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
 * （客户端 {@code input.keyPresses} 只在本机）。所以角度在客户端累积、观感也在客户端，
 * 落地那一刻把最终角度交给服务端，由服务端用<b>同一份</b> {@link SkiAir#isCleanLanding} 判定并施罚
 * （降速 + {@code hurtMarked}，不 setPos）。
 *
 * <p>注册用的是 26.2 的标准通道：{@link PayloadTypeRegistry#serverboundPlay()} ＋
 * {@link ServerPlayNetworking#registerGlobalReceiver}，两者都在<b>主源码集</b>里（客户端也会跑到，
 * 客户端发包要的编解码表就是这里登记的）。
 *
 * <p><b>合法性</b>：非有限值 / 负值 / 超过 {@link SkiAir#MAX_NET_ANGLE_DEGREES}（10 圈）一律直接丢弃，
 * 不结算、不进失控 —— 网络来的数一律当不可信。
 */
public final class SkiFlip {
	/** 通道名：{@code skis:flip_landing}。 */
	public static final String CHANNEL_PATH = "flip_landing";

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
			SkiAir.applyLanding(player, angle, tuning);
		});
	}
}
