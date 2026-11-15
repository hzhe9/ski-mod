package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkisMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>第一人称：相机随空翻一起翻滚 —— 朝向翻滚 + 位置绕腰做圆周</b>
 * （两块板都有；第三人称那条链在 {@code EntityTiltMixin}，这里不动它）。
 *
 * <p><b>为什么挂在 {@code Camera#alignWithEntity(float)} 的 TAIL</b>：那正是「相机的位置与朝向
 * 这一帧已经算完」的最后一刻 —— 原版在方法体里 {@code setRotation(getViewYRot(pt), getViewXRot(pt))}
 * 把朝向写进 {@code rotation} 四元数（{@code setRotation} 里 {@code rotationYXZ(π − yaw, −xRot, 0)}
 * 并把 {@code matrixPropertiesDirty} 打脏，{@code getViewRotationMatrix} / {@code extractRenderState}
 * 随后都读它）。TAIL 再 {@code setRotation} 一次、并把位置按下面的公式搬到圆弧上，
 * 后面的视锥 / 投影 / HUD 全都拿到新值；同类的 {@code CameraTiltMixin}（单板 W / S 的位移修正）
 * 改的是这个方法里 {@code setPosition} 的<b>参数</b>，而我们从 {@code position()} 读的是它已经写好
 * 的结果 —— 顺序天然是「先做倾斜修正、再把整份偏移一起旋过去」，两边不互相覆盖。
 *
 * <p><b>为什么位置也要跟着转</b>：第三人称模型（{@code EntityTiltMixin}）是把整个 poseStack 绕
 * 「脚底往上 {@link SkiAir#FLIP_PIVOT_HEIGHT} 格」那个点转的，所以模型的头（眼，眼高 1.62）画在半径
 * {@code 1.62 − 1.0 = 0.62} 格的圆弧上跑。相机若留在竖直眼高那点原地转，翻到 180° 时眼睛不在头的位置，
 * 身体背部就跑到视线正前方 —— 看见自己的后背。所以这里把相机位置也当成刚体上的一个点，
 * 用<b>同一个枢轴、同一个角度</b>转过去：两边逐帧同源，任何角度相机都贴在「头」上。
 *
 * <p><b>符号与 {@code EntityTiltMixin} 同一套</b>：那边 {@code poseStack.mulPose(Axis.XP.rotationDegrees(flip))}，
 * 即「绕玩家左右轴、正角＝身体向后翻」（局部 +z 指向背后，+X 旋转把 +Y 压向 +z，即 up → back）。
 * 相机这边 {@code xRot} 正值＝俯视，所以「身体向后翻 φ 度」对应 {@code xRot −= φ}。
 * 位置用同一套旋转式（{@code newUp = du·cos − hb·sin}、{@code newBack = du·sin + hb·cos}）：
 * 自检 φ＝90° 时相机落在「腰高（1.0）、身后 0.62 格」，φ＝180° 时落在脚底往上 1.0 − 0.62 ≈ 0.38 格
 * —— 正是翻过来的模型的头（眼）所在处。
 *
 * <p><b>局部轴为什么取身体朝向（{@code yBodyRot}）而不是视角朝向</b>：模型的翻转是在
 * {@code setupRotations} 里 {@code Axis.YP.rotationDegrees(180 − bodyRot)} 之后的局部系里做的，
 * 那个系的 +z 就是<b>身体背后</b>；用视角偏航会在歪头 / 越肩时把圆弧转到错的平面
 * （头是绕颈轴转的、眼在轴上，所以眼睛相对枢轴的偏移恒为竖直分量，这个取法才成立）。
 *
 * <p><b>只在第一人称、只在穿本模组的板、角度为 0 时一个字都不写</b>（0 就 return，
 * 不调 {@code setRotation} / {@code setPosition}，以免平白打脏矩阵缓存）。不碰 FOV、
 * 不碰鼠标灵敏度、不写视角状态。
 */
@Mixin(Camera.class)
public abstract class CameraFlipMixin {
	/** 重算朝向（原版 {@code setRotation} 会一并更新四元数与矩阵脏标记）。 */
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	/** 写位置（protected 的同名重载，只能影子进来）。 */
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	/** 本帧的位置（原版已经算完，含 {@code CameraTiltMixin} 的修正）。 */
	@Shadow
	public abstract Vec3 position();

	/** 当前俯仰（度，正＝朝下）。 */
	@Shadow
	public abstract float xRot();

	/** 当前偏航（度）。 */
	@Shadow
	public abstract float yRot();

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void skis$flipFirstPersonCamera(float partialTick, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (!client.options.getCameraType().isFirstPerson()) {
			return;                                  // 第三人称：EntityTiltMixin 已经转过 poseStack
		}
		Entity cameraEntity = ((Camera) (Object) this).entity();
		if (!(cameraEntity instanceof Player player)
				|| !SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return;                                  // 没穿板（或相机跟的不是玩家）：原版视角
		}
		float flip = SkiAir.angleDegrees(player.getId(), partialTick);
		if (flip == 0.0F) {
			return;                                  // 没在翻：一个字都不写
		}
		this.setRotation(this.yRot(), this.xRot() - flip);
		this.skis$orbitPosition(player, partialTick, flip);
	}

	/**
	 * 把相机位置绕「腰」搬到翻转后的位置上（刚体旋转，与模型同一条圆弧）。
	 *
	 * <p>分解：{@code d = C − P} 拆成竖直分量 {@code du}、沿身体前后轴的分量 {@code hb} 与侧向
	 * 分量 {@code lat}；绕左右轴的刚体旋转只动前两者（侧向原样保留、不平移），拼回去就是新位置。
	 * 枢轴 {@code P} 用<b>插值后的脚底点</b> —— 与 vanilla 普通分支算相机位置时同一个点，
	 * 否则半帧的位移差会让相机在圆弧上抖一下。
	 */
	private void skis$orbitPosition(Player player, float partialTick, float flip) {
		double pivotX = Mth.lerp(partialTick, player.xo, player.getX());
		double pivotY = Mth.lerp(partialTick, player.yo, player.getY()) + SkiAir.FLIP_PIVOT_HEIGHT;
		double pivotZ = Mth.lerp(partialTick, player.zo, player.getZ());

		// 身体朝向（不是视角朝向）：模型的翻转发生在「转了 180 − bodyRot」之后的局部系里，局部 +z = 背后
		double bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
		double theta = Math.toRadians(bodyYaw);
		double backX = Math.sin(theta);        // yaw=0 朝 +z 时，背后在 −z
		double backZ = -Math.cos(theta);

		Vec3 current = this.position();
		double dx = current.x - pivotX;
		double dy = current.y - pivotY;
		double dz = current.z - pivotZ;
		double up = dy;                                // 竖直分量
		double alongBack = dx * backX + dz * backZ;    // 沿身体前后轴的分量
		double latX = dx - alongBack * backX;          // 侧向分量（旋转不动它）
		double latZ = dz - alongBack * backZ;

		double rad = Math.toRadians(flip);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double newUp = up * cos - alongBack * sin;     // 正角：up 转向 back（向后翻）
		double newBack = up * sin + alongBack * cos;

		this.setPosition(
				pivotX + latX + newBack * backX,
				pivotY + newUp,
				pivotZ + latZ + newBack * backZ);
	}
}
