package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 貼在量體外牆上、一路折上屋頂的清水混凝土室外樓梯。
 *
 * <h2>它解決的是尺度問題</h2>
 * <p>一棟三百格高的量體沒有任何東西告訴你它有多高——這是量體本身要的（見 {@link Plot} 的
 * {@code raw}），但整座城市都這樣的話，高度就失去參照。外掛的樓梯是**人的尺寸**：
 * 一階一階、一段一段，眼睛沿著它數上去，那棟樓才突然變得很高。
 *
 * <h2>它必須真的能走</h2>
 * <p>整塊方塊堆出來的階梯玩家爬不上去——原版的自動跨步只有 0.6 格。所以踏階一律是
 * **樓梯方塊**：低的那半是 0.5 格，走上去不用跳。樓梯方塊的 {@code FACING}
 * 就是**往上走的方向**（高的那半在 facing 那一側，見原版 {@code block/stairs} 模型）。
 *
 * <h2>折返梯為什麼要兩道</h2>
 * <p>把去程與回程放在同一條帶子上，兩段在靠近平台的地方只差兩格——玩家走到那裡會撞頭。
 * 真實的樓梯間不是這樣解的：去程與回程是**並排的兩道**，所以一段的正上方是
 * **兩段之後**的那一段，淨高變成兩個段高。這裡照做，{@code lane} 就是那兩道。
 *
 * <h2>座標</h2>
 * <p>四個面各寫一次是這種東西最容易長歪的地方，所以 {@link Plot} 在呼叫前先把世界座標換成
 * 「沿著牆面走多遠（a）、離牆多遠（b）、離底部多高（h）」，這裡只認得這三個數字。
 * 方向也一樣：外面給進來的 {@code axes} 已經是換算好的世界方向。
 */
public final class Stair {

    /** 折返梯：兩道並排，兩端各一個平台。 */
    public static final int SWITCHBACK = 0;
    /** 迴旋梯：繞著一根中柱一路轉上去。 */
    public static final int SPIRAL = 1;

    /** {@code axes} 的四個索引：沿牆的正負、離牆的正負。 */
    public static final int PLUS_A = 0;
    public static final int MINUS_A = 1;
    public static final int PLUS_B = 2;
    public static final int MINUS_B = 3;

    private static final double TAU = Math.PI * 2;

    /**
     * 樓梯往 {@code h = 0} 以下還要再長幾格。
     *
     * <p>量體坐在**自己中心**的地形高度上，而樓梯在牆外幾十格遠的地方，那裡的地面可能低
     * 十幾格。底層平台停在 {@code h = 0} 的話，它會浮在半空、而且離地一大截——玩家跳不上去。
     *
     * <p>解法不是給它一根柱子墊到地面（那只是把平台變成一個高台），而是讓折線**繼續往下折**，
     * 折到地面為止。埋進土裡的那幾段看不到也不要緊，反正它會在地面高度自己冒出來。
     */
    public static final int DIG = 32;

    private final int layout;
    private final int face;
    private final int along;

    // ---- 折返梯
    private final int lane;         // 一道有多寬
    private final int landing;      // 平台沿牆方向多深
    private final int flight;       // 一段升幾格
    private final int tread;        // 一階往前幾格

    // ---- 迴旋梯
    private final int radius;       // 外緣半徑，含女兒牆那一圈
    private final int band;         // 踏面帶多寬
    private final int riseTurn;     // 轉一圈升幾格

    private Stair(int layout, int face, int along, int lane, int landing, int flight, int tread,
                  int radius, int band, int riseTurn) {
        this.layout = layout;
        this.face = face;
        this.along = along;
        this.lane = lane;
        this.landing = landing;
        this.flight = flight;
        this.tread = tread;
        this.radius = radius;
        this.band = band;
        this.riseTurn = riseTurn;
    }

    /**
     * 擲一座。{@code null} ＝ 這面牆放不下。
     *
     * <p>尺寸的上限不是美感問題而是**幾何保證**：量體離街廓邊界至少 {@code street} 格
     * （見 {@link Corridor}），樓梯往外伸出去不能把那段淨空吃光，否則它會長到馬路上。
     */
    static Stair roll(RandomSource r, int width, int depth, int street) {
        int face = r.nextInt(4);
        // 南北面沿著 x 走，東西面沿著 z 走——面決定了可用的長度，所以要先擲面
        int faceSpan = face < 2 ? width : depth;

        Stair s = r.nextInt(100) < 35
                ? new Stair(SPIRAL, face, 0, 0, 0, 0, 0, 4, 3, 8 + r.nextInt(4))
                : new Stair(SWITCHBACK, face, 0,
                3 + r.nextInt(2), 4 + r.nextInt(3), 6 + r.nextInt(3), 1 + r.nextInt(2),
                0, 0, 0);

        return s.fits(faceSpan, street) ? s.place(r, faceSpan) : null;
    }

    private boolean fits(int faceSpan, int street) {
        return span() + 2 <= faceSpan && reach() <= street - 1;
    }

    private Stair place(RandomSource r, int faceSpan) {
        return new Stair(layout, face, r.nextInt(faceSpan - span() + 1),
                lane, landing, flight, tread, radius, band, riseTurn);
    }

    public int face() {
        return face;
    }

    public int along() {
        return along;
    }

    /** 沿著牆面佔多寬。 */
    public int span() {
        return layout == SPIRAL ? radius * 2 + 1 : runSpan() + 2;
    }

    /** 往外伸出去幾格。{@link Plot} 用它把自己的外框撐大。 */
    public int reach() {
        return layout == SPIRAL ? radius * 2 + 1 : lane * 2 + 1;
    }

