package io.github.wf.skis.client;

import io.github.wf.skis.SkisControl;
import io.github.wf.skis.SkisMod;
import io.github.wf.skis.SnowboardControl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * <b>视角限位的唯一权威（单板 + 双板同一套）</b>：头相对锁定的身体朝向最多左右各
 * {@link SnowboardControl#VIEW_LIMIT_DEGREES} / {@link SkisControl#VIEW_LIMIT_DEGREES} 度。
 *
 * <p><b>状态只有一个</b>：{@code headOffset}（视角相对身体朝向的偏角，度）。两个入口改的都是<b>同一个标量</b>、
 * 夹的也是同一个限位，所以结构上不可能互相拉回：
 * <ul>
 *   <li>{@code mixin/client/SkiHeadLimitMixin}（{@code Entity#turn} 的 HEAD / TAIL —— 鼠标每帧的入口）：
 *       把这一帧真正转过的角度记进 {@code headOffset} 并夹一次，<b>只撤掉超出限位的那一点</b>；</li>
 *   <li>{@link #frame}（{@code Camera#alignWithEntity} 的 HEAD，每帧一次）：先把「身体这一帧转了多少」从
 *       {@code headOffset} 里扣掉（没饱和时视角留在世界坐标里不动、只听鼠标），再用
 *       {@code 插值后的身体朝向 + headOffset} 作目标写一次 yaw —— <b>整个模组唯一一处按帧写视角</b>。</li>
 * </ul>
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
	private static final float LIMIT_EPSILON = 0.01F;

	/** 上一 tick 的身体朝向（本段插值的起点）。 */
	private static float bodyPrev;
	/** 上一帧看到的身体朝向（用来判断 tick 有没有翻页）。 */
	private static float bodyLast;
	/** 本帧插值出来的身体朝向（相机基准）。 */
	private static float bodyRender = Float.NaN;
	/** 上一帧用的基准（算「身体这一帧转了多少」）。 */
	private static float renderLast = Float.NaN;
	/** <b>唯一限位状态</b>：视角相对身体朝向的偏角（度，已夹在 ±limit 内）。 */
	private static float headOffset = Float.NaN;
	/** 插值 / 限位状态是否有效（脱板、骑乘、换板、换世界都要重新起算）。 */
	private static boolean tracked;
	/** 正在跟踪哪个玩家。 */
	private static int trackedId = Integer.MIN_VALUE;
	/** 跟踪的是不是单板（决定取哪份限位常量、哪张身体朝向表）。 */
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
			// 限位、插值、每帧写视角全部停手（也不改 yRot / yHeadRot），否则会和对面模组的相机打架。
			// reset() 顺手清状态：退出越肩后下一次 frame 走「初次接手」分支，
			// 按当时的视角重新起算 headOffset（并夹一次），限位是干净重启的。
			reset();
			return;
		}
		boolean snowboard = SnowboardControl.isSnowboard(player);
		float bodyNow = snowboard
				? SnowboardControl.bodyYaw(player.getId(), player.getYRot(), true)
				: SkisControl.bodyYaw(player.getId(), player.getYRot(), true);

		if (!tracked || trackedId != player.getId() || trackedSnowboard != snowboard) {
			// 刚穿板 / 换了另一块板 / 换了玩家：从当前视角接手，夹一次，避免视角跳变
			bodyPrev = bodyNow;
			bodyLast = bodyNow;
			bodyRender = bodyNow;
			renderLast = bodyNow;
			headOffset = Mth.clamp(Mth.wrapDegrees(player.getYRot() - bodyNow),
					-limit(snowboard), limit(snowboard));
			tracked = true;
			trackedId = player.getId();
			trackedSnowboard = snowboard;
		} else if (bodyNow != bodyLast) {
			bodyPrev = bodyLast;   // 身体转过一次：上一帧的值就是本段的插值起点
			bodyLast = bodyNow;
		}
		bodyRender = bodyPrev + Mth.wrapDegrees(bodyNow - bodyPrev) * partialTick;
		float limit = limit(snowboard);

		// 身体这一帧转了多少，就从 offset 里扣掉多少 —— 没饱和时视角留在世界坐标里不动（只听鼠标）；
		// 饱和后 offset 被夹住，视角就只能跟着身体走（「头被身体拖着走」，而且是一帧一小步）。
		float bodyStep = Mth.wrapDegrees(bodyRender - renderLast);
		renderLast = bodyRender;
		headOffset = Mth.clamp(headOffset - bodyStep, -limit, limit);

		// ——— 整个模组里唯一一处「按帧写视角」 ———
		float correction = Mth.wrapDegrees(bodyRender + headOffset - player.getYRot());
		if (Math.abs(correction) > LIMIT_EPSILON) {
			turnView(player, correction);
		}
	}

	/** {@code Entity#turn} 的 HEAD：记下转之前的 yaw（TAIL 用它算这一帧真正转了多少）。 */
	public static void turnStart(LocalPlayer player) {
		turnStart = player.getYRot();
	}

	/**
	 * {@code Entity#turn} 的 TAIL：鼠标这一帧真正转过的角度记进 {@link #headOffset} 并夹一次，
	 * <b>只撤掉超出限位的那一点</b>，其余照旧由鼠标生效 —— 所以顶到限位后往回转会立刻响应，没有死区。
	 *
	 * <p>这里刻意<b>不</b>拿身体朝向当基准把 yRot 重新夹一遍：那正是旧设计「两处各夹一次、基准差一帧」的来源
	 * （鼠标那次按上一帧的 bodyRender 夹、本帧相机那次按本帧的夹），于是每帧来回拉。
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
		float limit = limit(trackedSnowboard);
		float raw = headOffset + applied;
		float clamped = Mth.clamp(raw, -limit, limit);
		headOffset = clamped;
		if (clamped != raw) {
			turnView(player, clamped - raw);
		}
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

	/** 这块板的视角限位（单板 / 双板各一份常量：双板 90 / 单板 135）。 */
	private static float limit(boolean snowboard) {
		return snowboard ? SnowboardControl.VIEW_LIMIT_DEGREES : SkisControl.VIEW_LIMIT_DEGREES;
	}

	/**
	 * 转视角一个「增量」，<b>四个朝向字段一起挪同样的量</b>：
	 * {@code yRot}（朝向本体）+ {@code yRotO}（上一 tick 的朝向＝帧间插值起点）+
	 * {@code yHeadRot} / {@code yHeadRotO}（头）。
	 *
	 * <p>只动 {@code yRot} 的话 {@code yRotO} 会拖在后面（原版 {@code Entity#turn} 是两者同加），
	 * 任何走 {@code getYRot(partial)} / {@code Mth.rotLerp} 的地方（乘客分支、第三人称模型）都会被拖慢。
	 *
	 * <p>两个调用点都用它：{@link #frame} 的每帧目标补正、{@link #turnEnd} 的越界撤除。
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
