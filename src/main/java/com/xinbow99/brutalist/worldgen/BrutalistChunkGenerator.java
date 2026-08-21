package com.xinbow99.brutalist.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一個沒有地形的世界：只有一片混凝土地坪，跟站在上面的巨型量體。
 *
 * <h2>為什麼可以完全不管客戶端</h2>
 * <p>地形生成整條管線都不過網路——{@code LEVEL_STEM}（維度連同它的生成器）、
 * {@code NOISE_SETTINGS}、{@code DENSITY_FUNCTION} 都不在
 * {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES} 裡。客戶端收到的只有
 * {@code ClientboundLevelChunkWithLightPacket} 裡算好的方塊，它從來不跑生成器。
 *
 * <h2>三個入口算的是同一件事</h2>
 * <p>{@link #fillFromNoise}（填方塊）、{@link #getBaseHeight}（高度圖）、
 * {@link #getBaseColumn}（結構物取樣）必須給出一致的答案，所以它們共用
 * {@link Plot#blockAt} 這唯一一個「這一格是什麼」的來源。各自寫一份的話，
 * 高度圖跟實際方塊會慢慢對不上，而那種錯誤的症狀是玩家掉進地板或卡在半空。
 */
public class BrutalistChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BrutalistChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            Settings.CODEC.optionalFieldOf("settings", Settings.DEFAULT).forGetter(g -> g.settings)
    ).apply(i, BrutalistChunkGenerator::new));

    /** 街廓亂數的命名空間。同一個世界種子配同一個字串，得到的城市永遠一樣。 */
    private static final Identifier PLOTS = Identifier.parse("brutalist:plots");
    private static final Identifier GROUND = Identifier.parse("brutalist:ground");
    private static final Identifier LINES = Identifier.parse("brutalist:corridors");

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    private final Settings settings;

    /**
     * 街廓 → 量體。
     *
     * <p>一棟樓橫跨幾十個區塊，每個區塊都重擲一次的話不只是慢，而是**會擲出不一樣的結果**
     * ——除非亂數只吃座標，而它確實只吃座標（{@code PositionalRandomFactory.at}）。
     * 所以這個快取純粹是為了速度，拿掉也不會生出破圖。
     *
     * <p>超過上限就整個清掉：這裡不需要真正的 LRU，玩家的移動本來就有局部性，
     * 清完馬上又會把附近那幾格補回來。
     */
    private final ConcurrentHashMap<Long, Optional<Plot>> plots = new ConcurrentHashMap<>();

    /** 街廓邊界線 → 上面的基礎設施。跟 plots 同樣的理由：純粹是為了速度。 */
    private final ConcurrentHashMap<Long, Optional<Corridor>> corridors = new ConcurrentHashMap<>();

    /**
     * 地表侵蝕圖案的鹽，從世界種子導出。
     *
     * <p>沒有它的話 {@link Ground} 只吃座標，於是**每一個世界的草皮長得一模一樣**，
     * 只有建築會變——兩個種子擺在一起看就會發現地面是複製的。
     *
     * <p>兩條執行緒同時算出來也沒關係，值一樣。先寫值再寫旗標，讀到旗標就一定讀得到值。
     */
    private volatile int groundSalt;
    private volatile boolean groundSaltReady;

    private int groundSalt(RandomState random) {
        if (!groundSaltReady) {
            groundSalt = random.getOrCreateRandomFactory(GROUND).at(0, 0, 0).nextInt();
            groundSaltReady = true;
        }
        return groundSalt;
    }

    public BrutalistChunkGenerator(BiomeSource biomeSource, Settings settings) {
        super(biomeSource);
        this.settings = settings;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
                                                        StructureManager structures, ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        int floor = chunk.getMinY();
        int roof = floor + chunk.getHeight() - 1;
        int ground = settings.ground();

        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 地坪。壓得很低（預設 y=8）不只是省事——它把 384 格的世界高度幾乎全部留給天空，
        // 而這個模組要的就是仰望
        int salt = groundSalt(random);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = x0 + lx;
                int wz = z0 + lz;
                int top = Math.clamp(Ground.height(wx, wz, settings, salt), floor + 1, roof);

                put(chunk, ocean, surface, cursor, lx, floor, lz, BEDROCK);
                for (int y = floor + 1; y < top; y++) {
                    put(chunk, ocean, surface, cursor, lx, y, lz,
                            Ground.below(wx, wz, top - y, top, y, settings, salt));
                }
                put(chunk, ocean, surface, cursor, lx, top, lz,
                        Ground.surface(wx, wz, top, settings, salt));

                // 草擺在量體之前，所以被量體壓到的那些會被蓋掉——但架空層底下的會留著，
                // 那正好是野草最該長的地方
                BlockState plant = Ground.plant(wx, wz, top, settings, salt);
                if (plant != null && top + 1 <= roof) {
                    put(chunk, ocean, surface, cursor, lx, top + 1, lz, plant);
                }
            }
        }

        // 量體。只掃「這棟樓跟這個區塊的交集」，所以再高的樓也不會讓單一區塊變慢
        for (Plot plot : overlapping(random, x0, z0)) {
            int ax = Math.max(x0, plot.minX());
            int bx = Math.min(x0 + 15, plot.maxX());
            int az = Math.max(z0, plot.minZ());
            int bz = Math.min(z0 + 15, plot.maxZ());
            int ay = Math.max(floor, plot.minY());
            int by = Math.min(roof, plot.maxY());

            for (int wx = ax; wx <= bx; wx++) {
                for (int wz = az; wz <= bz; wz++) {
                    // 基座：地面比這棟樓的起算高度低的話，往下補到地面為止。
                    // 沒有這一段，坐落在坡上的量體會有一半懸在空中
                    if (plot.footprintSolid(wx, wz)) {
                        int land = Ground.height(wx, wz, settings, salt);
                        int stop = Math.min(plot.minY(), roof + 1);
                        for (int wy = Math.max(floor, land + 1); wy < stop; wy++) {
                            put(chunk, ocean, surface, cursor, wx - x0, wy, wz - z0, plot.skin(wx, wy, wz));
                        }
                    }
                    for (int wy = ay; wy <= by; wy++) {
                        BlockState state = plot.blockAt(wx, wy, wz);
                        if (state != null) {
                            put(chunk, ocean, surface, cursor, wx - x0, wy, wz - z0, state);
                        }
                    }
                }
            }
        }

        // 基礎設施。放在量體之後：它們本來就落在街廓之間的淨空裡，不會重疊，
        // 但萬一參數被調到重疊，讓後蓋的贏比讓先蓋的贏容易理解
        Plot.Terrain terrain = (tx, tz) -> Ground.height(tx, tz, settings, salt);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = x0 + lx;
                int wz = z0 + lz;
                int land = Ground.height(wx, wz, settings, salt);
                lay(chunk, ocean, surface, cursor, corridor(random, true, nearestLine(wz)),
                        wx, wz, lx, lz, land, floor, roof, terrain);
                lay(chunk, ocean, surface, cursor, corridor(random, false, nearestLine(wx)),
                        wx, wz, lx, lz, land, floor, roof, terrain);
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /** 離這個座標最近的那一條街廓邊界線的編號。 */
    private int nearestLine(int w) {
        int cell = settings.cell();
        return Math.floorDiv(w + cell / 2, cell);
    }

    private Corridor corridor(RandomState random, boolean alongX, int lineIndex) {
        long key = ((long) lineIndex << 1) | (alongX ? 1L : 0L);
        Optional<Corridor> cached = corridors.get(key);
        if (cached != null) return cached.orElse(null);

        if (corridors.size() > 4096) corridors.clear();
        Corridor rolled = Corridor.at(lineIndex, alongX, settings, groundSalt(random));
        corridors.put(key, Optional.ofNullable(rolled));
        return rolled;
    }

    private void lay(ChunkAccess chunk, Heightmap ocean, Heightmap surface,
                     BlockPos.MutableBlockPos cursor, Corridor corridor,
                     int wx, int wz, int lx, int lz, int land, int floor, int roof,
                     Plot.Terrain terrain) {
        if (corridor == null || !corridor.covers(wx, wz)) return;
        int lo = Math.max(floor, corridor.lowestY(land));
        int hi = Math.min(roof, corridor.highestY(land));
        for (int wy = lo; wy <= hi; wy++) {
            BlockState state = corridor.blockAt(wx, wy, wz, land, terrain);
            if (state != null) {
                put(chunk, ocean, surface, cursor, lx, wy, lz, state);
            }
        }
    }

    private static void put(ChunkAccess chunk, Heightmap ocean, Heightmap surface,
                            BlockPos.MutableBlockPos cursor, int lx, int y, int lz, BlockState state) {
        cursor.set(lx, y, lz);
        chunk.setBlockState(cursor, state);
        ocean.update(lx, y, lz, state);
        surface.update(lx, y, lz, state);
    }

    /**
     * 這個區塊碰得到的量體。
     *
     * <p>要往回多看 {@code GROUP-1} 格：一棟 2×2 的量體錨在左上那一格，卻會蓋到右下那幾格，
     * 只掃自己這一格的話那些區塊會整片空掉。
     */
    private List<Plot> overlapping(RandomState random, int x0, int z0) {
        int cell = settings.cell();
        List<Plot> found = new ArrayList<>(4);
        for (int cx = Math.floorDiv(x0, cell) - (Plot.GROUP - 1); cx <= Math.floorDiv(x0 + 15, cell); cx++) {
            for (int cz = Math.floorDiv(z0, cell) - (Plot.GROUP - 1); cz <= Math.floorDiv(z0 + 15, cell); cz++) {
                Plot plot = plot(random, cx, cz);
                if (plot != null && plot.maxX() >= x0 && plot.minX() <= x0 + 15
                        && plot.maxZ() >= z0 && plot.minZ() <= z0 + 15) {
                    found.add(plot);
                }
            }
        }
        return found;
    }

    private Plot plot(RandomState random, int cellX, int cellZ) {
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Optional<Plot> cached = plots.get(key);
        if (cached != null) return cached.orElse(null);

        if (plots.size() > 8192) plots.clear();
        // 兩個亂數源：切法要整個超級街廓共用（同一組的四格必須看到同一種切法），
        // 蓋成什麼樣則是每一格自己的事
        PositionalRandomFactory factory = random.getOrCreateRandomFactory(PLOTS);
        int salt = groundSalt(random);
        Plot rolled = Plot.roll(
                factory.at(Math.floorDiv(cellX, Plot.GROUP), 1, Math.floorDiv(cellZ, Plot.GROUP)),
                factory.at(cellX, 0, cellZ),
                settings, cellX, cellZ,
                (wx, wz) -> Ground.height(wx, wz, settings, salt));
        plots.put(key, Optional.ofNullable(rolled));
        return rolled;
    }

    /**
     * 蓋住這一柱的量體。
     *
     * <p>不能只問「這一柱所在的那一格」——那一格可能是被隔壁錨點吃掉的空格，實際蓋住它的
     * 量體錨在別處。高度圖跟柱體取樣都靠這個，問錯了就會回報成空地，玩家會掉進樓裡。
     */
    private Plot plotCovering(RandomState random, int wx, int wz) {
        int cell = settings.cell();
        int cx = Math.floorDiv(wx, cell);
        int cz = Math.floorDiv(wz, cell);
        for (int ax = cx - (Plot.GROUP - 1); ax <= cx; ax++) {
            for (int az = cz - (Plot.GROUP - 1); az <= cz; az++) {
                Plot plot = plot(random, ax, az);
                if (plot != null && plot.coversColumn(wx, wz)) return plot;
            }
        }
        return null;
    }

    /**
     * 地表之上第一個空的高度。
     *
     * <p>刻意不算野草：它不擋移動，玩家站在草上跟站在地上是同一格。把草算進去只會讓
     * 出生點被墊高一格，然後人從空中掉下來。
     */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int land = Ground.height(x, z, settings, groundSalt(random));
        Plot plot = plotCovering(random, x, z);
        if (plot != null) {
            int top = Math.min(plot.maxY(), level.getMinY() + level.getHeight() - 1);
            for (int y = top; y > land; y--) {
                if (plot.blockAt(x, y, z) != null) return y + 1;
            }
            // 沒有量體但有基座（例如架空層底下的柱子）
            if (plot.footprintSolid(x, z) && plot.minY() > land) return plot.minY();
        }
        return land + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int floor = level.getMinY();
        int span = level.getHeight();
        BlockState[] column = new BlockState[span];
        Plot plot = plotCovering(random, x, z);
        int salt = groundSalt(random);
        int land = Ground.height(x, z, settings, salt);

        for (int i = 0; i < span; i++) {
            int y = floor + i;
            BlockState state;
            if (y == floor) {
                state = BEDROCK;
            } else if (y < land) {
                state = Ground.below(x, z, land - y, land, y, settings, salt);
            } else if (y == land) {
                state = Ground.surface(x, z, y, settings, salt);
            } else {
                state = plot == null ? null : plot.blockAt(x, y, z);
                if (state == null && plot != null && plot.footprintSolid(x, z)
                        && y > land && y < plot.minY()) {
                    state = plot.skin(x, y, z);        // 基座
                }
                if (state == null && y == land + 1) {
                    state = Ground.plant(x, z, land, settings, salt);
                }
            }
            column[i] = state == null ? AIR : state;
        }
        return new NoiseColumn(floor, column);
    }

    /**
     * 出生高度。
     *
     * <p>拿不到座標，所以回傳**地形可能達到的最高點**再加一格：寧可從幾格高掉下來，
     * 也不要生在岩石裡面。實際上原版多半是用區塊的高度圖決定出生點，這裡只是保險。
     */
    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return Ground.ceiling(settings);
    }

    @Override
    public int getSeaLevel() {
        return settings.ground();
    }

    @Override
    public int getMinY() {
        return settings.minY();
    }

    @Override
    public int getGenDepth() {
        return settings.height();
    }

    /** 沒有洞穴。這個世界的空隙都是設計出來的，不是挖出來的。 */
    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
                             BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {
    }

    /** 沒有表層處理：草、沙、礫石在這裡都沒有意義，{@link #fillFromNoise} 已經是最終樣貌。 */
    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures,
                             RandomState random, ChunkAccess chunk) {
    }

    /** 不生成初始生物群。 */
    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos) {
        Plot plot = plotCovering(random, pos.getX(), pos.getZ());
        lines.add(plot == null
                ? "Brutalist: 廣場"
                : "Brutalist: 量體 " + plot.width() + "x" + plot.depth() + "x" + plot.height());
    }
}
