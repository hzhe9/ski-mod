package com.hzhe9.skis;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>单板板轴同步包</b>（C2S，只传一个 float ＝ 本机锁定的板轴角，度）。
 *
 * <p><b>为什么需要它</b>：板轴（{@code BODY_YAW_CLIENT} / {@code BODY_YAW_SERVER}）原先<b>两端各自积分</b>，
 * 唯一写者是 {@link SnowboardControl#applyCarve}，而每 tick 的转角取决于<b>本端</b>的速度、{@code onGround()}
 * 与脚下方块摩擦（{@link SnowboardControl} 的 {@code turnDegreesPerTick}）。同一 tick 两端只要有一项不一致
 * （雪 0.546 / 冰 0.892 / 空中 0.91），轴就以 <b>2–4°/tick</b> 分叉；两边都「自认已对齐」（误差 &lt; 0.05°），
 * 谁也不自我纠正 —— 而位移真正取的是服务端那份方向（{@code hurtMarked} → {@code ClientboundSetEntityMotionPacket}
 * 整份覆盖客户端速度），板子却按客户端轴画，于是画面上就是一个<b>永久存在、与操作无关的固定夹角</b>。
 *
 * <p>现在板轴<b>只有客户端一份权威</b>：本机把锁定的轴发上来，服务端这张表被<b>覆盖式</b>写入
 * （{@link SnowboardControl#acceptClientAxis}），两端从此用同一个数。远端玩家看到的板轴也是服务端这张表，
 * 所以本机同步之后别人跟着正确；<b>不需要</b> S2C 回传。
 *
 * <p>注册沿用 {@link SkiFlip} 那套（26.2 标准通道）：{@link PayloadTypeRegistry#serverboundPlay()} ＋
 * {@link ServerPlayNetworking#registerGlobalReceiver}，都写在<b>主源码集</b>里（客户端发包要的编解码表
 * 就是这里登记的）。
 *
 * <p><b>合法性</b>（网络来的数一律当不可信）：非有限值直接丢；这一 tick 脚上没有本模组的板
 * （{@link SnowboardControl#isSnowboard} 为假）/ 不处于滑行状态（{@link SkisMod#isSkiMotionActive} 为假）
 * 也丢。写的是<b>接收者自己</b>那张表，且是覆盖式、不累加、不改速度大小 —— 没有「改别人状态」的路径，
 * 也就没有需要做频率门的地方（最多让它自己的板轴更勤地变，这和它自己按 A / D 是同一件事）。
 */
public final class SnowboardAxis {
	/** 通道名：{@code skis:board_axis}。 */
	public static final String CHANNEL_PATH = "board_axis";

	/** 板轴包体：只有一个角度（度，客户端那份锁定值，原样发、不取模 —— 两端用 {@code sin / cos} 读它）。 */
	public record Payload(float bodyYaw) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Payload> TYPE =
				new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, CHANNEL_PATH));

		/** 一个 float：写 {@code writeFloat}、读 {@code readFloat}。 */
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
				CustomPacketPayload.codec(
						(payload, buf) -> buf.writeFloat(payload.bodyYaw()),
						buf -> new Payload(buf.readFloat()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private SnowboardAxis() {
	}

	/** 主初始化里调一次：登记包类型 + 服务端接收器（客户端也走这里，编解码表是双端共用的）。 */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			float yaw = payload.bodyYaw();
			if (!Float.isFinite(yaw)) {
				return;   // 非法角度：丢弃
			}
			if (!SkisMod.isSkiMotionActive(player) || !SnowboardControl.isSnowboard(player)) {
				return;   // 这一 tick 没穿单板 / 在飞行 / 旁观：不写板轴
			}
			SnowboardControl.acceptClientAxis(player.getId(), yaw, player.level().getGameTime());
		});
	}
}
