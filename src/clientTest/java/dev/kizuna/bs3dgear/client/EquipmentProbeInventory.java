package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.client.ModelEquipment;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3d.vanilla.client.PlayerInventoryRows;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** 只在集成服务器线程准备真实物品；测试中的穿脱由客户端网络请求驱动。 */
final class EquipmentProbeInventory {
    static final List<String> WARDROBE = List.of(
            "bs3d:gu_jia_bei_lou", "bs3d:pi_nang_xiong_gua", "bs3d:ku_teng_yao_dai",
            "bs3d:herb_pouch", "bs3d_gear:can_bu_tou_jin", "bs3d_gear:xiu_pian_hu_mu",
            "bs3d_gear:cu_bu_mian_jin", "bs3d_gear:shou_ya_xiang_lian", "bs3d_gear:pi_sheng_hu_wan",
            "bs3d_gear:cu_bu_bang_tui", "bs3d_gear:ku_cao_guo_jiao", "bs3d_gear:cu_ma_dou_peng");
    private static final TransferService TRANSFERS = new TransferService(stack -> Volumes.UNIT);
    private static final int CHEST_SLOTS = 27;
    private static final int PACKED_SOURCE_SLOT = 1;
    static final Identifier PACKED_TYPE = Identifier.parse("bs3d_gear:can_bu_tou_jin");

    private EquipmentProbeInventory() {}

    static void initialize() {
        server(player -> {
            player.setGameMode(GameType.CREATIVE);
            player.setInvulnerable(true /* invulnerable */);
            ((PlayerStorage) player).bs3d$setInventory(TransferService.empty());
            player.getInventory().clearContent();
        });
    }

    static void hold(Identifier id) {
        server(player -> {
            player.getInventory().clearContent();
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), stack(id));
            player.inventoryMenu.broadcastChanges();
        });
    }

    static void wardrobe() {
        server(player -> {
            player.getInventory().clearContent();
            for (String name : WARDROBE) {
                ItemStack stack = stack(Identifier.parse(name));
                int slot = player.getInventory().getSelectedSlot();
                player.getInventory().setItem(slot, stack);
                var request = MoveRequest.newBuilder()
                        .setRevision(new PlayerAccount(player).data().getRevision())
                        .setOperation(UUID.randomUUID().toString())
                        .setPlayerSlot(slot)
                        .setStackFingerprint(StackCodec.fingerprint(stack, player.registryAccess()))
                        .setTarget(Volumes.WORN)
                        .setWornTarget(ModelEquipment.placement(stack))
                        .build();
                try {
                    TRANSFERS.commit(new PlayerAccount(player), request);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        });
    }

    static void fill() {
        // Pocket 的隐藏槽不属于可用背包，不能写入后依赖迁移丢到地上来制造“满库存”。
        server(player -> {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (!PlayerInventoryRows.visible(slot)) {
                    continue;
                }
                var full = new ItemStack(Items.STONE);
                full.setCount(full.getMaxStackSize());
                player.getInventory().setItem(slot, full);
            }
        });
    }

    static void packedEquipment() {
        server(player -> {
            player.getInventory().clearContent();
            var bag = ContainerItems.create(Identifier.parse("bs3d:herb_pouch"));
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), bag);
            var gear = WearableItems.create(PACKED_TYPE);
            player.getInventory().setItem(PACKED_SOURCE_SLOT, gear);
            var request = MoveRequest.newBuilder()
                    .setRevision(new PlayerAccount(player).data().getRevision())
                    .setOperation(UUID.randomUUID().toString())
                    .setPlayerSlot(PACKED_SOURCE_SLOT)
                    .setStackFingerprint(StackCodec.fingerprint(gear, player.registryAccess()))
                    .setTarget(ContainerItems.identity(bag))
                    .setAutoPlace(true /* autoPlace */)
                    .build();
            try {
                TRANSFERS.commit(new PlayerAccount(player), request);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    static void clearInventory() {
        server(player -> player.getInventory().clearContent());
    }

    static void survival() {
        server(player -> player.setGameMode(GameType.SURVIVAL));
    }

    static void openChest() {
        // 原版开菜单网络包创建客户端箱子页，测试不直接伪造客户端菜单。
        server(player -> {
            player.closeContainer();
            var provider = new SimpleMenuProvider((id, inventory, owner) ->
                    ChestMenu.threeRows(id, inventory, new SimpleContainer(CHEST_SLOTS)),
                    Component.literal("装备交互验收"));
            player.openMenu(provider);
        });
    }

    static void creativeInventory() {
        server(player -> {
            player.closeContainer();
            player.setGameMode(GameType.CREATIVE);
        });
    }

    static void requireReturned(Identifier id) {
        server(player -> {
            long count = new PlayerAccount(player).inventory().stream()
                    .filter(stack -> stack.is(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id)))
                    .count();
            assertTrue(count == 1, "卸装回执未对应恰好一个真实栈：" + id);
        });
    }

    static void requireWornOnly(Identifier id) {
        server(player -> {
            var account = new PlayerAccount(player);
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
            assertTrue(account.inventory().stream().noneMatch(stack -> stack.is(item)),
                    "创造穿戴后暂存槽仍残留复制品");
            assertTrue(account.data().getWornCount() + account.data().getWornWearablesCount() == 1,
                    "创造穿戴没有唯一挂载根");
            assertTrue(player.containerMenu.getCarried().isEmpty(), "服务端光标残留复制品");
        });
    }

    static void requireFullInventoryUnchanged() {
        // 可见槽仍满、隐藏槽仍空，拒绝不能偷偷写入 Pocket 已封闭的槽位。
        server(player -> {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (PlayerInventoryRows.visible(slot)) {
                    assertTrue(stack.is(Items.STONE) && stack.getCount() == stack.getMaxStackSize(),
                            "创造光标覆盖了原库存槽：" + slot);
                } else {
                    assertTrue(stack.isEmpty(), "创造光标写入了隐藏槽：" + slot);
                }
            }
        });
    }

    private static ItemStack stack(Identifier id) {
        if (id.getNamespace().equals("bs3d_gear")) {
            return WearableItems.create(id);
        }
        return ContainerItems.create(id);
    }

    private static void server(Consumer<ServerPlayer> action) {
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        UUID id = client.player.getUUID();
        server.submit(() -> action.accept(server.getPlayerList().getPlayer(id))).join();
    }
}
