package com.xinbow99.brutalist.worldgen;

/**
 * 量體的形狀——粗獷主義的字彙表。
 *
 * <h2>只描述量體，不描述立面</h2>
 * <p>每一種形狀只回答一件事：「這個點在不在混凝土裡面」。開窗、分縫、柱列那些**表面**的
 * 處理不在這裡，統一由 {@link Plot} 加上去。
 *
 * <p>這樣分是因為兩者的變化維度完全不同：形狀決定這是一棟什麼樣的建築（塔、牆、懸挑），
 * 立面決定它看起來多精緻。混在一起的話，每加一種形狀就要把開窗邏輯重寫一次，而那正是
 * 六種形狀會長成六份幾乎一樣的程式碼的原因。
 *
 * <h2>座標</h2>
 * <p>{@code u v h} 是相對於量體自己西北下角的局部座標，範圍由 {@link Plot} 保證，
 * 所以這裡不必檢查邊界。
 */
public enum Form {

    /**
     * 板樓：一個完整的長方體。
     *
     * <p>最基本、也最需要立面救的一種——量體本身沒有任何事件，全靠開窗的節奏撐起來。
     * 現實裡的 Trellick Tower、Robin Hood Gardens 都是這個路子。
     */
    SLAB {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            return true;
        }
    },

    /**
     * 梯形：每隔幾層往內縮一圈，愈往上愈小。
     *
     * <p>Habitat 67、Barbican 的側影。退縮出來的平台在遊戲裡特別有用——它給了這棟樓
     * 可以站人的地方，量體不再只是一塊不能互動的實心磚。
     */
    ZIGGURAT {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            int inset = (h / p.bandHeight()) * p.setback();
            return inside(u, v, inset, p);
        }
    },

    /**
     * 倒梯形：愈往上愈大，上面壓著下面。
     *
     * <p>粗獷主義最有攻擊性的一招（Buffalo City Court、波士頓市政廳的上半）。
     * 在遊戲裡效果比正梯形強得多，因為玩家站在地面往上看，懸出來的部分整個蓋住天空。
     */
    INVERTED {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            int inset = ((p.height() - 1 - h) / p.bandHeight()) * p.setback();
            return inside(u, v, inset, p);
        }
    },

    /**
     * 核心加懸挑：中間一根實心的塔，每隔一段插出一片通長的橫板。
     *
     * <p>這是整組裡唯一會產生**陰影下的空間**的形狀——橫板之間是通透的，人可以走進去，
     * 抬頭是幾十公尺厚的混凝土。粗獷主義真正嚇人的地方在這裡，不在牆有多厚。
     */
    CROSS {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            int cu = (p.width() - 1) / 2;
            int cv = (p.depth() - 1) / 2;
            boolean core = Math.abs(u - cu) <= Math.max(10, p.width() / 5)
                    && Math.abs(v - cv) <= Math.max(10, p.depth() / 5);
            if (core) return true;

            // 最底下一段不出板，讓核心單獨露出來——不然懸挑會從地面就開始，看不出是「挑」出來的
            if (h < p.armGap()) return false;
            return (h % p.armGap()) < p.armThickness();
        }
    },

    /**
     * 圓筒：平面是圓的，往上分段收分。
     *
     * <p>放在一堆直角量體中間才有意義——它的作用是打斷網格。所以出現機率要低，
     * 見 {@link Plot#pickForm}。
     */
    CYLINDER {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            double du = u - (p.width() - 1) / 2.0;
            double dv = v - (p.depth() - 1) / 2.0;
            double radius = Math.min(p.width(), p.depth()) / 2.0
                    - (h / p.bandHeight()) * (p.setback() / 2.0);
            if (radius < 3) return false;
            return du * du + dv * dv <= radius * radius;
        }
    },

    /**
     * 組合體：好幾塊巨大的方體與斜切面互相插接。
     *
     * <p>前面五種都是**單一操作**的結果（拉伸、退縮、挑出、旋轉），所以再怎麼調參數，
     * 一棟樓還是只講一句話。這一種講的是句子的組合：一塊壓著一塊、一塊斜穿過另一塊，
     * 交接處產生的稜線是量體自己算出來的，不是誰畫的。
     *
     * <p>形狀資料存在 {@link Plot#boxes()}，因為它需要**一組**參數而不是幾個數字——
     * 而那組參數是擲 Plot 時一次決定的，所以這裡仍然是純函數。
     *
     * <p>斜切用整數的交叉相乘判斷（見 {@link Plot.Box}），不碰浮點數：斜面必須在
     * 區塊邊界兩側算出完全一致的結果，浮點數在這裡是風險而不是精度。
     */
    ASSEMBLY {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            for (Plot.Box box : p.boxes()) {
                if (box.has(u, v, h)) return true;
            }
            return false;
        }
    },

    /**
     * 穿孔牆：一整片薄而極高的牆，打滿貫穿的方孔。
     *
     * <p>跟其他形狀不同，它的洞是**穿透的**，所以光會從另一邊透過來。一整排下來像水壩或
     * 是遮陽格柵（brise-soleil）。這種量體要又高又薄才有效果，比例由 {@link Plot} 控制。
     */
    PERFORATED {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            if (h < p.plinth()) return true;   // 底下留一段實心當基座
            int m = p.module();
            boolean holeU = (Math.floorMod(u, m)) >= 2 && (Math.floorMod(u, m)) < m - 2;
            boolean holeH = (Math.floorMod(h - p.plinth(), m)) >= 2
                    && (Math.floorMod(h - p.plinth(), m)) < m - 2;
            return !(holeU && holeH);
        }
    },

    /**
     * 集合體：一個巨大的有機外廓，由大大小小的方塊「融」出來。
     *
     * <h2>兩層：先有整體，再有方塊</h2>
     * <p>{@link #ASSEMBLY} 是**由下往上**——擲幾塊大方體，疊出來的側影是它們的副產品，
     * 所以它永遠讀得出「這是三四塊東西」。這一種反過來：**先決定整體要是什麼形狀**
     * （一團融合的球體場，見 {@link Plot#blob}），再把空間切成方磚，
     * 一磚一磚地問「你的中心在不在那個形狀裡面」。
     *
     * <p>結果是量體的輪廓由外廓決定、表面的顆粒由方磚決定。遠看是一座山，
     * 近看是幾千塊各種尺寸的混凝土盒子互相咬合——而那些盒子不是誰擺的，
     * 是同一個外廓被不同大小的網格取樣出來的。
     *
     * <h2>為什麼用球體場而不是雜訊當外廓</h2>
     * <p>雜訊的等值面會生出**飄在空中的孤島**，而純函數裡沒有辦法回頭檢查連通性。
     * 球體場則是擲的時候就讓每一顆掛在前一顆的半徑之內（見 {@link Plot#rollLobes}），
     * 所以整團一定是連通的、也一定落在地上。懸挑跟拱洞照樣有，但不會有斷掉的碎塊。
     *
     * <h2>方磚的抖動</h2>
     * <p>每一塊磚的門檻各自加一點偏移：有的往外凸出外廓、有的往內縮進去。
     * 沒有這個抖動，表面會精確貼合外廓，看起來像被砂紙磨過的一整塊，
     * 而不是很多塊拼起來的。
     */
    AGGREGATE {
        @Override
        boolean mass(int u, int v, int h, Plot p) {
            long brick = brickCentre(u, v, h, p);
            int bu = (int) (brick >>> 42);
            int bv = (int) ((brick >>> 21) & MASK);
            int bh = (int) (brick & MASK);

            // 先問純粹的外廓場。太遠就直接否決——雜訊與抖動只能推動已經在邊界附近的磚，
            // 不能無中生有，否則天上會飄著幾塊石頭（而純函數沒辦法事後檢查連通性）
            float core = p.blob(bu, bv, bh);
            if (core < 0.09f) return false;

            // 逐磚的門檻偏移：凸出去或縮進來
            float bias = (Math.floorMod(Masonry.hash(bu, bv, bh ^ p.brickSalt()), 1000) / 1000f
                    - 0.44f) * 0.46f;
            return core + p.blobNoise(bu, bv, bh) + bias >= 0.34f;
        }
    };

    /**
     * 磚的尺寸從這裡挑。大小差距要夠大，不然看起來只是一種磚加了公差。
     *
     * <p>尺度的關係是 **整體 ≫ 磚 > 玩家**：一塊磚三到十格，比人大得多（走過去要繞），
     * 但相對於一百多格寬的整團只是一格細胞。磚做大（十幾二十格）的話，一團就只剩十來塊，
     * 讀起來是「幾塊大石頭疊著」而不是「一大團密密麻麻的體塊融在一起」——
     * 那個密度正是這種形狀的全部。
     */
    private static final int[] BRICK = {3, 4, 4, 5, 6, 6, 8, 10};

    /** 每一區用同一種磚尺寸。區的邊界會把磚切開，那些薄片正好是尺度之間的過渡。 */
    private static final int ZONE = 21;

    private static final long MASK = (1L << 21) - 1;

    /**
     * 這一格所屬的磚，**中心**打包成一個 long。
     *
     * <p>回傳打包的整數而不是一個小物件：這是逐格呼叫的最內層，每一格配一次物件太貴。
     */
    static long brickCentre(int u, int v, int h, Plot p) {
        int zu = Math.floorDiv(u, ZONE);
        int zv = Math.floorDiv(v, ZONE);
        int zh = Math.floorDiv(h, ZONE);
        int size = BRICK[Math.floorMod(Masonry.hash(zu, zv, zh ^ p.brickSalt()), BRICK.length)];

        // 磚對齊到自己那一區的原點，所以相鄰兩區的接縫會錯開
        int bu = zu * ZONE + Math.floorDiv(u - zu * ZONE, size) * size + size / 2;
        int bv = zv * ZONE + Math.floorDiv(v - zv * ZONE, size) * size + size / 2;
        int bh = zh * ZONE + Math.floorDiv(h - zh * ZONE, size) * size + size / 2;
        return ((long) bu << 42) | ((long) (bv & MASK) << 21) | (bh & MASK);
    }

    /** 這一格是哪一塊磚，給材質用——每一塊磚各自去雜訊場的不同位置取樣。 */
    static int brickKey(int u, int v, int h, Plot p) {
        long brick = brickCentre(u, v, h, p);
        return Masonry.hash((int) (brick >>> 42), (int) ((brick >>> 21) & MASK), (int) (brick & MASK));
    }

    /** 這個點在不在混凝土量體裡面。邊界已由 {@link Plot#solid} 檢查過。 */
    abstract boolean mass(int u, int v, int h, Plot p);

    private static boolean inside(int u, int v, int inset, Plot p) {
        return u >= inset && u < p.width() - inset
                && v >= inset && v < p.depth() - inset;
    }
}
