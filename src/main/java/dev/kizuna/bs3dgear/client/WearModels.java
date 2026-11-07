package dev.kizuna.bs3dgear.client;

import dev.kizuna.backpack3d.client.ModelAsset;
import dev.kizuna.backpack3d.client.ModelResources;
import dev.kizuna.backpack3d.item.WearableItems;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 世界和物品预览共用资源校验；不完整的玩家覆盖模型只停用对应穿搭。 */
final class WearModels {
    private static final Logger LOG = LoggerFactory.getLogger("bs3d_gear/resources");
    private static final Map<String, Entry> CURRENT = new HashMap<>();

    private WearModels() {}

    static Loaded find(WearableItems.Definition type) {
        String modelId = type.model();
        ModelAsset asset = ModelResources.find(modelId);
        if (asset == null) {
            CURRENT.remove(modelId);
            return null;
        }
        return resolve(type, asset);
    }

    static Loaded resolve(WearableItems.Definition type, ModelAsset asset) {
        String modelId = type.model();
        Entry cached = CURRENT.get(modelId);
        if (cached != null && cached.source() == asset) {
            return cached.loaded();
        }
        Loaded loaded = validate(type, asset);
        CURRENT.put(modelId, new Entry(asset, loaded));
        return loaded;
    }

    private static Loaded validate(WearableItems.Definition type, ModelAsset asset) {
        // segment 同时校验 raw_segments 模式和段名；失败只对当前资源实例告警一次。
        var segments = new LinkedHashMap<String, ModelAsset>();
        try {
            for (var slot : type.slots()) {
                String name = slot.getSegment();
                segments.put(name, asset.segment(name));
            }
        } catch (IllegalArgumentException invalid) {
            LOG.warn("停用穿搭模型 {}：{}", type.model(), invalid.getMessage());
            return null;
        }
        return new Loaded(asset, Map.copyOf(segments));
    }

    record Loaded(ModelAsset source, Map<String, ModelAsset> segments) {}

    private record Entry(ModelAsset source, Loaded loaded) {}
}
