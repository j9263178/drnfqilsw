# Brutalist World

Minecraft 26.2 的 Fabric 模組。一個沒有自然地形的世界類型：起伏的岩盤上散落著巨型的粗獷主義量體，穿插廢棄的高架橋與高壓電塔。

伺服器端專用——**沒裝模組的原版客戶端可以正常連線**，原因見下面〈為什麼不用改客戶端〉。

## 跑起來

```bash
./gradlew runClient
```

進去之後 **Create New World → World Type**，切到 **粗獷主義 / Brutalism**。

專用伺服器：把 `build/libs/brutalist-*.jar` 跟 Fabric API 一起丟進 `mods/`，然後在 `server.properties` 設

```properties
level-type=brutalist\:brutalism
```

（那個反斜線要留著，Java 的 properties 格式會吃掉沒跳脫的冒號。）

## 世界長什麼樣

- 地面在 y=20 上下起伏 ±8，往上一路到 y=383 都是天空
- 每 160 格一個街廓，每個街廓上有一棟量體；2×2 的街廓組成一個超級街廓，整組一次決定怎麼切，所以會出現橫跨兩格的巨型量體
- 六種量體：板樓、梯形、倒梯形、核心懸挑、圓筒、穿孔牆
- 材質是石頭／鵝卵石／安山岩／凝灰岩／深板岩的混合，每棟一份配方
- 表面有稀疏的自然破損，破口邊緣是碎掉的鵝卵石
- 每 320 格的邊界線上可能有高架橋（會有塌掉的跨距）或高壓電塔線（導線會斷）

## 怎麼調

尺度全部在 `src/main/resources/data/brutalist/worldgen/world_preset/brutalism.json` 的 `settings` 裡，改完開新世界就生效，不用重編：

| 欄位 | 意思 |
|---|---|
| `cell` | 街廓間距，一格一棟 |
| `street` | 街廓邊緣留給街道的內縮寬度。**也是高架與電塔的可用淨空** |
| `ground` | 地面的基準高度 |
| `relief` | 地面起伏的振幅（格），0 ＝ 完全平坦 |
| `min_height` / `max_height` | 量體的高度範圍 |
| `density` | 有多少比例的街廓真的蓋東西 |
| `min_y` / `world_height` | **必須跟 `dimension_type/brutalism.json` 對得上** |

比例與門檻那類美感參數在程式碼裡，各自寫在相關的類別上方。

## 架構

### 一切都是座標的純函數

`ChunkGenerator` 一次只填一個 16×16 的區塊，而且**區塊之間是平行跑的、順序不保證**。所以一棟橫跨幾十個區塊的量體不能靠「先蓋好再切開」——它必須是一個 `(x,y,z) → 方塊` 的純函數，每個區塊各自算自己那一塊，算出來自然接得起來。

這條限制決定了整份程式碼的長相：沒有任何生成期間的狀態，所有隨機性都來自 `PositionalRandomFactory.at(...)`，而它只吃座標與世界種子。快取（`plots`、`corridors`）純粹是為了速度，拿掉也不會生出破圖。

### 跨格的東西怎麼不打架

要讓量體大小有差異就得允許它跨格。但如果每一格各自擲「我要往外佔幾格」，相鄰兩格會搶同一塊地，而**解決搶地需要往回追溯**：A 要看 B 是不是錨點，B 要看 C……這條鏈沒有上界，在區塊生成裡不能接受。

改成每 2×2 格一個超級街廓，**整組一次擲出怎麼切**。切法完全落在組內，跨組永遠不會衝突，每一格仍然是 O(1)。

### 線性構造物靠擺放規則避開建築

量體永遠內縮 `street` 格才開始，所以街廓邊界兩側各有 `street` 格是保證空的。高架與電塔的中心線壓在邊界上、寬度不超過那個淨空，就天生不可能撞到建築——一行碰撞檢查都不用寫。

但只有**超級街廓**的邊界（`cell * 2` 的倍數）才算數：2×2 的量體會橫跨自己那一組裡面的那條內部邊界。

### 三個入口必須一致

`fillFromNoise`（填方塊）、`getBaseHeight`（高度圖）、`getBaseColumn`（結構物取樣）要給出一致的答案，所以它們共用 `Plot.blockAt` 這唯一一個「這一格是什麼」的來源。各自寫一份的話，高度圖跟實際方塊會慢慢對不上，而那種錯誤的症狀是玩家掉進地板或卡在半空。

## 為什麼不用改客戶端

地形生成整條管線都**不過網路**。`RegistryDataLoader.SYNCHRONIZED_REGISTRIES` 裡沒有 `LEVEL_STEM`（維度連同它的生成器）、`NOISE_SETTINGS`、`DENSITY_FUNCTION`、`STRUCTURE` 這些；客戶端收到的只有 `ClientboundLevelChunkWithLightPacket` 裡算好的方塊，它從來不跑生成器。

Java 那一側，Fabric 只把「封包裡會用原始 id 指涉」的 registry 標成 `SYNCED`（`BLOCK`、`ITEM`、`ENTITY_TYPE`、`SOUND_EVENT`…）。`CHUNK_GENERATOR` 不在那份清單裡，所以這個模組註冊的東西在網路上完全看不見。

會送到客戶端的只有 `brutalist:brutalism` 這個 dimension_type 跟 `brutalist:concrete` 這個 biome，而那兩個是純資料，走原版自己的 registry 同步——原版客戶端讀得懂，就像連到一個裝了資料包的伺服器一樣。

## 檔案

| 檔案 | 負責 |
|---|---|
| `Brutalist.java` | 註冊生成器的 codec。整個模組就這一件事 |
| `BrutalistChunkGenerator.java` | 填區塊、地面、高度圖，把各部分組起來 |
| `Settings.java` | world_preset 讀進來的尺度參數 |
| `Plot.java` | 一格街廓上的量體：超級街廓的切法、立面、破損 |
| `Form.java` | 六種量體形狀的字彙表 |
| `Masonry.java` | 石材配方與三維雜訊 |
| `Ground.java` | 地面起伏、土壤侵蝕、植被 |
| `Corridor.java` | 高架橋與高壓電塔線 |
