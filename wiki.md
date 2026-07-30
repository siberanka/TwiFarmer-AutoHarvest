# Farmer AutoHarvest Wiki

## Türkçe

### Gereksinimler ve kurulum

| Bileşen | Gereksinim |
| --- | --- |
| Farmer | v6-b125 veya daha yeni uyumlu sürüm |
| Sunucu | Paper 1.21.x / 26.x, Leaf veya Folia |
| Java | 1.21.x için Java 21, 26.x için Java 25 |

1. Sunucuyu durdurun.
2. Modül JAR dosyasını `plugins/Farmer/modules/` klasörüne yerleştirin.
3. Sunucuyu başlatın.
4. `plugins/Farmer/modules/autoharvest/config.yml` içinde `status: true` yapın.
5. Farmer'ı yeniden yükleyin veya sunucuyu yeniden başlatın.

Bu modül bağımsız bir Bukkit eklentisi değildir; normal `plugins` klasörüne kurulmaz.

### Kullanım

Modül açıkken Farmer ana menüsündeki modüller bölümünde **Otomatik Hasat** görünür. `customPerm` iznine sahip kullanıcı modülü ilgili Farmer için açıp kapatabilir. Olgun ürün algılandığında:

1. Ürünün izin verilen türlerden olduğu doğrulanır.
2. Konumun doğru Farmer alanında olduğu ve modülün gereken seviyede açıldığı denetlenir.
3. Stok kontrolü açıksa ana ürün için yeterli Farmer kapasitesi aranır.
4. Kırma ve yeniden dikme işlemi konumun sahibi olan Paper/Folia bölge iş parçacığında yapılır.
5. Ürün Farmer'ın sanal stokuna gönderilir.

Kemik tozunun art arda kullanılması yinelenen blok işleri üretmez; aynı konum bekleyen iş kuyruğunda birleştirilir ve ürün yeniden olgunlaştığında tekrar izlenir.

### Komutlar

AutoHarvest ayrı bir komut kaydetmez. Kurulum ve yeniden yükleme için Farmer'ın `/farmer` ve `/farmer reload` komutları kullanılır.

### İzinler

| İzin | Açıklama |
| --- | --- |
| `farmer.autoharvest` | Varsayılan `customPerm`; Farmer menüsünden AutoHarvest durumunu değiştirmeye izin verir. |
| `farmer.admin` | Farmer yönetimi ve AutoHarvest güncelleme bildirimlerini alır. |

`customPerm` düğümü modül yapılandırmasından değiştirilebilir.

### Seviye kilidi

`required-farmer-level` bir tabanlı Farmer seviyesidir ve varsayılanı `1` değeridir. Daha yüksek bir değer verildiğinde:

- Düşük seviyeli mevcut Farmer'larda modül hemen etkisiz olur.
- Kullanıcı modülü açıp kapatamaz.
- Önceden kaydedilmiş tercih silinmez.
- Farmer gereken seviyeye yükseldiğinde veya gereksinim düşürüldüğünde tercih tekrar uygulanır.
- Farmer yükseltme menüsü modülün hangi seviyede açılacağını gösterir.

`withoutFarmer` kipinde doğrulanabilecek bir Farmer seviyesi bulunmadığından bu kip yalnızca `required-farmer-level: 1` iken çalışır.

### Ana yapılandırma

Dosya: `plugins/Farmer/modules/autoharvest/config.yml`

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `config-version` | `13` | Dosya şeması sürümü; elle değiştirilmemelidir. |
| `status` | `false` | Modülü ve Farmer menü girişini açar. |
| `requirePiston` | `false` | Ürünün toplanması için üzerinde piston bulunmasını zorunlu kılar. |
| `checkAllDirections` | `false` | Piston şartı açıkken yatay yönleri de denetler. |
| `withoutFarmer` | `false` | İzin verilen dünyalarda Farmer alanı dışında çalışmayı açar; yalnızca gereken seviye `1` iken geçerlidir. |
| `checkStock` | `true` | Ana ürün stoğu doluysa hasadı engeller. Tohum gibi ikincil ürünler ana ürünü engellemez. |
| `defaultStatus` | `false` | Yeni oluşturulan Farmer'larda başlangıç AutoHarvest durumudur. |
| `required-farmer-level` | `1` | Modülün kullanılabildiği en düşük Farmer seviyesi. |
| `customPerm` | `farmer.autoharvest` | Menüden durum değiştirme izni. |
| `items` | `WHEAT`, `CARROT`, `POTATO`, `PUMPKIN` | İşlenecek temel ürünlerin malzeme adları. |

