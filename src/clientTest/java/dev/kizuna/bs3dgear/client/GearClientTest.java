package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.junit.jupiter.api.Test;

/** 资源和原版模型均来自已启动客户端，不以手写几何代替实际导入资产。 */
final class GearClientTest {
    static final int GEAR_COUNT = 14;

    static List<WearableItems.Definition> types() {
        return WearableItems.types().values().stream()
                .filter(type -> type.id().getNamespace().equals("bs3d_gear"))
                .sorted(java.util.Comparator.comparing(type -> type.id().toString())).toList();
    }

    @Test
    void everyRegisteredAssetLoadsAllDeclaredSegmentsAndItemRenderer() {
        var types = types();
        assertEquals(GEAR_COUNT, types.size());
        var itemModel = new GearItemModel();
        for (var type : types) {
            var stack = WearableItems.create(type.id());
            assertNull(ContainerItems.definition(stack));
            var asset = ModelResources.find(type.model());
            assertNotNull(asset, type.model());
            assertEquals(type.slots().size(), asset.segments().size());
            for (var slot : type.slots()) {
                var segment = asset.segment(slot.getSegment());
                assertNotNull(segment);
                var bounds = segment.bounds(new com.mojang.blaze3d.vertex.PoseStack(), BagAnimation.Sample.CLOSED);
                for (float value : bounds) {
                    assertTrue(Float.isFinite(value), type.model());
                }
                assertNotNull(WearRig.bone(slot.getBone()));
            }
            assertNotNull(itemModel.extractArgument(stack));
        }
    }

    @Test
    void missingFrameUsesActualVanillaModelEvenWhenPaIsInstalled() {
        // 空快照不携带 EntityFrame；同一测试也在物理移除 PA 的客户端运行。
        var part = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
        var model = new PlayerModel(part, false /* slim */);
        var state = new AvatarRenderState();
        var bone = WearRig.bone("l_foot");
        assertEquals(RigidBindings.vanilla(model, bone), GearClient.bindings().transform(state, model, bone));
    }
}
