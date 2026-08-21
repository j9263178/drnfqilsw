package com.xinbow99.brutalist.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 把世界切成幾片氣候不同的大區。
 *
 * <h2>為什麼不用原版的 multi_noise</h2>
 * <p>{@code multi_noise} 是拿 {@link Climate.Sampler} 去查一張氣候參數表，而那個 sampler
 * 來自原版的噪聲設定——這個維度根本沒有噪聲設定，它的地形是自己算的。硬接上去等於讓一份
 * 我不控制的雜訊決定分區尺度，而尺度正是這裡唯一要調的東西。
 *
 * <h2>分界要不規則、區塊要大</h2>
 * <p>用跟地形同一套 {@link Noise}：多八度加定義域扭曲。少了扭曲，等值線會是一團團的圓，
 * 分界看起來像用圓規畫的；有了扭曲才會有半島、灣、夾在中間的細長帶。
 *
 * <p>波長訂在七百多格——一個街廓 160 格，所以一片氣候大概蓋得住四五個街廓見方，
 * 走過去要走一陣子才會換天氣。這是刻意的：氣候如果一個街廓就換一次，那不是地理，是磁磚。
 *
 * <h2>種子</h2>
 * <p>{@link BiomeSource} 拿不到世界種子（介面沒給），所以鹽是由
 * {@link BrutalistChunkGenerator#createBiomes} 在查生態系之前塞進來的。沒有它的話
 * 每個世界的氣候分布都會長得一模一樣。
 */
public class BrutalistBiomeSource extends BiomeSource {

    public static final MapCodec<BrutalistBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.fieldOf("cold").forGetter(s -> s.cold),
            Biome.CODEC.fieldOf("temperate").forGetter(s -> s.temperate),
            Biome.CODEC.fieldOf("warm").forGetter(s -> s.warm)
    ).apply(i, BrutalistBiomeSource::new));

    /** 氣候帶的波長（格）。 */
    private static final float SCALE = 720f;
    /** 定義域扭曲的幅度（格）。 */
    private static final float WARP = 260f;


    private final Holder<Biome> cold;
    private final Holder<Biome> temperate;
    private final Holder<Biome> warm;

    /** 由生成器填進來，見類別說明。兩條執行緒同時寫進同一個值也無所謂。 */
    private volatile int salt;

    /**
     * 一柱一個答案。
     *
     * <p>{@link #getNoiseBiome} 是**逐個 y** 問的（一個區塊要問近一千次），而氣候只跟
     * 平面位置有關——不快取的話同一柱的雜訊會被重算九十幾遍。
     */
    private final ConcurrentHashMap<Long, Holder<Biome>> cache = new ConcurrentHashMap<>();

    public BrutalistBiomeSource(Holder<Biome> cold, Holder<Biome> temperate, Holder<Biome> warm) {
        this.cold = cold;
        this.temperate = temperate;
        this.warm = warm;
    }

    void seed(int salt) {
        this.salt = salt;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(cold, temperate, warm);
    }

    /**
     * @param x 四分之一格座標（一格生態系＝ 4×4×4 個方塊），所以要左移兩位才是世界座標
     */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        Holder<Biome> hit = cache.get(key);
        if (hit != null) return hit;

        if (cache.size() > 1 << 16) cache.clear();
        Holder<Biome> rolled = pick(x << 2, z << 2);
        cache.put(key, rolled);
        return rolled;
    }

    /**
     * 三片各擲一份雜訊，誰高就是誰。
     *
     * <p>本來是在**一份**雜訊上切兩道門檻，結果三片變兩片：多八度疊出來的值集中在中間，
     * 兩端幾乎擲不到，所以靠外的那道門檻等於不存在。門檻要訂得準就得先知道分布，
     * 而分布會隨著八度數、扭曲幅度一起變——等於每調一次參數就要重新校準一次。
     *
     * <p>取三者最大值就沒有這個問題：三份雜訊同分布，誰最大是對稱的，比例天生是三分之一，
     * 而且分界仍然是雜訊的形狀，一樣不規則。
     */
    private Holder<Biome> pick(int bx, int bz) {
        float c = Noise.warped(bx, bz, SCALE, 3, salt ^ 0x0C71, WARP);
        float t = Noise.warped(bx, bz, SCALE, 3, salt ^ 0x51A3, WARP);
        float w = Noise.warped(bx, bz, SCALE, 3, salt ^ 0x77E9, WARP);
        if (c >= t && c >= w) return cold;
        return t >= w ? temperate : warm;
    }
}
