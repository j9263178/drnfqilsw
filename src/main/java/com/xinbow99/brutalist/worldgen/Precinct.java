package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * 一整格街廓不蓋樓，改成鋪面的公共空間：廣場，或廢棄的公車總站。
 *
 * <h2>為什麼佔一整格，而不是塞在街道裡</h2>
 * <p>廣場與月台都需要**大片連續的空地**，而街道只有二十格寬。硬塞的話尺寸得縮到十分之一，
 * 那就不是廣場，是安全島。
 *
 * <p>佔一整格還有一個好處：它是 {@link Plot} 的一種用途，而不是另一套擺放系統。
 * 街廓的切分、基座的填法、外框的計算、快取全部照舊，一行碰撞檢查都不用加——
 * 它天生就不會跟任何東西重疊，因為那一格已經被它佔走了。
 *
 * <h2>它讓天際線有洞</h2>
 * <p>整片都是三百格高的量體時，「高」會失去意義。空出來的那一格是**對照組**：
 * 站在廣場中間，四周的樓才第一次量得出來有多大。
 */
public final class Precinct {

    public static final int PLAZA = 0;
    public static final int DEPOT = 1;
    /** 冷卻塔群。見 {@link #tower}。 */
    public static final int PLANT = 2;
    /**
     * 空地上的一座舒霍夫式水塔。
     *
     * <p>它佔一整格街廓**只為了空出那一格**：塔本身只有二十幾格寬，剩下的一百多格
     * 是刻意留白的。孤獨是這座塔的內容，而孤獨只能用周圍的空來寫。
     */
    public static final int TOWER = 3;

    private static final int FLAT = 0;
    private static final int BARREL = 1;
    private static final int SAWTOOTH = 2;
    private static final int NO_ROOF = 3;

    private static final BlockState BALLAST = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState CANOPY = Blocks.SMOOTH_STONE.defaultBlockState();

    private final int kind;
    private final int width;
    private final int depth;

    // ---- 廣場
    private final boolean round;
    private final int sculptKind;
    private final int sculptHeight;
    private final int sculptReach;

    // ---- 公車總站
    private final boolean alongX;
    private final int platforms;
    private final int platformWidth;
    private final int gauge;
    private final int roofY;
    private final int roofKind;

    // ---- 冷卻塔群
    private final Tower[] towers;

    // ---- 水塔
    private final WaterTower mast;

    private final int salt;

    /**
     * 一座冷卻塔。
     *
     * @param throatR 喉部（最細處）的半徑，{@code baseR} 是裙擺、{@code topR} 是塔口
     * @param throatH 喉部的高度。真的冷卻塔喉部靠近頂端，不在中間——
     *                擺在中間會變成沙漏，擺在上面才是冷卻塔
     * @param legH    底下那圈斜撐有多高
     */
    private record Tower(int u, int v, int throatR, int baseR, int topR,
                         int height, int throatH, int legH) {
    }

    private Precinct(int kind, int width, int depth, boolean round,
                     int sculptKind, int sculptHeight, int sculptReach,
                     boolean alongX, int platforms, int platformWidth, int gauge, int roofY,
                     int roofKind, Tower[] towers, WaterTower mast, int salt) {
        this.kind = kind;
        this.width = width;
        this.depth = depth;
        this.round = round;
        this.sculptKind = sculptKind;
        this.sculptHeight = sculptHeight;
        this.sculptReach = sculptReach;
        this.alongX = alongX;
        this.platforms = platforms;
        this.platformWidth = platformWidth;
        this.gauge = gauge;
        this.roofY = roofY;
        this.roofKind = roofKind;
        this.towers = towers;
        this.mast = mast;
        this.salt = salt;
    }

