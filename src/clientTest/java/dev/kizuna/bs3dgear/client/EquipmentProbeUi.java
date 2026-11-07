package dev.kizuna.bs3dgear.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.backpack3d.client.InteriorRenderer.Rect;
import dev.kizuna.backpack3d.client.VolumeModule;
import dev.kizuna.backpack3d.client.VolumeScreen;
import dev.kizuna.backpack3d.client.WornPreview;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.PlayerInventoryRows;
import io.wispforest.owo.ui.base.BaseUIComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** 测试只反查已绘制的命中区，输入始终经过实际宿主页面。 */
final class EquipmentProbeUi {
    private static final int RIGHT_BUTTON = 1;
    private static final int SCAN_STEP_PIXELS = 2;
    private static final int ROTATION_DRAG_PIXELS = 12;
    private final Minecraft client = Minecraft.getInstance();
    private final VolumeScreen scene;
    private final BaseUIComponent component;
    private final StationHost station;
    private static final int SLOT_CENTER_PIXELS = 8;
    private static final int POCKET_VISIBLE_SLOTS = 18;

    EquipmentProbeUi(VolumeScreen scene) {
        this.scene = scene;
        component = null;
        station = null;
    }

    EquipmentProbeUi(VolumeModule module) {
        component = read(module, "embedded", BaseUIComponent.class);
        scene = read(component, "scene", VolumeScreen.class);
        station = null;
    }

    EquipmentProbeUi(AbstractContainerScreen<?> screen) {
        scene = null;
        component = null;
        station = (StationHost) screen;
    }

    WornPreview preview() {
        if (station != null) {
            return read(station.stationPanel(), "player", WornPreview.class);
        }
        return read(scene, "player", WornPreview.class);
    }

    void requirePocketLayout() {
        boolean pocket = FabricLoader.getInstance().isModLoaded("bs3d_pocket");
        assertTrue(pocket == Boolean.getBoolean("gear.test.pocket"), "Pocket 测试装配与运行时不符");
        if (!pocket) {
            return;
        }
        assertTrue(PlayerInventoryRows.visibleRows() == 1, "Pocket 没有限制为一行口袋");
        var screen = (AbstractContainerScreen<?>) client.gui.screen();
        long visible = screen.getMenu().slots.stream().filter(slot -> {
            var real = station.stationMenuSlot(slot);
            return real != null && real.container == client.player.getInventory()
                    && real.getContainerSlot() < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
                    && station.stationSlotVisible(slot) && PlayerInventoryRows.visible(real.getContainerSlot());
        }).count();
        assertTrue(visible == POCKET_VISIBLE_SLOTS, "Pocket 可见库存槽数量错误：" + visible);
        if (screen instanceof InventoryScreen) {
            assertTrue(station.stationTop() > screen.height / 2, "E 页没有使用 Pocket 底部紧凑菜单");
        }
    }

    void dragCreativeItem(Identifier type, int targetSlot) {
        Point start = creativeItem(type);
        Point end = point(targetSlot);
        var host = client.gui.screen();
        host.mouseClicked(mouse(start, 0), false /* doubleClick */);
        var menu = ((AbstractContainerScreen<?>) host).getMenu();
        assertTrue(menu.getCarried().is(BuiltInRegistries.ITEM.getValue(type)), "类别页没有拿起目标物品");
        host.mouseDragged(mouse(end, 0), end.x() - start.x(), end.y() - start.y());
        host.mouseReleased(mouse(end, 0));
    }

    void discardCreativeCursor(Identifier type) {
        click(creativeItem(type), RIGHT_BUTTON);
        var screen = (AbstractContainerScreen<?>) client.gui.screen();
        assertTrue(screen.getMenu().getCarried().isEmpty(), "原版类别页右键没有放回光标物品");
    }

    private Point creativeItem(Identifier type) {
        var screen = (AbstractContainerScreen<?>) client.gui.screen();
        for (var slot : screen.getMenu().slots) {
            if (station.stationMenuSlot(slot) == null && slot.getItem().is(BuiltInRegistries.ITEM.getValue(type))) {
                return new Point(station.stationLeft() + slot.x + SLOT_CENTER_PIXELS,
                        station.stationTop() + slot.y + SLOT_CENTER_PIXELS);
            }
        }
        throw new IllegalStateException("类别页没有目标物品：" + type);
    }

    void dragHandTo(int targetSlot) {
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        Point start;
        if (station == null) {
            start = point(selectedSlot);
        } else {
            start = inventoryPoint(selectedSlot);
        }
        Point end = point(targetSlot);
        Screen host = client.gui.screen();
        host.mouseClicked(mouse(start, 0), false /* doubleClick */);
        host.mouseDragged(mouse(end, 0), end.x() - start.x(), end.y() - start.y());
        host.mouseReleased(mouse(end, 0));
    }

