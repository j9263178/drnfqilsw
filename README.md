# Brutalist World

Minecraft 26.2 的 Fabric 模組。一個沒有自然地形的世界類型：起伏的岩盤上散落巨型的粗獷主義量體，穿插廢棄的高架橋與高壓電塔。

**伺服器端專用——沒裝模組的原版客戶端可以正常連線。**

## 安裝

單機：把 jar 跟 [Fabric API](https://modrinth.com/mod/fabric-api) 一起丟進 `mods/`，建立世界時 **Create New World → World Type** 切到 **粗獷主義 / Brutalism**。

伺服器：同樣丟進 `mods/`，然後在 `server.properties` 設

```properties
level-type=brutalist\:brutalism
```

反斜線要留著——Java 的 properties 格式會吃掉沒跳脫的冒號。

只有開伺服器的人需要裝，連進來的人不用。

## 世界長什麼樣

- 地面在 y=20 上下起伏 ±8，往上到 y=383 都是天空
- 每 160 格一個街廓、一棟量體；2×2 街廓為一組，會出現橫跨兩格的巨型量體
- 六種量體：板樓、梯形、倒梯形、核心懸挑、圓筒、穿孔牆
- 石頭／鵝卵石／安山岩／凝灰岩／深板岩混合，每棟一份配方，表面有稀疏破損
- 每 320 格的邊界線上可能有高架橋（有塌掉的跨距）或高壓電塔線（導線會斷）

## 調整

尺度都在 `src/main/resources/data/brutalist/worldgen/world_preset/brutalism.json` 的 `settings`，改完開新世界就生效，不用重編：

| 欄位 | 意思 |
|---|---|
| `cell` | 街廓間距，一格一棟 |
| `street` | 街廓邊緣的內縮寬度。也是高架與電塔的可用淨空 |
| `ground` / `relief` | 地面基準高度／起伏振幅 |
| `min_height` / `max_height` | 量體高度範圍 |
| `density` | 有多少比例的街廓真的蓋東西 |
| `min_y` / `world_height` | 必須跟 `dimension_type/brutalism.json` 對得上 |

比例、門檻那類美感參數在程式碼裡，寫在各個類別上方。

## 開發

```bash
./gradlew runClient     # 開發用客戶端
./gradlew runServer     # 開發用伺服器
./gradlew build         # 產出 build/libs/
```
