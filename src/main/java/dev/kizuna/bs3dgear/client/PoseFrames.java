package dev.kizuna.bs3dgear.client;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;

/** 可选 PA 接线在独立类里解析，未安装 PA 时不会链接其类型。 */
interface PoseFrames {
    Matrix4f boneDelta(AvatarRenderState state, String bone);

    boolean firstPerson(AvatarRenderState state);
}
