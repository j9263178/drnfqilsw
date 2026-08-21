package com.xinbow99.brutalist.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 散落在街道上的候車亭。
 *
 * <h2>它為什麼有效</h2>
 * <p>東歐那些造型離奇的公車亭之所以動人，是因為它們是**人的尺寸的東西被丟在不成比例的
 * 空曠裡**。這個世界正好只有不成比例的空曠：一座三格高、有張長椅的小棚子放在
 * 三百格高的量體腳下，那個對比比再蓋十棟樓有用。
 *
 * <p>所以它一定要有兩樣東西才成立：**看得出是給人坐的地方**，跟**一根站牌**。
 * 少了這兩樣，它就只是一塊小混凝土。
 *
 * <h2>局部座標</h2>
 * <p>{@code u} 沿著長邊，{@code v} 橫過去，{@code h} 是離鋪面多高。站牌在 {@code u = -2}，
 * 所以 {@code u} 的範圍從負的開始。
 */
public record Shelter(int x0, int z0, int baseY, boolean alongX,
                      int len, int wide, int tall, int form,
                      Masonry.Palette palette, int salt) {

    /** 一片背牆加一塊懸出去的頂。最常見，也最像真的。 */
    private static final int LEAN = 0;
    /** 三面牆的盒子，開口朝街。 */
    private static final int BOX = 1;
    /** 一排立鰭夾著長椅，頂是通長的一片。 */
    private static final int FIN = 2;
    /** 半圓的拱頂。 */
    private static final int VAULT = 3;
    /** 兩根細柱撐一片大得離譜的平頂——尺度上的玩笑。 */
    private static final int SLAB = 4;

    private static final BlockState POST = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState BOARD = Blocks.POLISHED_DEEPSLATE.defaultBlockState();

    /**
     * 擲一座，{@code null} ＝ 這一格沒有。
     *
     * @param gx 散佈網格的格號，跟街廓無關——候車亭是散在街上的，不歸任何一格街廓管
     */
    public static Shelter roll(RandomSource r, int gx, int gz, int step, Plot.Terrain terrain) {
        if (r.nextInt(100) < 52) return null;

        boolean alongX = r.nextInt(2) == 0;
        int form = r.nextInt(5);
        // 大的那種可以橫跨十幾格。全部做成小的，它就只是一種重複出現的路邊裝飾
        int len = form == SLAB ? 12 + r.nextInt(8) : 5 + r.nextInt(11);
        int wide = 4 + r.nextInt(form == SLAB ? 5 : 3);
        int tall = 3 + r.nextInt(form == SLAB ? 4 : 2);

        int spanU = len + 4;
        int x0 = gx * step + 6 + r.nextInt(Math.max(1, step - spanU - 12));
        int z0 = gz * step + 6 + r.nextInt(Math.max(1, step - spanU - 12));

        int highest = Integer.MIN_VALUE;
        for (int i = -4; i <= len; i += 4) {
            for (int j = 0; j <= wide; j += 4) {
                highest = Math.max(highest, terrain.heightAt(
                        x0 + (alongX ? i : j), z0 + (alongX ? j : i)));
            }
        }
        return new Shelter(x0, z0, highest + 1, alongX, len, wide, tall, form,
                Masonry.roll(r), r.nextInt());
    }

    public int minX() { return alongX ? x0 - 4 : x0; }
    public int maxX() { return alongX ? x0 + len : x0 + wide; }
    public int minZ() { return alongX ? z0 : z0 - 4; }
    public int maxZ() { return alongX ? z0 + wide : z0 + len; }
    public int maxY() { return baseY + tall + 3; }

    /**
     * 這一柱有沒有鋪面。基座要靠它往下補到地面。
     *
     * <p>**站牌那一柱也算**。它站在亭子外面兩格，不含進來的話它腳下沒有基座，
     * 地面一低就會浮起來一格——一根浮空的站牌比沒有站牌還糟。
     */
    public boolean covers(int wx, int wz) {
        int u = alongX ? wx - x0 : wz - z0;
        int v = alongX ? wz - z0 : wx - x0;
        if (u == -2 && v == wide / 2) return true;
        return u >= 0 && u < len && v >= 0 && v < wide;
    }

    /** 這一格是什麼，{@code null} ＝ 空氣。 */
    public BlockState blockAt(int wx, int wy, int wz) {
        int u = alongX ? wx - x0 : wz - z0;
        int v = alongX ? wz - z0 : wx - x0;
        int h = wy - baseY;
        if (h < 0 || h > tall + 3) return null;

        BlockState sign = sign(u, v, h);
        if (sign != null) return sign;

        if (u < 0 || u >= len || v < 0 || v >= wide) return null;
        if (h == 0) return palette.at(wx, wy, wz);       // 鋪面

        // 長椅：靠背牆那一側，用樓梯方塊——它是唯一一眼看得出「可以坐」的形狀
        if (h == 1 && v == 1 && u > 0 && u < len - 1) {
            return Masonry.stairs(palette.at(wx, wy, wz), out());
        }

        return switch (form) {
            case BOX -> box(u, v, h, wx, wy, wz);
            case FIN -> fin(u, v, h, wx, wy, wz);
            case VAULT -> vault(u, v, h, wx, wy, wz);
            case SLAB -> slab(u, v, h, wx, wy, wz);
            default -> lean(u, v, h, wx, wy, wz);
        };
    }

    /** 長椅面朝街的方向，也就是遠離背牆那一邊。 */
    private Direction out() {
        if (alongX) return Direction.SOUTH;
        return Direction.EAST;
    }

    /**
     * 站牌：一根桿子加一片牌子，立在亭子外面兩格。
     *
     * <p>它比亭子本身還重要——一座沒有牌子的棚子只是棚子，插上牌子它才是**車站**，
     * 而「有人會在這裡等車」正是這個東西唯一要說的事。
     */
    private BlockState sign(int u, int v, int h) {
        if (u != -2 || v != wide / 2) return null;
        if (h == 0) return palette.at(0, 0, 0);          // 站牌腳下的那一小塊鋪面
        if (h >= 1 && h <= 3) return POST;
        if (h == 4 || h == 5) return BOARD;
        return null;
    }

    private BlockState lean(int u, int v, int h, int wx, int wy, int wz) {
        if (v == 0 && h <= tall) return palette.at(wx, wy, wz);
        // 頂往街那一側挑出去，不是剛好蓋住背牆
        return h == tall + 1 ? palette.at(wx, wy, wz) : null;
    }

    private BlockState box(int u, int v, int h, int wx, int wy, int wz) {
        boolean wall = v == 0 || u == 0 || u == len - 1;
        if (wall && h <= tall) {
            // 背牆上開一道長窗，等車的人才看得到外面
            if (v == 0 && h == tall - 1 && u > 1 && u < len - 2) return null;
            return palette.at(wx, wy, wz);
        }
        return h == tall + 1 ? palette.at(wx, wy, wz) : null;
    }

    private BlockState fin(int u, int v, int h, int wx, int wy, int wz) {
        if (h <= tall && Math.floorMod(u, 4) == 0) return palette.at(wx, wy, wz);
        return h == tall + 1 ? palette.at(wx, wy, wz) : null;
    }

    private BlockState vault(int u, int v, int h, int wx, int wy, int wz) {
        double r = (wide - 1) / 2.0;
        double dv = v - r;
        double dh = h - 1;
        double d = Math.sqrt(dv * dv + dh * dh);
        // 拱只有一格厚，所以裡面是空的，人走得進去
        return d <= r + 0.5 && d > r - 0.6 ? palette.at(wx, wy, wz) : null;
    }

    private BlockState slab(int u, int v, int h, int wx, int wy, int wz) {
        if (h <= tall) {
            boolean column = (u == 2 || u == len - 3) && (v == 1 || v == wide - 2);
            return column ? palette.at(wx, wy, wz) : null;
        }
        // 兩格厚的頂。一格的在下面看是一張紙，而這一種的重點就是那片頂有多重
        return h <= tall + 2 ? palette.at(wx, wy, wz) : null;
    }
}
