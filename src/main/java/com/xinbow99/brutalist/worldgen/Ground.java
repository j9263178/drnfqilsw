package com.xinbow99.brutalist.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地面：起伏的岩盤，加上積在低處的土壤。
 *
 * <h2>為什麼是雜訊，不是格線</h2>
 * <p>廢土感的來源是**不規則**。任何週期性的圖案——每 N 格一塊草皮、棋盤、格線——都會讀成
 * 「有人鋪的」，而那正好是廢墟的反面。所以起伏跟土壤的分布都走平滑雜訊。
 *
 * <h2>土壤跟著低處走</h2>
 * <p>侵蝕值會**被高程壓低**：高的地方岩石裸露，低的地方積土積草。這是這片地看起來像自然
 * 地形而不是「隨機貼了一些草」的關鍵——土壤跟地勢無關的話，你會看到草長在山頂而山谷是
 * 光的，眼睛立刻知道哪裡不對，雖然說不出來為什麼。
 *
 * <h2>一個侵蝕值決定所有材質</h2>
 * <p>石頭 → 碎石 → 粗泥 → 泥土 → 草地是**同一個值的連續區間**，不是五種各自獨立的判斷。
 * 這讓交界自動變成漸層：草地不會直接貼著岩盤，中間一定會經過泥土跟碎石。分開擲的話
 * 每一種都會有自己的邊界，疊在一起是雜亂而不是層次。
 */
public final class Ground {

    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SHORT_GRASS = Blocks.SHORT_GRASS.defaultBlockState();
    private static final BlockState DEAD_BUSH = Blocks.DEAD_BUSH.defaultBlockState();

    /**
     * 四道門檻，切出岩盤 → 碎石 → 粗泥 → 泥土 → 草地。
     *
     * <p>刻意讓草是**最窄的一段**。雜訊接近常態分布，門檻愈高的區間愈稀有，所以草只會出現在
     * 侵蝕最徹底的核心。反過來調的話會變成綠地佔領整片地——那是草原，不是廢墟。
     */
    private static final float BARE = 0.52f;
    private static final float RUBBLE = 0.62f;
    private static final float BARREN = 0.70f;
    private static final float SOIL = 0.77f;

    /**
     * 裸岩的配方。
     *
     * <p>固定一份而不是每塊地各擲一份：地面是**一整塊岩盤**，它沒有理由每隔一段就換成
     * 另一種石頭。變化交給雜訊，配方本身要穩定。
     */
    private static final Masonry.Palette ROCK = new Masonry.Palette(
            Masonry.STONE, Masonry.ANDESITE, Masonry.TUFF, 0.63f, 0.83f, 9, 0x5A17C0DE);

    /** 深一點的地方換成板岩，往下挖才有「愈深愈不一樣」的層次。 */
    private static final Masonry.Palette DEEP = new Masonry.Palette(
            Masonry.DEEPSLATE, Masonry.TUFF, Masonry.STONE, 0.64f, 0.86f, 9, 0x0DEEB105);

    private Ground() {
    }

    /**
     * 這一柱的地面高度。
     *
     * <p>兩個八度、波長都拉得很長（71 與 29），所以是緩緩的丘陵而不是碎裂的地形——
     * 起伏是要讓建築有東西可以坐，不是要跟建築搶戲。
     */
    public static int height(int wx, int wz, Settings s, int salt) {
        float n = 0.68f * value(wx, wz, 71, salt ^ 0x11)
                + 0.32f * value(wx, wz, 29, salt ^ 0x27);
        return s.ground() + Math.round((n - 0.5f) * 2f * s.relief());
    }

    /**
     * 地表那一格。
     *
     * @param salt 由世界種子導出，不同世界的地貌才會不一樣
     */
    public static BlockState surface(int wx, int wz, int y, Settings s, int salt) {
        float n = erosion(wx, wz, y, s, salt);
        if (n < BARE) return ROCK.at(wx, y, wz);
        if (n < RUBBLE) return GRAVEL;      // 岩盤碎掉的邊緣
        if (n < BARREN) return COARSE_DIRT;
        if (n < SOIL) return DIRT;
        return GRASS_BLOCK;
    }

    /**
     * 長在地表上的東西，{@code null} ＝ 什麼都沒有。
     *
     * <p>只長在土上，而且**不是滿的**——稀疏才像自己長出來的。岩盤與碎石上不長任何東西：
     * 石頭風化才有土，有土才有草，這個順序是這片地看起來有故事的原因。
     */
    public static BlockState plant(int wx, int wz, int y, Settings s, int salt) {
        float n = erosion(wx, wz, y, s, salt);
        if (n < RUBBLE) return null;

        int roll = Math.floorMod(Masonry.hash(wx, salt ^ 0x5EED, wz), 100);
        if (n < BARREN) return roll < 7 ? DEAD_BUSH : null;           // 粗泥：只有枯枝
        if (n < SOIL) return roll < 6 ? DEAD_BUSH : (roll < 16 ? SHORT_GRASS : null);
        if (roll < 5) return DEAD_BUSH;
        return roll < 34 ? SHORT_GRASS : null;
    }

    /**
     * 地表以下那幾格。
     *
     * <p>草地底下要有土，不然一挖就露出岩盤，整片地立刻讀成「貼上去的皮」。土層只有幾格深，
     * 再往下就是岩盤。
     *
     * @param depth 距離地表幾格（1 ＝ 緊貼在地表下面）
     */
    public static BlockState below(int wx, int wz, int depth, int surfaceY, int y, Settings s, int salt) {
        float n = erosion(wx, wz, surfaceY, s, salt);
        if (n >= BARE && depth <= (int) ((n - BARE) * 20)) return DIRT;
        // 地表以下八格開始轉成板岩系，跟原版「愈深愈是深板岩」的直覺一致
        return (depth > 8 ? DEEP : ROCK).at(wx, y, wz);
    }

    /**
     * 0～1，愈大表示這裡被土壤佔得愈徹底。
     *
     * <p>高程會把它壓低：每高出基準一格就少一點點，所以高地是裸岩、低地積土。係數很小
     * （0.012）是刻意的——要的是傾向，不是硬把地形切成上下兩層。
     */
    private static float erosion(int wx, int wz, int surfaceY, Settings s, int salt) {
        float n = 0.65f * value(wx, wz, 37, salt) + 0.35f * value(wx, wz, 13, salt ^ 0x9E37);
        return n - (surfaceY - s.ground()) * 0.012f;
    }

    /**
     * 平滑的值雜訊。
     *
     * <p>自己寫而不是用 {@code NormalNoise}：那個要先在 registry 裡註冊一組噪聲參數，
     * 而這裡只需要一塊會起伏的斑。用 smoothstep 內插晶格上的隨機值就夠了，
     * 而且跟這個模組其他地方一樣，只吃座標。
     */
    private static float value(int x, int z, int scale, int salt) {
        int gx = Math.floorDiv(x, scale);
        int gz = Math.floorDiv(z, scale);
        float fx = smooth((x - gx * scale) / (float) scale);
        float fz = smooth((z - gz * scale) / (float) scale);

        float a = lattice(gx, gz, salt);
        float b = lattice(gx + 1, gz, salt);
        float c = lattice(gx, gz + 1, salt);
        float d = lattice(gx + 1, gz + 1, salt);

        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static float lattice(int gx, int gz, int salt) {
        return (Masonry.hash(gx, salt, gz) >>> 8) / (float) (1 << 24);
    }

    /** smoothstep：線性內插會在晶格線上留下看得見的折角。 */
    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }
}
