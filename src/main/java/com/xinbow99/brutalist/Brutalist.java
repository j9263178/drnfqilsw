package com.xinbow99.brutalist;

import com.xinbow99.brutalist.worldgen.BrutalistChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 註冊地形生成器。整個模組就這一件事——其餘都是 JSON。
 *
 * <h2>為什麼註冊這個不會擋掉原版客戶端</h2>
 * <p>Fabric 只把「封包裡會用原始 id 指涉」的 registry 標成 {@code SYNCED}：
 * {@code BLOCK}、{@code ITEM}、{@code ENTITY_TYPE}、{@code SOUND_EVENT}… 往那些裡面加東西，
 * id 對照表就會跟原版對不上，沒裝模組的人連不進來。
 *
 * <p>{@code CHUNK_GENERATOR} 不在那份清單裡，因為沒有任何封包提到生成器——
 * 客戶端只收算好的方塊。所以這裡註冊的東西，網路上完全看不見。
 */
public class Brutalist implements ModInitializer {

    public static final String MOD_ID = "brutalist";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Registry.register(
                BuiltInRegistries.CHUNK_GENERATOR,
                Identifier.parse(MOD_ID + ":brutalist"),
                BrutalistChunkGenerator.CODEC);

        LOGGER.info("Brutalist world type registered");
    }
}
