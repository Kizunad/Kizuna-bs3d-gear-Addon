package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.playeranimation.client.EntityFrame;
import dev.kizuna.playeranimation.client.PlayerAnimationClient;
import dev.kizuna.playeranimation.client.PlayerAssets;
import dev.kizuna.playeranimation.client.PlayerMeshRenderer;
import dev.kizuna.playeranimation.model.BoneId;
import dev.kizuna.playeranimation.model.Pose;
import dev.kizuna.playeranimation.model.Rig;
import dev.kizuna.playeranimation.model.Vec3;
import java.util.Set;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3f;
import org.joml.Matrix4f;

/** PA 在独立类中提供测试帧，无 PA 运行不会加载本类。 */
final class PaClientProbe {
    private static final float ELBOW_DEGREES = -60;
    private static final float KNEE_DEGREES = 55;
    private static final float HEAD_DEGREES = 30;
    private static final float WAIST_DEGREES = 18;
    private static final float CHEST_DEGREES = -14;
    private static final float WAIST_SHIFT_PIXELS = 2;
    private static final float CHEST_SHIFT_PIXELS = -3;
    private static final int BODY_COLOR = 0xff7f8890;
    private static final float EPSILON = 0.00001f;
    private static final float SAMPLE_X_PIXELS = 0.25f;
    private static final float SAMPLE_Z_PIXELS = 0.5f;
    private static final float ANIMATION_PROGRESS = 0.45f;
    private static final String RUNTIME_ANIMATION = "peddler_mule_kick";

    private PaClientProbe() {}

    static void attach(AvatarRenderState state, GearPreview.PoseMode mode) {
        PlayerAssets.Model model = PlayerAnimationClient.ASSETS.model(false /* thinArms */);
        if (model == null) {
            throw new IllegalStateException("PA 客户端未加载真实骨架");
        }
        Pose[] pose = Rig.zeroPose();
        if (mode == GearPreview.PoseMode.BENT) {
            pose[BoneId.WAIST.ordinal()] = new Pose(new Vec3(WAIST_SHIFT_PIXELS, 0, 0),
                    new Vec3(0, WAIST_DEGREES, 0));
            pose[BoneId.CHEST.ordinal()] = new Pose(new Vec3(0, 0, CHEST_SHIFT_PIXELS),
                    new Vec3(CHEST_DEGREES, 0, 0));
            pose[BoneId.R_FOREARM.ordinal()] = new Pose(Vec3.ZERO, new Vec3(ELBOW_DEGREES, 0, 0));
            pose[BoneId.L_SHIN.ordinal()] = new Pose(Vec3.ZERO, new Vec3(KNEE_DEGREES, 0, 0));
            pose[BoneId.HEAD.ordinal()] = new Pose(Vec3.ZERO, new Vec3(0, HEAD_DEGREES, 0));
        }
        var frame = new PlayerMeshRenderer.Frame(model, Rig.matrices(model.skeleton(), pose));
        var parts = new EntityFrame.PlayerParts(false /* firstPersonPass */, false /* vanillaHands */,
                Set.of(), false /* hideLeftBoot */, false /* hideRightBoot */, true /* emptyHands */, null, null);
        state.setData(EntityFrame.KEY, EntityFrame.player(frame, parts));
    }

    static void drawBody(AvatarRenderState state, PoseStack matrices, SubmitNodeCollector collector) {
        // 参考人体使用同一帧 PA 蒙皮，能直接看到护腕、绑腿和头饰是否跟错骨或重复变换。
        var frame = EntityFrame.of(state).pose();
        collector.submitCustomGeometry(matrices, RenderTypes.entityCutout(ModelAsset.WHITE), (pose, vertices) -> {
            PlayerMeshRenderer.draw(frame, frame.model().mesh().surfaces(), pose, vertices,
                    LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, BODY_COLOR, surface -> !surface.outer());
        });
    }

    static void attachFirstPerson(AvatarRenderState state) {
        attach(state, GearPreview.PoseMode.BENT);
        var frame = EntityFrame.of(state).pose();
        var parts = new EntityFrame.PlayerParts(true /* firstPersonPass */, false /* vanillaHands */,
                Set.of(), false /* hideLeftBoot */, false /* hideRightBoot */, true /* emptyHands */, null, null);
        state.setData(EntityFrame.KEY, EntityFrame.player(frame, parts));
    }

