package dev.kizuna.bs3dgear;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** 同时验证专用服务端登记和世界数据加载后的真实物品组件。 */
public final class GearRegistrationGameTest {
    private static final int GEAR_COUNT = 14;

    @GameTest
    public void everyWearableIsARealNonContainerItem(GameTestHelper context) {
        int count = 0;
        for (var type : WearableItems.types().values()) {
            if (!type.id().getNamespace().equals("bs3d_gear")) {
                continue;
            }
            var stack = WearableItems.create(type.id());
            context.assertTrue(WearableItems.definition(stack) == type, "物品类型丢失：" + type.id());
            context.assertTrue(ContainerItems.definition(stack) == null, "穿搭不能具有容器容量");
            count++;
        }
        context.assertTrue(count == GEAR_COUNT, "穿搭清单数量不符：" + count);
        context.succeed();
    }
}
