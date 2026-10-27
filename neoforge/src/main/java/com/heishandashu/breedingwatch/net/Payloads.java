package com.heishandashu.breedingwatch.net;

import com.heishandashu.breedingwatch.VbwMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 两个方向的 JSON 文本包（NeoForge 侧）。
 *
 * <p>内容一律是 JSON 字符串：调试时日志里能直接看懂，也不必为每个字段维护 codec。</p>
 *
 * <p><b>与 Fabric 版的唯一差别在 codec 的缓冲区类型</b>：
 * Fabric 的 {@code PayloadTypeRegistry} 收 {@code StreamCodec<ByteBuf, T>}，
 * 而 NeoForge 的 {@code PayloadRegistrar} 声明的是
 * {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}。
 * 所以这里必须写 {@code StreamCodec<RegistryFriendlyByteBuf, T>}，
 * 直接搬 Fabric 版本会编译失败（反之亦然）。</p>
 */
public final class Payloads {

    private Payloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VbwMod.MOD_ID, path);
    }

    /** S2C：评估快照（含统计）。 */
    public record Sync(String json) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(id("sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC =
                ByteBufCodecs.STRING_UTF8.<RegistryFriendlyByteBuf>mapStream(b -> b).map(Sync::new, Sync::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：界面指令（打开详情页 / 记录交易界面对应村民 / 要求客户端重发开关）。 */
    public record Ui(String json) implements CustomPacketPayload {
        public static final Type<Ui> TYPE = new Type<>(id("ui"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ui> CODEC =
                ByteBufCodecs.STRING_UTF8.<RegistryFriendlyByteBuf>mapStream(b -> b).map(Ui::new, Ui::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端设置 / 详情请求 / 统计刷新。 */
    public record Cmd(String json) implements CustomPacketPayload {
        public static final Type<Cmd> TYPE = new Type<>(id("cmd"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cmd> CODEC =
                ByteBufCodecs.STRING_UTF8.<RegistryFriendlyByteBuf>mapStream(b -> b).map(Cmd::new, Cmd::json);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