    private Point inventoryPoint(int inventorySlot) {
        var screen = (AbstractContainerScreen<?>) client.gui.screen();
        for (var page : screen.getMenu().slots) {
            var slot = station.stationMenuSlot(page);
            if (slot != null && slot.container == client.player.getInventory()
                    && slot.getContainerSlot() == inventorySlot && station.stationSlotVisible(page)) {
                return new Point(station.stationLeft() + page.x + SLOT_CENTER_PIXELS,
                        station.stationTop() + page.y + SLOT_CENTER_PIXELS);
            }
        }
        throw new IllegalStateException("页面没有来源库存槽：" + inventorySlot);
    }

    void openMenu(int slot) {
        click(point(slot), RIGHT_BUTTON);
        assertTrue(preview().equipmentMenuVisible(), "右键模型没有打开装备列表");
    }

    void rotatePlayer(int slot) {
        var preview = preview();
        float before = read(preview, "yaw", Float.class);
        Point start = point(slot);
        Point end = new Point(start.x() + ROTATION_DRAG_PIXELS, start.y());
        var host = client.gui.screen();
        host.mouseClicked(mouse(start, RIGHT_BUTTON), false /* doubleClick */);
        host.mouseDragged(mouse(end, RIGHT_BUTTON), ROTATION_DRAG_PIXELS, 0);
        assertTrue(read(preview, "yaw", Float.class) != before, "右拖没有旋转模型");
        host.mouseDragged(mouse(start, RIGHT_BUTTON), -ROTATION_DRAG_PIXELS, 0);
        host.mouseReleased(mouse(start, RIGHT_BUTTON));
        assertTrue(!preview.equipmentMenuVisible(), "右拖被误判为打开菜单");
    }

    void openHeldContainer() {
        Point hand = point(client.player.getInventory().getSelectedSlot());
        var host = client.gui.screen();
        host.mouseClicked(mouse(hand, 0), true /* doubleClick */);
        host.mouseReleased(mouse(hand, 0));
    }

    boolean containerOpen(String id) {
        return scene.openContainers().contains(id);
    }

    boolean dragFromContainer(String container, String item, int targetSlot) {
        var view = scene.viewport(container);
        if (view == null) {
            return false;
        }
        for (int y = view.y(); y < view.bottom(); y += SCAN_STEP_PIXELS) {
            for (int x = view.x(); x < view.right(); x += SCAN_STEP_PIXELS) {
                if (scene.itemAt(container, x, y).equals(item)) {
                    Point start = absolute(x, y);
                    Point end = point(targetSlot);
                    var host = client.gui.screen();
                    host.mouseClicked(mouse(start, 0), false /* doubleClick */);
                    host.mouseDragged(mouse(end, 0), end.x() - start.x(), end.y() - start.y());
                    host.mouseReleased(mouse(end, 0));
                    return true;
                }
            }
        }
        return false;
    }

    void closeContainer(String id) {
        scene.closeContainer(id);
    }

    void removeFirst() {
        Object menu = read(preview(), "equipment", Object.class);
        Rect panel = read(menu, "panel", Rect.class);
        int rowPixels = read(menu, "ROW_PIXELS", Integer.class);
        var point = absolute(panel.x() + 1, panel.y() + rowPixels + 1);
        click(point, 0);
        click(point, 0);
    }

    void scrollMenu(double amount) {
        var host = client.gui.screen();
        host.mouseScrolled(offsetX() + 1, offsetY() + 1, 0, amount);
    }

    private Point point(int slot) {
        var preview = preview();
        float[] bounds = preview.slotBounds(slot);
        assertTrue(bounds != null, "玩家部位未绘制：" + slot);
        for (int y = (int) bounds[1]; y <= bounds[3]; y += SCAN_STEP_PIXELS) {
            for (int x = (int) bounds[0]; x <= bounds[2]; x += SCAN_STEP_PIXELS) {
                if (preview.slotAt(x, y) == slot) {
                    return absolute(x, y);
                }
            }
        }
        throw new IllegalStateException("未找到可见玩家部位：" + slot);
    }

    private Point absolute(int x, int y) {
        return new Point(x + offsetX(), y + offsetY());
    }

    private int offsetX() {
        if (component == null) {
            return 0;
        }
        return component.x();
    }

    private int offsetY() {
        if (component == null) {
            return 0;
        }
        return component.y();
    }

    private void click(Point point, int button) {
        var host = client.gui.screen();
        var event = mouse(point, button);
        host.mouseClicked(event, false /* doubleClick */);
        host.mouseReleased(event);
    }

    private static MouseButtonEvent mouse(Point point, int button) {
        return new MouseButtonEvent(point.x(), point.y(), new MouseButtonInfo(button, 0));
    }

    static <T> T read(Object instance, String name, Class<T> type) {
        try {
            var field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true /* flag */);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Point(int x, int y) {}
}
