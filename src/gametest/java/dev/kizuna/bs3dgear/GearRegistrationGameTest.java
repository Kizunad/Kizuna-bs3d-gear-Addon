package dev.kizuna.bs3dgear;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ItemMetadata;
import dev.kizuna.backpack3d.item.WearableItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;

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

    @GameTest
    public void everyWearableHasADescriptionLine(GameTestHelper context) {
        // 物品提示里的描述由 BS3D 按登记的语言键显示；每件都要有键，语言文件里要有字。
        for (var type : WearableItems.types().values()) {
            if (!type.id().getNamespace().equals("bs3d_gear")) {
                continue;
            }
            String key = ItemMetadata.of(WearableItems.create(type.id())).descriptionTranslationKey();
            String expected = "item.bs3d_gear." + type.id().getPath() + ".description";
            context.assertTrue(key.equals(expected), type.id() + " 的描述键不对：" + key);
            context.assertTrue(Language.getInstance().has(key), "语言文件缺 " + key);
        }
        context.succeed();
    }
}
