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
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 模型菜单使用的自动卸装事务：整件、归属、容量与重放。 */
public final class GearEquipmentGameTest {
    private static final TransferService TRANSFERS = new TransferService(stack -> Volumes.UNIT);

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
        var wearable = WearableItems.definition(stack);
        Placement placement;
        String id;
        if (wearable != null) {
            placement = Placement.newBuilder().setWearableLayer(wearable.slots().getFirst().getLayer()).build();
            id = WearableItems.identity(stack);
        } else {
            var container = ContainerItems.definition(stack);
            placement = Placement.newBuilder().setMount(container.mounts().getFirst()).build();
            id = ContainerItems.identity(stack);
        }
        int slot = player.getInventory().getSelectedSlot();
        player.getInventory().setItem(slot, stack);
        var request = request(player, "", Volumes.WORN)
                .setPlayerSlot(slot)
                .setStackFingerprint(StackCodec.fingerprint(stack, player.registryAccess()))
                .setWornTarget(placement)
                .build();
        TRANSFERS.commit(new PlayerAccount(player), request);
        return id;
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
