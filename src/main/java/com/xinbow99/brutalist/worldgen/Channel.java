package com.xinbow99.brutalist.worldgen;

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
public record Channel(boolean alongX, int centre, int half, int inner, int depth, int bed, int salt) {

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
                Math.max(2, half - 3 - r.nextInt(2)), depth, s.ground() - depth, salt);
    }

    public boolean covers(int wx, int wz) {
        return Math.abs((alongX ? wz : wx) - centre) <= half;
    }

    /**
     * 挖過之後這一柱的地面在哪。
     *
     * <p>斷面是梯形不是矩形：垂直的溝壁玩家掉下去就上不來，而斜坡可以走下去。
     * 這條渠道是要給人進去的，不是給人看的。
     */
    public int floor(int wx, int wz, int land) {
        int o = Math.abs((alongX ? wz : wx) - centre);
        if (o > half) return land;
        if (o <= inner) return land - depth;
        return land - depth * (half - o) / Math.max(1, half - inner);
    }

    /** 渠底與斜坡的表層。渠緣那一圈用裂石磚當緣石，邊界才咬得住。 */
    public BlockState surface(int wx, int wy, int wz) {
        int o = Math.abs((alongX ? wz : wx) - centre);
        if (o == half) return KERB;
        return LINING.at(wx, wy, wz);
    }

    /** {@link #waterY} 用來表示這一段是乾的。 */
    public static final int DRY = Integer.MIN_VALUE;

    public static BlockState water() {
        return WATER;
    }

    /**
     * 這一段的水面**絕對高度**，{@link #DRY} ＝ 乾的。
     *
     * <p>原本是「渠底往上一格放水」。渠底跟著地形起伏，於是水面也跟著起伏——那不是水面，
     * 是一層沿著地形貼上去的藍色的漆，遠看是一階一階的。
     *
     * <p>水面必須是**水平的**，所以它是一個絕對高度，每 56 格一段、段內固定。渠底低於它的
     * 地方就積水，高於它的地方就是乾的——一條廢棄的排水渠本來就是這樣：一窪一窪的積水，
     * 中間隔著乾的段落。
     */
    public int waterY(int wx, int wz) {
        int t = alongX ? wx : wz;
        int k = Math.floorDiv(t, 56);
        if (Masonry.grain(k, 0, 0, 3, 3, salt ^ 0x5EA) <= 0.46f) return DRY;
        return bed + Math.round((Masonry.grain(k, 0, 0, 2, 2, salt ^ 0x77D) - 0.5f) * 6f);
    }
}
