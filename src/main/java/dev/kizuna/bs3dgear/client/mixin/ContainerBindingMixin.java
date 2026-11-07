package dev.kizuna.bs3dgear.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import dev.kizuna.backpack3d.client.WornPreview;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 合并旧 PA-BS3D 附属的刚性挂接职责，挂点公式仍由 BS3D 单独维护。 */
@Mixin(targets = "dev.kizuna.backpack3d.client.WorldEquipmentRenderer")
abstract class ContainerBindingMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Ldev/kizuna/backpack3d/client/WornModelTransform;apply("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/kizuna/backpack3d/client/ModelAsset;Z"
                    + "Ldev/kizuna/backpack3d/protocol/pb/VolumeProtocol$Mount;F)V"))
    private void gear$bindContainer(CallbackInfo callback, @Local(argsOnly = true) AvatarRenderState state,
            @Local(ordinal = 1) PoseStack matrices, @Local WornModel worn) {
        WornPreview.applyAttachmentTransforms(matrices, state, worn.getMount());
    }
}
