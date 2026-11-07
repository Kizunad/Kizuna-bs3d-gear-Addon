package dev.kizuna.bs3dgear.server;

import dev.kizuna.backpack3d.item.ArmorLayers;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.server.PlayerStorage;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;

/** 铁甲防御由真实穿戴栈的覆盖部位求并集，不按段或成套数量累加。 */
public final class WarArmor {
    private static final Set<String> ARMORED_ITEMS = Set.of("war_helm", "war_chest", "war_legs", "war_suit");
    private static final Map<EquipmentSlot, ArmorType> TYPES = Map.of(
            EquipmentSlot.HEAD, ArmorType.HELMET,
            EquipmentSlot.CHEST, ArmorType.CHESTPLATE,
            EquipmentSlot.LEGS, ArmorType.LEGGINGS,
            EquipmentSlot.FEET, ArmorType.BOOTS);
    private static final Identifier ARMOR_ID = Identifier.fromNamespaceAndPath("bs3d_gear", "war_armor");
    private static final Identifier TOUGHNESS_ID = Identifier.fromNamespaceAndPath("bs3d_gear", "war_toughness");

    private WarArmor() {}

    public static void refresh(ServerPlayer player) {
        // 修饰为非持久化值：穿脱、加载和换实体后都从实际库存重算，固定 ID 保证重复通知幂等。
        var covered = coveredSlots(player);
        var iron = ArmorMaterials.IRON;
        double armor = 0;
        double toughness = 0;
        for (EquipmentSlot slot : covered) {
            armor += iron.defense().get(TYPES.get(slot));
            toughness += iron.toughness();
        }
        update(player, Attributes.ARMOR, ARMOR_ID, armor);
        update(player, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID, toughness);
    }

    private static Set<EquipmentSlot> coveredSlots(ServerPlayer player) {
        // 类型和部位取自服务端登记；客户端展示绑定或物品自定义 NBT 不能授予防御。
        var covered = EnumSet.noneOf(EquipmentSlot.class);
        var inventory = ((PlayerStorage) player).bs3d$getInventory();
        for (var worn : inventory.getWornWearablesList()) {
            var stack = StackCodec.decode(worn.getStackNbt(), player.registryAccess());
            var type = WearableItems.definition(stack);
            if (type == null || !type.id().getNamespace().equals("bs3d_gear")
                    || !ARMORED_ITEMS.contains(type.id().getPath())) {
                continue;
            }
            for (var binding : type.slots()) {
                ArmorLayers.equipment(binding.getLayer()).ifPresent(covered::add);
            }
        }
        return covered;
    }

    private static void update(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
        // 只更新本附属的固定 ID，保留原版和其他附属的修饰；零值清除旧状态。
        var instance = player.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalStateException("玩家缺少防御属性：" + attribute);
        }
        var previous = instance.getModifier(id);
        if (amount == 0) {
            instance.removeModifier(id);
        } else if (previous == null || previous.amount() != amount) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(
                    id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
