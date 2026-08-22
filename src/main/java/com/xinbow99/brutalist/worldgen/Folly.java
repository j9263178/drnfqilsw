package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 裝置物：沿著街廓邊界、每隔一段距離立一座的巨大構造。
 *
 * <h2>它們不是建築</h2>
 * <p>建築要能讀成建築——有樓層、有入口、有重量。裝置物**刻意不要**：它沒有尺度線索，
 * 所以走近之前不知道它多大，而那個誤判正是它存在的理由。整座城市都是可讀的量體時，
 * 玩家很快就停止看它們了。
 *
 * <p>平面尺寸被走廊淨空鎖死在二十一格見方（見 {@link Corridor}），高度則從二十幾到一百一十。
 * 對旁邊那些兩三百格高的量體來說很小，對站在下面的人來說仍然是龐然大物。
 *
 * <h2>全部是靜態方法</h2>
 * <p>同一條線上每一跨都是不同的一座，如果做成物件就得**逐格配置**（區塊填充是逐格呼叫的），
 * 那個代價比幾何本身還貴。所以形狀完全由 {@code salt} 的雜湊決定，一個物件都不配。
 */
public final class Folly {

    /** 框中框：幾個逐漸縮小的方框排開，走進去是強迫透視的隧道。 */
    static final int FRAMES = 0;
    static final int CRYSTAL = 1;
    static final int FINS = 2;
    /** 裂開的方碑：一整塊巨石被切開，兩半拉開錯位，縫可以走過去。 */
    static final int SPLIT = 3;
    static final int STACK = 4;
    static final int LEAN = 5;
    /** 通往空無的樓梯：繞著中心爬的螺旋坡道，頂上是一個什麼都沒有的平台。 */
    static final int STAIR = 6;
    private static final int KINDS = 7;

    /**
     * 三份固定的配方。
     *
     * <p>不像建築那樣逐座擲，是因為擲一份 {@link Masonry.Palette} 要配一個物件，
     * 而這裡是最內層迴圈。三份夠讓相鄰的兩座不一樣，而那是唯一的目的。
     */
    private static final Masonry.Palette[] PALETTES = {
            new Masonry.Palette(Masonry.STONE, Masonry.ANDESITE, Masonry.DEEPSLATE, 0.64f, 0.88f, 5, 0x51F3A20D),
            new Masonry.Palette(Masonry.ANDESITE, Masonry.STONE, Masonry.TUFF, 0.60f, 0.86f, 6, 0x1D7C4E11),
            new Masonry.Palette(Masonry.DEEPSLATE, Masonry.TUFF, Masonry.COBBLE, 0.66f, 0.90f, 7, 0x3A9F0C57),
    };

    private Folly() {
    }

    static int kind(int salt) {
        return Math.floorMod(Masonry.hash(salt, 0x7F1D, 0x2C93), KINDS);
    }

    /**
     * 這一座有多高。
     *
     * <p>寬度被走廊淨空鎖死在二十一格，所以**高度是唯一能給它份量的維度**。訂在
     * 四十五到一百一十格：旁邊的量體是它的三倍，但走到腳下抬頭還是看不到頂。
     */
    static int height(int salt) {
        int roll = Math.floorMod(Masonry.hash(salt, 0x11A7, 0x6E05), 66);
        return switch (kind(salt)) {
            // 斜柱拉高就看不出是斜的
            case LEAN -> 28 + roll % 16;
            // 樓梯要爬得完：一百格高的螺旋坡道走到一半就會想跳下去
            case STAIR -> 40 + roll % 40;
            default -> 45 + roll;
        };
    }

