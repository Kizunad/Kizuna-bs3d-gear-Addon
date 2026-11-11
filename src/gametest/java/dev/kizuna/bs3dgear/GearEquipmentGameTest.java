package dev.kizuna.bs3dgear;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.backpack3d.service.TransferService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 模型菜单使用的自动卸装事务：整件、归属、容量与重放。 */
public final class GearEquipmentGameTest {
    private static final TransferService TRANSFERS = new TransferService(stack -> Volumes.UNIT);
    private static final Identifier BELT = Identifier.fromNamespaceAndPath("bs3d", "ku_teng_yao_dai");
    private static final List<Identifier> POUCHES = List.of(
            Identifier.fromNamespaceAndPath("bs3d", "herb_pouch"),
            Identifier.fromNamespaceAndPath("bs3d", "ore_sack"),
            Identifier.fromNamespaceAndPath("bs3d", "water_skin"));
    private static final int BELT_AND_TWO_POUCHES = 3;

    @GameTest
    public void everyRegisteredEquipmentCanReturnToInventoryExactlyOnce(GameTestHelper context) throws IOException {
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            var stacks = new ArrayList<ItemStack>();
            WearableItems.types().values().forEach(type -> stacks.add(WearableItems.create(type.id())));
            ContainerItems.types().values().stream().filter(type -> !type.mounts().isEmpty())
                    .forEach(type -> stacks.add(ContainerItems.create(type.id())));
            for (ItemStack stack : stacks) {
                String id = wear(player, stack);
                var remove = request(player, id, Volumes.PLAYER).build();
                TRANSFERS.commit(new PlayerAccount(player), remove);
                TRANSFERS.commit(new PlayerAccount(player), remove);
                var account = new PlayerAccount(player);
                context.assertTrue(account.data().getWornCount() == 0, "容器卸装后仍挂载");
                context.assertTrue(account.data().getWornWearablesCount() == 0, "套装没有整件脱下");
                long count = account.inventory().stream().filter(value -> ItemStack.matches(stack, value)).count();
                context.assertTrue(count == 1, "自动取回重放导致复制或丢失");
                player.getInventory().clearContent();
            }
        }
        context.succeed();
    }

    @GameTest
    public void fullInventoryStaleRevisionAndForeignIdentityLeaveEquipmentUntouched(GameTestHelper context)
            throws IOException {
        try (var owner = new GearTestPlayer(context); var stranger = new GearTestPlayer(context)) {
            var player = owner.player;
            var type = WearableItems.types().values().iterator().next();
            var stack = WearableItems.create(type.id());
            String id = wear(player, stack);
            var remove = request(player, id, Volumes.PLAYER).build();
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                var full = new ItemStack(Items.STONE);
                full.setCount(full.getMaxStackSize());
                player.getInventory().setItem(slot, full);
            }
            rejectedUnchanged(context, player, remove);
            var foreign = request(stranger.player, id, Volumes.PLAYER).build();
            rejectedUnchanged(context, stranger.player, foreign);
            player.getInventory().clearContent();
            TRANSFERS.commit(new PlayerAccount(player), remove);
            var stale = remove.toBuilder().setOperation(UUID.randomUUID().toString()).build();
            rejectedUnchanged(context, player, stale);
        }
        context.succeed();
    }

    @GameTest
    public void beltLeavesBothWaistPlacesForPouchesAndOnlyOneBeltFits(GameTestHelper context) throws IOException {
        // 真实 Bong 清单里枯藤腰带是整圈腰带，不占默认两个腰部名额；第三个挂包、第二条腰带都拒绝。
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            wear(player, ContainerItems.create(BELT));
            wear(player, ContainerItems.create(POUCHES.get(0)));
            wear(player, ContainerItems.create(POUCHES.get(1)));
            context.assertTrue(new PlayerAccount(player).data().getWornCount() == BELT_AND_TWO_POUCHES,
                    "系上腰带后挂不满两个挂包");
            rejectedUnchanged(context, player, wearRequest(player, ContainerItems.create(POUCHES.get(2))));
            rejectedUnchanged(context, player, wearRequest(player, ContainerItems.create(BELT)));
        }
        context.succeed();
    }

    private static void rejectedUnchanged(GameTestHelper context, ServerPlayer player, MoveRequest request)
            throws IOException {
        var account = new PlayerAccount(player);
        var saved = account.data();
        var inventory = account.inventory();
        try {
            TRANSFERS.commit(account, request);
            context.fail("非法卸装被接受");
        } catch (IllegalArgumentException expected) {
            context.assertTrue(saved.equals(account.data()), "拒绝改变了装备或收据");
            for (int slot = 0; slot < inventory.size(); slot++) {
                context.assertTrue(ItemStack.matches(inventory.get(slot), player.getInventory().getItem(slot)),
                        "拒绝改变了原版库存");
            }
        }
    }

    private static String wear(ServerPlayer player, ItemStack stack) throws IOException {
        String id;
        if (WearableItems.definition(stack) != null) {
            id = WearableItems.identity(stack);
        } else {
            id = ContainerItems.identity(stack);
        }
        TRANSFERS.commit(new PlayerAccount(player), wearRequest(player, stack));
        return id;
    }

    /** 把物品放到手上，生成穿到它第一个挂点或第一个穿戴层的请求。 */
    private static MoveRequest wearRequest(ServerPlayer player, ItemStack stack) {
        var wearable = WearableItems.definition(stack);
        Placement placement;
        if (wearable != null) {
            placement = Placement.newBuilder().setWearableLayer(wearable.slots().getFirst().getLayer()).build();
        } else {
            placement = Placement.newBuilder().setMount(ContainerItems.definition(stack).mounts().getFirst()).build();
        }
        int slot = player.getInventory().getSelectedSlot();
        player.getInventory().setItem(slot, stack);
        return request(player, "", Volumes.WORN)
                .setPlayerSlot(slot)
                .setStackFingerprint(StackCodec.fingerprint(stack, player.registryAccess()))
                .setWornTarget(placement)
                .build();
    }

    private static MoveRequest.Builder request(ServerPlayer player, String item, String target) {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(new PlayerAccount(player).data().getRevision())
                .setItem(item)
                .setTarget(target)
                .setAutoPlace(true /* autoPlace */);
    }
}
