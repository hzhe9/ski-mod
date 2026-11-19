package io.github.wf.skis.client;

import io.github.wf.skis.AlpineSkiControl;
import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SnowboardControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * <b>视角的每帧记账 + 帧间插值（两块板同一套）</b>：<b>第三人称不限位、第一人称夹 ±90°</b>。
 *
 * <p><b>两种视角姿态、两套口径</b>（用户 2026-11-19：「第三人称的视角是自由的，但是第一人称的视角应该跟头部
 * 模型一样，只能左右 90 度，单板和双板都是如此」）：第三人称（含正面第三人称）鼠标能转满一圈、背后也能看 ——
 * 这里<b>不夹</b>，只每帧折回整圈；<b>第一人称</b>把「视角相对锁定身体朝向的偏角」夹到
 * ±{@link SnowboardControl#HEAD_MODEL_LIMIT_DEGREES} —— <b>第一人称视角与头模型同一个 90° 限位，值只此一处</b>
 * （两块板共用 {@code SnowboardControl} 那个常量，本类<b>不新开第二个 90</b>）。判据是每帧现问的
 * {@code Minecraft#options.getCameraType().isFirstPerson()}（{@link #frame} 在相机读 yaw 之前跑，每个 frame 都要判）；
 * 夹的就是下面那个 {@code headOffset}，夹完由现有的
 * {@code correction = wrapDegrees(bodyRender + headOffset − player.getYRot()) → turnView(...)} 把视角拉回限位内
 * —— <b>不另写一套写 yaw 的路径</b>。越肩视角不受影响（那条分支本来就不写视角，见下）。
 *
 * <p>头模型仍是一个<b>独立的</b> ±90° 夹：{@code client/SkisClient} 每 tick 用「当前视 yaw − 身体朝向」现算、
 * 先 {@code Mth.wrapDegrees} 折叠、再夹 ±{@code HEAD_MODEL_LIMIT_DEGREES}（见 {@code SnowboardControl#headModelOffset}，
 * 两块板共用同一套）。第三人称下视角自由、头模型照样夹；第一人称下两者是同一个上限（同一份常量）。
 * 上一版把这里的 {@code headOffset}（当时是<b>不折叠</b>的连续累加量）直接夹了写进 {@code yHeadRot}，
 * 转几圈后累加量到 495 / 585…，夹出来恒为上限、自己又回不到真实夹角 —— 头模型就被<b>永久冻在背后</b>。
 * 无状态的现算是那条回归的修法；这里的 {@code headOffset} 现在<b>只服务于视角本身</b>。
 *
 * <p><b>状态只有一个</b>：{@code headOffset}（视角相对身体朝向的偏角，度）。两个入口改的都是<b>同一个标量</b>，
 * 所以结构上不可能互相拉回：
 * <ul>
 *   <li>{@code mixin/client/SkiHeadLimitMixin}（{@code Entity#turn} 的 HEAD / TAIL —— 鼠标每帧的入口）：
 *       把这一帧真正转过的角度记进 {@code headOffset}（不夹，只折回整圈）；</li>
 *   <li>{@link #frame}（{@code Camera#alignWithEntity} 的 HEAD，每帧一次）：先把「身体这一帧转了多少」从
 *       {@code headOffset} 里扣掉（视角留在世界坐标里不动、只听鼠标），再用
 *       {@code 插值后的身体朝向 + headOffset} 作目标写一次 yaw —— <b>整个模组唯一一处按帧写视角</b>。</li>
 * </ul>
 *
 * <p><b>每帧都折回 (−180°, 180°]</b>：视角不限位之后 {@code headOffset} 是「转几圈就是几百度」的累加量；
 * 折掉整圈对写入的 yaw 完全等价（差值本身就是被 wrap 过的），但状态不会漂到几百度。
 *
 * <p><b>为什么必须每帧做</b>：原版 {@code Entity#getViewYRot(float)} 本来是插值的
 * （{@code Mth.rotLerp(partial, yRotO, yRot)}），<b>但 {@code LocalPlayer#getViewYRot(float)} 把它覆盖掉了：
 * 不是乘客时直接 {@code return getYRot();} —— 完全丢掉 partialTick</b>。相机 {@code Camera#alignWithEntity}
 * 用的就是这个 {@code getViewYRot(partial)}，所以<b>本地玩家的镜头朝向是「每 tick 的原始 yRot」，没有帧间插值</b>。
 * 原版鼠标之所以顺，是因为 {@code MouseHandler.handleAccumulatedMovement()}（每帧，在
 * {@code Minecraft#runTick} 里）→ {@code turnPlayer} → {@code LocalPlayer#turn} 每帧都在改 yRot。
 * 身体朝向却是按 tick 转的（单板 carve 满速 12°/tick、双板 A/D 4°/tick），若只在 tick 上夹一次，镜头就只能
 * 20 Hz 一档一档地跳，所以这里把身体转动按 {@code partialTick} 插值到每一帧。
 *
 * <p>写入时 {@code yRot / yRotO / yHeadRot / yHeadRotO} 同量偏移（{@link #turnView}），插值不会下沉、
 * 也不会被二次插值拖慢；第三人称里身体的转动仍归原版 {@code yBodyRotO → yBodyRot}（双板那边
 * {@code SkisClient} 故意把 {@code yBodyRotO} 直接对齐，视角侧不碰它）。
 *
 * <p>调用点：{@code mixin/client/CameraViewSmoothingMixin} 在 {@code Camera#alignWithEntity} 的 HEAD
 * （每帧一次、恰在相机读 {@code getViewYRot} 之前，用的是同一个 partialTick）。
 * 注：Fabric API 0.161.0+26.2 里已没有 {@code WorldRenderEvents}（改成 {@code LevelRenderEvents}，
 * 而且在 {@code Camera#update} 之后才发），所以直接挂相机前一步更准（零延迟）。
 */
public final class SkiViewSmoothing {
	/** 写视角的容差（度）：残差比它还小就不动 yaw —— 别为 1e-4 的浮点误差每帧都写一次。 */
	private static final float VIEW_EPSILON = 0.01F;

	/** 上一 tick 的身体朝向（本段插值的起点）。 */
	private static float bodyPrev;
	/** 上一帧看到的身体朝向（用来判断 tick 有没有翻页）。 */
	private static float bodyLast;
	/** 本帧插值出来的身体朝向（相机基准）。 */
	private static float bodyRender = Float.NaN;
	/** 上一帧用的基准（算「身体这一帧转了多少」）。 */
	private static float renderLast = Float.NaN;
	/**
	 * <b>唯一的偏角状态</b>：视角相对身体朝向的角度（度；每帧折回 (−180°, 180°]）。
	 * 第三人称<b>不夹</b>、第一人称按 {@link SnowboardControl#HEAD_MODEL_LIMIT_DEGREES} 夹 ±90° —— 见 {@link #limitView}。
	 */
	private static float headOffset = Float.NaN;
	/** 插值状态是否有效（脱板、骑乘、换板、换世界都要重新起算）。 */
	private static boolean tracked;
	/** 正在跟踪哪个玩家。 */
	private static int trackedId = Integer.MIN_VALUE;
	/** 跟踪的是不是单板（决定读哪张身体朝向表 —— 两块板的视角都不限位）。 */
	private static boolean trackedSnowboard;
	/** {@code Entity#turn} 的 HEAD 记下的、转之前的 yaw（TAIL 用它算这一帧真正转了多少）。 */
	private static float turnStart = Float.NaN;

	private SkiViewSmoothing() {
	}

	/**
	 * 每帧调用一次，必须在相机读 {@code getViewYRot} 之前。
	 *
	 * @param player      相机跟的那个实体（本地玩家）
	 * @param partialTick 本帧的 tick 内插值 0..1（{@code Camera} 用的就是这个值）
	 */
	public static void frame(LocalPlayer player, float partialTick) {
		if (player == null || player.isPassenger()
				|| !SkisMod.isSkiMotionActive(player)) {
			// 没穿板 / 骑乘 / 创造飞行：不插值、不限位、也不写视角 —— 飞行时视角完全归原版
			reset();
			return;
		}
		if (ShoulderSurfingCompat.isActive()) {
			// ——— 越肩视角：相机完全自由 ———
			// 插值、每帧写视角全部停手（也不改 yRot / yHeadRot），否则会和对面模组的相机打架。
			// reset() 顺手清状态：退出越肩后下一次 frame 走「初次接手」分支，按当时的视角重新起算 headOffset。
			reset();
			return;
		}
		boolean snowboard = SnowboardControl.isSnowboard(player);
		float bodyNow = snowboard
				? SnowboardControl.bodyYaw(player.getId(), player.getYRot(), true)
				: AlpineSkiControl.bodyYaw(player.getId(), player.getYRot(), true);

		if (!tracked || trackedId != player.getId() || trackedSnowboard != snowboard) {
			// 刚穿板 / 换了另一块板 / 换了玩家：从当前视角接手，避免视角跳变
			bodyPrev = bodyNow;
			bodyLast = bodyNow;
			bodyRender = bodyNow;
			renderLast = bodyNow;
			// 接手时走同一份限位逻辑：第一人称首帧就直接落在 ±90° 内，不会先超出去一帧再被拉回（切视角不抖）
			headOffset = limitView(Mth.wrapDegrees(player.getYRot() - bodyNow));
			tracked = true;
			trackedId = player.getId();
			trackedSnowboard = snowboard;
		} else if (bodyNow != bodyLast) {
			bodyPrev = bodyLast;   // 身体转过一次：上一帧的值就是本段的插值起点
			bodyLast = bodyNow;
		}
		bodyRender = bodyPrev + Mth.wrapDegrees(bodyNow - bodyPrev) * partialTick;

		// 身体这一帧转了多少，就从 offset 里扣掉多少 —— 视角留在世界坐标里不动（只听鼠标），
		// 身体在下面按 tick 转、这里按帧插值，镜头于是平滑地搭在同一个基准上。
		float bodyStep = Mth.wrapDegrees(bodyRender - renderLast);
		renderLast = bodyRender;
		headOffset = limitView(Mth.wrapDegrees(headOffset - bodyStep));

		// ——— 整个模组里唯一一处「按帧写视角」 ———
		float correction = Mth.wrapDegrees(bodyRender + headOffset - player.getYRot());
		if (Math.abs(correction) > VIEW_EPSILON) {
			turnView(player, correction);
		}
	}

	/**
	 * <b>把「视角相对身体朝向的偏角」按当前视角姿态限位</b>：<b>第一人称视角与头模型同一个 90° 限位，值只此一处</b>
	 * —— 直接引用 {@link SnowboardControl#HEAD_MODEL_LIMIT_DEGREES}（两块板共用的那个 90°），本类<b>不新开</b>第二个 90。
	 *
	 * <p>只夹<b>第一人称</b>（{@code Minecraft#options.getCameraType().isFirstPerson()}，每帧现问 —— 玩家随时按 F5
	 * 切视角，{@link #frame} 在相机读 yaw 之前跑、每个 frame 都要判一次）。<b>第三人称（含正面第三人称）原样返回</b>：
	 * 转满一圈、背后也能看，与上一版完全一致。越肩视角根本走不到这里（{@link #frame} 在那之前就 {@code reset()}
	 * 返回了）。
	 *
	 * <p>夹的是唯一的偏角状态 {@link #headOffset}：夹完由 {@link #frame} 里现有的
	 * {@code correction = wrapDegrees(bodyRender + headOffset − player.getYRot()) → turnView(...)} 把视角拉回限位内 ——
	 * <b>不另写一条写 yaw 的路径</b>，所以「按帧写视角」仍然只有一处。接手（刚穿板 / 换板 / 退出越肩）那一帧
	 * 也过这个夹，因此切进第一人称时<b>首帧直接落在限位内</b>，不会先超出去一帧再被拉回。
	 *
	 * @param offset 视角相对身体朝向的偏角（度，调用方已经 {@code Mth.wrapDegrees} 折回过）
	 * @return 第一人称夹过 ±90° 的偏角；第三人称原样返回
	 */
	private static float limitView(float offset) {
		if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
			return offset;
		}
		return Mth.clamp(offset, -SnowboardControl.HEAD_MODEL_LIMIT_DEGREES,
				SnowboardControl.HEAD_MODEL_LIMIT_DEGREES);
	}

	/** {@code Entity#turn} 的 HEAD：记下转之前的 yaw（TAIL 用它算这一帧真正转了多少）。 */
	public static void turnStart(LocalPlayer player) {
		turnStart = player.getYRot();
	}

	/**
	 * {@code Entity#turn} 的 TAIL：这一帧鼠标真正转过的角度记进 {@link #headOffset}。
	 *
	 * <p><b>这里不夹、也不撤任何角度</b>：只把累加量折回 (−180°, 180°]，鼠标转多少视角就转多少（两块板同一套）。
	 * 第一人称那个 ±90° 的夹在 {@link #frame}（{@link #limitView}）里做 —— 同一次 frame 内先记账、再夹、再写 yaw，
	 * 所以镜头永远不会先越过 90° 再被拉回。视角是否还搭在身体朝向的基准上由 {@link #frame} 每帧对齐 —— 这里不动 yRot。
	 */
	public static void turnEnd(LocalPlayer player) {
		if (!tracked || Float.isNaN(turnStart)) {
			return;
		}
		float applied = Mth.wrapDegrees(player.getYRot() - turnStart);
		turnStart = Float.NaN;
		if (applied == 0.0F) {
			return;
		}
		headOffset = Mth.wrapDegrees(headOffset + applied);
	}

	/** 脱板 / 骑乘 / 换世界 / 换板时清状态：下一次 {@link #frame} 会按当时的视角重新起算。 */
	public static void reset() {
		tracked = false;
		trackedId = Integer.MIN_VALUE;
		bodyRender = Float.NaN;
		renderLast = Float.NaN;
		headOffset = Float.NaN;
		turnStart = Float.NaN;
	}

	/**
	 * 转视角一个「增量」，<b>四个朝向字段一起挪同样的量</b>：
	 * {@code yRot}（朝向本体）+ {@code yRotO}（上一 tick 的朝向＝帧间插值起点）+
	 * {@code yHeadRot} / {@code yHeadRotO}（头）。
	 *
	 * <p>只动 {@code yRot} 的话 {@code yRotO} 会拖在后面（原版 {@code Entity#turn} 是两者同加），
	 * 任何走 {@code getYRot(partial)} / {@code Mth.rotLerp} 的地方（乘客分支、第三人称模型）都会被拖慢。
	 *
	 * <p>调用点只有 {@link #frame} 的每帧目标补正（第一人称的 ±90° 也走这一条：夹在 {@link #headOffset} 上，
	 * 由同一个 {@code correction} 补正，没有第二条写 yaw 的路径）。
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
}
