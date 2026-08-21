package com.xinbow99.brutalist.worldgen;

/**
 * 梯度雜訊（Perlin）與它的兩個變奏：多八度疊加、以及定義域扭曲。
 *
 * <h2>為什麼不是值雜訊</h2>
 * <p>{@link Masonry} 用的是**值雜訊**——在晶格點上擲一個值，中間內插。那對石材夠用（它只要
 * 一塊一塊的斑），但拿來做地形會露餡：每個晶格點都是一個極值，所以起伏會變成一格一格的
 * 圓丘，等高線是橢圓，而且橢圓的中心排成一個網格。看久了會發現地形有週期。
 *
 * <p>梯度雜訊在晶格點上擲的是**方向**不是高度，晶格點本身的值恆為零。極值落在格子中間、
 * 位置不規則，等高線因此不再對齊網格。原版從 {@code ImprovedNoise} 開始就是這個。
 *
 * <h2>八度</h2>
 * <p>單一波長的雜訊不管多平滑，都只有一種尺度的細節，遠看是丘陵、近看還是同一組丘陵。
 * 疊上頻率加倍、振幅減半的幾層（fBm），大尺度的走勢跟小尺度的皺褶才會同時存在。
 *
 * <p>頻率倍率用 2.03 不是 2：剛好加倍的話每一層的晶格線會疊在同一批座標上，那些線會
 * 隱隱約約看得出來。原版是在 {@code NormalNoise} 裡把兩份雜訊用 1.0181 的比例錯開，
 * 同一個道理。
 *
 * <h2>定義域扭曲</h2>
 * <p>最便宜也最有效的一招：先用另一份雜訊把取樣座標推開一點，再去取樣。
 * 等高線會被拉成不對稱的、有拐彎的形狀，而不是一團一團的圓。原版的
 * {@code shift_x} / {@code shift_z} 密度函數做的就是這件事。
 */
public final class Noise {

    /** 八個方向的單位向量。查表比 sin/cos 便宜，八個方向對這個尺度的地形已經足夠。 */
    private static final float[] GRAD_X = {1f, -1f, 0f, 0f, 0.7071f, -0.7071f, 0.7071f, -0.7071f};
    private static final float[] GRAD_Z = {0f, 0f, 1f, -1f, 0.7071f, 0.7071f, -0.7071f, -0.7071f};

    /** 單位梯度的 2D Perlin 值域是 ±1/√2，換算回 0～1 要乘這個。 */
    private static final float NORM = 0.7071f;

    private Noise() {
    }

    /** 一格 Perlin，晶格邊長為 1。回傳大約 -0.71～0.71。 */
    public static float perlin(float x, float z, int salt) {
        int gx = (int) Math.floor(x);
        int gz = (int) Math.floor(z);
        float fx = x - gx;
        float fz = z - gz;

        float u = fade(fx);
        float v = fade(fz);

        float n00 = dot(gx, gz, fx, fz, salt);
        float n10 = dot(gx + 1, gz, fx - 1f, fz, salt);
        float n01 = dot(gx, gz + 1, fx, fz - 1f, salt);
        float n11 = dot(gx + 1, gz + 1, fx - 1f, fz - 1f, salt);

        float a = n00 + u * (n10 - n00);
        float b = n01 + u * (n11 - n01);
        return a + v * (b - a);
    }

    /**
     * 多八度疊加，回傳大約 0～1，中央密集兩端稀疏。
     *
     * @param scale 最大那一層的波長（格）
     */
    public static float fbm(float x, float z, float scale, int octaves, int salt) {
        float sum = 0f;
        float amp = 1f;
        float norm = 0f;
        float freq = 1f / scale;

        for (int i = 0; i < octaves; i++) {
            // 每一層再平移一段：光靠 salt 分開的話，各層的晶格原點仍然重合在 (0,0)
            sum += amp * perlin(x * freq + i * 137.13f, z * freq - i * 91.7f, salt ^ (i * 0x9E37));
            norm += amp;
            amp *= 0.5f;
            freq *= 2.03f;
        }
        return 0.5f + (sum / norm) * NORM;
    }

    /**
     * 先把取樣點推開，再取樣。
     *
     * @param amount 最多推開幾格。太小看不出來，太大會把地形攪成麵條
     */
    public static float warped(float x, float z, float scale, int octaves, int salt, float amount) {
        // 推移量本身用大一號的波長：跟被推的那份同尺度的話，兩者會互相抵銷成雜訊
        float dx = fbm(x, z, scale * 2f, 2, salt ^ 0x7A11) - 0.5f;
        float dz = fbm(x, z, scale * 2f, 2, salt ^ 0x1B93) - 0.5f;
        return fbm(x + dx * amount, z + dz * amount, scale, octaves, salt);
    }

    private static float dot(int gx, int gz, float dx, float dz, int salt) {
        int h = Masonry.hash(gx, salt, gz) & 7;
        return GRAD_X[h] * dx + GRAD_Z[h] * dz;
    }

    /** 原版用的五次曲線。三次的 smoothstep 二階不連續，疊了幾層之後晶格線會浮出來。 */
    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }
}
