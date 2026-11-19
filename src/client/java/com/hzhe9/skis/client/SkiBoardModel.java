package com.hzhe9.skis.client;

import com.hzhe9.skis.SkisMod;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The ski gear actually visible on the feet: boot (wrapping the lower shin) + board body + upturned tip.
 *
 * <p>The coordinate system follows the player model (1 unit = 1/16 block, the sole is at y=24,
 * the model faces -z); the board surface is drawn at y 23~24 (flush with the boot sole), the board is
 * 22 units long (about 1.37 blocks) and extends 14 units forward.
 *
 * <p>The lateral positions of the left and right groups come from {@link SkiStance} — the same value
 * {@code HumanoidModelMixin} uses to translate the legs, so the boards separate together with the feet,
 * stay parallel and keep a gap between them (no outward splay at all).
 */
public class SkiBoardModel extends Model<HumanoidRenderState> {
	public static final ModelLayerLocation LAYER =
			new ModelLayerLocation(Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "ski_board"), "main");
	public static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "textures/entity/ski_board.png");

	private static final float BOARD_TOP = 23.0F; // board surface (boot sole is at 24)

	public SkiBoardModel(ModelPart root) {
		super(root, RenderTypes::entityCutout);
	}

	public static LayerDefinition createLayerDefinition() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addSki(root, "right", -SkiStance.legOffsetX());
		addSki(root, "left", SkiStance.legOffsetX());
		return LayerDefinition.create(mesh, 128, 32);
	}

	private static void addSki(PartDefinition root, String side, float x) {
		PartDefinition ski = root.addOrReplaceChild("ski_" + side, CubeListBuilder.create(), PartPose.offset(x, 0.0F, 0.0F));
		// Boot: wraps the lower part of the shin
		ski.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.ZERO);
		// Board body: 3 wide, 1 thick, 22 long; the boot sits behind the midpoint (12.5 front / 9.5 back ≈ 57% front, close to a real mounting position)
		ski.addOrReplaceChild("board_" + side,
				CubeListBuilder.create().texOffs(20, 0).addBox(-1.5F, BOARD_TOP, -12.5F, 3.0F, 1.0F, 22.0F),
				PartPose.ZERO);
		// Upturned tip
		ski.addOrReplaceChild("tip_" + side,
				CubeListBuilder.create().texOffs(20, 24).addBox(-1.5F, 22.3F, -14.5F, 3.0F, 1.0F, 2.0F),
				PartPose.ZERO);
	}
}
