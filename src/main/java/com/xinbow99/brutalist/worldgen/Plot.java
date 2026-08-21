package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 一棟量體。
 *
 * <h2>為什麼是「純函數」</h2>
 * <p>ChunkGenerator 一次只填一個 16×16 的區塊，而且**區塊之間是平行跑的、順序不保證**。
 * 所以一棟橫跨幾十個區塊的量體不能靠「先蓋好再切開」——它必須是一個
 * {@code (x,y,z) → 方塊} 的純函數，每個區塊各自算自己那一塊，算出來自然接得起來。
 *
 * <p>這條限制決定了整份程式碼的長相：沒有任何生成期間的狀態，所有隨機性都來自
 * {@link net.minecraft.world.level.levelgen.PositionalRandomFactory#at}，
 * 而它只吃座標與世界種子。
 *
 * <h2>一棟樓可以佔好幾格</h2>
 * <p>要讓量體大小有差異，就得允許它跨格。但如果每一格各自擲「我要往外佔幾格」，相鄰的兩格
 * 會搶同一塊地，而**解決搶地需要往回追溯**：A 要看 B 是不是錨點，B 要看 C…… 這條鏈沒有
 * 上界，在區塊生成裡不能接受。
 *
 * <p>所以改成每 {@code 2×2} 格一個**超級街廓**，整組一次擲出要怎麼切（見 {@link #partition}）。
 * 切法完全落在組內，跨組永遠不會衝突，每一格仍然是 O(1) 算得出來。代價是量體最大就是 2×2 格、
 * 而且不會跨越超級街廓的邊界。
 */
public final class Plot {

    /** 超級街廓的邊長（幾個街廓）。 */
    static final int GROUP = 2;

    private final int x0;
    private final int z0;
    private final int baseY;

    private final int width;
    private final int depth;
    private final int height;

    private final Form form;

    /** 立面的柱距，也是穿孔牆的孔距。 */
    private final int module;
    /** 每幾格一條水平的窗帶。 */
    private final int floorHeight;
    /** 梯形每一段的高度。 */
    private final int bandHeight;
    /** 梯形每一段往內縮幾格。 */
    private final int setback;
    /** 懸挑橫板每隔幾格出現一次。 */
    private final int armGap;
    /** 懸挑橫板本身多厚。 */
    private final int armThickness;
    /** 底下這幾格是實心基座，不開窗。 */
    private final int plinth;
    /** 最上面這幾格是女兒牆，不開窗。 */
    private final int parapet;

    /**
     * 完全不開窗的量體。
     *
     * <p>開窗會把量體讀成「一棟樓」——有樓層、有尺度、可以估出多大。不開窗的話它就只是
     * 一塊**幾何體**，大小失去參照，看起來反而更大。這兩種都要有：整片都開窗會變成住宅區，
     * 整片都不開窗則會平掉，看不出哪些是巨大的。
     */
    private final boolean raw;

    /** 架空層高度，0 ＝ 不架空。 */
    private final int lift;
    private final int columnGap;
    private final int columnWidth;

    /** 這棟樓的石材配方。見 {@link Masonry.Palette}。 */
    private final Masonry.Palette palette;

    /**
     * 破損：雜訊超過 {@code decayAt} 而且離表面夠近的地方，方塊就不見了。
     *
     * <p>用**跟材質不同的一份雜訊**（另一個 salt）。共用同一份的話，破損會剛好長在某一種
     * 石材上——看起來像那種石頭比較脆，而不是像風化。風化跟材質是兩件無關的事。
     *
     * <p>尺度比材質大得多（12～20）：破損的總面積是由門檻決定的，跟尺度無關，所以放大尺度
     * 不會讓建築更破，只會把同樣的量**集中成幾個真正的缺口**，而不是撒成一臉痘子。
     */
    private final int decayScale;
    private final int decaySalt;
    private final float decayAt;

    /** {@link Form#ASSEMBLY} 的組成方塊，其他形狀是空陣列。 */
    private final Box[] boxes;

    /** 外掛的室外樓梯，{@code null} ＝ 這棟沒有。 */
    private final Stair stair;

    private Plot(int x0, int z0, int baseY, int width, int depth, int height, Form form,
                 int module, int floorHeight, int bandHeight, int setback,
                 int armGap, int armThickness, int plinth, int parapet,
                 boolean raw, int lift, int columnGap, int columnWidth,
                 Masonry.Palette palette, int decayScale, int decaySalt, float decayAt,
                 Box[] boxes, Stair stair) {
        this.x0 = x0;
        this.z0 = z0;
        this.baseY = baseY;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.form = form;
        this.module = module;
        this.floorHeight = floorHeight;
        this.bandHeight = bandHeight;
        this.setback = setback;
        this.armGap = armGap;
        this.armThickness = armThickness;
        this.plinth = plinth;
        this.parapet = parapet;
        this.raw = raw;
        this.lift = lift;
        this.columnGap = columnGap;
        this.columnWidth = columnWidth;
        this.palette = palette;
        this.decayScale = decayScale;
        this.decaySalt = decaySalt;
        this.decayAt = decayAt;
        this.boxes = boxes;
        this.stair = stair;
    }

    /**
     * 組合體的一塊：局部座標的長方體，可以再斜切一刀。
     *
     * <p>{@code cut} 的每一種都只是一個線性不等式，所以「斜面」不需要任何三角函數，
     * 也不會在區塊邊界上出現半格的誤差。
     */
    public record Box(int u0, int v0, int h0, int u1, int v1, int h1, int cut) {

        static final int NONE = 0;
        static final int RISE_U = 1;      // 沿 +u 爬升的斜面
        static final int RISE_NU = 2;
        static final int RISE_V = 3;
        static final int RISE_NV = 4;
        static final int BATTER = 5;      // 四面同時往上收，一座平頂的方錐

        boolean has(int u, int v, int h) {
            if (u < u0 || u > u1 || v < v0 || v > v1 || h < h0 || h > h1) return false;

            int du = u1 - u0;
            int dv = v1 - v0;
            int dh = h1 - h0;
            if (dh <= 0) return true;

            return switch (cut) {
                // 交叉相乘代替除法：((h-h0)/dh) <= ((u-u0)/du)
                case RISE_U -> (long) (h - h0) * du <= (long) (u - u0) * dh;
                case RISE_NU -> (long) (h - h0) * du <= (long) (u1 - u) * dh;
                case RISE_V -> (long) (h - h0) * dv <= (long) (v - v0) * dh;
                case RISE_NV -> (long) (h - h0) * dv <= (long) (v1 - v) * dh;
                case BATTER -> {
                    int shrink = Math.min(du, dv) / 3;
                    int inset = shrink * (h - h0) / dh;
                    yield u - u0 >= inset && u1 - u >= inset
                            && v - v0 >= inset && v1 - v >= inset;
                }
                default -> true;
            };
        }
    }

    /** 問「這一柱的地面在哪個高度」。由生成器接到 {@link Ground#height} 上。 */
    @FunctionalInterface
    public interface Terrain {
        int heightAt(int wx, int wz);
    }

    /** 超級街廓的一塊切片：從組內 {@code (u,v)} 起算、佔 {@code w×d} 格。 */
    private record Piece(int u, int v, int w, int d) {
        boolean covers(int lu, int lv) {
            return lu >= u && lu < u + w && lv >= v && lv < v + d;
        }
    }

    /**
     * 這一格上有什麼。{@code null} ＝ 這一格被同一組裡別的錨點吃掉了，不是它自己蓋。
     *
     * @param group 超級街廓的亂數（決定怎麼切），同一組的四格必須拿到同一個
     * @param build 這一格自己的亂數（決定蓋成什麼樣）
     * @param terrain 地面高度，量體要坐在上面
     */
    public static Plot roll(RandomSource group, RandomSource build, Settings s,
                            int cellX, int cellZ, Terrain terrain) {
        int groupX = Math.floorDiv(cellX, GROUP);
        int groupZ = Math.floorDiv(cellZ, GROUP);
        int lu = cellX - groupX * GROUP;
        int lv = cellZ - groupZ * GROUP;

        Piece piece = pieceAt(group, lu, lv);
        // 只有錨點那一格負責蓋，其餘幾格交給它
        if (piece.u() != lu || piece.v() != lv) return null;

        if (build.nextFloat() > s.density()) return null;

        Form form = pickForm(build);

        int originX = (groupX * GROUP + piece.u()) * s.cell();
        int originZ = (groupZ * GROUP + piece.v()) * s.cell();
        int spanX = piece.w() * s.cell() - s.street() * 2;
        int spanZ = piece.d() * s.cell() - s.street() * 2;

        int width;
        int depth;
        if (form == Form.PERFORATED) {
            // 穿孔牆要又寬又薄，不然孔洞讀起來像窗戶而不是貫穿的洞
            width = spanX - build.nextInt(Math.max(1, spanX / 4));
            depth = 8 + build.nextInt(10);
        } else {
            width = spanX * 7 / 10 + build.nextInt(spanX * 3 / 10 + 1);
            depth = spanZ * 7 / 10 + build.nextInt(spanZ * 3 / 10 + 1);
        }

        int x0 = originX + s.street() + build.nextInt(Math.max(1, spanX - width + 1));
        int z0 = originZ + s.street() + build.nextInt(Math.max(1, spanZ - depth + 1));

        // 坐在自己腳下的地形上。取中心而不是平均或最低點：中心最能代表這塊地的高度，
        // 而低於它的部分會由生成器往下補基座（見 footprintSolid），所以不會浮空
        int baseY = terrain.heightAt(x0 + width / 2, z0 + depth / 2) + 1;

        int height = pickHeight(build, s, form);
        height = Math.min(height, s.ceiling() - baseY);
        if (height < 8) return null;

        int module = 6 + build.nextInt(9);
        int lift = build.nextInt(4) == 0 ? 8 + build.nextInt(10) : 0;
        Masonry.Palette palette = Masonry.roll(build);
        Box[] boxes = form == Form.ASSEMBLY
                ? rollBoxes(build, width, depth, height)
                : NO_BOXES;
        Stair stair = rollStair(build, form, width, depth, s.street());

        return new Plot(
                x0, z0,
                baseY, width, depth, height, form,
                module,
                5 + build.nextInt(4),
                Math.max(8, height / (3 + build.nextInt(4))),
                3 + build.nextInt(6),
                26 + build.nextInt(20),
                12 + build.nextInt(10),
                4 + build.nextInt(8),
                2 + build.nextInt(4),
                build.nextInt(3) == 0,
                lift, module * 2, Math.max(3, module / 2),
                palette,
                12 + build.nextInt(9),
                build.nextInt(),
                0.76f + build.nextFloat() * 0.10f,
                boxes, stair);
    }

    /**
     * 擲一座外掛樓梯。
     *
     * <p>只掛在**牆面是垂直的**形狀上。梯形、倒梯形、圓筒的牆會隨高度往內或往外移動，
     * 樓梯要嘛跟著歪掉（它是折返梯，歪了就不是折返梯），要嘛爬到一半離開牆面飄在空中。
     * 兩種都比沒有樓梯難看，所以這裡用擺放規則把問題消掉，而不是想辦法讓樓梯貼著曲面走。
     */
    private static Stair rollStair(RandomSource r, Form form, int width, int depth, int street) {
        if (form != Form.SLAB && form != Form.PERFORATED && form != Form.CROSS) return null;
        if (r.nextInt(100) >= 45) return null;
        return Stair.roll(r, width, depth, street);
    }

    /**
     * 樓梯的四個局部方向（沿牆正負、離牆正負）在每個面上對應到哪個世界方向。
     *
     * <p>{@link Stair} 一個面都不必知道，它只說「往前」「往外」，換算全部在這張表裡。
     */
    private static final Direction[][] AXES = {
            {Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH},   // 北面
            {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH},   // 南面
            {Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST},   // 西面
            {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST},   // 東面
    };

    private static final Box[] NO_BOXES = new Box[0];

    /**
     * 擲一組組合體。
     *
     * <h3>兩種完全不同的結果</h3>
     * <p>四分之一的機率是**一整塊斜切的巨石**——整棟樓就是一個斜面，什麼都不做。
     * 這種最接近 Niemeyer 那類把一片混凝土直接插進地裡的東西，而它需要的正是「沒有細節」。
     *
     * <p>其餘是**一座基座上長出好幾根**：先鋪一塊佔滿平面的低基座，再往上疊。
     * 基座不是造型，是**結構保證**——沒有它，後面每一塊都得自己證明底下有東西撐，
     * 而那個檢查在純函數裡做不到（它會變成互相參照）。
     *
     * <h3>為什麼疊上去的那些要咬住前一塊</h3>
     * <p>離地起算的方塊如果隨機擺，會擲出浮在空中的量體。讓它在平面上壓住前一塊，
     * 它就一定接得到地面——代價是形體會沿著一條鏈往一個方向長，而那剛好就是想要的懸挑。
     */
    private static Box[] rollBoxes(RandomSource r, int width, int depth, int height) {
        if (r.nextInt(4) == 0) {
            return new Box[]{new Box(0, 0, 0, width - 1, depth - 1, height - 1, 1 + r.nextInt(5))};
        }

        int n = 3 + r.nextInt(4);
        Box[] boxes = new Box[n];

        int baseH = Math.max(6, height / (4 + r.nextInt(4)));
        boxes[0] = new Box(0, 0, 0, width - 1, depth - 1, baseH,
                r.nextInt(5) == 0 ? Box.BATTER : Box.NONE);

        for (int i = 1; i < n; i++) {
            int w = Math.max(8, width * (30 + r.nextInt(35)) / 100);
            int d = Math.max(8, depth * (30 + r.nextInt(35)) / 100);

            int u0;
            int v0;
            int h0;
            if (i == 1 || r.nextInt(2) == 0) {
                u0 = r.nextInt(Math.max(1, width - w));
                v0 = r.nextInt(Math.max(1, depth - d));
                h0 = 0;
            } else {
                // 咬住前一塊：起點落在它的平面範圍內，偏移量最多半塊，挑出去的那半塊就是懸挑
                Box on = boxes[i - 1];
                u0 = clamp(on.u0() + r.nextInt(Math.max(1, on.u1() - on.u0())) - w / 2, width - w);
                v0 = clamp(on.v0() + r.nextInt(Math.max(1, on.v1() - on.v0())) - d / 2, depth - d);
                h0 = Math.min(on.h1(), on.h0() + Math.max(4, (on.h1() - on.h0()) * (40 + r.nextInt(60)) / 100));
            }

            h0 = Math.min(h0, Math.max(0, height - 12));     // 留得下一塊有意義的高度
            int h1 = Math.min(height - 1, h0 + Math.max(10, height * (25 + r.nextInt(70)) / 100));
            boxes[i] = new Box(u0, v0, h0, u0 + w - 1, v0 + d - 1, h1,
                    r.nextInt(3) == 0 ? 1 + r.nextInt(5) : Box.NONE);
        }
        return boxes;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(Math.max(0, max), value));
    }

    /**
     * 把 2×2 的超級街廓切成幾塊長方形。
     *
     * <p>切法是**整組一起決定**的，所以同一組的四格問出來的答案一定一致，而不同組之間
     * 根本不會互相參照——這就是不必解衝突的原因。
     *
     * <p>權重刻意讓「四棟各自獨立」只佔三成：要的是大小差異，全部都 1×1 就沒有差異了。
     */
    private static List<Piece> partition(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 30) {
            return List.of(new Piece(0, 0, 1, 1), new Piece(1, 0, 1, 1),
                    new Piece(0, 1, 1, 1), new Piece(1, 1, 1, 1));
        }
        if (roll < 46) return List.of(new Piece(0, 0, 2, 2));                        // 整組一棟
        if (roll < 58) return List.of(new Piece(0, 0, 2, 1), new Piece(0, 1, 2, 1));  // 兩條橫的
        if (roll < 70) return List.of(new Piece(0, 0, 1, 2), new Piece(1, 0, 1, 2));  // 兩條直的
        if (roll < 78) return List.of(new Piece(0, 0, 2, 1), new Piece(0, 1, 1, 1), new Piece(1, 1, 1, 1));
        if (roll < 86) return List.of(new Piece(0, 1, 2, 1), new Piece(0, 0, 1, 1), new Piece(1, 0, 1, 1));
        if (roll < 93) return List.of(new Piece(0, 0, 1, 2), new Piece(1, 0, 1, 1), new Piece(1, 1, 1, 1));
        return List.of(new Piece(1, 0, 1, 2), new Piece(0, 0, 1, 1), new Piece(0, 1, 1, 1));
    }

    private static Piece pieceAt(RandomSource r, int lu, int lv) {
        for (Piece piece : partition(r)) {
            if (piece.covers(lu, lv)) return piece;
        }
        // 上面每一種切法都覆蓋滿 2×2，走到這裡表示 partition 漏了一塊
        throw new IllegalStateException("partition does not cover (" + lu + "," + lv + ")");
    }

    /**
     * 高度。
     *
     * <p>不是均勻分布：平方之後大部分落在低段、少數衝到頂。均勻分布會讓每棟都差不多高，
     * 天際線變成一條平的線——而「巨大」是比出來的，要有矮的東西當對照才看得出高。
     */
    private static int pickHeight(RandomSource r, Settings s, Form form) {
        int t = r.nextInt(100);
        int height = s.minHeight() + (s.maxHeight() - s.minHeight()) * t * t / 9801;
        return form == Form.PERFORATED ? Math.min(s.maxHeight(), height + 60) : height;
    }

    /**
     * 這一格是什麼方塊，{@code null} ＝ 空氣。
     *
     * <p>吃世界座標而不是局部座標：呼叫端（區塊填充、高度圖、柱體取樣）拿到的都是世界座標，
     * 換算放在這裡只要寫一次。
     */
    public BlockState blockAt(int wx, int wy, int wz) {
        int u = wx - x0;
        int v = wz - z0;
        int h = wy - baseY;

        BlockState mass = massAt(u, v, h, wx, wy, wz);
        if (mass != null) return mass;
        return stair == null ? null : stairAt(u, v, h, wx, wy, wz);
    }

    /**
     * 把世界座標換成樓梯自己的三個數字：沿牆走多遠、離牆多遠、離底多高。
     *
     * <p>四個面的差別全部收在這個 switch 裡，{@link Stair} 那邊一個面都不必知道。
     */
    private BlockState stairAt(int u, int v, int h, int wx, int wy, int wz) {
        int a;
        int b;
        switch (stair.face()) {
            case 0 -> { a = u; b = -v; }                    // 北面
            case 1 -> { a = u; b = v - (depth - 1); }       // 南面
            case 2 -> { a = v; b = -u; }                    // 西面
            default -> { a = v; b = u - (width - 1); }      // 東面
        }
        return stair.blockAt(a - stair.along(), b, h, height - 1, this, wx, wy, wz,
                AXES[stair.face()]);
    }

    private BlockState massAt(int u, int v, int h, int wx, int wy, int wz) {
        if (!solid(u, v, h)) return null;
        if (carved(u, v, h)) return null;

        float decay = Masonry.grain(wx, wy, wz, decayScale, decayScale, decaySalt);
        if (decay >= decayAt && bitten(u, v, h, decay)) return null;
        // 破口的邊緣：還沒垮掉、但已經碎了。同一個雜訊值順手用掉，不必再算一次
        if (decay >= decayAt - 0.05f) return Masonry.COBBLE;

        return skin(wx, wy, wz);
    }

    /**
     * 這一格會不會被啃掉。
     *
     * <p>先看雜訊、**再**看深度：破損很稀少，絕大多數的格子在第一個判斷就結束了，
     * 而深度判斷要問四五次量體，貴得多。順序反過來的話每一格都要付那個代價。
     *
     * <p>愈深要愈高的雜訊值才啃得動，所以破口會往內收成一個凹坑，而不是一根貫穿的洞。
     */
    private boolean bitten(int u, int v, int h, float decay) {
        if (exposed(u, v, h, 2)) return true;
        if (decay < decayAt + 0.06f) return false;
        return exposed(u, v, h, 5);
    }

    /** 往四周與上方看 {@code reach} 格，只要有一邊不是實體就算靠近表面。 */
    private boolean exposed(int u, int v, int h, int reach) {
        return !solid(u - reach, v, h) || !solid(u + reach, v, h)
                || !solid(u, v - reach, h) || !solid(u, v + reach, h)
                || !solid(u, v, h + reach);
    }

    /** 這棟樓的材料。基座也用它，基座本來就是同一棟樓的一部分。 */
    public BlockState skin(int wx, int wy, int wz) {
        return palette.at(wx, wy, wz);
    }

    /**
     * 這一柱在量體的**最底層**有沒有實體。
     *
     * <p>生成器靠它決定哪裡要往下補基座。用最底層而不是外接矩形：圓筒的基座才會是圓的，
     * 架空層的基座才會只在柱子底下——那正好是柱子該落地的地方。
     */
    public boolean footprintSolid(int wx, int wz) {
        return solid(wx - x0, wz - z0, 0);
    }

    /**
     * 區塊填充要從哪個高度開始掃。
     *
     * <p>比 {@link #minY()} 低，因為樓梯會往下折到地面（見 {@link Stair#DIG}）。
     * {@code minY} 本身不能動：基座是填到量體的底，不是填到樓梯的底。
     */
    public int scanFloor() {
        return stair == null ? baseY : baseY - Stair.DIG;
    }

    /** 這一柱在不在這棟樓的水平範圍內。 */
    public boolean coversColumn(int wx, int wz) {
        return wx >= x0 && wx <= maxX() && wz >= z0 && wz <= maxZ();
    }

    /** 量體本身（含架空層），不含立面開窗。 */
    private boolean solid(int u, int v, int h) {
        if (u < 0 || v < 0 || h < 0 || u >= width || v >= depth || h >= height) return false;

        if (lift > 0 && h < lift) {
            // 架空層：把量體抬起來，底下只留柱子。柱子必須落在量體的投影範圍內，
            // 不然會出現撐著空氣的柱子
            boolean column = Math.floorMod(u, columnGap) < columnWidth
                    && Math.floorMod(v, columnGap) < columnWidth;
            return column && form.mass(u, v, lift, this);
        }
        return form.mass(u, v, h, this);
    }

    /**
     * 立面：每隔一層挖一條水平的窗帶，再由垂直的立柱打斷。
     *
     * <p>只挖靠近垂直外表面的地方——深處保持實心，量體才有重量。判斷「靠不靠近表面」是
     * 直接問三格外還是不是實心，這對任何形狀都成立，不必為每種形狀各寫一次。
     */
    private boolean carved(int u, int v, int h) {
        if (raw || form == Form.PERFORATED) return false;   // 它的洞是量體自己的事
        if (h < plinth || h >= height - parapet) return false;
        if (Math.floorMod(h - plinth, floorHeight) >= 2) return false;
        if (Math.floorMod(u, module) < 2 || Math.floorMod(v, module) < 2) return false;

        return !solid(u - 3, v, h) || !solid(u + 3, v, h)
                || !solid(u, v - 3, h) || !solid(u, v + 3, h);
    }

    /**
     * 外框。
     *
     * <p>**含樓梯**：樓梯長在量體外面，外框不撐大的話，區塊填充根本不會掃到它那幾柱，
     * 高度圖也會回報那裡是空地。
     */
    public int minX() { return x0 - out(2); }
    public int minZ() { return z0 - out(0); }
    public int maxX() { return x0 + width - 1 + out(3); }
    public int maxZ() { return z0 + depth - 1 + out(1); }

    /** 這個面往外伸出去幾格。 */
    private int out(int face) {
        return stair != null && stair.face() == face ? stair.reach() : 0;
    }
    public int minY() { return baseY; }
    public int maxY() { return baseY + height - 1; }

    int width() { return width; }
    int depth() { return depth; }
    int height() { return height; }
    int module() { return module; }
    int bandHeight() { return bandHeight; }
    int setback() { return setback; }
    int armGap() { return armGap; }
    int armThickness() { return armThickness; }
    int plinth() { return plinth; }

    Box[] boxes() { return boxes; }

    /**
     * <p>組合體佔了將近三成，是單一形狀裡最多的一種——因為它其實不是一種形狀，而是一個
     * 形狀的**家族**，同樣的權重下它給出的變化比其他五種加起來還多。
     *
     * <p>其他幾種沒有被壓縮太多：單純的量體本身是好的，一整片都是複雜的組合體，
     * 複雜就變成新的均質。
     */
    private static Form pickForm(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 28) return Form.ASSEMBLY;
        if (roll < 48) return Form.SLAB;
        if (roll < 60) return Form.ZIGGURAT;
        if (roll < 72) return Form.INVERTED;
        if (roll < 85) return Form.CROSS;
        if (roll < 93) return Form.PERFORATED;
        return Form.CYLINDER;   // 圓筒最少，它的作用是打斷網格，多了就不特別了
    }

}