### Sütun ürünleri

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `stacked-crops.enable` | `true` | Dikey büyüyen ürünleri sütun halinde toplar. |
| `stacked-crops.items` | `SUGAR_CANE`, `CACTUS`, `BAMBOO`, `KELP` | Sütun işlemesine katılan türler. |
| `stacked-crops.max-segments-per-harvest` | `32` | Tek işlemde izin verilen en yüksek sütun parçası. Daha uzun sütun kısmen kırılmaz. |

### Güncelleme ve günlük

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `update-checker.enable` | `true` | Kararlı GitHub sürümlerini arka planda denetler. |
| `update-checker.check-interval-hours` | `6` | Denetim aralığı. |
| `update-checker.connect-timeout-seconds` | `5` | Bağlantı zaman aşımı. |
| `update-checker.request-timeout-seconds` | `8` | İstek zaman aşımı. |
| `logging.debug` | `false` | Kuyruk doluluğu ve dönemsel izleme istatistiklerini konsola yazar. |
| `logging.debug-interval-seconds` | `300` | Dönemsel tanılama kayıtlarının en kısa aralığı. |
| `logging.error-file.max-size-megabytes` | `5` | Etkin `error.log` dosyasının döndürülmeden önceki sınırı. |
| `logging.error-file.history-files` | `2` | Etkin dosyaya ek olarak tutulacak eski hata dosyası sayısı. |

Beklenmeyen hatalar `plugins/Farmer/modules/autoharvest/error.log` dosyasına sınırlı bir kuyruk üzerinden yazılır. Dünya/bölge iş parçacığında dosya I/O yapılmaz.

### Optimizasyon anahtarı

`optimize-module.enable` varsayılan olarak `false` değerindedir. Altındaki yapılandırılabilir değerler yalnızca bu anahtar açıkken uygulanır. Sabit güvenlik sınırları anahtar kapalıyken de etkin kalır.

#### Hasat hızı

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `optimize-module.harvest.max-harvests-per-tick` | `32` | Bütün dünya ve Folia bölgelerinde bir sunucu tick'i için ortak hasat sınırı. |
| `optimize-module.harvest.separate-speed-for` | `FARMER` | Hız grubu: `PLAYER`, `FARMER`, `LAND` veya `CHUNK`. |
| `...delay-between-harvests.enable` | `false` | Aynı gruptaki hasatlar arasına sabit gecikme ekler. |
| `...delay-between-harvests.ticks` | `2` | Gecikme açıkken işlemler arasındaki tick sayısı. |
| `...pause-after-batch.enable` | `false` | Belirli sayıda hasattan sonra grup molasını açar. |
| `...pause-after-batch.after-harvests` | `64` | Moladan önceki hasat sayısı. |
| `...pause-after-batch.ticks` | `20` | Mola süresi. |

Tablodaki `...` öneki `optimize-module.harvest` yolunu temsil eder.

#### Ürün arama

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `optimize-module.crop-search.mode` | `EVENTS` | `EVENTS`, `TIMER` veya `BOTH`. |
| `...triggers.natural-growth` | `true` | Doğal büyümeyle olgunlaşan ürünleri izler. |
| `...triggers.bone-meal` | `true` | Kemik tozu ve gübreleme değişikliklerini izler. |
| `...triggers.crop-placement` | `true` | Oyuncunun yerleştirdiği desteklenen ürünleri izler. |
| `...triggers.chunk-load` | `false` | Her chunk yüklenişinde tarama başlatır; büyük sunucularda dikkatli kullanılmalıdır. |
| `...triggers.new-farmer` | `true` | Yeni Farmer alındığında görünür alanı tarar. |
| `...triggers.player-join` | `true` | Oyuncu katılımında ve modül yüklenişinde yakındaki alanları tarar. |
| `...triggers.player-sees-chunk` | `true` | Hareket sonrası görünür olan chunk'ı yeniden denetler. |
| `...triggers.entire-loaded-farmer-area` | `true` | Etkin Farmer alanlarının yüklü chunk'larını adil sırayla tarar. |
| `...triggers.farmer-areas-only` | `true` | `withoutFarmer` kapalıyken Farmer alanı dışındaki tetikleri reddeder. |
| `...scan-radius.new-farmer-radius-chunks` | `8` | Yeni Farmer için görünür tarama yarıçapı. |
| `...scan-radius.player-radius-chunks` | `3` | Oyuncu etrafındaki tarama yarıçapı. |
| `...repeat-search.every-ticks` | `200` | Kontrollü tekrar taraması aralığı. |
| `...repeat-search.chunks-per-run` | `2` | Bir geçişte ilerletilen alan/chunk imleci sayısı. |
| `...priority.enable` | `true` | Daha çok olgun ürün içeren chunk'lara öncelik verir. |
| `...priority.prioritized-scans-before-normal` | `3` | Normal keşif taramasından önceki en yüksek öncelikli tarama sayısı. |

