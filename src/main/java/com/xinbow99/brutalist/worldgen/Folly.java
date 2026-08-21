package com.xinbow99.brutalist.worldgen;

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

    static final int GATE = 0;
    static final int CRYSTAL = 1;
    static final int FINS = 2;
    static final int RING = 3;
    static final int STACK = 4;
    static final int LEAN = 5;
    private static final int KINDS = 6;

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
            // 這兩種的造型自己有比例：環拉高就變成棒棒糖，斜柱拉高就看不出是斜的
            case RING -> 24 + roll % 14;
            case LEAN -> 28 + roll % 16;
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

        boolean solid = switch (kind(salt)) {
            case GATE -> gate(dt, o, h, reach, height);
            case CRYSTAL -> crystal(dt, o, h, reach, height);
            case FINS -> fins(dt, o, h, reach, height, salt);
            case RING -> ring(dt, o, h, reach, height);
            case STACK -> stack(dt, o, h, reach, height, salt);
            default -> lean(dt, o, h, reach, height);
        };
        return solid ? palette.at(wx, wy, wz) : null;
    }

    /**
     * 門：兩根腳撐著一片橫過去的厚板，上面再壓一段。
     *
     * <p>最古老的紀念性形式，而且它是**可以走過去的**——玩家會穿過它，那一刻才知道它多高。
     */
    private static boolean gate(int dt, int o, int h, int reach, int height) {
        int leg = reach - 2;
        int lintel = height * 68 / 100;
        if (Math.abs(dt) > 4) return false;

        if (h < lintel) return Math.abs(o) >= leg - 4 && Math.abs(o) <= leg;
        if (h < lintel + 8) return Math.abs(o) <= leg;
        // 頂上那一段刻意比橫樑窄，讓側影有兩層而不是一個平頭
        return Math.abs(o) <= leg / 2 && h < height - 2;
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

    /** 直立的圓環，環面對著路：走在下面會從環中間看出去。 */
    private static boolean ring(int dt, int o, int h, int reach, int height) {
        if (Math.abs(dt) > 3) return false;
        int cy = height * 60 / 100;
        int outer = Math.min(reach - 1, cy);
        int inner = outer * 6 / 10;
        int dy = h - cy;
        int d2 = o * o + dy * dy;
        if (d2 <= outer * outer && d2 >= inner * inner) return true;
        // 環要落地，不然它是飄的
        return Math.abs(o) <= 3 && h < cy - inner;
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
