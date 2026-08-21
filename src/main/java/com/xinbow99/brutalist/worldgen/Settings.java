package com.xinbow99.brutalist.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 世界的尺度參數，寫在 world_preset 的 JSON 裡，改完重開新世界就生效，不用重編。
 *
 * <p>{@code minY} 與 {@code height} **必須跟 dimension_type 對得上**。對不上的話生成器會
 * 算出超出世界高度的方塊，那些寫入會被安靜丟掉——症狀是樓被削平，而不是報錯。
 *
 * @param cell      每一格街廓的間距（一格一棟）
 * @param street    街廓邊緣留給「街」的寬度，量體不會超出這個內縮範圍
 * @param ground    廣場地坪的 y
 * @param minHeight 量體最矮幾格
 * @param maxHeight 量體最高幾格
 * @param density   有多少比例的街廓真的蓋東西（留白跟量體一樣重要）
 * @param minY      世界底部，要等於 dimension_type 的 min_y
 * @param height    世界總高，要等於 dimension_type 的 height
 * @param relief    地面起伏的振幅（格）。0 ＝ 完全平坦
 */
public record Settings(
        int cell, int street, int ground,
        int minHeight, int maxHeight, float density,
        int minY, int height, int relief) {

    public static final Settings DEFAULT = new Settings(160, 10, 20, 56, 340, 1.0f, 0, 384, 8);

    public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("cell", DEFAULT.cell()).forGetter(Settings::cell),
            Codec.INT.optionalFieldOf("street", DEFAULT.street()).forGetter(Settings::street),
            Codec.INT.optionalFieldOf("ground", DEFAULT.ground()).forGetter(Settings::ground),
            Codec.INT.optionalFieldOf("min_height", DEFAULT.minHeight()).forGetter(Settings::minHeight),
            Codec.INT.optionalFieldOf("max_height", DEFAULT.maxHeight()).forGetter(Settings::maxHeight),
            Codec.FLOAT.optionalFieldOf("density", DEFAULT.density()).forGetter(Settings::density),
            Codec.INT.optionalFieldOf("min_y", DEFAULT.minY()).forGetter(Settings::minY),
            Codec.INT.optionalFieldOf("world_height", DEFAULT.height()).forGetter(Settings::height),
            Codec.INT.optionalFieldOf("relief", DEFAULT.relief()).forGetter(Settings::relief)
    ).apply(i, Settings::new));

    /** 量體可以往上長到哪，超過就會被世界高度截掉。 */
    public int ceiling() {
        return minY + height - 1;
    }
}