Bu tablodaki `...` öneki `optimize-module.crop-search` yolunu temsil eder.

#### Arama sınırları

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `...limits.remembered-chunks` | `8192` | Hatırlanan ürün/chunk önbelleği sınırı. |
| `...limits.scans-at-once` | `1` | Aynı anda çalışan chunk taraması. |
| `...limits.snapshots-per-tick` | `1` | Tick başına bölge iş parçacığından istenen snapshot. |
| `...limits.new-scans-per-second` | `4` | Saniye başına başlatılan yeni tarama. |
| `...limits.sections-per-second` | `32` | Saniye başına denetlenen chunk bölümü. |
| `...limits.blocks-per-async-task` | `8192` | Bir asenkron görevdeki en yüksek blok okuması. |
| `...limits.waiting-scans` | `4096` | Bellekte bekleyen benzersiz tarama. |
| `...limits.crops-found-per-scan` | `512` | Bir taramanın döndürebileceği olgun ürün. |
| `...limits.crops-queued-per-tick` | `32` | Bir bölge tick'inde kuyruğa eklenen ürün. |

Buradaki `...` öneki `optimize-module.crop-search` yolunu temsil eder. Kuyruk dolduğunda alan adil tarama sırasından çıkarılmaz; sonraki geçişte yeniden denenir.

#### Sunucu yükü koruması

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `optimize-module.server-load-protection.enable` | `true` | Yük duyarlı hız azaltmayı açar. |
| `...slow-down-at-mspt` | `35.0` | Üzerinde iş miktarının azaltılmaya başlandığı MSPT. |
| `...stop-at-mspt` | `45.0` | Üzerinde yeni işin geçici durdurulduğu MSPT. |
| `...resume-below-mspt` | `40.0` | İşin yeniden başladığı MSPT. |
| `...minimum-speed-percent` | `10` | Yavaşlama sırasındaki en düşük çalışma yüzdesi. |
| `...check-every-ticks` | `20` | MSPT değerlendirme aralığı. |
| `...region-delay-limit-millis` | `100` | Geç kalmış Folia/Leaf bölge geri çağrısı eşiği. |
| `...region-recovery-ticks` | `100` | Etkilenen bölgenin soğuma süresi. |

#### Gelişmiş kuyruk

| Ayar | Varsayılan | Açıklama |
| --- | --- | --- |
| `optimize-module.advanced.harvest-queue.first-run-delay-ticks` | `2` | Yeni etkin chunk kuyruğunun ilk çalışma gecikmesi. |
| `...next-run-delay-ticks` | `1` | Dolu kuyruktaki sonraki bölge çalışmaları arasındaki en kısa gecikme. |
| `...harvests-per-run` | `8` | Bir chunk/bölge çalışmasında doğrulanan hasat girişimi. |
| `...region-runs-per-tick` | `8` | Genel dağıtıcının tick başına gönderebildiği bölge çalışması. |
| `...waiting-harvests` | `8192` | Bellekte bekleyen hasat işi. |
| `...merge-duplicate-blocks` | `true` | Bekleyen aynı blok için yinelenen büyüme sinyallerini birleştirir. |

Buradaki `...` öneki `optimize-module.advanced.harvest-queue` yolunu temsil eder.

### Dil dosyaları

Modül Farmer'ın seçili dilini kullanır ve `plugins/Farmer/modules/autoharvest/lang/` altında `en.yml`, `tr.yml` ve `de.yml` sağlar. Modül adı, açık/kapalı/kilitli durum, seviye gereksinimi, menü açıklamaları, güncelleme bildirimi ve oyuncuya gösterilen diğer metinler buradan düzenlenir.

