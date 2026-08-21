package com.xinbow99.brutalist.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地面：起伏的岩盤，加上積在低處的土壤。
 *
 * <h2>為什麼是雜訊，不是格線</h2>
 * <p>廢土感的來源是**不規則**。任何週期性的圖案——每 N 格一塊草皮、棋盤、格線——都會讀成
 * 「有人鋪的」，而那正好是廢墟的反面。所以起伏跟土壤的分布都走雜訊。
 *
 * <p>而且是 {@link Noise} 的梯度雜訊、多八度、加定義域扭曲，不是單一波長的值雜訊——
 * 後者的極值排在晶格上，遠看等高線是一格一格的橢圓，週期藏不住。
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
     * <p>目標是**裸露的硬地兩成、土壤與草地八成**。雜訊接近常態分布，所以門檻的位置跟
     * 佔比不是線性關係：這四個數字是從實際生成出來的世界量回來的，不是算出來的。
     * 改動之後要重新量，見 {@code peek.py} 的 surface composition。
     */
    private static final float BARE = 0.371f;
    private static final float RUBBLE = 0.407f;
    private static final float BARREN = 0.478f;
    private static final float SOIL = 0.569f;

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
        // 起伏的**強度**自己也是一塊雜訊：有些地方近乎平坦，有些地方皺得厲害。
        // 少了這一層，整片地會是同一個粗糙度的丘陵，那本身就是一種規律。
        // 原版是用 erosion 這條參數做同一件事
        float rough = Noise.fbm(wx, wz, 260f, 2, salt ^ 0x4D2);
        float amp = s.relief() * (0.30f + 1.5f * rough * rough);

        float n = Noise.warped(wx, wz, 84f, 4, salt ^ 0x11, 40f);
        return s.ground() + Math.round((n - 0.5f) * 2f * amp);
    }

    /** 地形可能到達的最高點，出生點要靠它。跟 {@link #height} 的振幅上限對齊。 */
    public static int ceiling(Settings s) {
        return s.ground() + Math.round(s.relief() * 1.8f) + 1;
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
        if (n < BARREN) return roll < 5 ? DEAD_BUSH : null;           // 粗泥：只有枯枝
        if (n < SOIL) return roll < 6 ? DEAD_BUSH : (roll < 14 ? SHORT_GRASS : null);
        if (roll < 5) return DEAD_BUSH;
        return roll < 22 ? SHORT_GRASS : null;
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
        // 土層厚度從**碎石線**起算，不是從裸岩線。從裸岩線起算的話，門檻一往下調，
        // 連光禿的岩盤底下都會鋪一層土
        if (n >= RUBBLE && depth <= (int) ((n - RUBBLE) * 16)) return DIRT;
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
        float n = Noise.warped(wx, wz, 46f, 4, salt ^ 0x27, 26f);
        return n - (surfaceY - s.ground()) * 0.012f;
    }

}
