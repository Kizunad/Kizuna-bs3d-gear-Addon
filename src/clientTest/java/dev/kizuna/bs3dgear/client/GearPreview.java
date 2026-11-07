package dev.kizuna.bs3dgear.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.client.WornModelTransform;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import java.util.List;
import java.util.ArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;

/** 用生产分段绘制和真实 PA 帧生成每件装备的正、侧、背三视角。 */
final class GearPreview extends Screen {
    private static final int BACKGROUND = 0xff30353b;
    private static final int TEXT = 0xffe8edf2;
    private static final int BODY_COLOR = 0xff7f8890;
    private static final int MARGIN_PIXELS = 12;
    private static final int TOP_PIXELS = 48;
    private static final int FOOTER_PIXELS = 28;
    private static final int VIEW_COUNT = 3;
    private static final float VIEW_SCALE_RATIO = 0.35f;
    private static final float DEPTH_SCALE = -0.1f;
    private static final float CAMERA_X_DIRECTION = -1;
    private static final float CENTER_Y_BLOCKS = 0.5f;
    private static final float SIDE_DEGREES = 90;
    private static final float BACK_DEGREES = 180;
    private static final float ARM_RADIANS = -0.7f;
    private final WearableItems.Definition type;
    private final PoseMode mode;
    private final PlayerModel model;
    private final AvatarRenderState state = new AvatarRenderState();
    private final GearRenderer renderer = new GearRenderer(GearClient.bindings());
    private final List<WornModel> worn;
    private final List<WornModel> companions;
    private final boolean pa = FabricLoader.getInstance().isModLoaded("kizuna_players_animation");
    private int frames;

    enum PoseMode { REST, BENT }

    GearPreview(WearableItems.Definition type, PoseMode mode) {
        this(type, mode, List.of());
    }

    GearPreview(WearableItems.Definition type, PoseMode mode, List<WornModel> companions) {
        super(Component.literal(type.title()));
        this.type = type;
        this.mode = mode;
        this.companions = companions;
        var part = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
        model = new PlayerModel(part, false /* slim */);
        model.hat.visible = false;
        model.jacket.visible = false;
        model.leftSleeve.visible = false;
        model.rightSleeve.visible = false;
        model.leftPants.visible = false;
        model.rightPants.visible = false;
        if (pa) {
            PaClientProbe.attach(state, mode);
        } else if (mode == PoseMode.BENT) {
            model.rightArm.xRot = ARM_RADIANS;
            model.leftLeg.xRot = ARM_RADIANS;
        }
        String itemType = type.id().toString();
        var entries = new ArrayList<>(type.slots().stream().map(slot -> WornModel.newBuilder()
                .setItemType(itemType)
                .setWearableSlot(slot)
                .build()).toList());
        entries.addAll(companions);
        worn = List.copyOf(entries);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        frames++;
        context.fill(0, 0, width, height, BACKGROUND);
        var itemId = type.id();
        String companionLabel = "";
        if (!companions.isEmpty()) {
            companionLabel = " / 骨架背篓共穿";
        }
        String title = type.title() + " / " + itemId.getPath() + " / " + mode + companionLabel;
        context.text(font, title, MARGIN_PIXELS, MARGIN_PIXELS, TEXT);
        String path = "原版 ModelPart 回退";
        if (pa) {
            path = "真实 PA 骨架与 EntityFrame 刚性绑定";
        }
        context.text(font, path, MARGIN_PIXELS, MARGIN_PIXELS * 2, TEXT);
        int viewWidth = width / VIEW_COUNT;
        float[] yaws = {BACK_DEGREES, SIDE_DEGREES, 0};
        String[] labels = {"正面", "侧面", "背面"};
        for (int index = 0; index < VIEW_COUNT; index++) {
            int left = index * viewWidth;
            context.guiRenderState.addPicturesInPictureState(new View(this, yaws[index], left, TOP_PIXELS,
                    left + viewWidth, height - FOOTER_PIXELS, (height - TOP_PIXELS) * VIEW_SCALE_RATIO));
            context.centeredText(font, labels[index], left + viewWidth / 2, height - MARGIN_PIXELS, TEXT);
        }
        context.item(WearableItems.create(itemId), width - TOP_PIXELS, MARGIN_PIXELS);
    }

    int frames() {
        return frames;
    }

    private void submit(PoseStack matrices, SubmitNodeCollector collector) {
        // 参考人体和穿搭用相同模型空间；弯肘与弯膝的姿态能揭示错误的近端绑定。
        if (pa) {
            PaClientProbe.drawBody(state, matrices, collector);
        } else {
            collector.submitCustomGeometry(matrices, RenderTypes.entityCutout(ModelAsset.WHITE), (pose, vertices) -> {
                var stack = new PoseStack();
                stack.last().set(pose);
                model.renderToBuffer(stack, vertices, LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, BODY_COLOR);
            });
        }
        var context = new GearRenderer.Context(state, model, collector, LightCoordsUtil.FULL_BRIGHT);
        submitCompanions(matrices, collector);
        renderer.submit(matrices, worn, context);
    }

    private void submitCompanions(PoseStack base, SubmitNodeCollector collector) {
        for (var companion : companions) {
            var containerType = ContainerItems.types().values().stream()
                    .filter(value -> value.id().toString().equals(companion.getItemType())).findFirst().orElseThrow();
            var asset = ModelResources.find(containerType.model());
            var matrices = ModelAsset.copy(base);
            var delta = GearClient.bindings().boneDelta(state, "chest");
            if (delta == null) {
                model.body.translateAndRotate(matrices);
            } else {
                RigidBindings.apply(matrices, delta);
            }
            WornModelTransform.apply(matrices, asset, containerType,
                    companion.getMount(), companion.getWaistAngleDegrees());
            asset.render(matrices, collector, 0, null);
        }
    }

    record View(GearPreview preview, float yaw, int x0, int y0, int x1, int y1, float scale)
            implements PictureInPictureRenderState {
        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }

        @Override
        public ScreenRectangle bounds() {
            return new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
        }
    }

    static final class Renderer extends PictureInPictureRenderer<View> {
        @Override
        public Class<View> getRenderStateClass() {
            return View.class;
        }

        @Override
        protected void renderToTexture(View view, PoseStack matrices, SubmitNodeCollector collector) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
            matrices.scale(CAMERA_X_DIRECTION, 1, DEPTH_SCALE);
            matrices.mulPose(Axis.YP.rotationDegrees(view.yaw()));
            matrices.translate(0, -CENTER_Y_BLOCKS, 0);
            view.preview().submit(matrices, collector);
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2f;
        }

        @Override
        protected String getTextureLabel() {
            return "gear binding client test";
        }
    }
}
