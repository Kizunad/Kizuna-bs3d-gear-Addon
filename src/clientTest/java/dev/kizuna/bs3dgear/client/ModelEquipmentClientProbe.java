package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.client.ModelEquipment;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.PlayerSlots;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.VanillaClient;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTabs;

/** 实际鼠标路由、网络事务和 GPU 截图共同验证模型穿脱。 */
final class ModelEquipmentClientProbe {
    private static final int READY_TICKS = 4;
    private static final int GEAR_COUNT = 14;
    private static final int SCROLL_TO_END_ROWS = 100;
    private static final long TIMEOUT_MILLIS = 600_000;
    private static final int HEAD_SLOT = PlayerSlots.armorSlot(EquipmentSlot.HEAD);
    private static final int FEET_SLOT = PlayerSlots.armorSlot(EquipmentSlot.FEET);
    private final Queue<Check> checks = new ArrayDeque<>();
    private final List<String> passed = new ArrayList<>();
    private VolumeModule module;
    private EquipmentProbeUi ui;
    private long started;
    private long revision;
    private int ticks;
    private int captures;
    private volatile boolean captured;
    private boolean finished;
    private String packedContainer;
    private String packedItem;

    void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (finished || client.player == null || client.level == null || client.gui.overlay() != null) {
            return;
        }
        if (started == 0) {
            started = System.currentTimeMillis();
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(VolumeModule.class::isInstance).map(VolumeModule.class::cast).findFirst().orElseThrow();
            plan();
        }
        assertTrue(System.currentTimeMillis() - started < TIMEOUT_MILLIS, "客户端步骤超时：" + checks.peek());
        if (++ticks < READY_TICKS) {
            return;
        }
        ticks = 0;
        if (checks.isEmpty()) {
            finish(client);
        } else if (checks.peek().ready().getAsBoolean()) {
            String name = checks.remove().name();
            passed.add(name);
            System.out.println("GEAR_EQUIPMENT_OK " + name);
        }
    }

    private void plan() {
        waitFor("世界完成加载", () -> Minecraft.getInstance().gui.screen() == null);
        waitFor("协议就绪", module::ready);
        action("准备真实玩家", EquipmentProbeInventory::initialize);
        waitFor("创造模式同步", () -> Minecraft.getInstance().player.isCreative());
        action("独立创造栏准确列出 14 件", this::creativeTab);
        waitFor("创造页实际选中 Gear", this::creativeSelected);
        capture("creative-tab");
        action("独立背包页", () -> {
            var scene = new VolumeScreen(module);
            Minecraft.getInstance().gui.setScreen(scene);
            ui = new EquipmentProbeUi(scene);
        });
        action("右拖保持旋转且不打开菜单", () -> ui.rotatePlayer(HEAD_SLOT));
        var types = new ArrayList<Identifier>();
        WearableItems.types().values().forEach(type -> types.add(type.id()));
        ContainerItems.types().values().stream().filter(type -> !type.mounts().isEmpty())
                .forEach(type -> types.add(type.id()));
        types.sort(Comparator.comparing(Identifier::toString));
        int index = 0;
        for (var type : types) {
            int slot = HEAD_SLOT;
            if (index++ % 2 == 0) {
                slot = FEET_SLOT;
            }
            equipment(type, slot, "standalone");
        }
        packedEquipment();
        wardrobe("standalone");
        workspace();
        vanilla();
        creativeEquipment();
    }

    private void equipment(Identifier type, int targetSlot, String host) {
        action("手持 " + type, () -> EquipmentProbeInventory.hold(type));
        waitFor("手持快照 " + type, () -> module.stack(hand()).is(BuiltInRegistries.ITEM.getValue(type)));
        action("拖入模型 " + type, () -> {
            revision = module.state().getRevision();
            ui.dragHandTo(targetSlot);
            assertTrue(busy(), "拖到模型没有提交装备请求：" + type);
        });
        waitFor("穿戴回执 " + type, () -> !busy());
        action("穿戴成功 " + type, () -> {
            var state = module.state();
            assertTrue(state.getRevision() == revision + 1, module.status());
            assertTrue(ModelEquipment.worn(state).size() == 1, "穿戴根数量错误");
            ui.openMenu(HEAD_SLOT);
        });
        if (List.of("war_suit", "ku_teng_yao_dai", "can_bu_tou_jin").contains(type.getPath())) {
            capture(host + "-" + type.getPath() + "-menu");
        }
        action("重复点击卸装 " + type, () -> {
            revision = module.state().getRevision();
            ui.removeFirst();
        });
        waitFor("卸装回执 " + type, () -> !busy());
        action("实物归属及列表刷新 " + type, () -> {
            var state = module.state();
            assertTrue(state.getRevision() == revision + 1, module.status());
            assertTrue(ModelEquipment.worn(state).isEmpty(), "卸装后仍在列表");
            EquipmentProbeInventory.requireReturned(type);
            ui.preview().closeEquipmentMenu();
        });
    }

    private void wardrobe(String host) {
        action("叠穿多个层与挂点", EquipmentProbeInventory::wardrobe);
        waitFor("完整目录同步", () -> ModelEquipment.worn(module.state()).size()
                == EquipmentProbeInventory.WARDROBE.size());
        action("打开完整卸装列表", () -> ui.openMenu(HEAD_SLOT));
        capture(host + "-equipment-list-top");
        action("滚动卸装列表", () -> ui.scrollMenu(-SCROLL_TO_END_ROWS));
        capture(host + "-equipment-list-bottom");
        action("填满原版背包", EquipmentProbeInventory::fill);
        waitFor("满背包同步", () -> module.stack(hand()).is(net.minecraft.world.item.Items.STONE));
        action("满背包尝试卸装", () -> {
            revision = module.state().getRevision();
            ui.removeFirst();
        });
        waitFor("满背包回执", () -> !busy());
        action("拒绝保留物品和列表", () -> {
            requireRejected();
            var state = module.state();
            assertTrue(state.getRevision() == revision, "失败改变修订号");
            assertTrue(ModelEquipment.worn(state).size() == EquipmentProbeInventory.WARDROBE.size(),
                    "失败改变列表");
        });
        capture(host + "-equipment-inventory-full");
        action("释放库存空间", EquipmentProbeInventory::clearInventory);
        for (int count = EquipmentProbeInventory.WARDROBE.size(); count > 0; count--) {
            int remaining = count - 1;
            action("从完整列表脱下一件 " + count, () -> ui.removeFirst());
            waitFor("列表剩余 " + remaining, () -> !busy()
                    && ModelEquipment.worn(module.state()).size() == remaining);
        }
        capture(host + "-equipment-list-empty");
    }

    private void workspace() {
        action("切换工作台嵌入入口", () -> {
            var runtime = InventoryUiClient.runtime();
            runtime.openWorkspace();
            runtime.open("bs3d:open", "");
        });
        action("读取嵌入场景", () -> ui = new EquipmentProbeUi(module));
        equipment(Identifier.parse("bs3d_gear:war_suit"), FEET_SLOT, "workspace");
        equipment(Identifier.parse("bs3d:herb_pouch"), HEAD_SLOT, "workspace");
        capture("workspace-equipment-empty");
    }

    private void packedEquipment() {
        action("准备内景中的装备", EquipmentProbeInventory::packedEquipment);
        waitFor("内景装备同步", () -> {
            var item = module.state().getItemsList().stream()
                    .filter(value -> module.stack(value.getId()).is(
                            BuiltInRegistries.ITEM.getValue(EquipmentProbeInventory.PACKED_TYPE)))
                    .findFirst();
            if (item.isEmpty()) {
                return false;
            }
            packedContainer = item.get().getContainer();
            packedItem = item.get().getId();
            return true;
        });
        action("双击手持容器打开内景", () -> ui.openHeldContainer());
        waitFor("容器开盖后显示内景", () -> ui.containerOpen(packedContainer));
        waitFor("从内景拖到人物", () -> ui.dragFromContainer(packedContainer, packedItem, HEAD_SLOT));
        waitFor("内景穿戴回执", () -> !busy());
        action("内景装备整件穿戴", () -> {
            assertTrue(ModelEquipment.worn(module.state()).stream().anyMatch(item -> item.id().equals(packedItem)),
                    "内景拖入未保留装备身份");
            ui.closeContainer(packedContainer);
        });
        action("右键打开内景装备卸装列表", () -> ui.openMenu(HEAD_SLOT));
        capture("standalone-packed-equipment");
        action("卸下来自内景的装备", () -> ui.removeFirst());
        waitFor("内景装备卸装回执", () -> !busy());
        action("内景装备回到唯一实物", () -> {
            assertTrue(ModelEquipment.worn(module.state()).isEmpty());
            EquipmentProbeInventory.requireReturned(EquipmentProbeInventory.PACKED_TYPE);
            ui.preview().closeEquipmentMenu();
        });
    }

    private void vanilla() {
        action("关闭工作台并切换生存", () -> {
            Minecraft.getInstance().gui.setScreen(null);
            EquipmentProbeInventory.survival();
        });
        waitFor("生存模式同步", () -> !Minecraft.getInstance().player.isCreative());
        action("打开原版 E 页", () -> Minecraft.getInstance().gui.setScreen(
                new InventoryScreen(Minecraft.getInstance().player)));
        station("inventory", InventoryScreen.class);
        wardrobe("inventory");
        action("关闭 E 页并打开服务器箱子", EquipmentProbeInventory::openChest);
        station("chest", ContainerScreen.class);
        action("关闭箱子并切换创造", EquipmentProbeInventory::creativeInventory);
        waitFor("创造模式同步", () -> Minecraft.getInstance().player.isCreative());
        action("打开创造库存页", this::creativeInventory);
        station("creative-inventory", CreativeModeInventoryScreen.class);
    }

    private void station(String name, Class<?> screenType) {
        waitFor(name + " 菜单协议与模型就绪", () -> {
            var screen = Minecraft.getInstance().gui.screen();
            if (!screenType.isInstance(screen) || !(screen instanceof AbstractContainerScreen<?> container)
                    || !(screen instanceof StationHost host) || host.stationPanel() == null) {
                return false;
            }
            if (!host.stationPanel().enabled() || busy()) {
                return false;
            }
            ui = new EquipmentProbeUi(container);
            ui.requirePocketLayout();
            return true;
        });
        action(name + " 右拖保持旋转", () -> ui.rotatePlayer(HEAD_SLOT));
        equipment(Identifier.parse("bs3d_gear:war_suit"), FEET_SLOT, name);
        equipment(Identifier.parse("bs3d:ku_teng_yao_dai"), HEAD_SLOT, name);
    }

    private boolean busy() {
        return module.busy() || VanillaClient.instance().busy();
    }

    private void creativeEquipment() {
        // 真实类别页取物后直接拖到模型，覆盖创建实物、卸装和满库存拒绝。
        action("打开 Gear 类别页", this::creativeTab);
        waitFor("Gear 类别页就绪", () -> creativeSelected()
                && VanillaClient.instance().ready((AbstractContainerScreen<?>) Minecraft.getInstance().gui.screen()));
        action("绑定类别页输入", () -> ui = new EquipmentProbeUi(
                (AbstractContainerScreen<?>) Minecraft.getInstance().gui.screen()));
        Identifier suit = Identifier.parse("bs3d_gear:war_suit");
        directCreativeEquipment(suit);
        action("填满创造库存", EquipmentProbeInventory::fill);
        waitFor("创造满库存同步", () -> module.stack(hand()).is(net.minecraft.world.item.Items.STONE));
        action("满库存直接拖入", () -> {
            revision = module.state().getRevision();
            ui.dragCreativeItem(suit, FEET_SLOT);
            assertTrue(!busy(), "满库存不应发送创造写槽包");
        });
        action("满库存保持光标和库存", () -> {
            var screen = (AbstractContainerScreen<?>) Minecraft.getInstance().gui.screen();
            assertTrue(screen.getMenu().getCarried().is(BuiltInRegistries.ITEM.getValue(suit)));
            assertTrue(module.state().getRevision() == revision);
            EquipmentProbeInventory.requireFullInventoryUnchanged();
        });
        capture("creative-category-full");
        action("放回创造光标", () -> ui.discardCreativeCursor(suit));
        action("打开 BS3D 类别页", () -> {
            var screen = (FabricCreativeModeInventoryScreen) Minecraft.getInstance().gui.screen();
            assertTrue(screen.setSelectedTab(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(ContainerItems.TAB)));
        });
        directCreativeEquipment(Identifier.parse("bs3d:ku_teng_yao_dai"));
    }

    private void directCreativeEquipment(Identifier type) {
        // 穿戴必须只产生一个服务端实物，失败回执不能被客户端光标隐藏掩盖。
        action("清空创造来源槽 " + type, EquipmentProbeInventory::clearInventory);
        waitFor("创造来源槽同步 " + type, () -> module.stack(hand()).isEmpty());
        action("类别页直接拖入 " + type, () -> {
            revision = module.state().getRevision();
            ui.dragCreativeItem(type, FEET_SLOT);
            assertTrue(busy(), "创造类别页没有提交穿戴请求");
        });
        waitFor("创造穿戴回执 " + type, () -> !busy());
        action("创造装备唯一归属 " + type, () -> {
            assertTrue(module.state().getRevision() == revision + 1, VanillaClient.instance().detail());
            EquipmentProbeInventory.requireWornOnly(type);
            ui.openMenu(HEAD_SLOT);
        });
        capture("creative-category-" + type.getPath());
        action("卸下创造装备 " + type, () -> ui.removeFirst());
        waitFor("创造卸装回执 " + type, () -> !busy());
        action("创造卸装唯一实物 " + type, () -> {
            assertTrue(ModelEquipment.worn(module.state()).isEmpty());
            EquipmentProbeInventory.requireReturned(type);
            ui.preview().closeEquipmentMenu();
        });
    }

    private void requireRejected() {
        if (Minecraft.getInstance().gui.screen() instanceof StationHost) {
            var status = VanillaClient.instance().state().getStatus();
            assertTrue(status == dev.kizuna.bs3d.vanilla.protocol.pb.VanillaProtocol.Status.REJECTED,
                    "原版菜单满背包应拒绝");
        } else {
            assertTrue(module.state().getTransfer().getPhase() == Phase.REJECTED, "满背包应拒绝");
        }
    }

    private void creativeInventory() {
        var client = Minecraft.getInstance();
        var screen = new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(),
                true /* displayOperatorCreativeTab */);
        client.gui.setScreen(screen);
        var pages = (FabricCreativeModeInventoryScreen) screen;
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(Identifier.parse("minecraft:inventory"));
        assertTrue(pages.setSelectedTab(tab));
    }

    private boolean creativeSelected() {
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            return false;
        }
        var pages = (FabricCreativeModeInventoryScreen) screen;
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(Identifier.parse("bs3d_gear:equipment"));
        return pages.getSelectedTab() == tab;
    }

    private void creativeTab() {
        var client = Minecraft.getInstance();
        CreativeModeTabs.tryRebuildTabContents(client.level.enabledFeatures(), true /* hasPermissions */,
                client.player.registryAccess());
        var id = Identifier.parse("bs3d_gear:equipment");
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id);
        assertTrue(tab != null && tab.getDisplayItems().size() == GEAR_COUNT, "创造栏条目数不符");
        assertTrue(tab.getDisplayItems().stream().allMatch(stack -> WearableItems.definition(stack) != null),
                "Gear 创造栏混入非装备");
        var bs3d = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(ContainerItems.TAB);
        assertTrue(bs3d.getDisplayItems().stream().noneMatch(stack ->
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("bs3d_gear")),
                "Gear 仍重复出现在 BS3D 栏");
        var screen = new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(),
                true /* displayOperatorCreativeTab */);
        client.gui.setScreen(screen);
        var pages = (FabricCreativeModeInventoryScreen) screen;
        assertTrue(pages.setSelectedTab(tab), "无法切换到 Gear 所在创造分页");
        assertTrue(pages.getSelectedTab() == tab, "创造页没有选中 Gear");
    }

    private String hand() {
        return VolumeModule.playerSlotPreviewId(Minecraft.getInstance().player.getInventory().getSelectedSlot());
    }

    void action(String name, Runnable action) {
        waitFor(name, () -> {
            action.run();
            return true;
        });
    }

    void waitFor(String name, BooleanSupplier ready) {
        checks.add(new Check(name, ready));
    }

    void capture(String name) {
        action("截图 " + name, () -> {
            captured = false;
            var client = Minecraft.getInstance();
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    image.writeToFile(client.gameDirectory.toPath().resolve(name + ".png"));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                captured = true;
            });
        });
        waitFor("截图落盘 " + name, () -> captured);
        action("截图计数 " + name, () -> captures++);
    }

    private void finish(Minecraft client) {
        finished = true;
        try {
            Files.write(client.gameDirectory.toPath().resolve("equipment-checks.txt"), passed);
            Files.writeString(client.gameDirectory.toPath().resolve("result.txt"),
                    "success=true\nchecks=" + passed.size() + "\nscreenshots=" + captures + "\n");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        client.stop();
    }

    private record Check(String name, BooleanSupplier ready) {}
}
