package com.xinbow99.brutalist.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 舒霍夫式水塔：細鋼構織成的雙曲面塔身，頂著一個巨大的水箱。
 *
 * <h2>它為什麼跟這個世界其他東西不一樣</h2>
 * <p>這裡的一切都是混凝土的量體——厚、重、擋住視線。水塔是**線**做的：整座塔身是一層
 * 看得穿的網，天空從縫裡透過來。放在一堆實心的東西中間，它是唯一輕的那一個。
 *
 * <h2>雙曲面是直線織出來的</h2>
 * <p>舒霍夫那一手的全部就在這裡：曲面是彎的，但組成它的每一根桿子都是**直的**。
 * 兩族方向相反的斜桿各自繞著塔身旋上去，交叉出菱形的網——所以這裡的實作不是去描一條
 * 雙曲線，而是讓角度隨高度線性旋轉（{@code twist}），曲面是那個旋轉的結果，不是原因。
 *
 * <h2>孤獨</h2>
 * <p>它只長在**周圍很大一圈都沒有東西**的地方（見生成器的淨空檢查）。一座水塔擠在
 * 樓群裡只是一個設備；站在空地正中央、方圓幾十格什麼都沒有，它才是那張圖。
 */
public record WaterTower(int baseR, int throatR, int shaftH, int tankR, int tankH, int members) {

    /** 鋼構的顏色。跟屋頂設備、電塔同一種鋼。 */
    private static final BlockState STEEL = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState FRAME = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    /** 環形走道的護欄與格柵。 */
    private static final BlockState GRATE = Blocks.IRON_BARS.defaultBlockState();

    /** 擲一座。尺寸而已——位置由 {@link Precinct} 決定，它把塔放在整格街廓的正中央。 */
    public static WaterTower roll(RandomSource r) {
        int baseR = 9 + r.nextInt(6);
        return new WaterTower(baseR, Math.max(3, baseR * 55 / 100),
                30 + r.nextInt(22), baseR + 3 + r.nextInt(4), 9 + r.nextInt(6),
                8 + r.nextInt(4));
    }

    /** 塔頂到哪。 */
    public int top() {
        return shaftH + tankH + 3;
    }

    /** 塔腳的整地範圍：網狀的桿子全落在這一圈上。 */
    public int footing() {
        return baseR + 2;
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param du 離塔心多遠（東西），{@code dv} 是南北，{@code h} 是離地幾格
     */
    public BlockState blockAt(int du, int dv, int h) {
        if (h < 0 || h > shaftH + tankH + 2) return null;

        int span = tankR + 3;
        int d2 = du * du + dv * dv;
        if (d2 > span * span) return null;
        double d = Math.sqrt(d2);

        if (h <= shaftH) return shaft(du, dv, h, d);
        return tank(h, d);
    }

    /**
     * 塔身：兩族反向旋上去的直桿，加上每隔一段一道水平環箍。
     *
     * <p>環箍不是裝飾——沒有它，兩族斜桿在畫面上只是一堆交叉的線，加了才讀得出
     * 這是一個**筒**。真的舒霍夫塔也是這樣：斜桿受拉，環箍定形。
     */
    private BlockState shaft(int du, int dv, int h, double d) {
        int r = radiusAt(h);
        if (Math.abs(d - r) > 1.0) return null;

        // 每隔幾格一道環箍
        int hoop = Math.max(6, shaftH / 6);
        if (Math.floorMod(h, hoop) == 0) return FRAME;

        // 斜桿：角度隨高度線性旋轉，兩族方向相反。曲面是這個旋轉的結果，不是原因。
        //
        // 判斷要在**連續的角度**上做，不能先把弧長取整數：取整之後桿子的間距會隨半徑
        // 忽寬忽窄，整面網讀起來是一堆雜點而不是菱形。這裡量的是「離最近那一根桿子
        // 幾格」，所以不管半徑多少，桿子都是一格寬
        double angle = Math.atan2(dv, du);
        double twist = TWIST * h / (double) Math.max(1, shaftH);
        double step = Math.PI * 2 / members;
        return near(angle - twist, step, r) || near(angle + twist, step, r) ? STEEL : null;
    }

    /** 一族斜桿從底到頂總共轉過多少角度。轉太少像直的籠子，轉太多會變成麻花 */
    private static final double TWIST = 1.15;

    /** 這個角度離最近的一根桿子幾格。 */
    private static boolean near(double angle, double step, int r) {
        double f = angle / step;
        return Math.abs(f - Math.round(f)) * step * r < 0.9;
    }

    /**
     * 塔身的半徑：雙曲線，喉部在**塔頂**（水箱底下）。
     *
     * <p>喉部放在頂端而不是中間：水箱要盡量往上收才撐得住，而裙擺一路張開到地面
     * 才是那個「沙漏被拉長」的側影。
     */
    private int radiusAt(int h) {
        double throat = throatR;
        double c2 = (double) shaftH * shaftH * throat * throat
                / ((double) baseR * baseR - throat * throat);
        int dh = h - shaftH;
        return (int) Math.round(throat * Math.sqrt(1.0 + dh * dh / c2));
    }

    /**
     * 水箱：一個比塔身粗得多的圓筒，底下用一段錐裙接回塔身。
     *
     * <p>水箱**一定要比塔身寬**。等寬的話整座讀成一根柱子，而這種塔的表情全在
     * 「頭重腳輕」——一個沉的東西被一層看得穿的網舉在半空。
     *
     * <p>外圈一圈走道加格柵護欄。走道是這座塔唯一「人的尺度」的東西，
     * 沒有它，水箱只是一個幾何體；有了它，那個幾何體就有了高度。
     */
    private BlockState tank(int h, double d) {
        int up = h - shaftH;

        // 底下的錐裙：從塔喉張開到水箱
        if (up <= 2) {
            int r = throatR + (tankR - throatR) * up / 2;
            return Math.abs(d - r) <= 1.2 ? STEEL : null;
        }

        // 環形走道：一圈實心的板，外緣立格柵當護欄
        int deck = shaftH + 3;
        if (h == deck && d <= tankR + 2 && d >= tankR - 1) return FRAME;
        if ((h == deck + 1 || h == deck + 2) && d > tankR + 1 && d <= tankR + 2) return GRATE;

        if (up > tankH) {
            // 頂上再一圈護欄，側影才不是被切平的
            return h <= shaftH + tankH + 2 && d >= tankR - 1 && d <= tankR ? GRATE : null;
        }

        if (d > tankR) return null;
        // 桶身是殼，上下各一片蓋
        if (up == tankH || Math.abs(d - tankR) <= 1.0) return STEEL;
        return null;
    }
}
