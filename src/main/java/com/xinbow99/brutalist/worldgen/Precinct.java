package com.xinbow99.brutalist.worldgen;

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

    private final int salt;

    private Precinct(int kind, int width, int depth, boolean round, int kerb,
                     int sculptKind, int sculptHeight, int sculptReach,
                     boolean alongX, int platforms, int platformWidth, int gauge, int roofY,
                     int salt) {
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
                    false, 0, 0, 0, 0, salt);
        }

        boolean alongX = r.nextInt(2) == 0;
        int span = alongX ? depth : width;                 // 月台**橫向**排開的方向
        int platformWidth = 5 + r.nextInt(4);
        int gauge = 5 + r.nextInt(3);
        int platforms = Math.clamp(span / (platformWidth + gauge), 2, 5);
        return new Precinct(DEPOT, width, depth, false, 0, 0, 0, 0,
                alongX, platforms, platformWidth, gauge,
                r.nextInt(100) < 65 ? 7 + r.nextInt(4) : 0, salt);
    }

    /** 這一格的鋪面蓋到哪。基座要靠它決定往下補到哪裡。 */
    public boolean covers(int u, int v) {
        if (u < 0 || v < 0 || u >= width || v >= depth) return false;
        if (kind != PLAZA || !round) return true;
        return inEllipse(u, v, 0);
    }

    /** 要往上掃幾格。 */
    public int top() {
        return kind == PLAZA ? sculptHeight + 3 : Math.max(4, roofY + 3);
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
        if (!covers(u, v) || h < 0 || h > top()) return null;
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
        if (h == 0) return plot.skin(wx, wy, wz);        // 整片的場鋪面

        int across = alongX ? v : u;                      // 橫過月台的方向
        int along = alongX ? u : v;
        int pitch = platformWidth + gauge;
        int band = Math.floorMod(across, pitch);
        int index = Math.floorDiv(across, pitch);
        if (index >= platforms) return null;

        if (band < platformWidth) {
            if (h <= 1) return plot.skin(wx, wy, wz);     // 月台面，高出場鋪面一格
            // 月台邊緣的黃線位置留一道矮緣石
            if (h == 2 && (band == 0 || band == platformWidth - 1)) return KERB;
            return roof(along, band, h, plot, wx, wy, wz);
        }

        // 軌道。道碴鋪滿，鋼軌斷斷續續——完整的一條軌道會讀成「還在用」
        if (h == 1) return BALLAST;
        if (h == 2) {
            int mid = platformWidth + gauge / 2;
            if (Math.abs(band - mid) > 1) return roof(along, band, h, plot, wx, wy, wz);
            if (Math.floorMod(Masonry.hash(along, index, salt), 100) < 42) return null;
            return Blocks.RAIL.defaultBlockState().setValue(
                    BlockStateProperties.RAIL_SHAPE,
                    alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
        }
        return roof(along, band, h, plot, wx, wy, wz);
    }

    /**
     * 雨棚。柱子每隔一段一根，棚面整片——但會**整段不見**，那才是廢棄的樣子。
     *
     * <p>缺口用沿著月台的低頻雜訊決定，所以塌的是連續的一整段，不是隨機的破洞。
     */
    private BlockState roof(int along, int band, int h, Plot plot, int wx, int wy, int wz) {
        if (roofY == 0) return null;
        if (Masonry.grain(along, 0, 0, 26, 26, salt ^ 0x30F) > 0.66f) return null;

        if (h == roofY || h == roofY + 1) return CANOPY;
        if (h < roofY && band % (platformWidth + gauge) < 2
                && Math.floorMod(along, 11) < 1) {
            return plot.skin(wx, wy, wz);                 // 柱
        }
        return null;
    }
}
