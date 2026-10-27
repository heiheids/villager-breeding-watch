package com.heishandashu.breedingwatch.net;

import com.heishandashu.breedingwatch.VbwMod;

/**
 * 客户端发送端抽象。
 *
 * <p>与 {@link PacketSender} 同理：把"客户端怎么发一个 C2S 包"的加载器差异收在一处，
 * 客户端中枢（{@code ClientHub}）就能两个加载器共用。</p>
 */
public interface ClientSender {

    /** 尽力发送；未连接或对端没装模组时静默降级。 */
    void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload);

    /** 未注入时的兜底：只记一条日志，不抛异常。 */
    ClientSender NOOP = payload ->
            VbwMod.LOG.debug("[VBW] 客户端发送端未注入，丢弃 {}", payload.type().id());
}
