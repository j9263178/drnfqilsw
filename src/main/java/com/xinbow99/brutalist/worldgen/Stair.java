package com.xinbow99.brutalist.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 貼在量體外牆上、一路折上屋頂的室外樓梯。
 *
 * <h2>它解決的是尺度問題</h2>
 * <p>一棟三百格高的量體沒有任何東西告訴你它有多高——這是量體本身要的（見 {@link Plot} 的
 * {@code raw}），但整座城市都這樣的話，高度就失去參照。外掛的樓梯是**人的尺寸**：
 * 一階一格、一段七八階，眼睛沿著它數上去，那棟樓才突然變得很高。
 *
 * <h2>三種樣式</h2>
 * <p>對應三種真實的東西：紐約的鏽鐵防火梯（鏤空、掛在牆外）、日本公寓的橘色鋼梯
 * （實心、油漆、白欄杆）、以及粗獷主義自己的清水混凝土折板梯（沒有欄杆，只有一道實心女兒牆）。
 * 三種的**幾何是同一份**，只有材料不同——折線就是折線，差別在它是用什麼做的。
 *
 * <h2>座標</h2>
 * <p>四個面各寫一次是這種東西最容易長歪的地方，所以 {@link Plot} 在呼叫前先把世界座標換成
 * 「沿著牆面走多遠（a）、離牆多遠（b）、離底部多高（h）」，這裡只認得這三個數字。
 */
public record Stair(int face, int along, int depth, int flight, int style, int salt) {

    /** 紐約鏽鐵防火梯。 */
    public static final int RUST = 0;
    /** 日本公寓的橘色鋼梯。 */
    public static final int PAINT = 1;
    /** 混凝土折板梯。 */
    public static final int CONCRETE = 2;

    /**
     * 鏽色的踏面。
     *
     * <p>用**上蠟的**銅：沒上蠟的會繼續氧化成綠色，蓋好的樣子跟玩家幾天後看到的不一樣。
     * 選 EXPOSED 這一階是因為它是四階裡唯一偏鐵鏽的棕橘色，OXIDIZED 已經是青綠了。
     */
    private static final BlockState GRATE =
            Blocks.COPPER_GRATE.waxed().pick(WeatheringCopper.WeatherState.EXPOSED).defaultBlockState();

    /** 橘色鋼梯的踏面。未氧化的銅塊就是那個橘。 */
    private static final BlockState PAINTED =
            Blocks.COPPER_BLOCK.waxed().pick(WeatheringCopper.WeatherState.UNAFFECTED).defaultBlockState();

    private static final BlockState RAIL = Blocks.IRON_BARS.defaultBlockState();

    /** 這一段的踏面數。跟段高相同，所以斜率剛好一比一——玩家真的走得上去。 */
    public int run() {
        return flight + 1;
    }

    /** 這座樓梯往外伸出去幾格。{@link Plot} 用它把自己的外框撐大。 */
    public int reach() {
        return depth;
    }

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param a 沿著牆面走多遠，0 是這座樓梯的起點
     * @param b 離牆多遠，1 是緊貼牆的第一格
     * @param h 離樓梯底部多高
     */
    public BlockState at(int a, int b, int h, int top, Plot plot, int wx, int wy, int wz) {
        if (b < 1 || b > depth || a < 0 || a >= run() || h < 0 || h > top) return null;

        if (floor(a, b, h)) return tread(plot, wx, wy, wz);
        if (guard(a, b, h)) return guardBlock(plot, wx, wy, wz);
        // 兩根通到底的角柱。防火梯掛在牆外，那兩根柱子是它唯一的重量，少了就變成飄在空中的階梯
        if (style == RUST && b == depth && (a == 0 || a == run() - 1)) return RAIL;
        return null;
    }

    /**
     * 平台與踏面。
     *
     * <p>平台是**整段的寬度**，踏面只有一格——折返梯就是靠這個對比讀出來的：
     * 一條斜線走到底，撞上一片橫的，再往回走。
     */
    private boolean floor(int a, int b, int h) {
        int k = Math.floorDiv(h, flight);
        int r = h - k * flight;

        if (r == 0) return true;                       // 平台
        if (b > depth - 1) return false;               // 斜段比平台窄一格，外側留給欄杆
        if (broken(k)) return false;

        // 偶數段往前走、奇數段往回走，折返梯的來回就是這一行
        int tread = (k & 1) == 0 ? r : run() - 1 - r;
        return a == tread;
    }

    /**
     * 欄杆：踩得到的地方，外緣與兩端往上兩格。
     *
     * <p>直接問「腳下一格或兩格是不是踏面」，不必知道自己在第幾段——樓梯的形狀只寫一次，
     * 欄杆自動跟著折。
     */
    private boolean guard(int a, int b, int h) {
        if (b != depth && a != 0 && a != run() - 1) return false;
        return floor(a, b, h - 1) || floor(a, b, h - 2);
    }

    /** 鏽鐵那段偶爾整段不見。廢棄的防火梯最強的畫面就是斷掉的那一截。 */
    private boolean broken(int k) {
        return style == RUST && Math.floorMod(Masonry.hash(k, salt, 0x3E51), 100) < 12;
    }

    private BlockState tread(Plot plot, int wx, int wy, int wz) {
        return switch (style) {
            case RUST -> GRATE;
            case PAINT -> PAINTED;
            default -> plot.skin(wx, wy, wz);
        };
    }

    private BlockState guardBlock(Plot plot, int wx, int wy, int wz) {
        // 混凝土梯沒有欄杆，只有一道實心的女兒牆——那正是它看起來重的原因
        return style == CONCRETE ? plot.skin(wx, wy, wz) : RAIL;
    }
}