### Otomatik dosya bakımı

Başlangıçta ve Farmer yeniden yüklemesinde yapılandırma ile paketli dil dosyaları denetlenir. Eksik bilinen girdiler eklenir; geçersiz YAML, yanlış türler, boş zorunlu metinler, bozuk izinler, bilinmeyen ürünler, tekrarlar ve güvenli aralık dışındaki sayılar düzeltilir. Bilinmeyen ek anahtarlar korunur.

Değişiklikten önce:

| Dosya | Yedek konumu |
| --- | --- |
| `config.yml` | `plugins/Farmer/modules/autoharvest/backups/` |
| `lang/*.yml` | `plugins/Farmer/modules/autoharvest/lang/backups/` |

### Sorun giderme

- Menü girişi yoksa `status: true` ve doğru JAR konumunu kontrol edin.
- Modül kilitliyse Farmer seviyesini `required-farmer-level` ile karşılaştırın.
- Kullanıcı durumu değiştiremiyorsa `customPerm` iznini kontrol edin.
- Kemik tozuyla olgunlaşan ürün algılanmıyorsa `crop-search.mode` değerinin `EVENTS` veya `BOTH`, `triggers.bone-meal` değerinin `true` olduğunu doğrulayın.
- Ürün olgun olduğu halde alınmıyorsa `items`, Farmer bölgesi, `checkStock` ve Farmer kapasitesini kontrol edin.
- Tanılama için geçici olarak `logging.debug: true` kullanın; beklenmeyen hatalar için `error.log` dosyasını inceleyin.

### Derleme

```bash
mvn -o clean package
```

Üretilen modül JAR dosyası `target/` klasöründedir.

---

## English

### Requirements and installation

| Component | Requirement |
| --- | --- |
| Farmer | v6-b125 or a newer compatible build |
| Server | Paper 1.21.x / 26.x, Leaf, or Folia |
| Java | Java 21 for 1.21.x, Java 25 for 26.x |

1. Stop the server.
2. Place the module JAR in `plugins/Farmer/modules/`.
3. Start the server.
4. Set `status: true` in `plugins/Farmer/modules/autoharvest/config.yml`.
5. Reload Farmer or restart the server.

This module is not a standalone Bukkit plugin and does not belong in the normal `plugins` directory.

### Usage

When enabled, **Auto Harvest** appears in the modules section of the Farmer menu. A user with `customPerm` may toggle it for that Farmer. When a mature crop is detected:

1. The crop is validated against the configured types.
2. The location, Farmer region, and required level are verified.
3. If stock checking is enabled, Farmer must have room for the primary product.
4. Harvesting and replanting run on the Paper/Folia region thread that owns the location.
5. Products are committed to Farmer's virtual stock.

Repeated bone-meal use does not create duplicate block jobs. Pending work for the same location is merged, and the crop is tracked again when it reaches maturity later.

### Commands

AutoHarvest registers no separate commands. Use Farmer's `/farmer` and `/farmer reload` commands for management and reloads.

### Permissions

| Permission | Description |
| --- | --- |
| `farmer.autoharvest` | Default `customPerm`; allows AutoHarvest to be toggled through the Farmer menu. |
| `farmer.admin` | Farmer administration and AutoHarvest update notifications. |

The `customPerm` node may be changed in the module configuration.

### Level gate

`required-farmer-level` is a one-based Farmer level and defaults to `1`. With a higher value:

- Existing lower-level Farmers stop running the module immediately.
- Users cannot toggle the module below the requirement.
- The previously saved preference is retained.
- The preference becomes effective again after the Farmer reaches the level or the requirement is lowered.
- Farmer's upgrade menu shows the level at which the module unlocks.

Because `withoutFarmer` has no Farmer level to validate, that mode operates only while `required-farmer-level` is `1`.

### Main configuration

File: `plugins/Farmer/modules/autoharvest/config.yml`

