package com.heishandashu.breedingwatch.net;

import com.heishandashu.breedingwatch.VbwMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 两个方向的 JSON 文本包。
 *
 * <p>内容一律是 JSON 字符串：调试时日志里能直接看懂，也不必为每个字段维护 codec。
 * 26.3 的 payload 形态：{@code Type} 是只包 Identifier 的 record，codec 在注册时挂上，
 * 注册走 Fabric 的 {@code PayloadTypeRegistry}。</p>
 *
 * <p>缓冲区类型用 {@code ByteBuf}：{@code ByteBufCodecs.STRING_UTF8} 就是这个类型，
 * 不必绕 {@code RegistryFriendlyByteBuf}（26.3 里两者不通用）。</p>
 */
public final class Payloads {

    private Payloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VbwMod.MOD_ID, path);
    }

    /** S2C：评估快照（含统计）。 */
    public record Sync(String json) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(id("sync"));
        public static final StreamCodec<ByteBuf, Sync> CODEC =
                ByteBufCodecs.STRING_UTF8.map(Sync::new, Sync::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：界面指令（打开详情页 / 记录交易界面对应村民 / 要求客户端重发开关）。 */
    public record Ui(String json) implements CustomPacketPayload {
        public static final Type<Ui> TYPE = new Type<>(id("ui"));
        public static final StreamCodec<ByteBuf, Ui> CODEC =
                ByteBufCodecs.STRING_UTF8.map(Ui::new, Ui::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端设置 / 详情请求 / 统计刷新。 */
    public record Cmd(String json) implements CustomPacketPayload {
        public static final Type<Cmd> TYPE = new Type<>(id("cmd"));
        public static final StreamCodec<ByteBuf, Cmd> CODEC =
                ByteBufCodecs.STRING_UTF8.map(Cmd::new, Cmd::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
