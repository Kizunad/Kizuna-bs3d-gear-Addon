package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.EquipmentAppearances;
import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Container;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class ContainerPreviewClientTest {
    private static final float EPSILON = 0.00001f;
    private static final float BODY_YAW_RADIANS = 0.4f;
    private static final float VIEW_PITCH_RADIANS = -0.2f;
    private static final float VIEW_SCALE = 3;
    private static final String PREVIEW_ID = "test-container";

    @Test
    void actualPreviewAndWorldUseDistinctAnimatedWaistAndChest() throws ReflectiveOperationException {
        var waist = worn("bs3d:ku_teng_yao_dai", Mount.WAIST_RIGHT_FRONT);
        var chest = worn("bs3d:gu_jia_bei_lou", Mount.TORSO_BACK);
        var client = Minecraft.getInstance();
        var model = client.getEntityRenderDispatcher().getPlayerRenderer(client.player).getModel();
        float oldYaw = model.body.yRot;
        try {
            model.body.yRot = BODY_YAW_RADIANS;
            verifyAttachment(waist, "pelvis", model);
            verifyAttachment(chest, "chest", model);
        } finally {
            model.body.yRot = oldYaw;
        }
    }

    private static void verifyAttachment(WornModel worn, String bone, PlayerModel model)
            throws ReflectiveOperationException {
        var state = new AvatarRenderState();
        state.id = Minecraft.getInstance().player.getId();
        var root = new PoseStack();
        root.mulPose(new Matrix4f().rotateX(VIEW_PITCH_RADIANS).scale(VIEW_SCALE));
        var body = ModelAsset.copy(root);
        model.body.translateAndRotate(body);
        var baseline = world(worn, state, body);
        var preview = preview(worn);
        assertMatrix(baseline, preview.render(root, state, model), "无帧预览回退");
        Matrix4f expected = baseline;
        if (FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            PaClientProbe.attach(state, GearPreview.PoseMode.BENT);
            var delta = GearClient.bindings().boneDelta(state, bone);
            assertFalse(delta.equals(new Matrix4f(), EPSILON), bone + " 测试帧必须包含实际增量");
            var other = GearClient.bindings().boneDelta(state, bone.equals("pelvis") ? "chest" : "pelvis");
            assertFalse(delta.equals(other, EPSILON), "胸腰不能复用同一矩阵");
            expected = new Matrix4f(root.last().pose()).mul(delta)
                    .mul(new Matrix4f(body.last().pose()).invert()).mul(baseline);
        }
        assertMatrix(expected, world(worn, state, body), "世界挂点");
        assertMatrix(expected, preview.render(root, state, model), "预览挂点");
        var fresh = new AvatarRenderState();
        fresh.id = state.id;
        assertMatrix(baseline, preview.render(root, fresh, model), "新快照不能沿用旧 PA 帧");
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual, String message) {
        assertTrue(expected.equals(actual, EPSILON), message + "\nexpected=" + expected + "\nactual=" + actual);
    }

    static WornModel worn(String itemType, Mount mount) {
        return WornModel.newBuilder()
                .setItemType(itemType)
                .setMount(mount)
                .build();
    }

    static Matrix4f world(WornModel worn, AvatarRenderState state, PoseStack body)
            throws ReflectiveOperationException {
        var client = Minecraft.getInstance();
        var appearance = EquipmentAppearance.newBuilder()
                .setPlayerUuid(client.player.getUUID().toString())
                .setEntityId(client.player.getId())
                .setDimension(client.level.dimension().identifier().toString())
                .addModels(worn)
                .build();
        var appearances = new EquipmentAppearances();
        appearances.accept(appearance);
        Class<?> type = Class.forName("dev.kizuna.backpack3d.client.WorldEquipmentRenderer");
        var constructor = type.getDeclaredConstructor(EquipmentAppearances.class);
        constructor.setAccessible(true /* flag */);
        var method = type.getDeclaredMethod("render", AvatarRenderState.class, PoseStack.class,
                SubmitNodeCollector.class, int.class);
        method.setAccessible(true /* flag */);
        var collector = new GeometryProbe();
        method.invoke(constructor.newInstance(appearances), state, body, collector, LightCoordsUtil.FULL_BRIGHT);
        assertFalse(collector.transforms.isEmpty());
        return collector.transforms.getFirst();
    }

    static PreviewCall preview(WornModel worn) throws ReflectiveOperationException {
        var client = Minecraft.getInstance();
        var stack = ContainerItems.create(Identifier.parse(worn.getItemType()));
        var type = ContainerItems.definition(stack);
        String modelId = type.model();
        var container = Container.newBuilder()
                .setId(PREVIEW_ID)
                .setModel(modelId)
                .setMount(worn.getMount())
                .setWaistAngleDegrees(worn.getWaistAngleDegrees())
                .setWorn(true /* worn */)
                .build();
        var item = Item.newBuilder()
                .setId(PREVIEW_ID)
                .setStackNbt(StackCodec.encode(stack, client.getConnection().registryAccess()))
                .build();
        var snapshot = State.newBuilder()
                .addContainers(container)
                .addItems(item)
                .build();
        var module = new VolumeModule();
        var accept = VolumeModule.class.getDeclaredMethod("accept", State.class);
        accept.setAccessible(true /* flag */);
        accept.invoke(module, snapshot);
        return previewCall(module, modelId);
    }

    private static PreviewCall previewCall(VolumeModule module, String model) throws ReflectiveOperationException {
        var preview = new WornPreview(module);
        var animation = new BagAnimation();
        animation.configure(ModelResources.find(model));
        var animations = WornPreview.class.getDeclaredField("animations");
        animations.setAccessible(true /* flag */);
        animations.set(preview, Map.of(PREVIEW_ID, animation));
        var render = WornPreview.class.getDeclaredMethod("renderPreview", PoseStack.class,
                SubmitNodeCollector.class, AvatarRenderState.class, PlayerModel.class);
        render.setAccessible(true /* flag */);
        return new PreviewCall(preview, render);
    }

    record PreviewCall(WornPreview preview, Method method) {
        Matrix4f render(PoseStack root, AvatarRenderState state, PlayerModel model)
                throws ReflectiveOperationException {
            var collector = new GeometryProbe();
            method.invoke(preview, root, collector, state, model);
            assertFalse(collector.transforms.isEmpty(), "WornPreview 实际路径没有提交容器");
            assertNotNull(preview.bounds(PREVIEW_ID), "必须同时生成命中变换");
            return collector.transforms.getFirst();
        }
    }

    static final class GeometryProbe extends SubmitNodeStorage {
        final List<Matrix4f> transforms = new ArrayList<>();

        @Override
        public void submitCustomGeometry(PoseStack matrices, RenderType type,
                SubmitNodeCollector.CustomGeometryRenderer geometry) {
            transforms.add(new Matrix4f(matrices.last().pose()));
        }
    }
}
