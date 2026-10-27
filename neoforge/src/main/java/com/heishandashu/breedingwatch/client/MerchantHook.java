package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.client.ui.DetailScreen;
import com.heishandashu.breedingwatch.i18n.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 交易界面里的「繁殖详情」按钮（NeoForge 侧）。
 *
 * <p>用 {@code ScreenEvent.Init.Post} 往界面里加控件（Fabric 侧要绕
 * {@code ScreenEvents.AFTER_INIT} + {@code Screens.getWidgets}）。</p>
 *
 * <p>位置按 gui-scaled 尺寸算，并在「会掉出窗口」时贴回内部 ——
 * 不同模组会改 MerchantScreen 的布局，硬编码绝对坐标一定会踩空。</p>
 */
public final class MerchantHook {

    private MerchantHook() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(MerchantHook::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof MerchantScreen)) {
            ClientHub.clearPendingMerchant();
            return;
        }
        int eid = ClientHub.pendingMerchantEid();
        if (eid < 0) {
            return;   // 不是从「右键村民」进来的，不硬猜目标
        }
        try {
            int sw = screen.width;
            int sh = screen.height;
            int left = (sw - 276) / 2;
            int top = (sh - 166) / 2;
            int bx = left + 2;
            int by = top + 168;
            if (by + 20 > sh) {                 // 掉出窗口：挪到右上角
                bx = left + 276 - 92;
                by = top + 3;
            }
            bx = Math.max(2, Math.min(Math.max(2, sw - 92), bx));
            by = Math.max(2, Math.min(Math.max(2, sh - 22), by));

            Button button = Button.builder(Component.literal(Lang.t("ui.merchant_btn")), b -> {
                ClientHub.DATA.detailEid = eid;
                ClientHub.sendCmd("{\"c\":\"detailStart\",\"eid\":" + eid + "}");
                Minecraft.getInstance().gui.setScreen(new DetailScreen(eid));
                ClientHub.clearPendingMerchant();
            }).bounds(bx, by, 90, 20).build();
            addWidget(event, button);
        } catch (Throwable t) {
            VbwMod.LOG.warn("[VBW] 添加交易界面按钮失败：{}", t.toString());
        }
    }

    /** NeoForge 的 Init.Post 自带 addListener，失败只记日志，不影响交易本身。 */
    private static void addWidget(ScreenEvent.Init.Post event, AbstractWidget widget) {
        event.addListener(widget);
        VbwMod.LOG.debug("[VBW] 已在交易界面加入繁殖详情按钮");
    }
}
