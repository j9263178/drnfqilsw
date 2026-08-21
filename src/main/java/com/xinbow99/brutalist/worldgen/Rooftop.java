package com.xinbow99.brutalist.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 屋頂上的設備：女兒牆、水塔、機房、通風井、桅杆。
 *
 * <h2>為什麼是回報最高的一塊</h2>
 * <p>屋頂是這個世界裡**曝光度最高的表面**——每一棟樓的屋頂都被旁邊二十棟看到，而站在地面
 * 的人反而看不到自己腳下那一棟的。整片平屋頂會讓天際線變成一排切齊的方塊，
 * 而真實城市的天際線是雜的：水塔、機房、天線，那些東西才是「上面有人在用」的證據。
 *
 * <h2>只長在最頂那一層</h2>
 * <p>設備只出現在**量體最上面那一層是實心**的地方（{@code solid(u, v, height - 1)}）。
 * 一次判斷就對所有形狀成立：板樓長滿整片、梯形只長在最上面那個退縮平台、
 * 圓筒長成一個圓、組合體長在最高那一塊上。不必為每種形狀各寫一次。
 */
public record Rooftop(Item[] items, int parapet, int top) {

    /** 水塔：架高的圓筒。 */
    static final int TANK = 0;
    /** 機房：一個方盒。 */
    static final int HOUSE = 1;
    /** 通風井：一排小方井。 */
    static final int VENTS = 2;
    /** 桅杆：細而高，帶幾層橫擔。 */
    static final int MAST = 3;

    /** 金屬感的設備用它，跟電塔同一種鋼。 */
    private static final BlockState STEEL = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState FRAME = Blocks.POLISHED_DEEPSLATE.defaultBlockState();

    /**
     * 一件設備。
     *
     * @param u 在屋頂上的位置（量體的局部座標）
     * @param a 沿 u 的半寬，{@code b} 是沿 v 的半寬
     * @param h 高出屋頂幾格
     */
    public record Item(int kind, int u, int v, int a, int b, int h) {
    }

    private static final Item[] NONE = new Item[0];

    /**
     * 擲一組。
     *
     * <p>件數跟屋頂面積掛勾：一棟窄樓上擺四座水塔會讀成模型，而一片兩百格見方的屋頂
     * 只放一件則等於沒放。
     */
    public static Rooftop roll(RandomSource r, int width, int depth) {
        int parapet = r.nextInt(4) == 0 ? 0 : 1 + r.nextInt(2);
        int room = Math.min(width, depth);
        if (room < 14) return new Rooftop(NONE, parapet, parapet);

        int count = Math.clamp(room / 22, 1, 4) + r.nextInt(2);
        Item[] items = new Item[count];
        int tallest = parapet;

        for (int i = 0; i < count; i++) {
            int kind = pick(r);
            int a;
            int b;
            int h;
            switch (kind) {
                case TANK -> {
                    a = 4 + r.nextInt(3);
                    b = a;                       // 圓的，兩軸必須一樣
                    h = 14 + r.nextInt(12);
                }
                case HOUSE -> {
                    a = 5 + r.nextInt(6);
                    b = 4 + r.nextInt(6);
                    h = 5 + r.nextInt(7);
                }
                case VENTS -> {
                    a = 6 + r.nextInt(8);
                    b = 2 + r.nextInt(2);
                    h = 3 + r.nextInt(4);
                }
                default -> {
                    a = 1 + r.nextInt(2);
                    b = a;
                    h = 16 + r.nextInt(20);      // 桅杆要比別的高一截才看得出是桅杆
                }
            }
            // 留一格不要壓到女兒牆：設備擠在邊緣會讓屋頂看起來是被塞滿的，不是被使用的
            int u = a + 2 + r.nextInt(Math.max(1, width - 2 * a - 4));
            int v = b + 2 + r.nextInt(Math.max(1, depth - 2 * b - 4));
            items[i] = new Item(kind, u, v, a, b, h);
            tallest = Math.max(tallest, h);
        }
        return new Rooftop(items, parapet, tallest);
    }

    private static int pick(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 30) return HOUSE;
        if (roll < 55) return VENTS;
        if (roll < 80) return TANK;
        return MAST;
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param dh 高出屋頂幾格，0 是屋頂面上的第一格
     */
    public BlockState blockAt(int u, int v, int dh, int width, int depth,
                              Plot plot, int wx, int wy, int wz) {
        if (dh < 0 || dh >= top) return null;

        // 女兒牆先判斷：它是屋頂看起來像屋頂的最小條件，而且只吃邊緣那一圈，很便宜
        if (dh < parapet && (u == 0 || v == 0 || u == width - 1 || v == depth - 1)) {
            return plot.skin(wx, wy, wz);
        }

        for (Item item : items) {
            BlockState state = shape(item, u, v, dh, plot, wx, wy, wz);
            if (state != null) return state;
        }
        return null;
    }

    private BlockState shape(Item item, int u, int v, int dh, Plot plot, int wx, int wy, int wz) {
        int du = u - item.u();
        int dv = v - item.v();
        if (dh >= item.h()) return null;

        switch (item.kind()) {
            case TANK -> {
                int r = item.a();
                int legs = r + 2;
                if (dh < legs) {
                    // 架高的腳。四根，落在筒身的投影下面
                    boolean leg = Math.abs(Math.abs(du) - r + 1) <= 1 && Math.abs(Math.abs(dv) - r + 1) <= 1;
                    return leg ? STEEL : null;
                }
                if (du * du + dv * dv > r * r) return null;
                // 頂蓋與底板用深色，筒身用亮的，才看得出這是一個容器不是一根柱子
                return dh == legs || dh == item.h() - 1 ? FRAME : STEEL;
            }
            case HOUSE -> {
                if (Math.abs(du) > item.a() || Math.abs(dv) > item.b()) return null;
                boolean shell = Math.abs(du) == item.a() || Math.abs(dv) == item.b()
                        || dh == item.h() - 1;
                if (!shell) return null;
                // 側面開一條長窗，跟量體的立面同一種語言
                boolean slot = dh == item.h() / 2 && dh < item.h() - 1
                        && (Math.abs(du) == item.a() || Math.abs(dv) == item.b());
                return slot ? null : plot.skin(wx, wy, wz);
            }
            case VENTS -> {
                if (Math.abs(du) > item.a() || Math.abs(dv) > item.b()) return null;
                // 沿著 u 排成一列的小井，高度不齊
                if (Math.floorMod(du, 4) >= 3) return null;
                int step = Math.floorMod(Masonry.hash(Math.floorDiv(du, 4), item.u(), item.v()), 3);
                return dh < item.h() - step ? FRAME : null;
            }
            default -> {
                if (Math.abs(du) > item.a() || Math.abs(dv) > item.b()) return null;
                if (Math.abs(du) <= 0 && Math.abs(dv) <= 0) return STEEL;      // 主桿
                // 每隔一段一層橫擔，桅杆才不會只是一根棍子
                return Math.floorMod(dh, 6) == 0 ? STEEL : null;
            }
        }
    }
}
