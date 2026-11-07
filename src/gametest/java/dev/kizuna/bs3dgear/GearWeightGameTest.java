package dev.kizuna.bs3dgear;

import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.bs3d.weights.server.Burden;
import dev.kizuna.bs3d.weights.server.WeightRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** 生产 Weights API 读取 Gear 的真实清单和 BS3D 穿脱结果。 */
public final class GearWeightGameTest {
    private static final int REFRESH_TICKS = 6;
    private static final int TEST_TIMEOUT = 240;
    private static final long POUCH_GRAMS = 300;
    private static final long COIN_BOX_GRAMS = 800;
    private static final long MOISTURE_GUARD_GRAMS = 100;
    private static final long WATER_SKIN_GRAMS = 400;

    @GameTest(maxTicks = TEST_TIMEOUT)
    public void everyWearableCountsOnceAndUnequippingKeepsItsRealWeight(GameTestHelper context) {
        var fixture = new GearTestPlayer(context);
        var player = fixture.player;
        var sequence = context.startSequence();
        for (var type : WearableItems.types().values()) {
            if (!type.id().getNamespace().equals("bs3d_gear")) {
                continue;
            }
            sequence.thenExecute(() -> GearArmorGameTest.wear(player, type.id()));
            sequence.thenIdle(REFRESH_TICKS);
            sequence.thenExecute(() -> {
                reading(context, player, WeightRules.DEFAULT_UNIT_GRAMS);
                GearArmorGameTest.takeOff(player);
            });
            sequence.thenIdle(REFRESH_TICKS);
            sequence.thenExecute(() -> {
                reading(context, player, WeightRules.DEFAULT_UNIT_GRAMS);
                player.getInventory().setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            });
        }
        sequence.thenExecute(fixture::close).thenSucceed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT)
    public void everyBongContainerAndBeltUsesItsRegisteredOrPendingWeight(GameTestHelper context) {
        var fixture = new GearTestPlayer(context);
        var player = fixture.player;
        var sequence = context.startSequence();
        for (var type : ContainerItems.types().values()) {
            if (!type.id().getNamespace().equals("bs3d")) {
                continue;
            }
            sequence.thenExecute(() -> wearContainer(player, type.id()));
            sequence.thenIdle(REFRESH_TICKS);
            sequence.thenExecute(() -> reading(context, player, expectedContainerGrams(type.id().getPath())));
        }
        sequence.thenExecute(fixture::close).thenSucceed();
    }

    private static void wearContainer(ServerPlayer player, Identifier id) {
        var stack = ContainerItems.create(id);
        var type = ContainerItems.definition(stack);
        Mount mount = type.mounts().iterator().next();
        var worn = WornContainer.newBuilder()
                .setStackNbt(StackCodec.encode(stack, player.registryAccess()))
                .setMount(mount)
                .build();
        var storage = (PlayerStorage) player;
        var next = storage.bs3d$getInventory().toBuilder().clearWorn().addWorn(worn).build();
        storage.bs3d$setInventory(next);
    }

    private static long expectedContainerGrams(String id) {
        // 五件来源为固定 Bong 定义；其余明确保留未定重量的默认回退。
        return switch (id) {
            case "herb_pouch", "ore_sack" -> POUCH_GRAMS;
            case "coin_box" -> COIN_BOX_GRAMS;
            case "moisture_guard" -> MOISTURE_GUARD_GRAMS;
            case "water_skin" -> WATER_SKIN_GRAMS;
            default -> WeightRules.DEFAULT_UNIT_GRAMS;
        };
    }

    private static void reading(GameTestHelper context, ServerPlayer player, long expected) {
        long total = Burden.totalGrams(player).orElseThrow();
        long effective = Burden.effectiveGrams(player).orElseThrow();
        context.assertTrue(total == expected, "真实物品重复计重或漏计：" + total + "，预期 " + expected);
        context.assertTrue(effective == expected, "穿戴有效负重不符：" + effective + "，预期 " + expected);
    }
}
