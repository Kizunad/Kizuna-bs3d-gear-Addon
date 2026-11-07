package dev.kizuna.bs3dgear.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/** 导入与回退共用的绑定姿态，位置单位为像素；不保存逐帧动画状态。 */
public final class WearRig {
    private static final JsonObject DOCUMENT = read();
    public static final float PIXELS_PER_BLOCK = DOCUMENT.get("pixels_per_block").getAsFloat();
    public static final float ORIGIN_HEIGHT = DOCUMENT.get("origin_height_pixels").getAsFloat();
    private static final Map<String, Bone> BONES = bones();

    private WearRig() {}

    public static Bone bone(String name) {
        Bone bone = BONES.get(name);
        if (bone == null) {
            throw new IllegalArgumentException("穿搭未定义绑定骨：" + name);
        }
        return bone;
    }

    private static JsonObject read() {
        // 和导入脚本读取同一份静态坐标契约，不能在客户端另维护一组枢轴数值。
        var stream = WearRig.class.getResourceAsStream("/assets/bs3d_gear/rig.json");
        if (stream == null) {
            throw new IllegalStateException("缺少穿搭绑定姿态");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("读取穿搭绑定姿态失败", failure);
        }
    }

    private static Map<String, Bone> bones() {
        Map<String, Bone> result = new LinkedHashMap<>();
        for (var entry : DOCUMENT.getAsJsonObject("bones").entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            String name = entry.getKey();
            result.put(name, new Bone(name, point(value.getAsJsonArray("pivot")),
                    value.get("part").getAsString(), point(value.getAsJsonArray("part_pivot"))));
        }
        return Map.copyOf(result);
    }

    private static Point point(JsonArray value) {
        return new Point(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
    }

    public record Point(float x, float y, float z) {}

    public record Bone(String name, Point pivot, String part, Point partPivot) {
        public ModelPart vanillaPart(PlayerModel model) {
            // 小腿与脚在原版模型里共用整条腿，手臂分段同样退回对应整臂。
            return switch (part) {
                case "head" -> model.head;
                case "body" -> model.body;
                case "left_arm" -> model.leftArm;
                case "right_arm" -> model.rightArm;
                case "left_leg" -> model.leftLeg;
                case "right_leg" -> model.rightLeg;
                default -> throw new IllegalArgumentException("未知原版部件：" + part);
            };
        }
    }
}
