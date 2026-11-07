package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.client.EquipmentAppearances;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.EquipmentAppearance;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.client.ClientModule;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class GearRuntimeClientTest {
    private static final float BODY_YAW_RADIANS = 0.4f;
    private static final float EPSILON = 0.00001f;
    private static final String PA_CLASS = "dev.kizuna.playeranimation.client.EntityFrame";

    @Test
    void optionalPaIsActuallyAbsentOrUsesRealSkeletonMatrices() throws ReflectiveOperationException {
        boolean installed = FabricLoader.getInstance().isModLoaded("kizuna_players_animation");
        assertEquals(Boolean.getBoolean("gear.test.pa"), installed);
        if (installed) {
            assertNotNull(Class.forName(PA_CLASS));
            PaClientProbe.verifyBindings();
        } else {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(PA_CLASS));
        }
    }

    @Test
    void warArmorFollowsFramesExtractedFromActualPlayer() throws ReflectiveOperationException {
        // 通过真实玩家渲染入口取帧，不能用手工挂帧掩盖 extract 到 layer 的接线问题。
        if (FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            PaClientProbe.verifyActualPlayerFrames();
        }
    }

    @Test
    void invalidOverrideIsIsolatedAndAValidReplacementRecovers() {
        var types = GearClientTest.types();
        var type = types.getFirst();
        var asset = ModelResources.find(type.model());
        var container = ModelResources.find("bs3d:ku_teng_yao_dai");
        assertNotNull(container);
        assertNull(WearModels.resolve(type, container), "居中容器模式不能当作骨段");
        assertNull(WearModels.resolve(type, container), "错误实例应被缓存");
        var replacement = WearModels.resolve(type, asset);
        assertNotNull(replacement);
        assertSame(replacement, WearModels.find(type));
        assertNull(WearModels.resolve(type, ModelResources.find(types.getLast().model())), "错误段名必须拒绝");
        assertNotNull(WearModels.find(type), "原资源恢复后不能继续引用错误覆盖");
    }

    @Test
    void registeredPlayerLayerUsesMatchingAuthoritativeAppearance() throws ReflectiveOperationException {
        var module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(VolumeModule.class::isInstance).map(VolumeModule.class::cast).findFirst().orElseThrow();
        var type = GearClientTest.types().getFirst();
        var worn = type.slots().stream().map(slot -> WornModel.newBuilder()
                .setItemType(type.id().toString())
                .setWearableSlot(slot)
                .build()).toList();
        var snapshot = appearance(worn);
        var client = Minecraft.getInstance();
        var avatar = client.getEntityRenderDispatcher().getPlayerRenderer(client.player);
        var state = avatar.createRenderState();
        state.id = client.player.getId();
        var layer = registeredLayer(avatar);
        try {
            module.appearances().accept(snapshot.toBuilder().setDimension("minecraft:the_end").build());
            assertEquals(0, submitLayer(layer, state));
            module.appearances().accept(snapshot);
            assertTrue(submitLayer(layer, state) > 0, "实际注册的渲染层没有消费外观");
            state.isInvisible = true;
            assertEquals(0, submitLayer(layer, state));
            state.isInvisible = false;
            state.isSpectator = true;
            assertEquals(0, submitLayer(layer, state));
        } finally {
            module.appearances().accept(snapshot.toBuilder().clearModels().build());
        }
    }

    @Test
    void originalContainerRendererRunsMergedBindingMixin() throws ReflectiveOperationException {
        var worn = WornModel.newBuilder()
                .setItemType("bs3d:ku_teng_yao_dai")
                .setMount(Mount.WAIST_RIGHT_FRONT)
                .build();
        var appearances = new EquipmentAppearances();
        appearances.accept(appearance(List.of(worn)));
        var renderer = containerRenderer(appearances);
        var client = Minecraft.getInstance();
        var model = client.getEntityRenderDispatcher().getPlayerRenderer(client.player).getModel();
        float oldYaw = model.body.yRot;
        try {
            model.body.yRot = BODY_YAW_RADIANS;
            var body = new PoseStack();
            model.body.translateAndRotate(body);
            var state = new AvatarRenderState();
            state.id = client.player.getId();
            var baseline = renderer.render(state, body);
            var expected = containerTransform(state, body, baseline);
            var actual = renderer.render(state, body);
            assertTrue(expected.equals(actual, EPSILON));
        } finally {
            model.body.yRot = oldYaw;
        }
    }

    @Test
    void firstPersonFrameSuppressesHeadBoundSegments() {
        var state = new AvatarRenderState();
        assertFalse(GearClient.bindings().hidesHead(state));
        if (FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            PaClientProbe.attachFirstPerson(state);
            assertTrue(GearClient.bindings().hidesHead(state));
            var type = GearClientTest.types().getFirst();
            var worn = type.slots().stream().map(slot -> WornModel.newBuilder()
                    .setItemType(type.id().toString())
                    .setWearableSlot(slot)
                    .build()).toList();
            var client = Minecraft.getInstance();
            var model = client.getEntityRenderDispatcher().getPlayerRenderer(client.player).getModel();
            var collector = new GeometryProbe();
            var context = new GearRenderer.Context(state, model, collector, LightCoordsUtil.FULL_BRIGHT);
            new GearRenderer(GearClient.bindings()).submit(new PoseStack(), worn, context);
            assertTrue(collector.transforms.isEmpty());
        }
    }

    static EquipmentAppearance appearance(List<WornModel> models) {
        // 测试外观使用当前世界中的真实玩家身份。
        var client = Minecraft.getInstance();
        return EquipmentAppearance.newBuilder()
                .setPlayerUuid(client.player.getUUID().toString())
                .setEntityId(client.player.getId())
                .setDimension(client.level.dimension().identifier().toString())
                .addAllModels(models)
                .build();
    }

    private static ContainerCall containerRenderer(EquipmentAppearances appearances)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName("dev.kizuna.backpack3d.client.WorldEquipmentRenderer");
        var constructor = type.getDeclaredConstructor(EquipmentAppearances.class);
        constructor.setAccessible(true /* flag */);
        var method = type.getDeclaredMethod("render", AvatarRenderState.class, PoseStack.class,
                SubmitNodeCollector.class, int.class);
        method.setAccessible(true /* flag */);
        return new ContainerCall(constructor.newInstance(appearances), method);
    }

    private static Matrix4f containerTransform(AvatarRenderState state, PoseStack body, Matrix4f baseline) {
        if (!FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            return baseline;
        }
        PaClientProbe.attach(state, GearPreview.PoseMode.BENT);
        var delta = GearClient.bindings().boneDelta(state, "waist");
        return delta.mul(new Matrix4f(body.last().pose()).invert()).mul(baseline);
    }

    private record ContainerCall(Object renderer, Method method) {
        Matrix4f render(AvatarRenderState state, PoseStack body) throws ReflectiveOperationException {
            var collector = new GeometryProbe();
            method.invoke(renderer, state, body, collector, LightCoordsUtil.FULL_BRIGHT);
            assertFalse(collector.transforms.isEmpty(), "原 BS3D 容器渲染没有提交几何");
            return collector.transforms.getFirst();
        }
    }

    @SuppressWarnings("unchecked")
    static RenderLayer<AvatarRenderState, PlayerModel> registeredLayer(Object renderer)
            throws ReflectiveOperationException {
        // 直接取 Fabric 注册的生产层，避免测试另造一条渲染入口。
        var field = LivingEntityRenderer.class.getDeclaredField("layers");
        field.setAccessible(true /* flag */);
        var layers = (List<RenderLayer<AvatarRenderState, PlayerModel>>) field.get(renderer);
        return layers.stream().filter(layer -> layer.getClass().getName().equals(GearClient.class.getName() + "$Layer"))
                .findFirst().orElseThrow();
    }

    private static int submitLayer(RenderLayer<AvatarRenderState, PlayerModel> layer, AvatarRenderState state) {
        var collector = new GeometryProbe();
        layer.submit(new PoseStack(), collector, LightCoordsUtil.FULL_BRIGHT, state, 0, 0);
        return collector.transforms.size();
    }

    private static final class GeometryProbe extends SubmitNodeStorage {
        private final List<Matrix4f> transforms = new ArrayList<>();

        @Override
        public void submitCustomGeometry(PoseStack matrices, RenderType type,
                SubmitNodeCollector.CustomGeometryRenderer geometry) {
            transforms.add(new Matrix4f(matrices.last().pose()));
        }
    }
}
