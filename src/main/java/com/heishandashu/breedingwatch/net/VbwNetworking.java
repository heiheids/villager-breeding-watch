package com.heishandashu.breedingwatch.net;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.server.WatchServer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * 网络接线。
 *
 * <p>公共侧只注册"信道本身的类型"与服务端接收器；客户端的接收器在
 * {@code VbwClient} 里注册（专用服务端不会加载任何客户端类）。</p>
 *
 * <p>{@link #ON_SYNC} / {@link #ON_UI} 是客户端注入的接收器：包到达时若为 null，
 * 说明是专用服务端或客户端尚未就绪，只记一条日志，绝不抛出。</p>
 */
public final class VbwNetworking {

    public static volatile Consumer<String> ON_SYNC;
    public static volatile Consumer<String> ON_UI;

    private VbwNetworking() {}

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(Payloads.Sync.TYPE, Payloads.Sync.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.Ui.TYPE, Payloads.Ui.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Cmd.TYPE, Payloads.Cmd.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(Payloads.Cmd.TYPE, (payload, ctx) -> {
            String json = payload.json();
            ctx.server().execute(() -> {
                try {
                    WatchServer.onCommand(ctx.player(), json);
                } catch (Throwable t) {
                    VbwMod.LOG.warn("[VBW] 处理客户端指令失败：{}", t.toString());
                }
            });
        });
    }

    public static void handleSync(String json) {
        Consumer<String> h = ON_SYNC;
        if (h == null) {
            VbwMod.LOG.warn("[VBW] 收到 {} 字节快照但没有接收器，已忽略", json == null ? 0 : json.length());
            return;
        }
        h.accept(json);
    }

    public static void handleUi(String json) {
        Consumer<String> h = ON_UI;
        if (h == null) {
            VbwMod.LOG.warn("[VBW] 收到界面指令但没有接收器，已忽略");
            return;
        }
        h.accept(json);
    }
}
