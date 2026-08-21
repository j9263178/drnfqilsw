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

    private static final int FLAT = 0;
    private static final int BARREL = 1;
    private static final int SAWTOOTH = 2;
    private static final int NO_ROOF = 3;

    private static final BlockState KERB = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState BALLAST = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState CANOPY = Blocks.SMOOTH_STONE.defaultBlockState();

    private final int kind;
    private final int width;
    private final int depth;

    // ---- 廣場
    private final boolean round;
    private final int kerb;
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

    private final int salt;

    private Precinct(int kind, int width, int depth, boolean round, int kerb,
                     int sculptKind, int sculptHeight, int sculptReach,
                     boolean alongX, int platforms, int platformWidth, int gauge, int roofY,
                     int roofKind, int salt) {
        this.kind = kind;
        this.width = width;
        this.depth = depth;
        this.round = round;
        this.kerb = kerb;
        this.sculptKind = sculptKind;
        this.sculptHeight = sculptHeight;
        this.sculptReach = sculptReach;
        this.alongX = alongX;
        this.platforms = platforms;
        this.platformWidth = platformWidth;
        this.gauge = gauge;
        this.roofY = roofY;
        this.roofKind = roofKind;
        this.salt = salt;
    }

    public static Precinct roll(RandomSource r, int kind, int width, int depth) {
        int salt = r.nextInt();
        if (kind == PLAZA) {
            // 雕像的尺度跟廣場掛勾：一座小廣場配一根七十格高的碑會變成碑旁邊有塊空地
            int reach = Math.clamp(Math.min(width, depth) / 7, 5, 16);
            return new Precinct(PLAZA, width, depth,
                    r.nextInt(100) < 45, 1 + r.nextInt(2),
                    Math.floorMod(r.nextInt(), 6), reach * 3 + r.nextInt(reach * 3), reach,
                    false, 0, 0, 0, 0, 0, salt);
        }

        boolean alongX = r.nextInt(2) == 0;
        int span = alongX ? depth : width;                 // 月台**橫向**排開的方向
        int platformWidth = 5 + r.nextInt(4);
        int gauge = 5 + r.nextInt(3);
        int platforms = Math.clamp(span / (platformWidth + gauge), 2, 5);
        // 屋頂訂在二十二到三十五格。原本七到十格，站在月台上像頂著天花板——
        // 火車站的大棚本來就是**空曠**的，那個高度是它唯一的表情
        return new Precinct(DEPOT, width, depth, false, 0, 0, 0, 0,
                alongX, platforms, platformWidth, gauge,
                22 + r.nextInt(14), r.nextInt(4), salt);
    }

    /** 這一格的鋪面蓋到哪。基座要靠它決定往下補到哪裡。 */
    public boolean covers(int u, int v) {
        if (u < 0 || v < 0 || u >= width || v >= depth) return false;
        if (kind != PLAZA || !round) return true;
        return inEllipse(u, v, 0);
    }

    /** 要往上掃幾格。 */
    public int top() {
        return kind == PLAZA ? sculptHeight + 3 : roofY + 9;
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
        if (!covers(u, v) || h < -TRENCH - 2 || h > top()) return null;
        return kind == PLAZA
                ? plaza(u, v, h, plot, wx, wy, wz)
                : depot(u, v, h, plot, wx, wy, wz);
    }

    // ------------------------------------------------------------------ 廣場

    private BlockState plaza(int u, int v, int h, Plot plot, int wx, int wy, int wz) {
        if (h == 0) return plot.skin(wx, wy, wz);

        // 緣石：一圈矮牆，廣場才有邊界。沒有它，鋪面會跟外面的土地糊在一起
        if (h <= kerb && rim(u, v)) return KERB;

        int dt = u - width / 2;
        int o = v - depth / 2;
        return Folly.at(dt, o, h - 1, sculptReach, sculptHeight, salt,
                plot.palette(), wx, wy, wz);
    }

    private boolean rim(int u, int v) {
        if (round) return inEllipse(u, v, 0) && !inEllipse(u, v, 2);
        return u <= 1 || v <= 1 || u >= width - 2 || v >= depth - 2;
    }

    private boolean inEllipse(int u, int v, int inset) {
        double a = width / 2.0 - inset;
        double b = depth / 2.0 - inset;
        if (a <= 1 || b <= 1) return false;
        double du = (u - width / 2.0) / a;
        double dv = (v - depth / 2.0) / b;
        return du * du + dv * dv <= 1.0;
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
            int mid = platformWidth + gauge / 2;
            if (Math.abs(band - mid) <= 1
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
