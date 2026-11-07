package dev.kizuna.bs3dgear.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** 世界、UI 和验证预览共用刚性分段绘制；权威穿戴状态仍来自 BS3D。 */
public final class GearRenderer {
    private final RigidBindings bindings;
    private final Map<String, WearableItems.Definition> types = new LinkedHashMap<>();

    public GearRenderer(RigidBindings bindings) {
        this.bindings = bindings;
        for (var type : WearableItems.types().values()) {
            var id = type.id();
            if (id.getNamespace().equals("bs3d_gear")) {
                types.put(id.toString(), type);
            }
        }
    }

    public void submit(PoseStack base, List<WornModel> worn, Context context) {
        // 身份和维度在调用方核对；不从本地手持栈推断其他玩家装备。
        AvatarRenderState state = context.state();
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        for (WornModel entry : worn) {
            WearableItems.Definition type = types.get(entry.getItemType());
            if (type == null || !entry.hasWearableSlot()) {
                continue;
            }
            var slot = entry.getWearableSlot();
            if (!type.slots().contains(slot) || bindings.hidesHead(state) && slot.getBone().equals("head")) {
                continue;
            }
            WearModels.Loaded model = WearModels.find(type);
            if (model != null) {
                submitSegment(base, model.segments().get(slot.getSegment()), slot, context);
            }
        }
    }

    private void submitSegment(PoseStack base, ModelAsset segment,
            dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WearableSlot slot, Context context) {
        // 每帧重新取资源；F3+T 替换资产后不会继续引用旧段或贴图。
        var matrices = ModelAsset.copy(base);
        var state = context.state();
        var transform = bindings.transform(state, context.model(), WearRig.bone(slot.getBone()));
        RigidBindings.apply(matrices, transform);
        var offset = slot.getOffset();
        float units = WearRig.PIXELS_PER_BLOCK;
        matrices.translate(offset.getX() * units, offset.getY() * units, offset.getZ() * units);
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
        segment.render(matrices, context.collector(), 0, null, context.light(), overlay);
    }

    public record Context(AvatarRenderState state, PlayerModel model, SubmitNodeCollector collector, int light) {}
}
