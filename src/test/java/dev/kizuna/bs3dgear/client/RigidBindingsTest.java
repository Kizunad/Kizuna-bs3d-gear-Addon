package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RigidBindingsTest {
    private static final int SKIN_PIXELS = 64;
    private static final float EPSILON = 0.00001f;
    private static final float ARM_ROTATION_RADIANS = 0.7f;
    private static final float ANIMATION_SHIFT_BLOCKS = 0.25f;

    @Test
    void vanillaAndUnanimatedPaAgreeForEveryImportedBone() {
        // 对拍真实原版部件矩阵，避免肘/膝/脚在回退路径被重复平移。
        PlayerModel model = model();
        var state = new AvatarRenderState();
        var pa = new RigidBindings(new FixedFrame(new Matrix4f()));
        for (String name : new String[] {"head", "chest", "waist", "pelvis", "r_upper_arm", "l_upper_arm",
                "r_forearm", "l_forearm", "r_thigh", "l_thigh", "r_shin", "l_shin", "r_foot", "l_foot"}) {
            var bone = WearRig.bone(name);
            Matrix4f expected = pa.transform(state, model, bone);
            Matrix4f fallback = RigidBindings.vanilla(model, bone);
            assertTrue(expected.equals(fallback, EPSILON), name);
        }
    }

    @Test
    void absentFrameFallsBackToPosedVanillaPart() {
        PlayerModel model = model();
        model.rightArm.xRot = ARM_ROTATION_RADIANS;
        var bone = WearRig.bone("r_forearm");
        var state = new AvatarRenderState();
        Matrix4f actual = new RigidBindings(new FixedFrame(null)).transform(state, model, bone);
        assertTrue(RigidBindings.vanilla(model, bone).equals(actual, EPSILON));
        assertTrue(new RigidBindings(null).transform(state, model, bone).equals(actual, EPSILON));
    }

    @Test
    void paDeltaIsNotMultipliedByVanillaPoseAgain() {
        PlayerModel model = model();
        model.rightArm.xRot = ARM_ROTATION_RADIANS;
        var delta = new Matrix4f().translation(ANIMATION_SHIFT_BLOCKS, 0, 0);
        var bindings = new RigidBindings(new FixedFrame(delta));
        var bone = WearRig.bone("r_forearm");
        Vector3f position = bindings.transform(new AvatarRenderState(), model, bone)
                .transformPosition(new Vector3f());
        var pivot = bone.pivot();
        assertEquals(pivot.x() / WearRig.PIXELS_PER_BLOCK + ANIMATION_SHIFT_BLOCKS, position.x, EPSILON);
        assertEquals((WearRig.ORIGIN_HEIGHT - pivot.y()) / WearRig.PIXELS_PER_BLOCK, position.y, EPSILON);
        assertEquals(pivot.z() / WearRig.PIXELS_PER_BLOCK, position.z, EPSILON);
    }

    @Test
    void unknownBoneCannotSilentlyUseBody() {
        assertThrows(IllegalArgumentException.class, () -> WearRig.bone("left_foream"));
    }

    @Test
    void pixelScaleKeepsRenderedNormalsNormalized() {
        var stack = new PoseStack();
        var bindings = new RigidBindings(new FixedFrame(new Matrix4f()));
        var transform = bindings.transform(new AvatarRenderState(), model(), WearRig.bone("head"));
        RigidBindings.apply(stack, transform);
        var normal = stack.last().transformNormal(new Vector3f(0, 1, 0), new Vector3f());
        assertEquals(1, normal.length(), EPSILON);
        assertEquals(-1, normal.y, EPSILON);
    }

    private static PlayerModel model() {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false /* slim */);
        var part = LayerDefinition.create(mesh, SKIN_PIXELS, SKIN_PIXELS).bakeRoot();
        return new PlayerModel(part, false /* slim */);
    }

    private record FixedFrame(Matrix4f matrix) implements PoseFrames {
        @Override
        public Matrix4f boneDelta(AvatarRenderState state, String bone) {
            if (matrix == null) {
                return null;
            }
            return new Matrix4f(matrix);
        }

        @Override
        public boolean firstPerson(AvatarRenderState state) {
            return false;
        }
    }
}