    /** 折返梯扣掉兩側女兒牆之後的淨寬：兩個平台加中間的梯段。 */
    private int runSpan() {
        return landing * 2 + (flight - 1) * tread;
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param a    沿著牆面走多遠，0 是這座樓梯的起點
     * @param b    離牆多遠，1 是緊貼牆的第一格
     * @param h    離樓梯底部多高
     * @param axes 四個局部方向對應的世界方向，見 {@link #PLUS_A}
     */
    public BlockState blockAt(int a, int b, int h, int top, Plot plot,
                              int wx, int wy, int wz, Direction[] axes) {
        if (h < -DIG || h > top || b < 1 || b > reach() || a < 0 || a >= span()) return null;
        return layout == SPIRAL
                ? spiral(a, b, h, plot, wx, wy, wz, axes)
                : switchback(a - 1, b, h, plot, wx, wy, wz, axes);
    }

    // ------------------------------------------------------------------ 折返梯

    /**
     * @param q 沿牆座標，{@code -1} 與 {@code runSpan()} 是兩側的女兒牆，不放踏面
     */
    private BlockState switchback(int q, int b, int h, Plot plot,
                                  int wx, int wy, int wz, Direction[] axes) {
        int step = walk(q, b, h);
        if (step == FLAT) return plot.skin(wx, wy, wz);
        if (step != NONE) return Masonry.stairs(plot.skin(wx, wy, wz), axes[step]);

        // 女兒牆：外緣與兩端，踩得到的地方往上兩格。直接問「旁邊低一兩格是不是地板」，
        // 不必知道自己在第幾段——樓梯的形狀只寫一次，女兒牆自動跟著折
        if (q != -1 && q != runSpan() && b != reach()) return null;
        int iq = Math.clamp(q, 0, runSpan() - 1);
        int ib = Math.min(b, reach() - 1);
        if (walk(iq, ib, h - 1) != NONE || walk(iq, ib, h - 2) != NONE) {
            return plot.skin(wx, wy, wz);
        }
        return null;
    }

    private static final int NONE = -1;
    private static final int FLAT = -2;

    /**
     * 這一格是不是走得到的地板：{@link #FLAT} ＝ 平的，{@link #NONE} ＝ 不是，
     * 其餘是踏階要朝的方向。
     */
    private int walk(int q, int b, int h) {
        if (h < -DIG || q < 0 || q >= runSpan()) return NONE;

        int k = Math.floorDiv(h, flight);
        int r = h - k * flight;
        boolean forward = (k & 1) == 0;         // 偶數段往前走，奇數段折回來

        if (r == 0) {
            boolean here = forward ? q < landing : q >= runSpan() - landing;
            if (!here || b > lane * 2) return NONE;
            // 平台比上一段的最後一階高一格，所以**進平台的那一排也得是樓梯方塊**，
            // 否則玩家爬完整段之後要跳上平台。這是「看起來對」跟「走得上去」的差別，
            // 而它只在最後一階顯出來
            int mouth = forward ? landing - 1 : runSpan() - landing;
            if (q == mouth) return forward ? MINUS_A : PLUS_A;
            return FLAT;
        }

        // 去程走內側那一道、回程走外側，所以一段的正上方是兩段之後的那一段
        int lo = forward ? 1 : lane + 1;
        if (b < lo || b > lo + lane - 1) return NONE;

        int off = (forward ? q : runSpan() - 1 - q) - landing - (r - 1) * tread;
        if (off < 0 || off >= tread) return NONE;
        // 一階裡的第一格是樓梯方塊（那半格就是走得上去的原因），其餘補平
        return off == 0 ? (forward ? PLUS_A : MINUS_A) : FLAT;
    }

    // ------------------------------------------------------------------ 迴旋梯

    /**
     * 繞著中柱轉上去。
     *
     * <p>踏面的高度**只跟角度有關**：轉到哪個方位就該是哪個高度。這比把螺旋切成一段一段
     * 好寫得多，而且天生連續——沒有接縫要對。
     */
    private BlockState spiral(int a, int b, int h, Plot plot,
                              int wx, int wy, int wz, Direction[] axes) {
        double da = a - radius;
        double db = (b - 1) - radius;
        double dist = Math.sqrt(da * da + db * db);

        if (dist < radius - band) {
            return plot.skin(wx, wy, wz);       // 中柱。它是這座梯唯一的重量
        }
        if (dist > radius + 0.5) return null;

        double angle = Math.atan2(db, da);
        if (angle < 0) angle += TAU;
        double turn = riseTurn * angle / TAU;
        int step = (int) Math.floor(turn);

        boolean outer = dist > radius - 0.5;
        if (outer) {
            // 女兒牆跟著螺旋一起轉。踏面在腳下一兩格就立起來
            return Math.floorMod(h - 1 - step, riseTurn) == 0
                    || Math.floorMod(h - 2 - step, riseTurn) == 0
                    ? plot.skin(wx, wy, wz) : null;
        }
        if (Math.floorMod(h - step, riseTurn) != 0) return null;

        // 一階的前半是樓梯方塊，後半補平——跟折返梯同一個道理
        if (turn - step >= 0.5) return plot.skin(wx, wy, wz);
        return Masonry.stairs(plot.skin(wx, wy, wz), axes[tangent(angle)]);
    }

    /** 逆時針切線方向，取最接近的那個軸。 */
    private static int tangent(double angle) {
        double ta = -Math.sin(angle);
        double tb = Math.cos(angle);
        if (Math.abs(ta) >= Math.abs(tb)) return ta >= 0 ? PLUS_A : MINUS_A;
        return tb >= 0 ? PLUS_B : MINUS_B;
    }
}
