package dev.kizuna.bs3dgear;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

/** 真实玩家、存档与网络处理器；仅将套接字换成内存连接。 */
final class GearTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    final ServerPlayer player;
    private final MemoryConnection connection = new MemoryConnection();

    GearTestPlayer(GameTestHelper context) {
        this(context, profile());
    }

    private static GameProfile profile() {
        UUID id = UUID.randomUUID();
        return new GameProfile(id, "gear_" + id.toString().substring(0, NAME_ID_LENGTH));
    }

    GearTestPlayer(GameTestHelper context, GameProfile profile) {
        var level = context.getLevel();
        player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
    }

    void login() {
        // 与原版配置阶段一样先读同 UUID 的 playerdata，再进入正式玩家列表并触发 JOIN。
        var server = player.level().getServer();
        server.getPlayerList().loadPlayerData(player.nameAndId()).ifPresent(tag ->
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag)));
        var cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false /* transferred */);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    @Override
    public void close() {
        connection.disconnect(Component.literal("穿搭验收结束"));
        connection.handleDisconnection();
        connection.channel.finishAndReleaseAll();
    }

    private static final class MemoryConnection extends Connection {
        private final EmbeddedChannel channel;

        private MemoryConnection() {
            super(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(this);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            // 不交给真实客户端，但保留发送完成回调，包括关闭连接的通知。
            if (listener != null) {
                try {
                    listener.operationComplete(channel.newSucceededFuture());
                } catch (Exception failure) {
                    throw new IllegalStateException("测试连接回调失败", failure);
                }
            }
        }
    }
}
