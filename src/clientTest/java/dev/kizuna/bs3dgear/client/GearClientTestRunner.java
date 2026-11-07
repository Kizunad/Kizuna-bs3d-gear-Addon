package dev.kizuna.bs3dgear.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornModel;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class GearClientTestRunner implements ClientModInitializer {
    private static final int READY_FRAMES = 5;
    private static final int VIEWS = 3;
    private static final int EXPECTED_TESTS = 10;
    private static final long TIMEOUT_MILLIS = 180_000;
    private GearPreview preview;
    private int index;
    private boolean capturing;
    private volatile boolean captured;
    private long started;
    private boolean finished;
    private CompletableFuture<Void> reload;
    private WearModels.Loaded beforeReload;
    private List<Scene> scenes;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("gear.test.equipment")) {
            new ModelEquipmentClientProbe().register();
            return;
        }
        PictureInPictureRendererRegistry.register(context -> new GearPreview.Renderer());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("穿搭客户端验收失败", failure);
            }
        });
    }

    private void tick(Minecraft client) throws IOException {
        if (finished) {
            return;
        }
        if (started == 0) {
            started = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - started > TIMEOUT_MILLIS) {
            throw new IllegalStateException("截图验收超时");
        }
        if (preview == null) {
            if (client.level == null || client.player == null || client.gui.overlay() != null) {
                return;
            }
            if (!reloadReady(client)) {
                return;
            }
            runTests(client);
            scenes = scenes();
            showNext(client);
        } else if (captured) {
            captured = false;
            capturing = false;
            index++;
            showNext(client);
        } else if (!capturing && preview.frames() >= READY_FRAMES) {
            capture(client);
        }
    }

    private boolean reloadReady(Minecraft client) {
        var type = GearClientTest.types().getFirst();
        if (reload == null) {
            beforeReload = WearModels.find(type);
            reload = client.reloadResourcePacks();
            return false;
        }
        if (!reload.isDone()) {
            return false;
        }
        reload.join();
        var current = WearModels.find(type);
        if (beforeReload == null || current == null || beforeReload.source() == current.source()) {
            throw new IllegalStateException("资源重载后没有切换到新的穿搭模型实例");
        }
        return true;
    }

    private void showNext(Minecraft client) throws IOException {
        if (index == scenes.size()) {
            finished = true;
            Files.writeString(client.gameDirectory.toPath().resolve("result.txt"),
                    "success=true\nclientTests=" + EXPECTED_TESTS + "\nscreenshots=" + index
                            + "\nviews=" + index * VIEWS + "\nresourceReloads=1\n");
            client.stop();
            return;
        }
        var scene = scenes.get(index);
        preview = new GearPreview(scene.type(), scene.mode(), scene.companions());
        client.gui.setScreen(preview);
    }

    private void capture(Minecraft client) {
        capturing = true;
        String name = scenes.get(index).name() + ".png";
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(client.gameDirectory.toPath().resolve(name));
            } catch (IOException failure) {
                throw new IllegalStateException("保存穿搭截图失败", failure);
            }
            captured = true;
        });
    }

    private static void runTests(Minecraft client) throws IOException {
        // 真客户端中显式执行长期测试，失败或空测试集均不能继续产出成功标记。
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(GearClientTest.class),
                        DiscoverySelectors.selectClass(GearRuntimeClientTest.class),
                        DiscoverySelectors.selectClass(ContainerPreviewClientTest.class),
                        DiscoverySelectors.selectClass(CapeLayeringClientTest.class)).build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        try (var writer = new PrintWriter(Files.newBufferedWriter(
                client.gameDirectory.toPath().resolve("junit.txt")))) {
            summary.printTo(writer);
            summary.printFailuresTo(writer);
        }
        if (summary.getTestsSucceededCount() != EXPECTED_TESTS || summary.getTotalFailureCount() != 0) {
            throw new IllegalStateException("客户端 JUnit 未通过，查看 junit.txt");
        }
    }

    private static List<Scene> scenes() {
        var scenes = new ArrayList<Scene>();
        var backpack = ContainerPreviewClientTest.worn("bs3d:gu_jia_bei_lou", Mount.TORSO_BACK);
        for (var type : GearClientTest.types()) {
            String name = type.id().getPath();
            for (var mode : GearPreview.PoseMode.values()) {
                scenes.add(new Scene(type, mode, List.of(), name + "-" + mode));
                if (name.equals("war_cloak") || name.equals("war_suit") || name.equals("cu_ma_dou_peng")) {
                    scenes.add(new Scene(type, mode, List.of(backpack), name + "-backpack-" + mode));
                }
            }
        }
        return List.copyOf(scenes);
    }

    private record Scene(WearableItems.Definition type, GearPreview.PoseMode mode,
            List<WornModel> companions, String name) {}
}
