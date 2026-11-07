package dev.kizuna.bs3dgear;

import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Placement;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.backpack3d.server.PlayerStorage;
import dev.kizuna.backpack3d.service.TransferService;
import dev.kizuna.bs3dgear.server.WarArmor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** 真实服务端入口验证防御、甲层互斥、拒绝原子性与生命周期。 */
public final class GearArmorGameTest {
    private static final int IRON_HEAD = 2;
    private static final int IRON_BODY = 6;
    private static final int IRON_LEGS_AND_FEET = 7;
    private static final int IRON_SUIT = 15;
    private static final int OTHER_ARMOR = 4;
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int EQUIPMENT_COMMAND_OFFSET = 100;
    private static final int EXPECTED_TRANSFER_STACKS = 2;
    private static final Identifier OTHER_ID = Identifier.parse("gear_test:other_armor");
    private static final Map<String, Integer> EXPECTED = Map.of(
            "war_helm", IRON_HEAD, "war_chest", IRON_BODY,
            "war_legs", IRON_LEGS_AND_FEET, "war_suit", IRON_SUIT);
    private static final TransferService TRANSFERS = new TransferService(stack -> Volumes.UNIT);

    @GameTest
    public void everyPieceUsesIronCoverageAndRepeatedChangesAreIdempotent(GameTestHelper context) {
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            player.getAttribute(Attributes.ARMOR).addTransientModifier(new AttributeModifier(
                    OTHER_ID, OTHER_ARMOR, AttributeModifier.Operation.ADD_VALUE));
            for (var type : WearableItems.types().values()) {
                if (!type.id().getNamespace().equals("bs3d_gear")) {
                    continue;
                }
                var request = wear(player, type.id());
                commit(player, request);
                WarArmor.refresh(player);
                WarArmor.refresh(player);
                int expected = EXPECTED.getOrDefault(type.id().getPath(), 0);
                armor(context, player, expected + OTHER_ARMOR);
                takeOff(player);
                armor(context, player, OTHER_ARMOR);
                player.getInventory().setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            }
        }
        context.succeed();
    }

    @GameTest
    public void eachVanillaRegionRejectsWholeSuitAndPreservesInventory(GameTestHelper context) {
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            for (var slot : PlayerSlots.ARMOR) {
                var vanilla = vanillaArmor(slot);
                player.setItemSlot(slot, vanilla);
                player.setItemInHand(InteractionHand.MAIN_HAND, WearableItems.create(id("war_suit")));
                var before = new PlayerAccount(player);
                var saved = before.data();
                var stacks = before.inventory();
                rejected(context, () -> commit(player, wearRequest(player, "armor_head")));
                context.assertTrue(saved.equals(before.data()), "拒绝穿套装改变了存档或收据");
                sameInventory(context, player, stacks);
                player.setItemSlot(slot, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        context.succeed();
    }

    @GameTest
    public void wornSuitRejectsRightClickMenuDispenserCommandAndCreativePacket(GameTestHelper context) {
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            wear(player, id("war_suit"));
            for (var slot : PlayerSlots.ARMOR) {
                rejectVanillaEntrances(context, player, slot);
            }
            armor(context, player, IRON_SUIT);
        }
        context.succeed();
    }

    @GameTest
    public void swappingPiecesForSuitDoesNotRetainOldModifiers(GameTestHelper context) {
        try (var fixture = new GearTestPlayer(context)) {
            var player = fixture.player;
            wear(player, id("war_helm"));
            wear(player, id("war_legs"));
            armor(context, player, IRON_HEAD + IRON_LEGS_AND_FEET);
            takeOff(player);
            armor(context, player, IRON_LEGS_AND_FEET);
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            takeOff(player);
            armor(context, player, 0);
            wear(player, id("war_suit"));
            armor(context, player, IRON_SUIT);
        }
        context.succeed();
    }

    @GameTest
    public void saveReconnectAndRespawnRebuildOnlyCurrentArmor(GameTestHelper context) {
        var original = new GearTestPlayer(context);
        var profile = original.player.getGameProfile();
        try (original) {
            original.login();
            wear(original.player, id("war_suit"));
            armor(context, original.player, IRON_SUIT);
        }
        try (var reconnect = new GearTestPlayer(context, profile)) {
            reconnect.login();
            armor(context, reconnect.player, IRON_SUIT);
            reconnect.player.setHealth(0);
            reconnect.player.die(reconnect.player.damageSources().genericKill());
            var players = reconnect.player.level().getServer().getPlayerList();
            var respawned = players.respawn(reconnect.player, false /* alive */, Entity.RemovalReason.KILLED);
            armor(context, respawned, IRON_SUIT);
            takeOff(respawned);
            armor(context, respawned, 0);
        }
        context.succeed();
    }

    @GameTest
    public void illegalLegacySaveRejectsWithoutChangingSavedItems(GameTestHelper context) {
        try (var source = new GearTestPlayer(context); var target = new GearTestPlayer(context)) {
            wear(source.player, id("war_suit"));
            var worn = ((PlayerStorage) source.player).bs3d$getInventory();
            ((PlayerStorage) source.player).bs3d$setInventory(TransferService.empty());
            source.player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.player.registryAccess());
            source.player.saveWithoutId(output);
            var tag = output.buildResult();
            tag.putByteArray(PlayerStorage.NBT_KEY, worn.toByteArray());
            var before = tag.copy();
            try {
                target.player.load(TagValueInput.create(ProblemReporter.DISCARDING,
                        target.player.registryAccess(), tag));
                throw context.assertionException("非法叠穿存档未被拒绝");
            } catch (net.minecraft.ReportedException expected) {
                context.assertTrue(hasArmorConflict(expected), "加载失败并非同区装备冲突：" + expected);
                context.assertTrue(tag.equals(before), "拒绝恢复改写了原始物品 NBT");
            }
        }
        context.succeed();
    }

    private static boolean hasArmorConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof IllegalArgumentException && message != null
                    && message.contains("甲层与原版装备占用同一身体部位")) {
                return true;
            }
        }
        return false;
    }

    private static void rejectVanillaEntrances(GameTestHelper context, ServerPlayer player, EquipmentSlot slot) {
        var stack = vanillaArmor(slot);
        var original = stack.copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var saved = ((PlayerStorage) player).bs3d$getInventory();
        var before = new PlayerAccount(player).inventory();
        stack.get(DataComponents.EQUIPPABLE).swapWithEquipmentSlot(stack, player);
        context.assertTrue(ItemStack.matches(original, player.getMainHandItem()), "右键吞掉来源盔甲");
        context.assertTrue(!player.canEquipWithDispenser(stack), "发射器可穿入冲突甲层");
        int menu = armorMenuSlot(player, slot);
        context.assertTrue(!player.inventoryMenu.getSlot(menu).mayPlace(stack), "原版甲槽仍接受放入");
        player.inventoryMenu.setCarried(stack.copy());
        player.inventoryMenu.clicked(menu, 0, ContainerInput.PICKUP, player);
        context.assertTrue(ItemStack.matches(original, player.inventoryMenu.getCarried()), "甲槽点击吞掉光标栈");
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.clicked(menu, 0, ContainerInput.SWAP, player);
        context.assertTrue(ItemStack.matches(original, player.getMainHandItem()), "快捷栏交换吞掉来源栈");
        var access = player.getSlot(EQUIPMENT_COMMAND_OFFSET + slot.getIndex());
        context.assertTrue(!access.set(stack.copy()), "原版装备命令绕过互斥");
        rejected(context, () -> player.setItemSlot(slot, stack.copy()));
        rejected(context, () -> player.getInventory().setItem(PlayerSlots.armorSlot(slot), stack.copy()));
        player.setGameMode(GameType.CREATIVE);
        player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(menu, stack.copy()));
        sameInventory(context, player, before);
        context.assertTrue(saved.equals(((PlayerStorage) player).bs3d$getInventory()), "拒绝路径改变穿搭库存");
        checkQuickMove(context, player, slot, stack);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static void checkQuickMove(GameTestHelper context, ServerPlayer player,
                                       EquipmentSlot slot, ItemStack stack) {
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().setItem(FIRST_MAIN_SLOT, stack.copy());
        int menu = player.inventoryMenu.slots.stream()
                .filter(candidate -> candidate.container == player.getInventory()
                        && candidate.getContainerSlot() == FIRST_MAIN_SLOT)
                .findFirst().orElseThrow().index;
        player.inventoryMenu.clicked(menu, 0, ContainerInput.QUICK_MOVE, player);
        context.assertTrue(player.getItemBySlot(slot).isEmpty(), "快捷转移留下非法叠穿");
        long count = new PlayerAccount(player).inventory().stream()
                .filter(value -> value.is(stack.getItem())).mapToLong(ItemStack::getCount).sum();
        context.assertTrue(count == EXPECTED_TRANSFER_STACKS, "快捷转移丢失或复制物品");
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            player.getInventory().setItem(index, ItemStack.EMPTY);
        }
    }

    private static int armorMenuSlot(ServerPlayer player, EquipmentSlot equipment) {
        int inventorySlot = PlayerSlots.armorSlot(equipment);
        return player.inventoryMenu.slots.stream()
                .filter(slot -> slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot)
                .findFirst().orElseThrow().index;
    }

    private static ItemStack vanillaArmor(EquipmentSlot slot) {
        return new ItemStack(switch (slot) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            case FEET -> Items.IRON_BOOTS;
            default -> throw new IllegalArgumentException("不是人体甲槽");
        });
    }

    static MoveRequest wear(ServerPlayer player, Identifier typeId) {
        var stack = WearableItems.create(typeId);
        var type = WearableItems.definition(stack);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var request = wearRequest(player, type.slots().getFirst().getLayer());
        commit(player, request);
        return request;
    }

    private static MoveRequest wearRequest(ServerPlayer player, String layer) {
        var placement = Placement.newBuilder().setWearableLayer(layer).build();
        return request(player, Volumes.WORN)
                .setPlayerSlot(player.getInventory().getSelectedSlot())
                .setStackFingerprint(StackCodec.fingerprint(player.getMainHandItem(), player.registryAccess()))
                .setWornTarget(placement)
                .build();
    }

    static void takeOff(ServerPlayer player) {
        var worn = ((PlayerStorage) player).bs3d$getInventory().getWornWearables(0);
        var request = request(player, Volumes.PLAYER)
                .setItem(worn.getId())
                .setTargetPlayerSlot(Inventory.SLOT_OFFHAND)
                .build();
        commit(player, request);
    }

    private static MoveRequest.Builder request(ServerPlayer player, String target) {
        return MoveRequest.newBuilder()
                .setOperation(UUID.randomUUID().toString())
                .setRevision(((PlayerStorage) player).bs3d$getInventory().getRevision())
                .setTarget(target);
    }

    private static void commit(ServerPlayer player, MoveRequest request) {
        try {
            TRANSFERS.commit(new PlayerAccount(player), request);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void armor(GameTestHelper context, ServerPlayer player, int expected) {
        double actual = player.getAttributeValue(Attributes.ARMOR);
        context.assertTrue(actual == expected, "防御不符：" + actual + "，预期 " + expected);
        context.assertTrue(player.getAttributeValue(Attributes.ARMOR_TOUGHNESS) == 0, "铁甲韧性应为零");
    }

    private static void rejected(GameTestHelper context, Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw context.assertionException("应拒绝冲突装备");
    }

    private static void sameInventory(GameTestHelper context, ServerPlayer player, java.util.List<ItemStack> expected) {
        var actual = new PlayerAccount(player).inventory();
        for (int index = 0; index < expected.size(); index++) {
            context.assertTrue(ItemStack.matches(expected.get(index), actual.get(index)), "库存变化：" + index);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("bs3d_gear", path);
    }
}