    public static Precinct roll(RandomSource r, int kind, int width, int depth) {
        int salt = r.nextInt();
        if (kind == PLAZA) {
            // 雕像的尺度跟廣場掛勾：一座小廣場配一根七十格高的碑會變成碑旁邊有塊空地
            int reach = Math.clamp(Math.min(width, depth) / 7, 5, 16);
            return new Precinct(PLAZA, width, depth,
                    r.nextInt(100) < 45,
                    Math.floorMod(r.nextInt(), 6), reach * 3 + r.nextInt(reach * 3), reach,
                    false, 0, 0, 0, 0, 0, NO_TOWERS, null, salt);
        }

        if (kind == TOWER) {
            return new Precinct(TOWER, width, depth, false, 0, 0, 0,
                    false, 0, 0, 0, 0, 0, NO_TOWERS, WaterTower.roll(r), salt);
        }

        if (kind == PLANT) {
            return new Precinct(PLANT, width, depth, false, 0, 0, 0,
                    false, 0, 0, 0, 0, 0, rollTowers(r, width, depth), null, salt);
        }

        boolean alongX = r.nextInt(2) == 0;
        int span = alongX ? depth : width;                 // 月台**橫向**排開的方向
        // 月台與軌道區都加倍。原本五到八格寬的月台站上去像月台的模型——
        // 真的月台要能同時站下一整列車的人，而這座車站的表情就是它空得離譜
        int platformWidth = 10 + r.nextInt(8);
        int gauge = 10 + r.nextInt(6);
        int platforms = Math.clamp(span / (platformWidth + gauge), 2, 5);
        // 屋頂訂在二十二到三十五格。原本七到十格，站在月台上像頂著天花板——
        // 火車站的大棚本來就是**空曠**的，那個高度是它唯一的表情
        return new Precinct(DEPOT, width, depth, false, 0, 0, 0,
                alongX, platforms, platformWidth, gauge,
                22 + r.nextInt(14), r.nextInt(4), NO_TOWERS, null, salt);
    }

    private static final Tower[] NO_TOWERS = new Tower[0];

    /**
     * 排一群冷卻塔。
     *
     * <p>**一定要成群。** 一座孤立的冷卻塔是個地標，一群才是發電廠——而且群體才量得出
     * 單體有多大：後面那座被前面那座遮掉一半的時候，尺度才成立。
     *
     * <p>排成鬆散的格子而不是隨機丟：真的電廠就是排的（機組一組一座），
     * 而且隨機丟在一百四十格見方裡，兩座重疊的機率高到得寫排斥檢查。
     */
    private static Tower[] rollTowers(RandomSource r, int width, int depth) {
        // 排成格子，而不是排成一列。一列四座的話每座只分到街廓的四分之一寬，
        // 直徑掉到二十格——那是煙囪不是冷卻塔。這種東西的全部就是它的量體，
        // 寧可少擺兩座也要讓每一座夠大
        int roll = r.nextInt(100);
        int cols = roll < 30 ? 1 : 2;
        int rows = roll < 30 || roll >= 70 ? 2 : (roll < 50 ? 2 : 3);

        int cw = width / cols;
        int cd = depth / rows;
        int baseR = Math.clamp(Math.min(cw, cd) / 2 - 5, 12, 30);

        Tower[] out = new Tower[cols * rows];
        int i = 0;
        for (int a = 0; a < cols; a++) {
            for (int b = 0; b < rows; b++) {
                // 抖一點點，免得排成一個完美的矩陣；抖太多會撞在一起
                int u = cw / 2 + a * cw + r.nextInt(7) - 3;
                int v = cd / 2 + b * cd + r.nextInt(7) - 3;
                int rr = baseR - r.nextInt(Math.max(1, baseR / 6));

                // 矮胖：高度大約是直徑的一點二到一點六倍。真的冷卻塔比這個瘦，
                // 但在遊戲裡瘦的塔遠看會變成一根柱子
                int height = rr * 2 * (120 + r.nextInt(45)) / 100;
                int throat = rr * (60 + r.nextInt(10)) / 100;

                out[i++] = new Tower(u, v, throat, rr, throat + Math.max(2, rr / 6),
                        height, height * (74 + r.nextInt(8)) / 100,
                        Math.max(7, height / 7));
            }
        }
        return out;
    }

