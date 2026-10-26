package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.client.ui.DetailScreen;
import com.heishandashu.breedingwatch.i18n.Lang;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;

/**
 * 交易界面里的"繁殖详情"按钮。
 *
 * <p>有交易的村民右键会打开交易界面；此时在界面左下补一个按钮，点它进详情页。
 * 按钮位置按 gui-scaled 尺寸算，并在"会掉到窗口外"时贴回窗口内部 —— 不同模组会改
 * MerchantScreen 的布局，硬编码绝对坐标一定会踩空。</p>
 *
 * <p>26.3 没有 {@code ScreenEvent.Init}，用 fabric 的 {@code ScreenEvents.AFTER_INIT} +
 * {@code Screens.getWidgets(screen)} 往界面里加控件。界面关闭时清掉待处理标记，
 * 避免下一次误用旧的目标。</p>
 */
public final class MerchantHook {

    private MerchantHook() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof MerchantScreen)) {
                ClientHub.clearPendingMerchant();
                return;
            }
            int eid = ClientHub.pendingMerchantEid();
            if (eid < 0) {
                return;   // 不是从"右键村民"进来的，不硬猜目标
            }
            int left = (scaledWidth - 276) / 2;
            int top = (scaledHeight - 166) / 2;
            int bx = left + 2;
            int by = top + 168;
            if (by + 20 > scaledHeight) {          // 掉出窗口：挪到右上角
                bx = left + 276 - 92;
                by = top + 3;
            }
            bx = Math.max(2, Math.min(Math.max(2, scaledWidth - 92), bx));
            by = Math.max(2, Math.min(Math.max(2, scaledHeight - 22), by));

            Button button = Button.builder(Component.literal(Lang.t("ui.merchant_btn")), b -> {
                ClientHub.DATA.detailEid = eid;
                ClientHub.sendCmd("{\"c\":\"detailStart\",\"eid\":" + eid + "}");
                Minecraft.getInstance().gui.setScreen(new DetailScreen(eid));
                ClientHub.clearPendingMerchant();
            }).bounds(bx, by, 90, 20).build();
            addWidget(screen, button);
        });
    }

    /** 把控件塞进界面。失败只记日志：按钮没出来不该影响交易本身。 */
    private static void addWidget(Screen screen, AbstractWidget widget) {
        try {
            Screens.getWidgets(screen).add(widget);
            VbwMod.LOG.debug("[VBW] 已在交易界面加入繁殖详情按钮");
        } catch (Throwable t) {
            VbwMod.LOG.warn("[VBW] 添加交易界面按钮失败：{}", t.toString());
        }
    }
}
