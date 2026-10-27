package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.client.ui.DetailScreen;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.net.ClientSender;
import com.heishandashu.breedingwatch.net.Payloads;
import com.heishandashu.breedingwatch.net.VbwNetworking;
import com.heishandashu.breedingwatch.view.Views;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 客户端状态中枢：设置、缓存、与服务端的双向通信。
 *
 * <p>设置要送到服务端，光靠"按键时发送"不够：主菜单里发不出去（没连接），
 * 进世界那一刻的时序也可能错过首帧扫描。所以这里做了三重保险：
 * 客户端进世界后重发、服务端登录时主动要求重发、两边各打一条 INFO 日志。</p>
 */
public final class ClientHub {

    public static final ClientSettings SETTINGS = new ClientSettings();
    public static final ClientData DATA = new ClientData();

    private static ClientLevel lastLevel;
    private static int pendingMerchantEid = -1;

    private ClientHub() {}

    // ------------------------------------------------------------------ 世界进出

    /** 进世界：清掉上一个世界的残留数据，并把开关重发一遍。 */
    public static void onJoinedWorld() {
        DATA.clear();
        pushPrefs();
    }

    public static void onLeftWorld() {
        DATA.clear();
        lastLevel = null;
    }

    /** 每 tick 检查维度切换（换维度后服务端是另一套 POI 与村民）。 */
    public static void tickWorldWatch(Minecraft mc) {
        ClientLevel lvl = mc.level;
        if (lvl != lastLevel) {
            lastLevel = lvl;
            if (lvl != null) {
                DATA.clear();
                pushPrefs();
            }
        }
    }

    // ------------------------------------------------------------------ 网络

    /**
     * 发送一个 C2S 指令。
     *
     * <p>只依赖 {@link ClientSender} 接口：Fabric 与 NeoForge 的发送写法差异
     * 收在各自入口注入的实现里。</p>
     */
    public static void sendCmd(String json) {
        try {
            ClientSender sender = VbwNetworking.CLIENT_SENDER;
            if (sender != null) {
                sender.sendToServer(new Payloads.Cmd(json));
            }
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 发送指令失败：{}", e.toString());
        }
    }

    public static void pushPrefs() {
        Lang.setDefault(SETTINGS.effectiveLanguage());
        String json = SETTINGS.prefsJson();
        VbwMod.LOG.info("[VBW] 已发送客户端开关给服务器：{}", json);
        sendCmd(json);
    }

    /** 服务端下发的界面指令。 */
    public static void onUi(String json) {
        try {
            UiMsg m = Json.COMPACT.fromJson(json, UiMsg.class);
            if (m == null) {
                return;
            }
            if ("sync".equals(m.c())) {
                pushPrefs();      // 服务端要求重发（进世界 / 换维度之后）
                return;
            }
            if (m.eid() == null) {
                return;
            }
            DATA.detailEid = m.eid();
            if ("detail".equals(m.c())) {
                Minecraft.getInstance().gui.setScreen(new DetailScreen(m.eid()));
            } else {
                pendingMerchantEid = m.eid();
            }
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 解析界面指令失败：{}", e.toString());
        }
    }

    public static int pendingMerchantEid() {
        return pendingMerchantEid;
    }

    public static void clearPendingMerchant() {
        pendingMerchantEid = -1;
    }

    /** 服务端界面指令的载荷。 */
    public record UiMsg(String c, Integer eid) {}

    /** 供其他类读取一条村民视图（没有就返回 null）。 */
    public static Views.VillagerView view(int eid) {
        return DATA.view(eid);
    }
}