    /** 這一格的鋪面蓋到哪。基座要靠它決定往下補到哪裡。 */
    public boolean covers(int u, int v) {
        if (u < 0 || v < 0 || u >= width || v >= depth) return false;
        if (kind == TOWER) {
            // 只整平塔腳那一小圈，其餘一百多格保持原本的土地——那片空地就是這一格的內容
            int du = u - width / 2;
            int dv = v - depth / 2;
            int rr = mast.footing();
            return du * du + dv * dv <= rr * rr;
        }
        if (kind == PLANT) {
            // **只蓋住每座塔腳下那一圈。** 整格鋪成水泥的話，這群塔會像被放在一塊
            // 標示用的底板上——而它們該是坐在土地上的。塔底仍然要整平一小塊，
            // 不然斜撐不是浮空就是被土埋掉
            for (Tower t : towers) {
                int du = u - t.u();
                int dv = v - t.v();
                int rr = t.baseR() + 4;
                if (du * du + dv * dv <= rr * rr) return true;
            }
            return false;
        }
        if (kind == PLAZA && round && !inEllipse(u, v, 0)) return false;
        return !eaten(u, v);
    }

    /** 土地最多能往裡面吃幾格。 */
    private static final int BITE = 30;

    /**
     * 這一格的鋪面有沒有被土地吃掉。
     *
     * <p>廣場與月台原本是一塊**邊界完美的長方形水泥板**，而完美的邊界正是它看起來假的原因：
     * 現實裡沒有人在荒地中央鋪出一塊四角筆直的板，就算有，二十年後草也從外圈長回來了。
     *
     * <p>所以侵蝕從**離邊界的距離**算起：外圈最深可以被吃掉三十格，愈往裡愈安全，
     * 中心永遠留著。深度由一份平滑雜訊決定，所以吃進來的是一片一片的舌狀，
     * 不是一圈等寬的環——後者只是把長方形縮小，邊界還是完美的。
     *
     * <p>吃掉的地方 {@link #covers} 回報 false，於是那裡不整地、不鋪面、地表照長草，
     * 而水泥板的斷口就成了廣場的新邊界。
     */
    private boolean eaten(int u, int v) {
        int edge = edgeDistance(u, v);
        if (edge >= BITE) return false;

        // 兩層尺度：大的決定舌頭在哪，小的把斷口咬碎，不然邊緣會是一條滑順的曲線
        float broad = Masonry.grain(u, 0, v, 34, 34, salt ^ 0x51E3);
        float fine = Masonry.grain(u, 0, v, 11, 11, salt ^ 0x77B9);
        float n = broad * 0.72f + fine * 0.28f;

        // 拉開對比：平滑雜訊的值擠在 0.5 附近，直接乘上去的話每個地方都吃掉一半，
        // 又變成一圈等寬的環
        float reach = Math.clamp((n - 0.36f) * 2.4f, 0f, 1f);
        return edge < reach * BITE;
    }

    /**
     * 離鋪面邊界幾格。
     *
     * <p>圓形廣場要用徑向的距離：用長方形的四邊算，四個角會被當成離邊界很遠，
     * 但那四個角根本不在廣場上。
     */
    private int edgeDistance(int u, int v) {
        if (kind == PLAZA && round) {
            double a = Math.max(1, width / 2.0);
            double b = Math.max(1, depth / 2.0);
            double du = (u - a) / a;
            double dv = (v - b) / b;
            return (int) Math.round((1.0 - Math.sqrt(du * du + dv * dv)) * Math.min(a, b));
        }
        return Math.min(Math.min(u, v), Math.min(width - 1 - u, depth - 1 - v));
    }

    /** 要往上掃幾格。 */
    public int top() {
        if (kind == TOWER) return mast.top();
        if (kind == PLAZA) return sculptHeight + 3;
        if (kind != PLANT) return roofY + 9;
        int tallest = 12;
        for (Tower t : towers) tallest = Math.max(tallest, t.height());
        return tallest + 3;
    }

