package dev.kizuna.bs3dgear.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;

/** 将骨枢轴局部像素转换到原版实体模型空间，PA 与原版只选一条路径。 */
public final class RigidBindings {
    private final PoseFrames frames;

    RigidBindings(PoseFrames frames) {
        this.frames = frames;
    }

    public Matrix4f transform(AvatarRenderState state, PlayerModel model, WearRig.Bone bone) {
        // PA 矩阵是全局绑定姿态的增量，先恢复骨枢轴，再进行像素和 Y 方向转换。
        Matrix4f delta = boneDelta(state, bone.name());
        if (delta == null) {
            return vanilla(model, bone);
        }
        var pivot = bone.pivot();
        float units = WearRig.PIXELS_PER_BLOCK;
        return delta.translate(pivot.x() / units, (WearRig.ORIGIN_HEIGHT - pivot.y()) / units, pivot.z() / units)
                .scale(1 / units, -1 / units, 1 / units);
    }

    public static Matrix4f vanilla(PlayerModel model, WearRig.Bone bone) {
        // 原版部件已经包含蹲伏和走路变换，只追加此段枢轴相对部件原点的差值。
        var stack = new PoseStack();
        bone.vanillaPart(model).translateAndRotate(stack);
        var pivot = bone.pivot();
        var part = bone.partPivot();
        float units = WearRig.PIXELS_PER_BLOCK;
        stack.translate((pivot.x() - part.x()) / units, (part.y() - pivot.y()) / units,
                (pivot.z() - part.z()) / units);
        stack.scale(1 / units, -1 / units, 1 / units);
        return new Matrix4f(stack.last().pose());
    }

    Matrix4f boneDelta(AvatarRenderState state, String bone) {
        if (frames == null) {
            return null;
        }
        return frames.boneDelta(state, bone);
    }

    boolean hidesHead(AvatarRenderState state) {
        return frames != null && frames.firstPerson(state);
    }

    public static void apply(PoseStack stack, Matrix4f transform) {
        // 原生接口同时计算逆转置和法线归一化标记，不能直接修改 pose/normal 而漏掉标记。
        stack.mulPose(transform);
    }
}