    static void verifyBindings() {
        var state = new AvatarRenderState();
        attach(state, GearPreview.PoseMode.BENT);
        var frame = EntityFrame.of(state).pose();
        var client = Minecraft.getInstance();
        var model = client.getEntityRenderDispatcher().getPlayerRenderer(client.player).getModel();
        var names = GearClientTest.types().stream().flatMap(type -> type.slots().stream())
                .map(slot -> slot.getBone()).distinct().toList();
        for (String name : names) {
            var bone = WearRig.bone(name);
            var boneId = BoneId.named(name);
            var actualPivot = frame.model().skeleton().bone(boneId).pivot();
            var pivot = bone.pivot();
            assertEquals(actualPivot.x(), pivot.x(), EPSILON, name);
            assertEquals(actualPivot.y(), pivot.y(), EPSILON, name);
            assertEquals(actualPivot.z(), pivot.z(), EPSILON, name);
            var local = new Vector3f(SAMPLE_X_PIXELS, 1, SAMPLE_Z_PIXELS);
            var global = new Vector3f(local).add(pivot.x(), pivot.y(), pivot.z());
            var animated = frame.matrices()[boneId.ordinal()].transformPosition(global, new Vector3f());
            var expected = new Vector3f(animated.x / Rig.PIXELS_PER_BLOCK,
                    (Rig.MODEL_ORIGIN_HEIGHT - animated.y) / Rig.PIXELS_PER_BLOCK,
                    animated.z / Rig.PIXELS_PER_BLOCK);
            var actual = GearClient.bindings().transform(state, model, bone).transformPosition(local, new Vector3f());
            assertTrue(expected.equals(actual, EPSILON), name);
        }
    }

    static void verifyActualPlayerFrames() throws ReflectiveOperationException {
        // 公开播放入口改变真实玩家，渲染器自己 extract，随后调用已注册的穿搭层。
        var client = Minecraft.getInstance();
        var avatar = client.getEntityRenderDispatcher().getPlayerRenderer(client.player);
        var layer = GearRuntimeClientTest.registeredLayer(avatar);
        var module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(VolumeModule.class::isInstance).map(VolumeModule.class::cast).findFirst().orElseThrow();
        try {
            PlayerAnimationClient.PLAYBACK.scrub(client.player.getUUID(), RUNTIME_ANIMATION, 0);
            AvatarRenderState start = avatar.createRenderState(client.player, 1);
            var before = actualArmorTransforms(start, avatar.getModel(), layer, module);
            PlayerAnimationClient.PLAYBACK.scrub(client.player.getUUID(), RUNTIME_ANIMATION, ANIMATION_PROGRESS);
            AvatarRenderState moved = avatar.createRenderState(client.player, 1);
            var after = actualArmorTransforms(moved, avatar.getModel(), layer, module);
            assertFalse(before.equals(after), "实际动画发生后整件战甲仍停在原位");
        } finally {
            PlayerAnimationClient.PLAYBACK.stop(client.player.getUUID(), RUNTIME_ANIMATION);
            module.appearances().accept(GearRuntimeClientTest.appearance(List.of()));
        }
    }

    private static List<Matrix4f> actualArmorTransforms(AvatarRenderState state,
            net.minecraft.client.model.player.PlayerModel model,
            net.minecraft.client.renderer.entity.layers.RenderLayer<AvatarRenderState,
                    net.minecraft.client.model.player.PlayerModel> layer, VolumeModule module) {
        // 每段比较实际提交与同帧骨骼矩阵，确认装备确实消费了 extract 的动画快照。
        assertNotNull(EntityFrame.of(state), "真实玩家 extract 丢失 PA 帧");
        var transforms = new java.util.ArrayList<Matrix4f>();
        var armor = GearClientTest.types().stream()
                .filter(type -> type.id().getPath().equals("war_suit")).findFirst().orElseThrow();
        for (var slot : armor.slots()) {
            var worn = WornModel.newBuilder()
                    .setItemType(armor.id().toString())
                    .setWearableSlot(slot)
                    .build();
            module.appearances().accept(GearRuntimeClientTest.appearance(List.of(worn)));
            var collector = new ContainerPreviewClientTest.GeometryProbe();
            layer.submit(new PoseStack(), collector, LightCoordsUtil.FULL_BRIGHT, state, 0, 0);
            assertFalse(collector.transforms.isEmpty(), slot.getSegment());
            var actual = collector.transforms.getFirst();
            var expected = GearClient.bindings().transform(state, model, WearRig.bone(slot.getBone()));
            assertTrue(expected.equals(actual, EPSILON), slot.getSegment());
            transforms.add(actual);
        }
        return List.copyOf(transforms);
    }
}
