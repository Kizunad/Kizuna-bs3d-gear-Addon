package dev.kizuna.bs3dgear.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import dev.kizuna.backpack3d.client.BagAnimation;
import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.client.mixin.SpecialModelRenderersAccess;
import dev.kizuna.backpack3d.item.WearableItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** 物品栏和手持先恢复各段绑定位置，再整体适配一格显示；不改变穿戴尺度。 */
final class GearItemModel implements SpecialModelRenderer<GearItemModel.Shape> {
    private static final Identifier TYPE = Identifier.fromNamespaceAndPath("bs3d_gear", "wearable");
    private static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
    private static final float ITEM_CENTER_BLOCKS = 0.5f;
    private static final float ITEM_EXTENT_BLOCKS = 0.85f;
    private static final float SIDE_VIEW_DEGREES = 90;
    private final Map<String, Shape> shapes = new HashMap<>();

    static void register() {
        SpecialModelRenderersAccess.idMapper().put(TYPE, CODEC);
    }

    @Override
    public Shape extractArgument(ItemStack stack) {
        // 每种物品只保留当前资源实例；热重载后用新资产替换缓存，不积累旧贴图引用。
        var type = WearableItems.definition(stack);
        if (type == null) {
            return null;
        }
        String modelId = type.model();
        WearModels.Loaded asset = WearModels.find(type);
        if (asset == null) {
            shapes.remove(modelId);
            return null;
        }
        Shape cached = shapes.get(modelId);
        if (cached != null && cached.asset() == asset.source()) {
            return cached;
        }
        Shape shape = shape(type, asset);
        shapes.put(modelId, shape);
        return shape;
    }

    private static Shape shape(WearableItems.Definition type, WearModels.Loaded asset) {
        var segments = new ArrayList<Segment>();
        var low = new Vector3f(Float.POSITIVE_INFINITY);
        var high = new Vector3f(Float.NEGATIVE_INFINITY);
        for (var slot : type.slots()) {
            var part = asset.segments().get(slot.getSegment());
            var pivot = WearRig.bone(slot.getBone()).pivot();
            var view = new PoseStack();
            view.translate(pivot.x(), pivot.y(), pivot.z());
            float[] front = part.bounds(view, BagAnimation.Sample.CLOSED);
            var sideView = new PoseStack();
            sideView.mulPose(Axis.YP.rotationDegrees(SIDE_VIEW_DEGREES));
            sideView.translate(pivot.x(), pivot.y(), pivot.z());
            float[] side = part.bounds(sideView, BagAnimation.Sample.CLOSED);
            low.min(new Vector3f(front[0], front[1], side[0]));
            high.max(new Vector3f(front[2], front[3], side[2]));
            segments.add(new Segment(part, pivot));
        }
        Vector3f center = new Vector3f(low).add(high).mul(ITEM_CENTER_BLOCKS);
        Vector3f extent = new Vector3f(high).sub(low);
        float largest = Math.max(extent.x, Math.max(extent.y, extent.z));
        if (!Float.isFinite(largest) || largest <= 0) {
            return null;
        }
        return new Shape(asset.source(), List.copyOf(segments), center, ITEM_EXTENT_BLOCKS / largest);
    }

    @Override
    public void submit(Shape shape, PoseStack matrices, SubmitNodeCollector collector, int light,
            int overlay, boolean hasFoil, int outlineColor) {
        // 原版特殊模型接口的参数固定；逐段复用 BS3D 的实际贴图与环境光绘制。
        if (shape == null) {
            return;
        }
        matrices.pushPose();
        matrices.translate(ITEM_CENTER_BLOCKS, ITEM_CENTER_BLOCKS, ITEM_CENTER_BLOCKS);
        float scale = shape.scale();
        matrices.scale(scale, scale, scale);
        var center = shape.center();
        matrices.translate(-center.x, -center.y, -center.z);
        for (Segment segment : shape.segments()) {
            matrices.pushPose();
            var pivot = segment.pivot();
            matrices.translate(pivot.x(), pivot.y(), pivot.z());
            segment.asset().render(matrices, collector, 0, null, light, overlay);
            matrices.popPose();
        }
        matrices.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        for (var corner : ModelAsset.vertices(new Vector3f(), new Vector3f(1))) {
            output.accept(corner);
        }
    }

    record Segment(ModelAsset asset, WearRig.Point pivot) {}

    record Shape(ModelAsset asset, List<Segment> segments, Vector3f center, float scale) {}

    record Unbaked() implements SpecialModelRenderer.Unbaked<Shape> {
        @Override
        public SpecialModelRenderer<Shape> bake(BakingContext context) {
            return new GearItemModel();
        }

        @Override
        public MapCodec<Unbaked> type() {
            return CODEC;
        }
    }
}
