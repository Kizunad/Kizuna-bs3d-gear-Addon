package dev.kizuna.bs3dgear.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.inventoryui.client.ClientModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;

public final class GearClient implements ClientModInitializer {
    private static RigidBindings bindings;

    @Override
    public void onInitializeClient() {
        // 可选 PA 类只在加载器确认已安装时构造，无 PA 的物理 classpath 也能启动。
        PoseFrames frames = null;
        if (FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
            frames = new PaPoseFrames();
        }
        bindings = new RigidBindings(frames);
        WornPreview.registerAttachmentTransform(GearClient::bindContainer);
        var module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(VolumeModule.class::isInstance)
                .map(VolumeModule.class::cast)
                .findFirst().orElseThrow();
        var renderer = new GearRenderer(bindings);
        GearItemModel.register();
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, parent, helper, context) -> {
            if (parent instanceof AvatarRenderer<?> avatar) {
                helper.register(new Layer(avatar, module, renderer));
            }
        });
    }

    public static RigidBindings bindings() {
        return bindings;
    }

    public static void bindContainer(PoseStack matrices, AvatarRenderState state, Mount mount) {
        // BS3D 已应用原版 body；去掉它后接 PA 腰/胸增量，随后仍由原 BS3D 计算挂点与缩放。
        String bone = "waist";
        if (mount == Mount.TORSO_FRONT || mount == Mount.TORSO_BACK) {
            bone = "chest";
        }
        Matrix4f delta = bindings.boneDelta(state, bone);
        if (delta == null) {
            return;
        }
        var client = Minecraft.getInstance();
        var entity = client.level.getEntity(state.id);
        if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
            return;
        }
        var body = new PoseStack();
        client.getEntityRenderDispatcher().getPlayerRenderer(player).getModel().body.translateAndRotate(body);
        Matrix4f adjustment = new Matrix4f(body.last().pose()).invert().mul(delta);
        RigidBindings.apply(matrices, adjustment);
    }

    private static final class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final VolumeModule module;
        private final GearRenderer renderer;

        Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, VolumeModule module, GearRenderer renderer) {
            super(parent);
            this.module = module;
            this.renderer = renderer;
        }

        @Override
        public void submit(PoseStack matrices, SubmitNodeCollector collector, int light,
                AvatarRenderState state, float yRot, float xRot) {
            // UI 使用世界中同一玩家的快照；实体 ID 和维度同时匹配才允许取其公开外观。
            var level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            var entity = level.getEntity(state.id);
            if (entity == null) {
                return;
            }
            String dimension = level.dimension().identifier().toString();
            var worn = module.appearances().models(entity.getUUID(), state.id, dimension);
            renderer.submit(matrices, worn, new GearRenderer.Context(state, getParentModel(), collector, light));
        }
    }
}
