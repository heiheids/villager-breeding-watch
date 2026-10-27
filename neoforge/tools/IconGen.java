import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成模组图标 assets/villagerbreedingwatch/textures/icon.png（128x128）。
 *
 * 结构简明：圆角底板 + 一颗心（能繁殖）+ 一条虚线连到小方框（床）。
 * 这三个元素正好是程序的三件事：状态、能不能生、村民与床的绑定关系。
 * 用 4 倍尺寸绘制再缩小，得到平滑边缘。
 *
 * 用法： java tools/IconGen.java <输出路径>
 */
public final class IconGen {

    private static final int S = 4;      // 超采样
    private static final int N = 128;    // 最终边长

    public static void main(String[] args) throws Exception {
        int w = N * S;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, w, w);
        g.setComposite(AlphaComposite.SrcOver);

        // ---------- 圆角底板（深绿竖向渐变）----------
        float r = 22f * S;
        RoundRectangle2D plate = new RoundRectangle2D.Float(0, 0, w - 1, w - 1, r * 2, r * 2);
        g.setPaint(new GradientPaint(0, 0, new Color(30, 52, 46),
                0, w, new Color(12, 22, 20)));
        g.fill(plate);

        // 内描边，让底板在深色界面里也有边界
        g.setPaint(new Color(140, 220, 170, 70));
        g.setStroke(new BasicStroke(1.5f * S));
        g.draw(plate);

        // ---------- 心（可繁殖）----------
        double cx = w * 0.40;
        double cy = w * 0.38;
        double size = w * 0.46;
        Shape heart = heart(cx, cy, size);
        g.setPaint(new GradientPaint(0, (float) (cy - size * 0.5),
                new Color(124, 227, 139), 0, (float) (cy + size),
                new Color(46, 160, 92)));
        g.fill(heart);

        // ---------- 虚线连到右侧的小方框（床）----------
        g.setColor(new Color(64, 204, 230, 235));
        g.setStroke(new BasicStroke(3.0f * S, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[] {8f * S, 6f * S}, 0f));
        g.draw(new Line2D.Double(w * 0.60, w * 0.60, w * 0.74, w * 0.72));

        // 方框
        float boxX = w * 0.71f;
        float boxY = w * 0.665f;
        float boxW = w * 0.205f;
        g.setColor(new Color(240, 210, 120));
        g.setStroke(new BasicStroke(3.0f * S));
        g.draw(new Rectangle2D.Float(boxX, boxY, boxW, boxW));

        // 方框里一条横线，暗示"床"
        g.draw(new Line2D.Double(boxX, boxY + boxW * 0.55, boxX + boxW, boxY + boxW * 0.55));

        g.dispose();

        // ---------- 缩小并保存 ----------
        BufferedImage out = new BufferedImage(N, N, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = out.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(img, 0, 0, N, N, null);
        sg.dispose();

        File f = new File(args.length > 0 ? args[0] : "icon.png");
        f.getParentFile().mkdirs();
        ImageIO.write(out, "PNG", f);
        System.out.println("written: " + f.getAbsolutePath());
    }

    /**
     * 用四段三次贝塞尔画心形（em 单位 0..1 的经典心形曲线，再缩放平移）。
     * 早期版本用"两个椭圆 + 三角"拼接，实际画出来只是一个圆 —— 拼接口径不对，
     * 这里改成显式路径，形状可控。
     */
    private static Shape heart(double cx, double cy, double size) {
        double s = size;
        // 标准心形（宽 1，高约 0.9），原点在心形外接框左上
        double x0 = cx - s * 0.5;
        double y0 = cy - s * 0.40;
        Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
        p.moveTo(x0 + s * 0.50, y0 + s * 0.18);
        // 左半边：从顶部中点向左下卷到底部尖角
        p.curveTo(x0 + s * 0.20, y0 - s * 0.12, x0 - s * 0.06, y0 + s * 0.14,
                x0 + s * 0.10, y0 + s * 0.42);
        p.curveTo(x0 + s * 0.22, y0 + s * 0.62, x0 + s * 0.42, y0 + s * 0.76,
                x0 + s * 0.50, y0 + s * 0.92);
        // 右半边：对称回来
        p.curveTo(x0 + s * 0.58, y0 + s * 0.76, x0 + s * 0.78, y0 + s * 0.62,
                x0 + s * 0.90, y0 + s * 0.42);
        p.curveTo(x0 + s * 1.06, y0 + s * 0.14, x0 + s * 0.80, y0 - s * 0.12,
                x0 + s * 0.50, y0 + s * 0.18);
        p.closePath();
        return p;
    }
}
