package dev.kizuna.bs3dgear.server;

import dev.kizuna.backpack3d.server.WearableEquipment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 穿搭的服务端属性；库存事务、互斥与持久化仍由 BS3D 管理。 */
public final class GearServer implements ModInitializer {
    @Override
    public void onInitialize() {
        // 清单把正式穿搭加入独立创造栏，图标延迟读取已注册的战盔。
        var tab = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.bs3d_gear.equipment"))
                .icon(() -> new ItemStack(BuiltInRegistries.ITEM.getValue(
                        Identifier.fromNamespaceAndPath("bs3d_gear", "war_helm"))))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                Identifier.fromNamespaceAndPath("bs3d_gear", "equipment"), tab);
        WearableEquipment.CHANGED.register(WarArmor::refresh);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WarArmor.refresh(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((previous, player, alive) -> WarArmor.refresh(player));
    }
}
