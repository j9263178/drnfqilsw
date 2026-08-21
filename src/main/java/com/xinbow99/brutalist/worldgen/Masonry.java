package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 量體的材質：石材的混合。
 *
 * <h2>為什麼不是「每 N 格換一種」</h2>
 * <p>用 {@code hash(x>>2, y>>3, z>>2)} 之類的位移雜湊很便宜，但它切出來的是**軸對齊的方盒**
 * ——4×8×4 一塊，邊界全部平行於座標軸。在遊戲裡看就是一塊一塊的規則色斑，讀起來像馬賽克，
 * 不像石頭。
 *
 * <p>改成三維平滑雜訊：材質的分界變成任意方向的曲面，才會讀成礦物的分布。
 *
 * <h2>邊界要打碎</h2>
 * <p>光是平滑雜訊還不夠——它的等值面太乾淨，兩種石材的交界會是一條滑順的曲線，看起來像
 * 用筆刷塗上去的。所以在雜訊值上加一點**逐格抖動**，讓交界變成互相咬合的顆粒。
 * 形狀由雜訊決定，邊緣由抖動決定，兩件事分開做。
 *
 * <h2>垂直拉長</h2>
 * <p>y 方向的尺度是水平的兩倍，所以斑塊是直的。岩層與風化的痕跡都是垂直發展的，
 * 各向同性的斑點看起來會像雜訊本身。
 */
public final class Masonry {

    public static final BlockState STONE = Blocks.STONE.defaultBlockState();
    public static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
    public static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    public static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    public static final BlockState TUFF = Blocks.TUFF.defaultBlockState();

    /** 主要材料。石頭權重最高，因為它最素，可以襯托其他材料。 */
    private static final BlockState[] PRIMARY = {STONE, STONE, STONE, ANDESITE, TUFF, COBBLE};

    /**
     * 次要材料。**深板岩不在裡面**。
     *
     * <p>深板岩比其他四種暗得多，一旦讓它當主要或次要材料，整棟樓就會變成深淺兩色的
     * 迷彩，而不是一塊有雜質的石頭。把它限制在最稀有的那一層，它才會讀成礦脈。
     */
    private static final BlockState[] MID = {STONE, COBBLE, ANDESITE, TUFF};

    /** 點綴。深板岩加權，因為它就是要當那個「偶爾一道暗的」。 */
    private static final BlockState[] ACCENT = {DEEPSLATE, DEEPSLATE, DEEPSLATE, TUFF, COBBLE, ANDESITE};

    private Masonry() {
    }

