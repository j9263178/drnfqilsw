package com.xinbow99.brutalist.worldgen;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 沿著街廓邊界挖下去的混凝土排水渠。
 *
 * <h2>它補的是方向</h2>
 * <p>高架往上、電塔往上、裝置物往上、管線往上——這個世界目前沒有任何東西往**下**。
 * 走在渠底抬頭看兩側斜坡與上面的量體，那個視角原本不存在。
 *
 * <h2>為什麼不是 Corridor 的一種</h2>
 * <p>{@link Corridor} 一條線只能有一種東西，而排水渠**應該要能跟高架疊在一起**——
 * 一條高架橫過一道乾涸的大排水渠是這個題材裡最好的一張圖，把它們做成互斥就永遠拍不到。
 *
 * <p>更重要的是它不是「加上去的方塊」而是**地形本身**：它改的是這一柱的地面高度，
 * 所以高度圖、柱體取樣、電塔的塔腳全都會自動跟著走。做成 Corridor 的話這些各要補一次。
 */
public record Channel(boolean alongX, int centre, int half, int inner, int depth,
                      Settings settings, int groundSalt, int salt) {

    /** 渠道的襯砌。固定一份，它是一次工程澆出來的。 */
    private static final Masonry.Palette LINING = new Masonry.Palette(
            Masonry.STONE, Masonry.ANDESITE, Masonry.COBBLE, 0.58f, 0.86f, 7, 0x2D9A61FF);

    private static final BlockState KERB = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /**
     * 這條邊界線上有沒有渠道。
     *
     * <p>跟 {@link Corridor} 一樣只能放在超級街廓的邊界上：街廓內部那條線上是有建築的。
     */
    public static Channel at(int lineIndex, boolean alongX, Settings s, int worldSalt) {
        if (Math.floorMod(lineIndex, Plot.GROUP) != 0) return null;

        int salt = Masonry.hash(lineIndex, alongX ? 0x3333 : 0x4444, worldSalt);
        RandomSource r = RandomSource.create(salt);
        if (r.nextInt(100) >= 24) return null;

        int half = Math.max(5, Math.min(s.street(), 8 + r.nextInt(3)));
        int depth = 6 + r.nextInt(6);
        return new Channel(alongX, lineIndex * s.cell(), half,
                Math.max(2, half - 3 - r.nextInt(2)), depth, s, worldSalt, salt);
    }

    public boolean covers(int wx, int wz) {
        return Math.abs((alongX ? wz : wx) - centre) <= half;
    }

    /**
     * 挖過之後這一柱的地面在哪。
     *
     * <p>斷面是梯形不是矩形：垂直的溝壁玩家掉下去就上不來，而斜坡可以走下去。
     * 這條渠道是要給人進去的，不是給人看的。
     *
     * <p>渠底本身是**每一段各自水平**的（見 {@link #invert}），不跟著地形起伏——真的排水渠
     * 是澆出來的，底是平的，段與段之間落一階。而且只有底是平的，水才填得滿：
     * 底跟著地形走的話，水面一水平，半段渠底就露在水面上。
     */
    public int floor(int wx, int wz, int land) {
        int o = Math.abs((alongX ? wz : wx) - centre);
        if (o > half) return land;
        int invert = invert(alongX ? wx : wz);
        if (invert >= land) return land;                  // 地面已經比渠底低，不要反過來墊高它
        if (o <= inner) return invert;
        return invert + (land - invert) * (o - inner) / Math.max(1, half - inner);
    }

    /** 渠底與斜坡的表層。渠緣那一圈用裂石磚當緣石，邊界才咬得住。 */
    public BlockState surface(int wx, int wy, int wz) {
        int o = Math.abs((alongX ? wz : wx) - centre);
        if (o == half) return KERB;
        return LINING.at(wx, wy, wz);
    }

    /** {@link #waterY} 用來表示這一段是乾的。 */
    public static final int DRY = Integer.MIN_VALUE;

    /** 一段渠道有多長。一段共用一個渠底高度，所以它同時是「一窪積水」的長度。 */
    private static final int REACH = 56;

    /** 段號 → 渠底高度。一段要取樣十幾次地形，不快取的話每一柱都要重算一遍。 */
    private static final ConcurrentHashMap<Long, Integer> INVERTS = new ConcurrentHashMap<>();

    public static BlockState water() {
        return WATER;
    }

    /**
     * 這一段的渠底**絕對高度**。
     *
     * <p>取這一段裡**最低**的地面再往下挖：這樣段內每一處的渠底都在地面以下，
     * 渠道不會有一截浮出地表變成一條堤。
     */
    private int invert(int t) {
        int k = Math.floorDiv(t, REACH);
        long key = ((long) salt << 24) ^ k;
        Integer cached = INVERTS.get(key);
        if (cached != null) return cached;

        if (INVERTS.size() > 8192) INVERTS.clear();
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i <= REACH; i += 4) {
            int u = k * REACH + i;
            lowest = Math.min(lowest, Ground.height(
                    alongX ? u : centre, alongX ? centre : u, settings, groundSalt));
        }
        int invert = lowest - depth;
        INVERTS.put(key, invert);
        return invert;
    }

    /**
     * 這一段的水面**絕對高度**，{@link #DRY} ＝ 乾的。
     *
     * <p>水面必須是水平的，否則它不是水面，是一層沿著地形貼上去的藍色的漆。所以它是一個
     * 絕對高度，每 {@link #REACH} 格一段、段內固定——跟渠底同一段，所以整段的底都在水面下，
     * 一窪積水是**滿的**，不是中間一條水線兩頭露底。
     *
     * <p>深度上限是 {@code depth - 2}：水面永遠低於渠緣兩格，不會漫到街上。
     */
    public int waterY(int wx, int wz) {
        int t = alongX ? wx : wz;
        int k = Math.floorDiv(t, REACH);
        if (Masonry.grain(k, 0, 0, 3, 3, salt ^ 0x5EA) <= 0.46f) return DRY;
        int fill = Math.min(depth - 2, 2 + Math.round(Masonry.grain(k, 0, 0, 2, 2, salt ^ 0x77D) * 3f));
        return invert(t) + fill;
    }
}