    private static Masonry.Palette palette(int salt) {
        return PALETTES[Math.floorMod(Masonry.hash(salt, 0x4C31, 0x9B72), PALETTES.length)];
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param dt    離這座裝置物中心的沿線距離
     * @param o     離中心線的橫向距離
     * @param h     離地面幾格
     * @param reach 可用的半寬（走廊淨空）
     */
    static BlockState at(int dt, int o, int h, int reach, int height, int salt,
                         int wx, int wy, int wz) {
        return at(dt, o, h, reach, height, salt, palette(salt), wx, wy, wz);
    }

    /**
     * 指定石材的版本。
     *
     * <p>{@link Precinct} 的雕像要用**那一格自己的**配方，而不是裝置物那三份固定的——
     * 廣場跟它周圍的樓是同一批混凝土澆的，雕像也該是。
     */
    static BlockState at(int dt, int o, int h, int reach, int height, int salt,
                         Masonry.Palette palette, int wx, int wy, int wz) {
        if (h < 0 || h > height) return null;
        if (Math.abs(dt) > reach || Math.abs(o) > reach) return null;

        int kind = kind(salt);
        int ops = Math.floorMod(Masonry.hash(salt, 0x2B5D, 0x71C3), 1 << 16);

        // ---- 架空：整座抬到幾根細腿上，底下走得進去
        int legs = (ops & 3) == 0 && kind != STAIR ? 6 + ((ops >>> 2) & 7) : 0;
        if (legs > 0) {
            if (h < legs) {
                int foot = Math.max(2, reach / 4);
                boolean onLeg = Math.abs(Math.abs(dt) - (reach - foot - 1)) <= 1
                        && Math.abs(Math.abs(o) - (reach - foot - 1)) <= 1;
                return onLeg ? palette.at(wx, wy, wz) : null;
            }
            h -= legs;
            height -= legs;
        }

        boolean solid;
        // ---- 剖切：一刀切開，上半往旁邊挪、往上抬。切面是整數斜率，所以在方塊裡是
        //      乾淨的階梯；圓弧那種切法只會得到一圈鋸齒
        if ((ops >>> 5 & 3) == 0 && kind != STAIR && kind != SPLIT) {
            int slip = 3 + ((ops >>> 7) & 3);
            int side = 1 + ((ops >>> 9) & 3);
            int slope = ((ops >>> 11) & 3) - 1;
            int cut = height * (30 + ((ops >>> 13) & 7) * 5) / 100;
            solid = (shape(kind, dt, o, h, reach, height, salt) && h < cut + slope * o)
                    || (shape(kind, dt - side, o, h - slip, reach, height, salt)
                        && h - slip >= cut + slope * o);
        } else {
            solid = shape(kind, dt, o, h, reach, height, salt);
        }

        // ---- 開槽：每隔一段挖一道通槽，光會穿過去。樓梯不開，開了就斷了
        if (solid && (ops >>> 4 & 1) == 0 && kind != STAIR) {
            int period = 9 + ((ops >>> 6) & 7);
            if (Math.floorMod(h + (ops & 7), period) < 2) solid = false;
        }

        if (!solid) return null;
        // 樓梯的踏面要是樓梯方塊，不然爬不上去（原版自動跨步只有 0.6 格）
        if (kind == STAIR) {
            Direction facing = tread(dt, o, h, reach, height);
            if (facing != null) return Masonry.stairs(palette.at(wx, wy, wz), facing);
        }
        return palette.at(wx, wy, wz);
    }

    private static boolean shape(int kind, int dt, int o, int h, int reach, int height, int salt) {
        if (h < 0 || h > height) return false;
        return switch (kind) {
            case FRAMES -> frames(dt, o, h, reach, height, salt);
            case CRYSTAL -> crystal(dt, o, h, reach, height);
            case FINS -> fins(dt, o, h, reach, height, salt);
            case SPLIT -> split(dt, o, h, reach, height, salt);
            case STACK -> stack(dt, o, h, reach, height, salt);
            case STAIR -> stair(dt, o, h, reach, height);
            default -> lean(dt, o, h, reach, height);
        };
    }

    /**
     * 框中框：三到五個逐漸縮小的方框沿著一個軸排開。
     *
     * <p>它取代了原本的「門」。門只是兩根腳加一根樑，走過去就走過去了；框中框走進去是
     * 一條強迫透視的隧道，而且**它框住的是背後剛好有什麼**——同一座裝置物，背後是塔、
     * 是高架、還是空的天空，看起來就是三件不同的作品。這種變化不是參數給的，
     * 是它跟周圍的關係給的。
     *
     * <p>框做成「П」形而不是封閉的圈：封閉的圈底下有一條橫木，人就走不過去了。
     */
    private static boolean frames(int dt, int o, int h, int reach, int height, int salt) {
        int n = 3 + Math.floorMod(Masonry.hash(salt, 0x4F21, 0x1B0D), 3);
        int pitch = Math.max(3, reach * 2 / n);
        int i = Math.floorDiv(dt + reach, pitch);
        if (i < 0 || i >= n) return false;

        int centre = -reach + i * pitch + pitch / 2;
        if (Math.abs(dt - centre) > 1) return false;                 // 每一框兩格厚

        int w = reach - 1 - i * (reach - 3) / Math.max(1, n);        // 一框比一框小
        int top = height - i * height / (n + 2);
        if (w < 2 || Math.abs(o) > w || h > top) return false;

        // 中間掏空的部分一路通到地面，所以是「П」不是「口」
        return !(Math.abs(o) <= w - 3 && h <= top - 3);
    }

    /**
     * 裂開的方碑：一整塊巨石被一刀切開，兩半拉開並上下錯位。
     *
     * <p>它取代了原本的圓環。圓在方塊裡只會得到一圈鋸齒，而這一種的「洞」是**減法**做的——
     * 那道縫是兩塊石頭之間剩下的空氣，不是誰畫的形狀。人可以從縫裡走過去。
     */
    private static boolean split(int dt, int o, int h, int reach, int height, int salt) {
        int a = Math.max(3, reach - 3);
        int slip = 4 + Math.floorMod(Masonry.hash(salt, 0x77A1, 0), 4);
        int side = 2 + Math.floorMod(Masonry.hash(salt, 0x78B2, 0), 3);
        int slope = Math.floorMod(Masonry.hash(salt, 0x79C3, 0), 3) - 1;
        int cut = height * (30 + Math.floorMod(Masonry.hash(salt, 0x7AD4, 0), 30)) / 100;

        boolean lower = Math.abs(dt) <= a && Math.abs(o) <= a && h < cut + slope * o;
        boolean upper = Math.abs(dt - side) <= a && Math.abs(o) <= a
                && h - slip >= cut + slope * o && h - slip <= height;
        return lower || upper;
    }

    /**
     * 通往空無的樓梯：繞著中心爬的螺旋坡道，頂上一個什麼都沒有的平台。
     *
     * <p>這是唯一一種玩家**能互動**的裝置物——爬得上去，站在上面看整個廣場。
     * 其他幾種都只能繞著看，而「可以上去」會讓一座裝置物從佈景變成目的地。
     *
     * <p>坡道是方形的螺旋：沿著外環走一圈爬八個邊長。踏面用樓梯方塊，
     * 底下留三格厚的緞帶當支撐，所以它看起來是一條纏上去的帶子，不是一階一階的積木。
     */
    private static boolean stair(int dt, int o, int h, int reach, int height) {
        int r = Math.max(4, reach - 2);
        int core = Math.max(2, r / 3);
        int ring = Math.max(Math.abs(dt), Math.abs(o));

        if (h >= height - 1) return ring <= r;                       // 頂上的平台
        if (ring <= core) return h < height - 1;                     // 中央的核
        if (ring != r) return false;

        int p = perimeter(dt, o, r);
        return Math.floorMod(h - p, 8 * r) < 3;                      // 三格厚的緞帶
    }

    /** 外環上某一點沿著環走了多遠。四個邊接起來就是一圈。 */
    private static int perimeter(int dt, int o, int r) {
        if (dt == r) return o + r;
        if (o == r) return 2 * r + (r - dt);
        if (dt == -r) return 4 * r + (r - o);
        return 6 * r + (dt + r);
    }

    /**
     * 這一格是不是踏面，是的話面朝哪。
     *
     * <p>{@code FACING} 是**往上走的方向**，也就是沿著環前進的方向：
     * 東側往南走、北側往西走、西側往北走、南側往東走。
     */
    private static Direction tread(int dt, int o, int h, int reach, int height) {
        int r = Math.max(4, reach - 2);
        if (h >= height - 1 || Math.max(Math.abs(dt), Math.abs(o)) != r) return null;
        // 緞帶是三格厚的，踏面在**最上面**那一格。標成最下面那一格的話，
        // 玩家踩到的是整塊方塊，每走一步就要跳一次
        if (Math.floorMod(h - perimeter(dt, o, r), 8 * r) != 2) return null;
        if (dt == r) return Direction.SOUTH;
        if (o == r) return Direction.WEST;
        if (dt == -r) return Direction.NORTH;
        return Direction.EAST;
    }

    /** 立方體立在一個角上：實作成八面體，那正是一個方塊沿對角線看過去的樣子。 */
    private static boolean crystal(int dt, int o, int h, int reach, int height) {
        int r = reach - 1;
        // 八面體的半高等於半寬，它才是一個「立方體」而不是紡錘。多出來的高度全部
        // 留給底下那根細支點——愈高的一座，就是同一塊石頭被舉得愈高
        int body = r * 2;
        int cy = Math.max(body, height - body);
        if (h < cy - body) return Math.abs(dt) <= 2 && Math.abs(o) <= 2;
        int shrink = Math.abs(h - cy) * r / body;
        return Math.abs(dt) + Math.abs(o) <= r - shrink;
    }

    /** 一排厚板，高度不齊。沿線走過去像一段被拉長的條碼。 */
    private static boolean fins(int dt, int o, int h, int reach, int height, int salt) {
        int gap = 5;
        if (Math.floorMod(dt, gap) >= 2) return false;
        if (Math.abs(o) > reach - 2) return false;
        int index = Math.floorDiv(dt, gap);
        int top = height * (45 + Math.floorMod(Masonry.hash(index, salt, 0x5511), 55)) / 100;
        return h <= top;
    }

    /** 一疊互相錯開的板，每一層轉九十度。 */
    private static boolean stack(int dt, int o, int h, int reach, int height, int salt) {
        int slab = Math.max(5, height / 7);
        int layer = h / slab;
        int shift = Math.floorMod(Masonry.hash(layer, salt, 0x77C1), 9) - 4;
        int longSide = reach - 1;
        int shortSide = Math.max(3, reach / 3);
        boolean turned = (layer & 1) == 1;
        int a = Math.abs(dt - shift);
        int b = Math.abs(o + shift);
        return turned
                ? a <= longSide && b <= shortSide
                : a <= shortSide && b <= longSide;
    }

    /** 一根斜插在地上的方碑。往一個方向倒，倒到快要撐不住的角度。 */
    private static boolean lean(int dt, int o, int h, int reach, int height) {
        int drift = h * (reach - 3) / Math.max(1, height);
        int thick = Math.max(3, reach / 3);
        return Math.abs(dt - drift) <= thick && Math.abs(o) <= thick;
    }
}
