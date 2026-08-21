package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 沿著街廓邊界跑的線性構造物：高架道路、高壓電塔線，或一連串的裝置物。
 *
 * <h2>為什麼放在邊界線上</h2>
 * <p>量體永遠內縮 {@code street} 格才開始（見 {@link Plot#roll}），所以每一條街廓邊界的
 * 兩側各有 {@code street} 格是**保證空的**。把高架與電塔的中心線壓在邊界上、寬度不超過那個
 * 淨空，它們就**天生不可能撞到任何建築**——一行碰撞檢查都不用寫。
 *
 * <p>不這樣做的話就得處理「高架穿過大樓」：要嘛讓建築閃開（需要建築知道路在哪，多一層耦合），
 * 要嘛讓路切進建築（看起來像 bug）。這裡是用擺放規則把整個問題消掉。
 *
 * <h2>沿著哪個軸</h2>
 * <p>幾何只寫「沿著 x 跑」那一種。沿著 z 跑的在入口把座標對調，出來再對調回去——
 * 兩份幾乎一樣的程式碼是這類東西最容易長歪的地方。
 */
public final class Corridor {

    public enum Kind {VIADUCT, POWER, FOLLY}

    /**
     * 塔身。
     *
     * <p>本來用鐵柵欄，想做出格狀鋼構的通透感，結果適得其反：柵欄是**半透明**的，
     * 遠看整座塔會糊進天空，讀不出是一個結構體。實心方塊反而更像鋼塔——
     * 格狀的感覺應該由**形狀**（斜撐與留空）給，不是由材質的透明度給。
     */
    private static final BlockState STEEL = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState WIRE = Blocks.IRON_CHAIN.defaultBlockState();
    private static final BlockState FOOTING = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();

    /** 高架與電塔基座的石材。固定一份：它們是同一批工程蓋的。 */
    private static final Masonry.Palette CONCRETE = new Masonry.Palette(
            Masonry.STONE, Masonry.COBBLE, Masonry.DEEPSLATE, 0.60f, 0.84f, 6, 0x0B7A1D6E);

    private final Kind kind;
    private final boolean alongX;
    /** 中心線的世界座標（alongX 時是 z，否則是 x）。 */
    private final int centre;
    private final int half;
    private final int salt;

    // ---- 高架
    private final int deckY;
    private final int pierGap;
    private final int pierPhase;

    // ---- 電塔與裝置物共用：沿線每隔多遠放一座、從哪裡起算
    private final int spacing;
    private final int phase;
    private final int pylonHeight;
    private final int armReach;

    private Corridor(Kind kind, boolean alongX, int centre, int half, int salt,
                     int deckY, int pierGap, int pierPhase,
                     int spacing, int phase, int pylonHeight, int armReach) {
        this.kind = kind;
        this.alongX = alongX;
        this.centre = centre;
        this.half = half;
        this.salt = salt;
        this.deckY = deckY;
        this.pierGap = pierGap;
        this.pierPhase = pierPhase;
        this.spacing = spacing;
        this.phase = phase;
        this.pylonHeight = pylonHeight;
        this.armReach = armReach;
    }

    /**
     * 這條邊界線上有什麼，{@code null} ＝ 什麼都沒有。
     *
     * <p>大部分的線是空的：每一條邊界都有東西的話，整張地圖會被切成棋盤，
     * 而基礎設施要稀疏才顯得是**穿過**這座城市，不是這座城市的格線本身。
     */
    public static Corridor at(int lineIndex, boolean alongX, Settings s, int worldSalt) {
        // **只能放在超級街廓的邊界上。** 街廓邊界不夠：2×2 的量體會橫跨自己那一組裡面的
        // 那條內部邊界（見 Plot 的 partition），所以那條線上是有建築的。只有每隔一條、
        // 也就是 GROUP 的倍數，才真的保證兩側都空著
        if (Math.floorMod(lineIndex, Plot.GROUP) != 0) return null;

        int salt = Masonry.hash(lineIndex, alongX ? 0x1111 : 0x2222, worldSalt);
        RandomSource r = RandomSource.create(salt);

        int roll = r.nextInt(100);
        Kind kind;
        if (roll < 38) return null;
        else if (roll < 64) kind = Kind.VIADUCT;
        else if (roll < 78) kind = Kind.POWER;
        else kind = Kind.FOLLY;

        int half = Math.max(3, Math.min(s.street(), 10));
        int centre = lineIndex * s.cell();

        // 裝置物之間要留得夠遠：連著擺就變成一排路燈，而它們要像是各自被丟在那裡的
        int spacing = kind == Kind.FOLLY ? 90 + r.nextInt(80) : 84 + r.nextInt(44);

        return new Corridor(kind, alongX, centre, half, salt,
                s.ground() + 24 + r.nextInt(22),
                26 + r.nextInt(14), r.nextInt(64),
                spacing, r.nextInt(64),
                88 + r.nextInt(56), Math.max(4, half - 1));
    }

    /** 這一柱在不在這條走廊的範圍內。 */
    public boolean covers(int wx, int wz) {
        return Math.abs((alongX ? wz : wx) - centre) <= half + 1;
    }

    public int lowestY(int terrainY) {
        return terrainY;
    }

    /**
     * 要掃到多高。
     *
     * <p>電塔的底是**它自己那個位置**的地面，不是這一柱的地面，所以這裡多留一段餘裕
     * 蓋過地形起伏，寧可多掃十幾格空氣也不要把塔頂切掉。
     */
    public int highestY(int terrainY) {
        return switch (kind) {
            // 橋面會沿線起伏（見 deckAt），所以上限要含最大的抬升量
            case VIADUCT -> deckY + DECK_SWING + 4;
            case POWER -> terrainY + pylonHeight + 14;
            case FOLLY -> terrainY + FOLLY_CEILING;
        };
    }

    /** 橋面相對基準高度的最大擺幅，見 {@link #nodeY}。 */
    private static final int DECK_SWING = 12;

    /** 裝置物的高度上限，見 {@link Folly#height}。 */
    private static final int FOLLY_CEILING = 118;

    /**
     * 這一格是什麼，{@code null} ＝ 空氣。
     *
     * @param terrainY 這一柱的地面高度，柱腳與塔腳要落到地上
     */
    public BlockState blockAt(int wx, int wy, int wz, int terrainY, Plot.Terrain terrain) {
        int t = alongX ? wx : wz;                       // 沿著線走的方向
        int o = (alongX ? wz : wx) - centre;            // 離中心線多遠
        return switch (kind) {
            case VIADUCT -> viaduct(t, o, wx, wy, wz, terrainY);
            case POWER -> power(t, o, wx, wy, wz, terrain);
            case FOLLY -> folly(t, o, wx, wy, wz, terrain);
        };
    }

    /**
     * 裝置物：沿線每隔 {@link #spacing} 立一座，每一座長得都不一樣。
     *
     * <p>形狀完全交給 {@link Folly}，這裡只負責把世界座標換成「離這一座多遠、離地多高」，
     * 以及把每一座的亂數種子算出來。分工的理由跟 {@link Plot} 與 {@link Form} 一樣：
     * 擺放與造型是兩件會各自變動的事。
     */
    private BlockState folly(int t, int o, int wx, int wy, int wz, Plot.Terrain terrain) {
        int dt = Math.floorMod(t - phase, spacing);
        int centred = dt <= spacing / 2 ? dt : dt - spacing;
        int index = Math.floorDiv(t - centred - phase, spacing);
        int seed = Masonry.hash(index, salt, 0x2E11);

        int base = groundAlong(t - centred, terrain) + 1;
        return Folly.at(centred, o, wy - base, half - 1, Folly.height(seed), seed, wx, wy, wz);
    }

    /** 中心線上、沿線座標 t 的那一點的地面高度。塔腳與導線都要錨在這個高度。 */
    private int groundAlong(int t, Plot.Terrain terrain) {
        return terrain.heightAt(alongX ? t : centre, alongX ? centre : t);
    }

    // ------------------------------------------------------------------ 高架

    /**
     * 高架橋：一片厚板、兩道欄牆、每隔一段一根橋墩。
     *
     * <p>會有整跨不見——廢棄的高架最強的意象就是斷掉的那一段。塌陷是用沿著線的低頻雜訊
     * 決定的，所以塌的是**連續的一整跨**，不是隨機的破洞。
     */
    private BlockState viaduct(int t, int o, int wx, int wy, int wz, int terrainY) {
        int deck = deckAt(t);
        int dy = wy - deck;
        boolean gone = collapsed(t);

        if (!gone && Math.abs(o) <= half) {
            if (dy <= 0 && dy >= -3) return CONCRETE.at(wx, wy, wz);        // 橋面板
            if (dy >= 1 && dy <= 2 && Math.abs(o) >= half - 1) {
                return CONCRETE.at(wx, wy, wz);                              // 欄牆
            }
        }

        // 橋墩。就算上面那一跨塌了橋墩還是留著，那正是廢墟該有的樣子
        if (pier(t, o, wy, deck, terrainY)) return CONCRETE.at(wx, wy, wz);
        return null;
    }

    /**
     * 橋面的高度，沿線起伏。
     *
     * <p>原本是一個固定的高度，於是不管走多遠，高架都是同一條水平線——那條線是**格線**，
     * 不是道路。改成每個橋墩節點各自擲一個高度，中間線性內插，整條路就開始爬升與下降。
     *
     * <p>內插而不是直接跳階：跳階會在節點上留一道垂直的斷面，讀起來像兩段接錯的橋，
     * 而不是一條連續的路。
     */
    private int deckAt(int t) {
        int k = Math.floorDiv(t - pierPhase, pierGap);
        int within = Math.floorMod(t - pierPhase, pierGap);
        int a = nodeY(k);
        return a + (nodeY(k + 1) - a) * within / pierGap;
    }

    /**
     * 一個橋墩節點的高度。
     *
     * <p>用**沿著節點編號的平滑雜訊**，不是每個節點各自擲。各自擲的話相鄰兩節點可以差到
     * 整個擺幅，三十格內爬升三十格——那不是道路，是雲霄飛車。平滑雜訊讓相鄰節點只差一點，
     * 起伏要好幾跨才走完。
     */
    private int nodeY(int k) {
        float n = Masonry.grain(k, 0, 0, 4, 4, salt ^ 0x3C71);
        return deckY + Math.round((n - 0.5f) * 2f * DECK_SWING);
    }

    /**
     * 橋墩。同一條線上四種墩型輪替，由節點編號決定。
     *
     * <p>只有一種墩型的話，這條高架的每一跨都是同一張照片重貼——真正讓一條路看起來很長的，
     * 是它在不同的地方用了不同的辦法過去。
     */
    private boolean pier(int t, int o, int wy, int deck, int terrainY) {
        int within = Math.floorMod(t - pierPhase, pierGap);
        int dt = within <= pierGap / 2 ? within : within - pierGap;
        int k = Math.floorDiv(t - dt - pierPhase, pierGap);

        if (wy <= terrainY || wy >= deck - 3) return false;

        int span = deck - 3 - terrainY;                  // 墩身可用的高度
        int up = wy - terrainY;
        int type = Math.floorMod(Masonry.hash(k, salt, 0x5A17), 4);

        // 帽梁：單柱式的墩要靠它把力傳到整個橋面寬度，沒有它柱子看起來是插在橋底下的
        if (type == 2 && up >= span - 4 && Math.abs(dt) <= 2 && Math.abs(o) <= half - 1) {
            return true;
        }

        return switch (type) {
            // 厚牆式：整片實心，最重的一種
            case 0 -> Math.abs(dt) <= 2 && Math.abs(o) <= half - 3;

            // V 形：兩支腳往下併攏，把橋面撐開
            case 1 -> {
                int splay = (half - 3) * up / Math.max(1, span);
                yield Math.abs(dt) <= 2 && Math.abs(Math.abs(o) - splay) <= 2;
            }

            // 單柱：中央一根，上面靠帽梁展開
            case 2 -> Math.abs(dt) <= 3 && Math.abs(o) <= 3;

            // 拱：一片牆從中間掏掉一個圓洞，可以從底下穿過去
            default -> {
                if (Math.abs(dt) > 3 || Math.abs(o) > half - 2) yield false;
                int r = Math.max(3, Math.min(half - 4, span * 4 / 10));
                int dy = up - span * 55 / 100;
                yield o * o + dy * dy > r * r;
            }
        };
    }

    /** 沿著線的低頻雜訊：連續好幾十格一起塌，而不是東缺一塊西缺一塊。 */
    private boolean collapsed(int t) {
        return Masonry.grain(t, 0, 0, 34, 34, salt ^ 0x60117) > 0.70f;
    }

    // ------------------------------------------------------------------ 電塔

    /**
     * 高壓電塔線：鐵塔加上垂下來的導線。
     *
     * <p>塔身是四支往上收的腳、水平的橫桁、加上面上的斜撐——斜撐是用
     * {@code (h ± o) % 4} 這種週期判斷畫出來的 X 形，那是格狀鋼塔看起來像鋼塔的原因，
     * 少了它只會剩下四根柱子。
     */
    private BlockState power(int t, int o, int wx, int wy, int wz, Plot.Terrain terrain) {
        int dt = Math.floorMod(t - phase, spacing);
        int centred = dt <= spacing / 2 ? dt : dt - spacing;   // 離最近一座塔多遠

        // 塔腳踩在**那座塔**的地面上，不是這一柱的地面。用這一柱的話，塔會隨著
        // 腳下的地形歪掉，而鋼塔是剛體
        int base = groundAlong(t - centred, terrain) + 1;

        BlockState tower = tower(centred, o, wx, wy, wz, base);
        if (tower != null) return tower;
        return wire(t, o, wy, terrain);
    }

    private BlockState tower(int dt, int o, int wx, int wy, int wz, int base) {
        int h = wy - base;
        if (h < 0 || h > pylonHeight) return null;

        // 往上收：腳從 half-1 收到 3
        int wide = half - 1;
        int narrow = 3;
        double taper = Math.min(1.0, h / (pylonHeight * 0.62));
        int w = (int) Math.round(wide - (wide - narrow) * taper);
        if (w < 1) w = 1;

        int at = Math.abs(dt);
        int ao = Math.abs(o);
        if (at > w || ao > w) {
            return arm(dt, o, h);
        }

        boolean onEdge = at == w || ao == w;
        if (!onEdge) return arm(dt, o, h);

        if (h <= 2) return FOOTING;                                   // 塔腳
        if (at == w && ao == w) return STEEL;                          // 四支主腳
        if (Math.floorMod(h, 7) == 0) return STEEL;                    // 水平橫桁
        // 面上的斜撐：兩個方向的週期線交錯成 X
        int along = at == w ? o : dt;
        if (Math.floorMod(h + along, 4) == 0 || Math.floorMod(h - along, 4) == 0) return STEEL;

        return arm(dt, o, h);
    }

    /**
     * 橫擔：靠近塔頂的三層，往兩側伸出去掛導線。
     *
     * <p>愈上面愈短。真實的輸電塔就是這個側影，而且塔拉高之後這件事更重要——
     * 三層一樣長會讓整座塔變成一支插了三根等長橫木的桿子。
     *
     * <p>長度上限是走廊淨空（見類別說明），所以只能往內收，不能往外加。
     */
    private BlockState arm(int dt, int o, int h) {
        if (Math.abs(dt) > 2) return null;              // 沿線 5 格深
        for (int i = 0; i < 3; i++) {
            int at = armHeight(i);
            if ((h == at || h == at + 1) && Math.abs(o) <= armSpan(i)) return STEEL;
        }
        return null;
    }

    private int armSpan(int i) {
        return Math.max(3, armReach - i * 2);
    }

    private int armHeight(int i) {
        return (int) Math.round(pylonHeight * (0.60 + 0.16 * i));
    }

    /**
     * 導線：兩座塔之間垂下來的鏈條。
     *
     * <p>用拋物線近似懸鏈線——差別在遊戲裡看不出來，而拋物線只要一次乘法。
     *
     * <p>有將近一半的導線是斷的。全部掛滿的話它就不是廢墟，是還在供電的電網。
     */
    private BlockState wire(int t, int o, int wy, Plot.Terrain terrain) {
        int span = Math.floorDiv(t - phase, spacing);
        int within = Math.floorMod(t - phase, spacing);

        // 兩端塔腳的高度可能不一樣，導線要在兩者之間拉直，不能貼著地形起伏
        int t0 = span * spacing + phase;
        double lean = groundAlong(t0, terrain)
                + (groundAlong(t0 + spacing, terrain) - groundAlong(t0, terrain))
                * (within / (double) spacing);

        for (int i = 0; i < 3; i++) {
            if (Math.abs(o) != armSpan(i)) continue;      // 導線只掛在臂端
            double attach = lean + 1 + armHeight(i);
            // 這一跨的這一條有沒有斷
            if (Math.floorMod(Masonry.hash(span, i * 31 + o, salt), 100) < 45) continue;

            // 弛度跟著跨距走。固定值的話，跨距一拉長導線就繃成直線，看起來像鋼索不像電纜
            double x = (within - spacing / 2.0) / (spacing / 2.0);
            int y = (int) Math.round(attach - spacing * 0.14 * (1 - x * x));
            if (wy == y) {
                return WIRE.setValue(BlockStateProperties.AXIS,
                        alongX ? Direction.Axis.X : Direction.Axis.Z);
            }
        }
        return null;
    }
}
