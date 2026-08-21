package com.xinbow99.brutalist.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 兩棟量體之間的高空連通道。
 *
 * <h2>為什麼只連同一個超級街廓裡的兩塊</h2>
 * <p>「連接兩棟樓」需要同時知道兩棟樓，而那正是整份程式碼刻意避開的事——跨格追溯沒有上界
 * （見 {@link Plot} 的說明）。
 *
 * <p>但同一個超級街廓內部沒有這個問題：切法是**整組一次擲**的，所以組裡任何一格都算得出
 * 其他幾塊在哪、多高。所以天橋只在組內連，一格都不跨出去。副作用剛好是好的：
 * 天橋永遠不會越過街廓邊界線，所以它天生撞不到高架與電塔。
 *
 * <h2>粗大優先於功能</h2>
 * <p>斷面九到十三格寬、七到十一格高——比一條走廊該有的尺寸大得多。理由是這個世界的量體
 * 有兩三百格高，一條「正常」寬度的空橋掛在上面根本看不見。它要先被看到，才輪得到好不好走。
 *
 * <p>四種斷面而不是一種：一律方管會讓所有天橋讀成同一根管子重複貼上。
 */
public record Bridge(boolean alongX, int centre, int from, int to, int y,
                     int half, int tall, int section, int salt) {

    /** 方箱：最基本的一種，靠長窗撐場面。 */
    public static final int BOX = 0;
    /** 圓管：側影完全不同，一眼分得出來。 */
    public static final int TUBE = 1;
    /** 深梁：側牆打滿貫穿的大圓孔，光會從另一邊透過來。 */
    public static final int VIERENDEEL = 2;
    /** 莢艙：中段膨大成一個房間，像掛在兩塔之間。 */
    public static final int POD = 3;

    /**
     * 擲一條連接 {@code a} 與 {@code b} 的天橋，{@code null} ＝ 這兩塊不適合連。
     *
     * <p>兩塊必須在某一軸上**投影重疊**才連得起來。斜對角的兩塊硬連的話，橋會斜著穿過
     * 中間那塊空地，而斜的橋在方格座標上是一堆階梯狀的鋸齒——那個代價換不到什麼。
     */
    public static Bridge roll(RandomSource r, Plot a, Plot b) {
        int overlapX = span(a.minX(), a.maxX(), b.minX(), b.maxX());
        int overlapZ = span(a.minZ(), a.maxZ(), b.minZ(), b.maxZ());

        boolean alongX;
        if (overlapZ >= 24 && a.maxX() < b.minX() - 8) alongX = true;
        else if (overlapZ >= 24 && b.maxX() < a.minX() - 8) {
            return roll(r, b, a);
        } else if (overlapX >= 24 && a.maxZ() < b.minZ() - 8) alongX = false;
        else if (overlapX >= 24 && b.maxZ() < a.minZ() - 8) {
            return roll(r, b, a);
        } else {
            return null;
        }

        int lo = alongX ? Math.max(a.minZ(), b.minZ()) : Math.max(a.minX(), b.minX());
        int hi = alongX ? Math.min(a.maxZ(), b.maxZ()) : Math.min(a.maxX(), b.maxX());

        int half = 4 + r.nextInt(3);
        int tall = 7 + r.nextInt(5);
        if (hi - lo < half * 2 + 4) return null;

        int centre = lo + half + 2 + r.nextInt(hi - lo - half * 2 - 3);

        // 高度不設限：低的橋讓人在底下走過去，高的橋要抬頭才看得到。兩種都要
        int ceiling = Math.min(a.minY() + a.height(), b.minY() + b.height()) - tall - 3;
        int floor = Math.max(a.minY(), b.minY()) + 10;
        if (ceiling <= floor) return null;

        int section = r.nextInt(4);
        int salt = r.nextInt();

        // 擲幾次高度，挑一個**兩端都真的有東西可以接**的。
        //
        // 外接矩形上有那一面，不代表那個高度上有材料——核心懸挑型的量體在兩片橫板之間
        // 是空的，穿孔牆上到處是洞。接在那種地方，橋的兩頭會懸在半空，看起來像斷掉的
        for (int attempt = 0; attempt < 12; attempt++) {
            int y = floor + r.nextInt(ceiling - floor);
            int from = reach(a, alongX, centre, y, tall, true);
            int to = reach(b, alongX, centre, y, tall, false);
            if (from == NONE || to == NONE || to - from < 10) continue;
            return new Bridge(alongX, centre, from, to, y, half, tall, section, salt);
        }
        return null;
    }

    private static final int NONE = Integer.MIN_VALUE;

    /**
     * 從外接矩形往量體裡面找，第一根**實心**的柱子在哪。
     *
     * <p>往裡面探而不是直接用外接矩形的那一面，有兩個理由：外框含了外掛樓梯的厚度
     * （見 {@link Plot#minX}），而且退縮、圓筒、穿孔牆的實際牆面本來就不在框上。
     */
    private static int reach(Plot plot, boolean alongX, int centre, int y, int tall, boolean toward) {
        int edge = toward
                ? (alongX ? plot.maxX() : plot.maxZ())
                : (alongX ? plot.minX() : plot.minZ());
        int step = toward ? -1 : 1;

        for (int i = 0; i < 16; i++) {
            int t = edge + step * i;
            int wx = alongX ? t : centre;
            int wz = alongX ? centre : t;
            // 樓板高度與半腰各驗一次：只驗一格的話會剛好落在一條窗帶上
            if (plot.blockAt(wx, y, wz) != null && plot.blockAt(wx, y + tall / 2, wz) != null) {
                return t - step * 2;        // 再往外推兩格，橋是插進牆裡的
            }
        }
        return NONE;
    }

    private static int span(int a0, int a1, int b0, int b1) {
        return Math.min(a1, b1) - Math.max(a0, b0) + 1;
    }

    public int minX() { return alongX ? from : centre - half - 3; }
    public int maxX() { return alongX ? to : centre + half + 3; }
    public int minZ() { return alongX ? centre - half - 3 : from; }
    public int maxZ() { return alongX ? centre + half + 3 : to; }
    public int minY() { return y - 3; }
    public int maxY() { return y + tall + 4; }

    /** 這一格是什麼，{@code null} ＝ 空氣。 */
    public BlockState blockAt(int wx, int wy, int wz, Plot skin) {
        int t = alongX ? wx : wz;
        if (t < from || t > to) return null;

        int p = (alongX ? wz : wx) - centre;
        int dh = wy - y;

        int hw = half;
        int ht = tall;
        if (section == POD) {
            // 中段膨大。用一個平滑的隆起，不是階梯狀的放大——後者在側面會看到兩道折角
            double f = (t - from) / (double) Math.max(1, to - from);
            double bump = Math.max(0.0, 1.0 - Math.abs(f - 0.5) * 3.2);
            hw += (int) Math.round(bump * 4);
            ht += (int) Math.round(bump * 5);
        }

        boolean solid = switch (section) {
            case TUBE -> tube(p, dh, hw, ht);
            case VIERENDEEL -> vierendeel(t, p, dh, hw, ht);
            default -> box(t, p, dh, hw, ht);
        };
        return solid ? skin.skin(wx, wy, wz) : null;
    }

    /**
     * 方箱：殼加一道通長的水平長窗。
     *
     * <p>底板做兩格厚。一格的底板在下面看是一片薄紙，而這種尺度的橋必須看起來扛得住自己。
     */
    private boolean box(int t, int p, int dh, int hw, int ht) {
        if (Math.abs(p) > hw || dh < -1 || dh > ht) return false;
        boolean shell = Math.abs(p) == hw || dh <= 0 || dh == ht;
        if (!shell) return false;
        return !window(t, p, dh, hw, ht);
    }

    /**
     * 長窗：側牆眼睛高度那一條。每隔一段留一根柱子，不然整面牆會斷成兩截。
     *
     * <p>只開**三格高**。原本是從腰部一路開到頂，結果側牆被吃掉四成，整條橋讀成一個開放的
     * 桁架而不是封閉的通道——而封閉正是它跟高架橋的差別。窗要有，但它是牆上的一條線，
     * 不是牆本身。
     */
    private boolean window(int t, int p, int dh, int hw, int ht) {
        if (Math.abs(p) != hw) return false;
        if (dh < 2 || dh > Math.min(4, ht - 2)) return false;
        return Math.floorMod(t, 9) >= 2;
    }

    private boolean tube(int p, int dh, int hw, int ht) {
        double cy = ht / 2.0;
        double dy = (dh - cy) * (hw / Math.max(1.0, cy));      // 壓成正圓再判斷
        double dist = Math.sqrt(p * p + dy * dy);
        if (dist > hw + 0.5 || dist < hw - 1.2) {
            // 底下補一片平的地板，不然圓管裡面沒地方站
            return dh == 0 && Math.abs(p) <= hw - 2 && dist <= hw;
        }
        // 兩側各開一條長縫
        return !(dh >= (int) cy && dh <= (int) cy + 1 && Math.abs(p) >= hw - 2);
    }

    /**
     * 深梁：側牆上打一排貫穿的大圓孔。
     *
     * <p>孔是**貫穿**的，所以光會從另一邊透進來——這是四種裡唯一會在橋底下投出圖案的。
     */
    private boolean vierendeel(int t, int p, int dh, int hw, int ht) {
        if (Math.abs(p) > hw || dh < -1 || dh > ht) return false;
        boolean shell = Math.abs(p) == hw || dh <= 0 || dh == ht;
        if (!shell) return false;
        if (Math.abs(p) != hw) return true;

        int gap = hw * 2 + 6;
        int du = Math.floorMod(t - Math.floorDiv(salt, 8), gap) - gap / 2;
        int dv = dh - ht / 2;
        int r = Math.max(2, Math.min(hw, ht / 2) - 1);
        return du * du + dv * dv > r * r;
    }
}