    /**
     * 同一種石材的樓梯方塊，朝著往上走的方向。
     *
     * <p>踏階非得是樓梯方塊不可：整塊的話玩家爬不上去（原版的自動跨步只有 0.6 格）。
     * {@code FACING} 是**往上走的方向**——原版 {@code block/stairs} 模型裡高的那半塊
     * 落在 facing 那一側，所以踩上去先遇到低的半格。
     *
     * <p>深板岩沒有素面的樓梯，用鵝卵深板岩代替；它是這五種裡唯一對不上的。
     */
    public static BlockState stairs(BlockState material, Direction facing) {
        Block block;
        if (material == COBBLE) block = Blocks.COBBLESTONE_STAIRS;
        else if (material == DEEPSLATE) block = Blocks.COBBLED_DEEPSLATE_STAIRS;
        else if (material == ANDESITE) block = Blocks.ANDESITE_STAIRS;
        else if (material == TUFF) block = Blocks.TUFF_STAIRS;
        else block = Blocks.STONE_STAIRS;
        return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    /**
     * 一棟樓的石材配方：主要 + 次要 + 點綴，三種不重複。
     *
     * @param secondaryAt 雜訊超過這個值就換成次要材料
     * @param accentAt    再超過這個值就換成點綴材料
     * @param scale       斑塊的水平尺度（格）
     */
    public record Palette(BlockState primary, BlockState secondary, BlockState accent,
                          float secondaryAt, float accentAt, int scale, int salt) {

        public BlockState at(int x, int y, int z) {
            float n = grain(x, y, z, scale, salt);
            if (n >= accentAt) return accent;
            if (n >= secondaryAt) return secondary;
            return primary;
        }
    }

    /**
     * 擲一份配方。
     *
     * <p>門檻訂得高：主要材料要佔七成以上。三種材料平均分配的話，眼睛看到的是**迷彩**
     * ——三塊等面積的色斑互相競爭，沒有哪一種是「這棟樓的材料」。要有一個明確的底，
     * 另外兩種才會讀成雜質。
     *
     * <p>尺度訂得小（水平 4～7 格）：斑塊比窗戶還大的話，它就不再是材質，而變成分區塗裝。
     */
    public static Palette roll(RandomSource r) {
        BlockState primary = PRIMARY[r.nextInt(PRIMARY.length)];
        BlockState secondary = otherThan(r, MID, primary, null);
        BlockState accent = otherThan(r, ACCENT, primary, secondary);
        return new Palette(primary, secondary, accent,
                0.62f + r.nextFloat() * 0.08f,
                0.82f + r.nextFloat() * 0.08f,
                4 + r.nextInt(4),
                r.nextInt());
    }

    private static BlockState otherThan(RandomSource r, BlockState[] pool, BlockState a, BlockState b) {
        for (int guard = 0; guard < 32; guard++) {
            BlockState pick = pool[r.nextInt(pool.length)];
            if (pick != a && pick != b) return pick;
        }
        return pool[0] != a && pool[0] != b ? pool[0] : COBBLE;
    }

    /**
     * 三維平滑雜訊，再加上逐格抖動。回傳大致落在 0～1。
     *
     * <p>抖動的幅度刻意很小（±0.05）：夠把等值面咬碎，又不會讓材質變成隨機雜點。
     */
    public static float grain(int x, int y, int z, int scale, int salt) {
        return grain(x, y, z, scale, Math.max(2, scale * 2), salt);
    }

    /**
     * 水平與垂直尺度分開給。
     *
     * <p>材質要垂直拉長（風化是流下來的），但**破損不要**——垮掉的一塊石頭沒有方向性，
     * 拉長的話會變成一道道直的凹槽，看起來像刻意開的溝而不是崩落。
     */
    public static float grain(int x, int y, int z, int scaleXZ, int scaleY, int salt) {
        float n = noise3(x, y, z, scaleXZ, Math.max(2, scaleY), salt);
        float jitter = (Math.floorMod(hash(x, y ^ salt, z), 1024) / 1024f - 0.5f) * 0.10f;
        return n + jitter;
    }

    private static float noise3(int x, int y, int z, int sxz, int sy, int salt) {
        int gx = Math.floorDiv(x, sxz);
        int gy = Math.floorDiv(y, sy);
        int gz = Math.floorDiv(z, sxz);

        float fx = smooth((x - gx * sxz) / (float) sxz);
        float fy = smooth((y - gy * sy) / (float) sy);
        float fz = smooth((z - gz * sxz) / (float) sxz);

        float x00 = lerp(lattice(gx, gy, gz, salt), lattice(gx + 1, gy, gz, salt), fx);
        float x10 = lerp(lattice(gx, gy + 1, gz, salt), lattice(gx + 1, gy + 1, gz, salt), fx);
        float x01 = lerp(lattice(gx, gy, gz + 1, salt), lattice(gx + 1, gy, gz + 1, salt), fx);
        float x11 = lerp(lattice(gx, gy + 1, gz + 1, salt), lattice(gx + 1, gy + 1, gz + 1, salt), fx);

        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static float lattice(int gx, int gy, int gz, int salt) {
        return (hash(gx ^ salt, gy, gz) >>> 8) / (float) (1 << 24);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** smoothstep：線性內插會在晶格線上留下看得見的折角。 */
    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * 位置雜湊。
     *
     * <p>自己寫而不是用 {@code RandomSource}：這是逐格呼叫的最內層，配一個物件出來太貴。
     * 用 splitmix64 的攪拌常數，位元散得夠開。
     */
    public static int hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (int) h;
    }
}
