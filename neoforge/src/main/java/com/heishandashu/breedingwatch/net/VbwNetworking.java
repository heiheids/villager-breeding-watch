package com.heishandashu.breedingwatch.net;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.server.WatchServer;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络接线（NeoForge 侧）。
 *
 * <p>与 Fabric 的三处差别：
 * <ol>
 *   <li>注册入口是 mod bus 上的 {@code RegisterPayloadHandlersEvent}，
 *       通过 {@code event.registrar("1")} 拿 registrar（Fabric 是静态的
 *       {@code PayloadTypeRegistry.clientboundPlay()}）</li>
 *   <li>收发端在注册时一次挂好，没有单独的 {@code registerGlobalReceiver}</li>
 *   <li>发送走 {@code PacketDistributor.sendToPlayer(...)}，
 *       且它**不存在 canSend**，对端没装模组时由框架自行处理</li>
 * </ol></p>
 */
public final class VbwNetworking {

    public static volatile Consumer<String> ON_SYNC;
    public static volatile Consumer<String> ON_UI;

    /** 客户端发送端（由 {@code VbwClient} 注入）。 */
    public static volatile ClientSender CLIENT_SENDER;

    /** 发送端实现：把 NeoForge 的写法收在这一处，服务端逻辑保持加载器无关。 */
    public static final PacketSender SENDER = (player, payload) -> {
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Throwable t) {
            // 对端没装模组 / 信道未就绪：让这一轮没有输出，不要在 tick 线程里抛出
            VbwMod.LOG.debug("[VBW] 发送 {} 失败：{}", payload.type().id(), t.toString());
        }
    };

    private VbwNetworking() {}

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(Payloads.Sync.TYPE, Payloads.Sync.CODEC,
                (payload, ctx) -> runOnClient(() -> handleSync(payload.json()), ctx));
        registrar.playToClient(Payloads.Ui.TYPE, Payloads.Ui.CODEC,
                (payload, ctx) -> runOnClient(() -> handleUi(payload.json()), ctx));
        registrar.playToServer(Payloads.Cmd.TYPE, Payloads.Cmd.CODEC, (payload, ctx) -> {
            ServerPlayer sp = ctx.player() instanceof ServerPlayer s ? s : null;
            if (sp == null) {
                return;
            }
            String json = payload.json();
            ctx.enqueueWork(() -> {
                try {
                    WatchServer.onCommand(sp, json);
                } catch (Throwable t) {
                    VbwMod.LOG.warn("[VBW] 处理客户端指令失败：{}", t.toString());
                }
            });
        });
    }

    /**
     * 客户端处理器。
     *
     * <p>专用服务端上不会有 S2C 包，但注册本身是双向的；
     * 这里用 {@code enqueueWork} 回到主线程，避免在 netty 线程里碰 UI/缓存。</p>
     */
    private static void runOnClient(Runnable body, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                VbwMod.LOG.warn("[VBW] 处理 S2C 包失败：{}", t.toString());
            }
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