    /** 軌道區比月台低幾格。差距小於三看不出來，大於四就跳不上去也走不下去。 */
    private static final int TRENCH = 3;

    /**
     * 這一柱的**地面高度**相對於鋪面基準面差幾格。
     *
     * <p>整個場區都貼著地走，所以基準面就是地面；軌道區則是往下挖出來的。這個位移交給
     * 生成器的 {@code land()}，讓地表生成本身就把溝挖好——高度圖、柱體取樣、基座
     * 全部自動跟著走，不必事後再挖一次空氣。
     */
    public int levelAt(int u, int v) {
        if (kind == PLANT) {
            // 塔裡面是水池，往下挖兩格
            for (Tower t : towers) {
                int du = u - t.u();
                int dv = v - t.v();
                if (du * du + dv * dv <= (t.baseR() - 2) * (t.baseR() - 2)) return -2;
            }
            return 0;
        }
        if (kind != DEPOT) return 0;
        int across = alongX ? v : u;
        int pitch = platformWidth + gauge;
        if (Math.floorDiv(across, pitch) >= platforms) return 0;
        return Math.floorMod(across, pitch) < platformWidth ? 0 : -TRENCH;
    }

    public int kind() {
        return kind;
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param h 相對鋪面的高度，0 就是鋪面本身
     */
    public BlockState blockAt(int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        // 水塔不吃 covers()：那個遮罩是**整地範圍**（只有塔腳那一小圈），
        // 而水箱比塔腳寬得多。拿它當繪製遮罩的話，整顆頭會被裁掉只剩塔身
        if (kind == TOWER) {
            return h > top() ? null : mast.blockAt(u - width / 2, v - depth / 2, h);
        }
        if (!covers(u, v) || h < -TRENCH - 2 || h > top()) return null;
        return switch (kind) {
            case PLAZA -> plaza(u, v, h, plot, wx, wy, wz);
            case PLANT -> plant(u, v, h, plot, wx, wy, wz);
            default -> depot(u, v, h, plot, wx, wy, wz);
        };
    }

    // ------------------------------------------------------------------ 廣場

    private BlockState plaza(int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        if (h == 0) return plot.skin(wx, wy, wz);

        // 原本沿著外圈立一圈矮牆當緣石，本意是給廣場一個邊界。
        // 但邊界現在是**土地咬出來的斷口**（見 eaten），那個斷口本身就是邊界，
        // 而且比一圈規整的矮牆好看得多——矮牆只會把「這是一塊鋪好的板」再強調一次
        int dt = u - width / 2;
        int o = v - depth / 2;
        return Folly.at(dt, o, h - 1, sculptReach, sculptHeight, salt,
                plot.palette(), wx, wy, wz);
    }

    private boolean inEllipse(int u, int v, int inset) {
        double a = width / 2.0 - inset;
        double b = depth / 2.0 - inset;
        if (a <= 1 || b <= 1) return false;
        double du = (u - width / 2.0) / a;
        double dv = (v - depth / 2.0) / b;
        return du * du + dv * dv <= 1.0;
    }

    // ------------------------------------------------------------------ 冷卻塔群

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /**
     * 冷卻塔群。
     *
     * <p>場區**不鋪面**：塔直接坐在土地上，只有塔腳下那一圈被整平（見 {@link #covers}）。
     * 這幾座東西的份量來自它們自己，不需要一塊水泥底板來宣告「這裡是廠區」。
     */
    private BlockState plant(int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        for (Tower t : towers) {
            BlockState state = tower(t, u, v, h, plot, wx, wy, wz);
            if (state != null) return state;
        }
        return null;
    }

    /**
     * 一座冷卻塔：雙曲面的薄殼，底下一圈倒 V 斜撐，裡面一池水。
     *
     * <h3>殼是空的</h3>
     * <p>真的冷卻塔就是一層十幾公分的殼，而做成實心會浪費掉這個題材最好的一件事：
     * 人可以從斜撐之間走進去，站在裡面抬頭看那個口。整座建築裡沒有別的地方給得出這個。
     *
     * <h3>為什麼喉部在上面而不是中間</h3>
     * <p>擺在中間會變成沙漏——上下對稱、沒有方向。真的冷卻塔喉部在七成半高的地方，
     * 所以裙擺很長很緩、塔口很短很急，側影是有重心的。
     */
    private BlockState tower(Tower t, int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        int du = u - t.u();
        int dv = v - t.v();
        int reach = t.baseR() + 2;
        int d2 = du * du + dv * dv;
        if (d2 > reach * reach || h > t.height()) return null;

        double d = Math.sqrt(d2);

        // 塔內的水池。水面是絕對高度（就是鋪面基準面），所以它一定是平的
        if (h <= 0) {
            return h >= -2 && d <= t.baseR() - 3 ? WATER : null;
        }

        if (h < t.legH()) {
            int ring = radiusAt(t, t.legH());
            if (Math.abs(d - ring) > 1.6) return null;

            // 倒 V：兩族斜柱在殼底交會成一個尖，往下各自岔開，相鄰兩個尖之間的
            // 兩支腳在地面會合——連起來就是照片裡那一圈鋸齒
            int pitch = Math.max(5, ring / 4);
            int lean = (t.legH() - h) * pitch / Math.max(1, t.legH());
            long arc = Math.round(Math.atan2(dv, du) * ring);
            boolean strut = Math.floorMod(arc - lean, 2L * pitch) < 2
                    || Math.floorMod(arc + lean, 2L * pitch) < 2;
            return strut ? plot.skin(wx, wy, wz) : null;
        }

        return Math.abs(d - radiusAt(t, h)) <= 1.4 ? plot.skin(wx, wy, wz) : null;
    }

    /**
     * 塔殼在某個高度的半徑：真的雙曲線 {@code r = throat·√(1 + (dh/c)²)}。
     *
     * <p>本來用拋物線逼近，結果側影是「直筒加一個外撇的腳」——因為拋物線的收分
     * 全部擠在離喉部最遠的地方。雙曲線遠離喉部時是**線性**的，所以裙擺是一路斜下來的斜線，
     * 那才是冷卻塔的側影；曲率只集中在喉部附近的那一小段。
     *
     * <p>{@code c} 由「裙擺要正好收到 baseR」反推，喉部上下各算一個——
     * 下面很長很緩、上面很短很急，側影才有重心。
     */
    private static int radiusAt(Tower t, int h) {
        int dh = h - t.throatH();
        int span = Math.max(1, dh < 0 ? t.throatH() : t.height() - t.throatH());
        int edge = Math.max(t.throatR() + 1, dh < 0 ? t.baseR() : t.topR());

        double throat = t.throatR();
        // c² = span²·throat² / (edge² − throat²)
        double c2 = (double) span * span * throat * throat
                / ((double) edge * edge - throat * throat);
        return (int) Math.round(throat * Math.sqrt(1.0 + dh * dh / c2));
    }

    // ------------------------------------------------------------------ 公車總站

    /**
     * 幾道月台並排，中間夾著已經鏽掉的軌道。
     *
     * <p>月台之間**一定要有東西**，不然它讀起來只是幾條莫名其妙的長條。軌道是最省事也最
     * 明確的答案：一看就知道這裡本來停的是什麼。
     */
    private BlockState depot(int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        int across = alongX ? v : u;
        int along = alongX ? u : v;
        int pitch = platformWidth + gauge;
        int band = Math.floorMod(across, pitch);
        int index = Math.floorDiv(across, pitch);

        if (index >= platforms) {
            // 月台之外的場鋪面
            return h == 0 ? plot.skin(wx, wy, wz) : roof(across, along, h, plot, wx, wy, wz);
        }

        if (band < platformWidth) {
            // 月台。負的高度是它面向溝那一側的側牆——不包的話溝壁是裸露的土
            //
            // **台面是平的**。原本兩側邊緣各立一圈緣石，本意是月台的黃線，實際效果是
            // 中間那幾格讀成凹下去一格的溝，而且柱子被那一圈擋著只能從再上面一格開始長，
            // 看起來就是沒接到地。月台跟軌道之間已經有三格落差，邊界不需要再標一次
            if (h <= 0 && h >= -TRENCH) return plot.skin(wx, wy, wz);
            return roof(across, along, h, plot, wx, wy, wz);
        }

        // 下月台的階梯。沒有它，三格的落差只能用跳的下去、上不來
        BlockState step = steps(band, along, h, plot, wx, wy, wz);
        if (step != null) return step;

        if (h == -TRENCH) return BALLAST;
        if (h == -TRENCH + 1) {
            // 軌道區加寬之後中間只擺一條軌會顯得空，改成雙線——
            // 兩條軌之間那道空隙本來就是月台之間該有的東西
            int mid = platformWidth + gauge / 2;
            int off = gauge / 4;
            boolean onTrack = Math.abs(band - (mid - off)) <= 1
                    || Math.abs(band - (mid + off)) <= 1;
            if (onTrack
                    && Math.floorMod(Masonry.hash(along, index, salt), 100) >= 42) {
                return Blocks.RAIL.defaultBlockState().setValue(
                        BlockStateProperties.RAIL_SHAPE,
                        alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
            }
            return null;
        }
        return roof(across, along, h, plot, wx, wy, wz);
    }

    /**
     * 每隔一段，月台邊上切出兩級踏階通到軌道區。
     *
     * <p>用樓梯方塊，所以是真的走得下去也走得上來——三格的落差用整塊方塊做，玩家只能跳下去，
     * 然後困在溝裡。
     */
    private BlockState steps(int band, int along, int h, Plot plot, int wx, int wy, int wz) {
        if (Math.floorMod(along, 22) >= 2) return null;
        int into = band - platformWidth;                   // 離月台邊緣幾格
        if (into > 1) return null;
        if (h != -1 - into) return null;
        return Masonry.stairs(plot.skin(wx, wy, wz),
                alongX ? Direction.SOUTH : Direction.EAST);
    }

    /**
     * 大棚。
     *
     * <p>四種：平頂、筒形拱、鋸齒、以及沒有。一律平頂的話，每一座車站的天空都是同一片，
     * 而大棚幾乎是車站唯一會被記住的部分。
     */
    private BlockState roof(int across, int along, int h, Plot plot, int wx, int wy, int wz) {
        if (roofKind == NO_ROOF) return null;
        // 缺口用沿著月台的低頻雜訊，所以塌的是連續的一整段
        if (Masonry.grain(along, 0, 0, 26, 26, salt ^ 0x30F) > 0.68f) return null;

        int pitch = platformWidth + gauge;
        int used = platforms * pitch;
        int deck = deckAt(across, used);

        if (h == deck || h == deck + 1) return CANOPY;

        // 柱。從台面上**第一格**就開始長，不是第二格——差一格在三十格高的柱子上看起來
        // 就是浮著的。三格見方，不是一根一格的棍子
        if (h >= 1 && h < deck && Math.floorMod(across, pitch) < 3
                && Math.floorMod(along, 15) < 3) {
            return plot.skin(wx, wy, wz);
        }
        return null;
    }

    private int deckAt(int across, int used) {
        return switch (roofKind) {
            case BARREL -> {
                // 筒形拱：中央最高，往兩側落。用拋物線近似，差別在遊戲裡看不出來
                double a = Math.max(1, used / 2.0);
                double f = (across - a) / a;
                yield roofY - (int) Math.round(f * f * roofY * 0.34);
            }
            case SAWTOOTH -> roofY + Math.floorMod(across, platformWidth + gauge) * 7 / (platformWidth + gauge);
            default -> roofY;
        };
    }
}
