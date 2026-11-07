package dev.kizuna.bs3dgear.client;

import dev.kizuna.playeranimation.client.EntityFrame;
import dev.kizuna.playeranimation.model.BoneId;
import dev.kizuna.playeranimation.model.Rig;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;

final class PaPoseFrames implements PoseFrames {
    @Override
    public Matrix4f boneDelta(AvatarRenderState state, String bone) {
        // 只消费 extract 阶段挂在同一渲染快照上的矩阵，不重新取样动画或读取旧 PLAYBACK.frame。
        EntityFrame frame = EntityFrame.of(state);
        if (frame == null) {
            return null;
        }
        return Rig.toVanilla(frame.pose().matrices()[BoneId.named(bone).ordinal()]);
    }

    @Override
    public boolean firstPerson(AvatarRenderState state) {
        EntityFrame frame = EntityFrame.of(state);
        return frame != null && frame.player() != null && frame.player().firstPersonPass();
    }
}
