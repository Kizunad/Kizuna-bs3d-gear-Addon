package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.Test;

final class CapeLayeringClientTest {
    @Test
    void backContainersDoNotDisplaceWearableSegments() {
        // 穿戴背包只增加自己的几何，披风等穿搭仍保持同一骨局部位置。
        var client = Minecraft.getInstance();
        var model = client.getEntityRenderDispatcher().getPlayerRenderer(client.player).getModel();
        var renderer = new GearRenderer(GearClient.bindings());
        for (var mode : GearPreview.PoseMode.values()) {
            var state = new AvatarRenderState();
            if (FabricLoader.getInstance().isModLoaded("kizuna_players_animation")) {
                PaClientProbe.attach(state, mode);
            }
            var collector = new ContainerPreviewClientTest.GeometryProbe();
            var context = new GearRenderer.Context(state, model, collector, LightCoordsUtil.FULL_BRIGHT);
            verifyUnchangedSegments(renderer, context, collector);
        }
    }

    private static void verifyUnchangedSegments(GearRenderer renderer, GearRenderer.Context context,
            ContainerPreviewClientTest.GeometryProbe collector) {
        // 实际模型的提交矩阵在有无背包时必须一致，覆盖组合战装中的披风段。
        for (var type : GearClientTest.types()) {
            for (var slot : type.slots()) {
                var worn = WornModel.newBuilder()
                        .setItemType(type.id().toString())
                        .setWearableSlot(slot)
                        .build();
                collector.transforms.clear();
                renderer.submit(new PoseStack(), List.of(worn), context);
                var baseline = List.copyOf(collector.transforms);
                assertFalse(baseline.isEmpty());
                for (var container : ContainerItems.types().values()) {
                    if (container.mounts().contains(Mount.TORSO_BACK)) {
                        var pack = ContainerPreviewClientTest.worn(container.id().toString(), Mount.TORSO_BACK);
                        collector.transforms.clear();
                        renderer.submit(new PoseStack(), List.of(worn, pack), context);
                        assertEquals(baseline, collector.transforms, type.id() + "/" + slot.getSegment());
                    }
                }
            }
        }
    }
}