| Setting | Default | Description |
| --- | --- | --- |
| `config-version` | `13` | File schema version; do not edit manually. |
| `status` | `false` | Enables the module and Farmer menu entry. |
| `requirePiston` | `false` | Requires a piston above a crop before harvesting. |
| `checkAllDirections` | `false` | Includes horizontal checks while piston mode is enabled. |
| `withoutFarmer` | `false` | Allows operation outside Farmer areas in allowed worlds; valid only at required level `1`. |
| `checkStock` | `true` | Prevents harvest when primary-product stock is full. Secondary drops such as seeds do not block it. |
| `defaultStatus` | `false` | Initial AutoHarvest state for newly created Farmers. |
| `required-farmer-level` | `1` | Lowest Farmer level that may use the module. |
| `customPerm` | `farmer.autoharvest` | Permission required to toggle the menu state. |
| `items` | `WHEAT`, `CARROT`, `POTATO`, `PUMPKIN` | Base material names handled by the module. |

### Stacked crops

| Setting | Default | Description |
| --- | --- | --- |
| `stacked-crops.enable` | `true` | Harvests vertically growing crops as columns. |
| `stacked-crops.items` | `SUGAR_CANE`, `CACTUS`, `BAMBOO`, `KELP` | Types included in column processing. |
| `stacked-crops.max-segments-per-harvest` | `32` | Largest accepted column in one operation. Taller columns are not partially broken. |

### Updates and logging

| Setting | Default | Description |
| --- | --- | --- |
| `update-checker.enable` | `true` | Checks stable GitHub releases asynchronously. |
| `update-checker.check-interval-hours` | `6` | Release-check interval. |
| `update-checker.connect-timeout-seconds` | `5` | Connection timeout. |
| `update-checker.request-timeout-seconds` | `8` | Request timeout. |
| `logging.debug` | `false` | Writes queue saturation and periodic tracking statistics to console. |
| `logging.debug-interval-seconds` | `300` | Minimum interval between periodic diagnostics. |
| `logging.error-file.max-size-megabytes` | `5` | Active `error.log` size before rotation. |
| `logging.error-file.history-files` | `2` | Rotated error files retained in addition to the active file. |

Unexpected errors are written through a bounded queue to `plugins/Farmer/modules/autoharvest/error.log`. File I/O never runs on a world or region thread.

### Optimization switch

`optimize-module.enable` defaults to `false`. Configurable children apply only while it is enabled. Fixed safety bounds remain active when it is disabled.

#### Harvest speed

| Setting | Default | Description |
| --- | --- | --- |
| `optimize-module.harvest.max-harvests-per-tick` | `32` | Shared per-tick harvest limit across worlds and Folia regions. |
| `optimize-module.harvest.separate-speed-for` | `FARMER` | Pacing group: `PLAYER`, `FARMER`, `LAND`, or `CHUNK`. |
| `...delay-between-harvests.enable` | `false` | Adds a steady delay between harvests in one group. |
| `...delay-between-harvests.ticks` | `2` | Tick delay while the feature is enabled. |
| `...pause-after-batch.enable` | `false` | Enables a group pause after a harvest batch. |
| `...pause-after-batch.after-harvests` | `64` | Harvests before each pause. |
| `...pause-after-batch.ticks` | `20` | Pause duration. |

Here `...` means the `optimize-module.harvest` prefix.

#### Crop search

| Setting | Default | Description |
| --- | --- | --- |
| `optimize-module.crop-search.mode` | `EVENTS` | `EVENTS`, `TIMER`, or `BOTH`. |
| `...triggers.natural-growth` | `true` | Tracks crops reaching maturity through natural growth. |
| `...triggers.bone-meal` | `true` | Tracks bone-meal and fertilization changes. |
| `...triggers.crop-placement` | `true` | Tracks supported crop blocks placed by players. |
| `...triggers.chunk-load` | `false` | Scans on every chunk-load signal; use carefully on large servers. |
| `...triggers.new-farmer` | `true` | Scans the visible area after a Farmer is created. |
| `...triggers.player-join` | `true` | Scans nearby areas on join and module load. |
| `...triggers.player-sees-chunk` | `true` | Rechecks chunks that become visible after movement. |
| `...triggers.entire-loaded-farmer-area` | `true` | Fairly rotates loaded chunks of active Farmer areas. |
| `...triggers.farmer-areas-only` | `true` | Rejects triggers outside Farmer areas unless `withoutFarmer` applies. |
| `...scan-radius.new-farmer-radius-chunks` | `8` | Visible scan radius for a new Farmer. |
| `...scan-radius.player-radius-chunks` | `3` | Scan radius around players. |
| `...repeat-search.every-ticks` | `200` | Bounded reconciliation interval. |
| `...repeat-search.chunks-per-run` | `2` | Area/chunk cursor steps per pass. |
| `...priority.enable` | `true` | Prioritizes chunks with more mature crops. |
| `...priority.prioritized-scans-before-normal` | `3` | Maximum priority scans before one normal discovery scan. |

