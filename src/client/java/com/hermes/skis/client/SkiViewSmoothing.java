package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import com.hermes.skis.SnowboardControl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * <b>单板：把「身体拖视角」从每 tick 一档拆到每一帧</b>（纯客户端观感，不改任何物理数值、不改 carve 符号）。
 *
 * <p>双板不再经过这里：它的 A / D 只转<b>身体朝向</b>（{@code SkisControl.turnBody}，由 {@code SkisClient}
 * 每 tick 写进 {@code yBodyRot / yBodyRotO}），<b>视角一律不碰</b>（只听鼠标），所以没有「按帧转视角」这回事了
 * （原 {@code client/SkiSteering} 已删）。
 *
 * <p><b>为什么必须每帧做</b>：原版 {@code Entity#getViewYRot(float)} 本来是插值的
 * （{@code getYRot(partial)} → {@code Mth.rotLerp(partial, yRotO, yRot)}），
 * <b>但 {@code LocalPlayer#getViewYRot(float)} 把它覆盖掉了：不是乘客时直接 {@code return getYRot();}
 * —— 完全丢掉 partialTick</b>。相机 {@code Camera#alignWithEntity} 用的就是这个
 * {@code getViewYRot(partial)}，所以<b>本地玩家的镜头朝向是「每 tick 的原始 yRot」，没有任何帧间插值</b>。
 * 原版鼠标之所以顺，是因为 {@code MouseHandler.handleAccumulatedMovement()}（每帧，在
 * {@code Minecraft#runTick} 里）→ {@code turnPlayer} → {@code LocalPlayer#turn} 每帧都在改 yRot。
 * 单板的身体朝向是每 tick 转 2°，若在 {@code END_CLIENT_TICK} 里一次夹到位，
 * 镜头就只能 20 Hz 一档一档地跳。
 *
 * <p>所以这里按帧插值身体的转动：每帧用 {@code partialTick} 在 {@code bodyPrev → bodyNow} 之间取一个值，
 * 再用它夹视角。写入时 {@code yRot / yRotO / yHeadRot / yHeadRotO} 同量偏移，插值不会下沉、
 * 也不会被二次插值拖慢。
 *
 * <p>单板那边没有「按键转角」，它的问题是<b>身体朝向每 tick 转 2°、头顶在 ±135° 限制上时被硬夹着走</b>
 * —— 同样会把视角按 tick 一档一档地拖。这里把「锁定身体朝向」按帧插值
 * （{@code bodyPrev → bodyNow}，与 {@code yBodyRotO → yBodyRot} 同一套），再用插值后的身体朝向夹一次，
 * 视角就是贴着身体平滑拖过去的，而不是每 tick 跳 2°。
 *
 * <p>调用点：{@code mixin/client/CameraViewSmoothingMixin} 在 {@code Camera#alignWithEntity} 的 HEAD
 * （每帧一次、恰在相机读 {@code getViewYRot} 之前，用的是同一个 partialTick）。
 * 注：Fabric API 0.161.0+26.2 里已没有 {@code WorldRenderEvents}（改成 {@code LevelRenderEvents}，
 * 而且在 {@code Camera#update} 之后才发），所以直接挂相机前一步更准（零延迟）。
 */
public final class SkiViewSmoothing {
	/** 夹角误差容限（度）：别因为 1e-4 的浮点误差每帧都去动 yaw。 */
	private static final float LIMIT_EPSILON = 0.01F;

	/** 单板：上一 tick 的身体朝向（本段插值的起点）。 */
	private static float bodyPrev;
	/** 单板：上一帧看到的身体朝向（用来判断 tick 有没有翻页）。 */
	private static float bodyLast;
	/** 单板：本帧插值出来的身体朝向（相机的头限制基准）。 */
	private static float bodyRender = Float.NaN;
	/** 单板：身体朝向插值状态是否有效（脱板 / 换板就失效）。 */
	private static boolean bodyTracked;

	private SkiViewSmoothing() {
	}

	/**
	 * 每帧调用一次，必须在相机读 {@code getViewYRot} 之前。
	 *
	 * @param player      相机跟的那个实体（本地玩家）
	 * @param partialTick 本帧的 tick 内插值 0..1（{@code Camera} 用的就是这个值）
	 */
	public static void frame(LocalPlayer player, float partialTick) {
		if (player == null || !SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			bodyTracked = false;
			return;
		}
		if (player.isPassenger()) {
			bodyTracked = false;
			return;   // 骑乘时原版走的是插值分支（乘客不是 LocalPlayer 的裸 yRot），别插手
		}
		if (SnowboardControl.isSnowboard(player)) {
			dragHeadWithBody(player, partialTick);
		} else {
			bodyTracked = false;   // 双板只在 tick 上转身体（SkisClient 写 yBodyRot），视角完全不碰
		}
	}

	/**
	 * 单板：身体朝向按帧插值，头上的 ±{@link SnowboardControl#VIEW_LIMIT_DEGREES} 也按帧夹。
	 *
	 * <p>限制生效（头被身体拖着走）时，这样夹出来的是「每帧一小步」，而不是每 tick 2° 的一档。
	 * 没到限制时是空操作 —— 视角仍然只跟鼠标。
	 */
	private static void dragHeadWithBody(LocalPlayer player, float partialTick) {
		float bodyNow = SnowboardControl.bodyYaw(player.getId(), player.getYRot());
		if (!bodyTracked) {
			bodyPrev = bodyNow;
			bodyLast = bodyNow;
			bodyTracked = true;
		} else if (bodyNow != bodyLast) {
			bodyPrev = bodyLast;   // carve 转过一次：上一帧的值就是本段的插值起点
			bodyLast = bodyNow;
		}
		bodyRender = bodyPrev + Mth.wrapDegrees(bodyNow - bodyPrev) * partialTick;

		float limit = SnowboardControl.VIEW_LIMIT_DEGREES;
		float off = Mth.wrapDegrees(player.getYRot() - bodyRender);
		if (Math.abs(off) > limit + LIMIT_EPSILON) {
			turnView(player, Mth.clamp(off, -limit, limit) - off);
		}
	}

	/**
	 * 转视角一个「增量」，<b>四个朝向字段一起挪同样的量</b>：
	 * {@code yRot}（朝向本体）+ {@code yRotO}（上一 tick 的朝向＝帧间插值起点）+
	 * {@code yHeadRot} / {@code yHeadRotO}（头）。
	 *
	 * <p>只动 {@code yRot} 的话 {@code yRotO} 会拖在后面（原版 {@code Entity#turn} 是两者同加），
	 * 任何走 {@code getYRot(partial)} / {@code Mth.rotLerp} 的地方（乘客分支、第三人称模型）都会被拖慢。
	 *
	 * <p><b>只有单板的「身体拖视角」用它</b>（视角被 ±135° 夹住时补回差值）；
	 * 双板的转向不碰视角（{@code SkisControl.turnBody} 只转身体朝向）。
	 */
	public static void turnView(LocalPlayer player, float degrees) {
		if (degrees == 0.0F || !Float.isFinite(degrees)) {
			return;
		}
		player.setYRot(player.getYRot() + degrees);
		player.yRotO += degrees;
		player.setYHeadRot(player.getYHeadRot() + degrees);
		player.yHeadRotO += degrees;
	}

	/**
	 * 本帧相机应该看到的「锁定身体朝向」（插值后）。单板的头限制以它为基准，
	 * 这样 {@code SnowboardHeadLimitMixin}（鼠标每帧转视角时夹）与这里的每帧夹用同一个基准，
	 * 不会互相拉回 2°。
	 */
	public static float renderBodyYaw(int entityId, float fallback) {
		return bodyTracked && !Float.isNaN(bodyRender)
				? bodyRender
				: SnowboardControl.bodyYaw(entityId, fallback);
	}
}