Here `...` means the `optimize-module.crop-search` prefix.

#### Search limits

| Setting | Default | Description |
| --- | --- | --- |
| `...limits.remembered-chunks` | `8192` | Crop/chunk cache limit. |
| `...limits.scans-at-once` | `1` | Concurrent chunk scans. |
| `...limits.snapshots-per-tick` | `1` | Region-thread snapshots requested per tick. |
| `...limits.new-scans-per-second` | `4` | New scans started per second. |
| `...limits.sections-per-second` | `32` | Chunk sections inspected per second. |
| `...limits.blocks-per-async-task` | `8192` | Block reads in one asynchronous task. |
| `...limits.waiting-scans` | `4096` | Unique scans waiting in memory. |
| `...limits.crops-found-per-scan` | `512` | Mature crops returned by one scan. |
| `...limits.crops-queued-per-tick` | `32` | Crops added to harvest queues in one region tick. |

Here `...` means the `optimize-module.crop-search` prefix. A full queue does not remove an area from fair rotation; it is retried later.

#### Server load protection

| Setting | Default | Description |
| --- | --- | --- |
| `optimize-module.server-load-protection.enable` | `true` | Enables load-aware scaling. |
| `...slow-down-at-mspt` | `35.0` | MSPT above which work begins to scale down. |
| `...stop-at-mspt` | `45.0` | MSPT above which new work pauses. |
| `...resume-below-mspt` | `40.0` | MSPT below which work resumes. |
| `...minimum-speed-percent` | `10` | Lowest work percentage during slowdown. |
| `...check-every-ticks` | `20` | MSPT evaluation interval. |
| `...region-delay-limit-millis` | `100` | Late Folia/Leaf region-callback threshold. |
| `...region-recovery-ticks` | `100` | Cooldown for the affected region. |

#### Advanced queue

| Setting | Default | Description |
| --- | --- | --- |
| `optimize-module.advanced.harvest-queue.first-run-delay-ticks` | `2` | First-run delay for a newly active chunk queue. |
| `...next-run-delay-ticks` | `1` | Minimum delay between later region runs. |
| `...harvests-per-run` | `8` | Validated harvest attempts per chunk/region run. |
| `...region-runs-per-tick` | `8` | Region runs submitted by the dispatcher per tick. |
| `...waiting-harvests` | `8192` | Harvest jobs waiting in memory. |
| `...merge-duplicate-blocks` | `true` | Merges repeated growth signals for one pending block. |

Here `...` means the `optimize-module.advanced.harvest-queue` prefix.

### Language files

The module follows Farmer's selected language and provides `en.yml`, `tr.yml`, and `de.yml` under `plugins/Farmer/modules/autoharvest/lang/`. The module name, enabled/disabled/locked states, level requirement, menu descriptions, update notice, and other player-facing text are editable there.

### Automatic file maintenance

Configuration and bundled language files are validated on startup and Farmer reload. Missing known entries are added; invalid YAML, wrong types, empty required text, malformed permissions, unknown crops, duplicates, and unsafe numeric ranges are repaired. Unknown extension keys are preserved.

Before modification:

| File | Backup location |
| --- | --- |
| `config.yml` | `plugins/Farmer/modules/autoharvest/backups/` |
| `lang/*.yml` | `plugins/Farmer/modules/autoharvest/lang/backups/` |

### Troubleshooting

- If no menu entry exists, check `status: true` and the module JAR location.
- If the module is locked, compare the Farmer level with `required-farmer-level`.
- If a user cannot toggle it, verify `customPerm`.
- If bone-meal maturity is missed, use `EVENTS` or `BOTH` and ensure `triggers.bone-meal` is `true`.
- If a mature crop remains, check `items`, Farmer region ownership, `checkStock`, and Farmer capacity.
- Temporarily enable `logging.debug` for diagnostics and inspect `error.log` for unexpected failures.

### Building

```bash
mvn -o clean package
```

The module JAR is written under `target/`.
