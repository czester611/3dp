# U1 Companion — dokument projektowy

Aplikacja na Androida (prywatny APK, sideload) do wyszukiwania modeli 3D w internecie, przygotowania
wydruku i wysłania go na drukarkę **Snapmaker U1** w sieci lokalnej.

- Status: projekt + szkielet kodu (`/home/piotrb/3dP/SnapmakerCompanion`) + szkielet serwera slicingu (`/home/piotrb/3dP/slicer-server`).
- Data researchu: 2026-10-17.
- Legenda weryfikacji: **[Z-DRUK]** sprawdzone odczytem na prawdziwej drukarce (192.168.1.50, tylko GET);
  **[Z-KOD]** sprawdzone w kodzie źródłowym; **[Z-API]** sprawdzone zapytaniem do API serwisu;
  **[ŹRÓDŁO]** artykuł/dokumentacja zewnętrzna; **[NIEZWERYFIKOWANE]** założenie do sprawdzenia.

---

## 1. Decyzje użytkownika (wiążące)

| # | Decyzja |
|---|---------|
| D1 | Slicing na **serwerze w LAN** (OrcaSlicer / Snapmaker Orca CLI na PC lub RPi) — prosty serwis HTTP (zlecenie → status → pobranie G-code), adres konfigurowany w aplikacji. Gotowe G-code obsługiwane bez slicingu. |
| D2 | Priorytet serwisów: **Printables, Thingiverse, MakerWorld**; potem Thangs, MyMiniFactory, Cults3D. |
| D3 | Tylko **prywatny APK**, `minSdk 29` (Android 10+), bez wymogów Google Play. |
| D4 | **OTA** z GitHub Releases: okresowe sprawdzanie (WorkManager) + powiadomienie, instalacja po zgodzie przez PackageInstaller, ręczny przycisk „Sprawdź aktualizacje”. |

---

## 2. Research: Snapmaker U1

### 2.1 Fakty

| Temat | Ustalenie | Weryfikacja |
|---|---|---|
| Oprogramowanie | Forki Klipper + Moonraker + Fluidd (Snapmaker/u1-klipper, u1-moonraker, u1-fluidd), GPLv3, opublikowane 2026-03-30. ~20% zmian w Klipperze (toolchanger, RFID, power-loss), ~15% w Moonrakerze (chmura, LAN). | [ŹRÓDŁO] blog Snapmaker, All3DP; [Z-KOD] |
| Wersje | Moonraker 2.0.0, API 1.4.0, firmware 2.0.0 | [Z-DRUK] |
| Port API | HTTP/WS **7125** (`[server] port: 7125`) | [Z-KOD] lava/moonraker.conf, [Z-DRUK] |
| Autoryzacja | Brak logowania (`login_required=false`); `trusted_clients` obejmuje 10.0.0.0/8, 172.16.0.0/12, **192.0.0.0/8**, 169.254/16. W typowym LAN klucz API zbędny. Obsłużony opcjonalny `X-Api-Key`. | [Z-KOD], [Z-DRUK] |
| mDNS | Usługa **`_snapmaker._tcp`** (nie `_moonraker._tcp`!), port **1884** (LAN MQTT), host `U1-<sn>.local`. TXT: `version, machine_type, device_name, sn, link_mode (lan/wan), userid, ip, region`. HTTP API jest pod tym samym IP na porcie 7125. | [Z-KOD] components/zeroconf.py; na urządzeniu [NIEZWERYFIKOWANE] |
| SSDP | Kod obecny, domyślnie wyłączony (`enable_ssdp`). | [Z-KOD] |
| Upload | `POST /server/files/upload` (multipart `file`, `root=gcodes`, `path`, `print=true`). Root `gcodes` → `/userdata/gcodes` (rw). Akceptowane rozszerzenia: **.gcode .g .gco .ufp** — **3MF nie jest drukowalny bezpośrednio**. | [Z-KOD] file_manager.py, [Z-DRUK] roots |
| Start/kontrola | `/printer/print/start?filename=`, `pause`, `resume`, `cancel`, `/printer/gcode/script`. Makra: `PRINT_START, PRINT_END, CANCEL_PRINT, SET_PAUSE_AT_LAYER, AUTO_FEEDING, MANUAL_FEEDING, INNER_FILAMENT_UNLOAD, SM_PRINT_*`. | [Z-DRUK] (tylko listing — POST nie testowany) |
| Status | WebSocket `/websocket`, `printer.objects.subscribe`. Obiekty: `print_stats` (state, filename, info.current_layer/total_layer), `virtual_sdcard.progress`, `toolhead.extruder` (aktywna głowica, np. `extruder1`), `extruder`, `extruder1..3`, `heater_bed`, `temperature_sensor cavity`, `fan_generic cavity_fan`, `led cavity_led`, `purifier`, `filament_feed left/right`, `filament_detect`, `filament_motion_sensor e0..e3`, `exclude_object`, `timelapse`, `defect_detection`, `bed_mesh`. | [Z-DRUK] |
| Głowice | 4 fizyczne (`PHYSICAL_EXTRUDER_NUM=4`), 32 logiczne (`LOGICAL_EXTRUDER_NUM=32`). | [Z-KOD] print_task_config.py |
| Filamenty | Obiekt **`print_task_config`**: `filament_vendor[4]`, `filament_type[4]` (np. `PETG, PLA, PLA, PETG`), `filament_sub_type[4]`, `filament_color_rgba[4]`, `filament_exist[4]`, `extruder_map_table[32]` (T logiczne → głowica), `extruders_used[4]`, `auto_bed_leveling`, `flow_calibrate`, `time_lapse_camera`, `auto_replenish_filament`… | [Z-DRUK] |
| Mapowanie | G-code: `SET_PRINT_EXTRUDER_MAP CONFIG_EXTRUDER=<T> MAP_EXTRUDER=<głowica>` — odrzucane w trakcie druku. Także `SET_PRINT_FILAMENT_CONFIG`, `SET_PRINT_PREFERENCES`, `SET_PRINT_TASK_PARAMETERS`. | [Z-KOD]; wykonanie [NIEZWERYFIKOWANE] |
| Wolumen | 270×270×270 mm (Orca `printable_area 270x270`, `printable_height 270`); `toolhead.axis_max` 271×335×275 (Y obejmuje parking głowic). | [Z-KOD] fdm_U1.json, [Z-DRUK] |
| Temperatury | Dysza max 300 °C, stół max 100 °C. | [Z-KOD] printer.cfg |
| Kamera | `server/webcams/list` **puste**; `/webcam/` na :80 → 502. Dostępny tylko snapshot **`/server/files/camera/monitor.jpg`** (root `camera`, r). W teście plik nie odświeżał się w ciągu kilku sekund — częstotliwość aktualizacji nieznana. Stream WebRTC tylko w nieoficjalnym firmware (paxx12). | [Z-DRUK], [ŹRÓDŁO] forum/OctoEverywhere |
| Metadane | `/server/files/metadata` zwraca m.in. `slicer`, `filament_type "PETG;PLA;PLA;PETG"`, `nozzle_temp[]`, `filament_weight[]`, `layer_height`, `estimated_time`, miniatury `.thumbs/*.png`. Pliki z OrcaSlicer 2.4.2 drukują się poprawnie. Pliki są w katalogu głównym `gcodes`. | [Z-DRUK] |
| Profile slicera | Upstream OrcaSlicer zawiera `Snapmaker U1 (0.2/0.4/0.4+0.6/0.6/0.8 nozzle)`; start G-code: `PRINT_START TOOL_TEMP=.. T0_TEMP=.. .. BED_TEMP=.. TOOL=..`, `gcode_flavor=klipper`. | [Z-KOD] OrcaSlicer/OrcaSlicer resources/profiles/Snapmaker |

### 2.2 Czego nie zweryfikowano (U1)
- Zachowanie POST (upload, start, `SET_PRINT_EXTRUDER_MAP`) — drukarka drukowała, testy tylko odczytowe.
- Czy `PRINT_START` z profilu Orca nadpisuje `extruder_map_table` ustawione wcześniej przez aplikację.
- Rozgłaszanie `_snapmaker._tcp` widziane z telefonu (izolacja Wi‑Fi, filtry multicast).
- Jak często odświeża się `monitor.jpg` (możliwe, że tylko na żądanie aplikacji Snapmaker/timelapse).
- Upload/druk plików `.3mf` przez inne ścieżki (All3DP wspomina o „3MF handling” w Moonrakerze — w `file_manager.py` nie znaleziono).

---

## 3. Research: serwisy z modelami

| Serwis | Dostęp | Klucz | Wyszukiwanie/pobieranie | Status | Decyzja |
|---|---|---|---|---|---|
| **Printables** | Nieudokumentowany GraphQL `https://api.printables.com/graphql/` (używany przez stronę), introspekcja wyłączona | brak | `searchPrints2(query, limit, offset, ordering)`; `ordering ∈ {best_match, latest, popular, rating, makes_count}`; `print(id)` (stls, gcodes, otherFiles, images, license, tags); mutacja `getDownloadLink(id, printId, fileType ∈ {stl,gcode,sla,other,pack}, source)` zwraca link do `files.printables.com` — działa anonimowo | [Z-API] 2026-10-17 | **Adapter MVP** (`PrintablesSource`), oznaczony „nieoficjalne API”. ToS — [NIEZWERYFIKOWANE] (strona ToS zwraca 403 dla automatów). Moderator forum Prusa (2022): API istnieje, ale nie jest publiczne. |
| **Thingiverse** | Oficjalne REST `https://api.thingiverse.com`, Bearer App Token | tak (użytkownik tworzy własną aplikację) | `/search/{term}?type=things`, `/things/{id}`, `/things/{id}/files`, `/things/{id}/images`, `/files/{id}/download` | ścieżki potwierdzone tylko w kodzie klientów zewn. (Kiln); bez tokenu 403; dokumentacja dev zwraca 403 dla automatów | **Adapter MVP** (`ThingiverseSource`), wymaga tokenu w Ustawieniach; nazwy pól odpowiedzi [NIEZWERYFIKOWANE]. |
| **MakerWorld** (Bambu Lab) | Brak publicznego API. Endpointy `/api/v1/...` istnieją, ale wyszukiwanie za Cloudflare challenge (403) | — | — | [Z-API] (403/404) | **WebView wspomagany** (`WebAssistedSource`): użytkownik przegląda stronę, aplikacja przechwytuje pobrany 3MF. 3MF z MakerWorld mają profile Bambu → **zawsze reslicing** z profilem U1. |
| Thangs | Brak publicznego API (wyszukiwarka geometryczna Physna) | — | — | [ŹRÓDŁO] brak informacji o API | WebView (etap 3). |
| MyMiniFactory | Oficjalne API v2 `https://www.myminifactory.com/api/v2` z `key`; przeglądanie bez logowania, **pobieranie wymaga zalogowania użytkownika (OAuth)**; scraping „w celu wyszukiwarki” — prośba o kontakt | tak | `/search`, `/objects/{id}`, `/objects/{id}/files` | [ŹRÓDŁO] wytyczne API (cytat z wyszukiwarki; strona 403), kod Kiln | Adapter w etapie 3 (klucz + OAuth); do tego czasu WebView. |
| Cults3D | Oficjalne GraphQL `https://cults3d.com/graphql`, Basic Auth (login + klucz z `cults3d.com/en/api/keys`) | tak | `creationsSearchBatch`, `creation(identifier)` | [ŹRÓDŁO] kod Kiln; dokumentacja 403 | Adapter w etapie 3; dużo modeli płatnych → domyślnie filtr „darmowe”. |
| Creality Cloud | Brak znanego publicznego API | — | — | [NIEZWERYFIKOWANE] | WebView. |
| GrabCAD | Brak publicznego API (głównie CAD/STEP) | — | — | [NIEZWERYFIKOWANE] (403) | WebView. |
| CGTrader | Oficjalne API (OAuth2), `api.cgtrader.com/docs`; głównie płatne modele | tak | GET /models, kategorie, licencje | [ŹRÓDŁO] konektor Microsoft, blog CGTrader | Niski priorytet. |
| Yeggi | Agregator, brak API | — | — | — | Tylko link do wyszukiwania (LINK_OUT). |

Wnioski:
1. Jedyny serwis z działającym od ręki dostępem bez klucza to Printables (nieoficjalnie) — dlatego MVP opiera się na nim.
2. Thingiverse, MyMiniFactory, Cults3D — oficjalne API z kluczem; dla prywatnej aplikacji użytkownik generuje własne klucze (brak współdzielonego sekretu w APK).
3. Dla serwisów bez API — **wbudowana przeglądarka**, bez scrapingu i bez obchodzenia Cloudflare (użytkownik sam przegląda stronę).

---

## 4. Kluczowy problem: STL/3MF bez G-code

| Opcja | Plusy | Minusy | Ocena |
|---|---|---|---|
| A. Slicing na telefonie (CuraEngine/PrusaSlicer/Orca w NDK) | Offline, brak serwera | Port Orca/Prusa do Androida to ogromna praca (wxWidgets, Boost, OpenGL, TBB); CuraEngine łatwiejszy, ale **brak profili U1 i obsługi toolchangera** zgodnej z `PRINT_START`; wydajność/RAM; utrzymanie | ✗ odrzucona dla MVP |
| B. **Serwer slicingu w LAN** (OrcaSlicer CLI + profile Snapmaker U1) | Ten sam slicer i profile co na PC → poprawne `PRINT_START`, mapowanie T0–T3, miniatury; łatwa aktualizacja | Wymaga PC/RPi włączonego w LAN; CLI Orca bywa kapryśne (flagi zmieniają się między wersjami, czasem potrzebne Xvfb); oficjalny build Linux to AppImage x86_64 (RPi arm64 — [NIEZWERYFIKOWANE]) | ✓ **wybrana (D1)** |
| C. Gotowe 3MF z profilami Snapmaker Orca / G-code dla U1 z serwisu | Zero slicingu | Rzadkie (filtr „Profil U1”); 3MF i tak trzeba pociąć (Moonraker nie drukuje 3MF) | ✓ jako skrót (filtr + priorytet plików U1) |
| D. Tryb tylko‑G-code | Prosty, działa od razu | Mało modeli z G-code dla U1; G-code innych drukarek jest niebezpieczny | ✓ jako tryb awaryjny z ostrzeżeniami |

**Rekomendacja:** B + D (+C jako filtr). W MVP: gotowy G-code (z ostrzeżeniami, patch temperatur, mapowanie głowic) **oraz** STL/3MF → serwer slicingu → G-code → drukarka.

### 4.1 Serwer slicingu — kontrakt API (`/home/piotrb/3dP/slicer-server/app.py`)

```
GET    /api/v1/health              -> {status, version, slicer_version}
GET    /api/v1/profiles            -> {printers[], processes[], filaments[]}
POST   /api/v1/jobs                multipart: model=<STL/3MF/OBJ/STEP>, config=<JSON>
                                   -> {id, status: queued|slicing|done|failed, progress, error, gcode_name}
GET    /api/v1/jobs/{id}           -> jak wyżej
GET    /api/v1/jobs/{id}/gcode     -> plik G-code
DELETE /api/v1/jobs/{id}
Auth:  Authorization: Bearer <token>  (wspólny sekret, ustawiany w aplikacji)
```

`config`:
```json
{ "printer_profile": "Snapmaker U1 (0.4 nozzle)",
  "process_profile": null,
  "filament_profiles": ["Snapmaker PETG", "Snapmaker PLA"],
  "overrides": {"layer_height":"0.2","sparse_infill_density":"15%","enable_support":"0",
                "brim_type":"no_brim","nozzle_temperature":"245,220","hot_plate_temp":"75"},
  "plate": 1, "reset_project_settings": true, "arrange": true }
```

Działanie: serwer rozwiązuje łańcuch `inherits` profili JSON (skopiowanych z `resources/profiles/Snapmaker` OrcaSlicera lub wyeksportowanych z GUI), nakłada `overrides`, zapisuje tymczasowe JSON-y i uruchamia:
`orca-slicer --slice <plate> --load-settings "machine.json;process.json" --load-filaments "f1.json;f2.json" --arrange 1 --outputdir out model.3mf` (opcjonalnie przez `xvfb-run`). Jeden slicing naraz (semafor), limit rozmiaru i czasu.
**[NIEZWERYFIKOWANE]:** dokładne flagi dla zainstalowanej wersji, nazwy plików wynikowych, nadpisywanie ustawień projektu 3MF z Bambu.

Wdrożenie: `docker build` (Dockerfile z rozpakowanym AppImage) albo `pip install -r requirements.txt && uvicorn app:app --host 0.0.0.0 --port 8484` na PC z zainstalowanym OrcaSlicer. Odkrywanie serwera: w MVP ręczny adres; w etapie 3 mDNS `_u1slicer._tcp`.

---

## 5. Architektura aplikacji

Stos: **Kotlin 2.1, Jetpack Compose + Material 3, Hilt, Room, Retrofit/OkHttp, kotlinx.serialization, Paging 3, Coil, WorkManager, DataStore, NsdManager (mDNS)**. `minSdk 29`, `targetSdk 36`. Jedna aktywność, Navigation Compose z trasami type‑safe.

```
┌──────────────────────────── UI (Compose, MVVM) ────────────────────────────┐
│ Browse  Detail  Library  PrintSetup  Printers  Settings(+OTA)  WebSource   │
└──────────────┬───────────────┬──────────────┬───────────────┬──────────────┘
               │ ViewModel (StateFlow, PagingData)                           
┌──────────────▼───┐ ┌─────────▼──────┐ ┌─────▼──────────┐ ┌──▼────────────┐
│ ModelRepository  │ │LibraryRepository│ │PrinterRepository│ │SlicingRepo   │
│ (Paging, cache)  │ │fav/hist/saved   │ │Moonraker, mDNS  │ │(LAN server)  │
└──────┬───────────┘ └───────┬────────┘ └─────┬───────────┘ └──┬───────────┘
┌──────▼─────────────────┐   │  Room DB  ┌────▼────────────┐ ┌─▼────────────┐
│ SourceRegistry          │   │           │MoonrakerClient  │ │ SlicingApi   │
│ ├ PrintablesSource (GQL)│   │           │PrinterDiscovery │ │ (Retrofit)   │
│ ├ ThingiverseSource     │   │           │GcodeInspector/  │ └──────────────┘
│ ├ WebAssistedSource×N   │   │           │GcodePatcher     │
│ └ DemoSource            │   │           └─────────────────┘
└─────────────────────────┘   │  DownloadWorker / UpdateCheckWorker (WorkManager)
```

### 5.1 Struktura pakietów (szkielet)

```
pl.u1companion
├─ U1CompanionApp, MainActivity
├─ core/model/Models.kt              SourceId, SortOrder, LicenseFamily, FileKind, Dimensions,
│                                    ModelSummary, ModelDetail, ModelFile, SearchFilters, SearchQuery
├─ data/source/                      ModelSource, SourceCapabilities, AccessMode, SourceException,
│  │                                 AggregatedSearchPagingSource, ClientSideFilter, SourceRegistry
│  ├─ printables/PrintablesSource    (realny adapter, GraphQL)
│  ├─ thingiverse/ThingiverseSource  (realny adapter, wymaga tokenu)
│  ├─ web/WebAssistedSources         (MakerWorld, Thangs, MMF, Cults3D, Creality, GrabCAD, CGTrader, Yeggi)
│  └─ demo/DemoSource                (fake data)
├─ data/local/                       Room: AppDatabase, Entities, Daos
├─ data/repo/                        ModelRepository, LibraryRepository
├─ data/settings/                    SettingsRepository (DataStore)
├─ download/                         DownloadWorker, DownloadRepository
├─ printer/discovery/                PrinterDiscovery (NSD: _snapmaker._tcp, _moonraker._tcp)
├─ printer/moonraker/                MoonrakerClient (HTTP+WS), PrinterStatus, ToolStatus, U1Objects
├─ printer/job/                      PrintConfig, Material, GcodeInspector, GcodePatcher
├─ printer/PrinterRepository
├─ slicing/                          SlicingApi (Retrofit), SlicingRepository
├─ update/                           UpdateRepository, UpdateInstaller, InstallResultReceiver, UpdateCheckWorker
├─ di/AppModule                      Json, OkHttp (internet), @LanClient OkHttp, Room
└─ ui/                               AppRoot (nawigacja), browse/, detail/, library/, print/, printers/,
                                     settings/, web/, theme/
```

Docelowo (etap 3+) podział na moduły Gradle: `:core:model`, `:core:network`, `:data:sources:*` (adapter = moduł), `:printer`, `:slicing`, `:update`, `:feature:*`. W szkielecie jeden moduł `:app` dla prostoty budowania.

### 5.2 Model danych

Domena (niezależna od serwisu):
- `ModelSummary(source, id, title, author, thumbnailUrl, webUrl, likes, downloads, publishedAt, license, isFree, priceCents, category, hasPrintReadyFile?, hasU1Profile?)` — `key = "SOURCE:id"`.
- `ModelDetail(summary, descriptionHtml, images[], files[], tags[], dimensions?)`.
- `ModelFile(id, name, sizeBytes, kind, printerHint, sourceFileType)` + `isLikelyForU1` (heurystyka nazwy: „U1”, „Snapmaker U1”).
- `License(code, name, family)`; `LicenseFamily` z flagami `commercial`, `derivatives` — mapowanie heurystyczne z tekstu.
- `SearchQuery(text, SearchFilters(sources, categoryId, licenses, onlyFree, onlyPrintReady, onlyU1Profile, onlyFitsU1, onlyCommercialUse, sort))` — serializowalne (zapisane wyszukiwania, argument nawigacji).

Room (`u1companion.db`, v1, eksport schematu):

| Tabela | Klucz | Zawartość |
|---|---|---|
| `favorites` | `key` | snapshot podsumowania (działa offline) |
| `history` | autoinc | typ VIEWED/DOWNLOADED/SENT_TO_PRINTER/PRINTED, modelKey, tytuł, czas |
| `saved_searches` | autoinc | nazwa, `queryJson`, createdAt, lastRunAt, `notifyNew` (etap 4) |
| `library_files` | autoinc | plik lokalny: modelKey, nazwa, ścieżka, kind, status QUEUED/DOWNLOADING/READY/FAILED/SLICING/SLICED, licencja, `gcodeMetaJson` |
| `printers` | `id` (sn z mDNS lub host:port) | host, port, apiKey?, model, firmware, lastSeen |
| `print_jobs` | autoinc | printerId, zdalna nazwa pliku, configJson, stan |

Pliki: `filesDir/library/<source>/<modelId>/…`, wyniki slicingu `filesDir/sliced/`, tymczasowe `cacheDir/upload`, `cacheDir/updates`.

### 5.3 Abstrakcja `ModelSource`

```kotlin
interface ModelSource {
    val id: SourceId
    val capabilities: SourceCapabilities   // access mode, obsługiwane sorty, filtry serwerowe, wymaga klucza, pobieranie w aplikacji
    suspend fun isAvailable(): Boolean     // np. jest token
    suspend fun search(query: SearchQuery, page: PageRequest): SearchPage   // kursor nieprzezroczysty
    suspend fun details(modelId: String): ModelDetail
    suspend fun resolveDownload(modelId: String, file: ModelFile): DownloadTicket  // URL + nagłówki
    suspend fun categories(): List<Category>
    fun webSearchUrl(text: String): String
}
```

- `AccessMode`: `OFFICIAL_API`, `UNOFFICIAL_API`, `ASSISTED_WEBVIEW`, `LINK_OUT`, `DEMO` — UI pokazuje odpowiednie oznaczenia.
- Błędy: `SourceException.{NotConfigured, Unauthorized, RateLimited, SchemaChanged, Unsupported, Network}`.
- `AggregatedSearchPagingSource`: równoległe zapytania do wybranych źródeł, osobny kursor per źródło (`AggregateKey`), przeplatanie wyników round‑robin (metryki serwisów są nieporównywalne, więc nie sortujemy globalnie), deduplikacja, błąd jednego źródła → ostrzeżenie (snackbar), błąd wszystkich → `LoadResult.Error`. Gdy filtry klienckie wytną całą stronę, dociąga do 3 kolejnych (unikamy „zawieszenia” Paging na pustej stronie).
- `ClientSideFilter`: filtry nieobsługiwane przez API (licencja, darmowe, G-code, profil U1); wartość nieznana (`null`) nie odrzuca wyniku — oznaczenie „?”.
- Nowy serwis = nowa klasa + wpis w `SourceRegistry` + testy kontraktowe na nagranych odpowiedziach (etap 2).

### 5.4 Warstwa drukarki

- **Wykrywanie** (`PrinterDiscovery`): `NsdManager` dla `_snapmaker._tcp.` i `_moonraker._tcp.`, `MulticastLock`, kolejka resolve (starsze Androidy nie pozwalają na równoległe). Z TXT bierzemy `sn`, `device_name`, `ip`, `version`, `machine_type`; port API = 7125 dla `_snapmaker`. Weryfikacja przez `GET /server/info` przed zapisaniem. Fallback: ręczny IP:port.
- **Klient** (`MoonrakerClient`): `serverInfo`, `printerInfo`, `fileMetadata`, `queryStatus`, `upload(file, print=true, progress)`, `startPrint`, `pause/resume/cancel`, `runGcode`, `setExtruderMap`, `statusUpdates()` (WS JSON‑RPC, scalanie różnicowych `notify_status_update`), `cameraSnapshotUrl()`.
- **Model wielogłowicowy**: `PrinterStatus(printState, progress, layers, bed, cavityTemp, activeTool, tools[4], extruderMap[32])`, `ToolStatus(index, temp, target, filamentVendor/Type/SubType, colorRgba, loaded, usedInCurrentJob)`.
- **Zlecenie** (`PrinterRepository.sendAndPrint`): (1) opcjonalny patch temperatur, (2) `SET_PRINT_EXTRUDER_MAP` dla każdej używanej głowicy, (3) upload z `print=true`, (4) wpis w `print_jobs`. Blokada, gdy drukarka `printing/paused`.
- **GcodeInspector**: czyta 256 KB początku i 512 KB końca (Orca zapisuje konfigurację na końcu): `printer_model`, `filament_type`, `nozzle_temperature`, `layer_height`, `sparse_infill_density`, użyte `T<n>`. Alternatywnie po uploadzie — `/server/files/metadata`.
- **GcodePatcher**: zmienia tylko `M104/M109/M140/M190 S..` i parametry `PRINT_START (TOOL_TEMP, Tn_TEMP, BED_TEMP)`; nie rusza `S0` (wyłączanie grzałek). Zmiana materiału bez reslicingu = tylko temperatury (ostrzeżenie w UI).
- **Kamera**: polling `monitor.jpg` co 5 s (cache-buster). Stream — gdy pojawi się w `server/webcams/list` (np. przyszły firmware).

---

## 6. Makiety ekranów (ASCII)

### 6.1 Odkrywaj (przeglądanie i filtrowanie) — ekran najważniejszy

```
┌──────────────────────────────────────────┐
│ Odkrywaj modele                    [🔖]   │  🔖 = zapisz wyszukiwanie
│ ┌──────────────────────────────────────┐ │
│ │🔍 organizer szuflady            [✕] │ │  debounce 400 ms
│ └──────────────────────────────────────┘ │
│ [✓Printables] [Thingiverse] [Demo]  →    │  źródła z API (multi-select)
│ [⚙Filtry ③] [⇅Popularne▾] [✓Darmowe]     │  szybkie filtry
│ [Z G-code/3MF] [Profil U1]           →   │
│ ┌────────────┐ ┌────────────┐            │
│ │  [obraz] ♡ │ │  [obraz] ♥ │            │  siatka adaptacyjna (≥168dp)
│ │Printables  │ │Thingiverse │            │  znacznik źródła
│ │Organizer.. │ │Uchwyt ..   │            │
│ │maker123    │ │jan_k       │            │
│ │👍2.1k ⬇14k │ │👍830 ⬇5.2k │            │
│ │[CC-BY][U1] │ │[CC0][G-code]│           │  licencja, płatny, U1/G-code
│ └────────────┘ └────────────┘            │
│        … infinite scroll (Paging 3) …     │
│  ◌ ładowanie / [Błąd — ponów]             │
│  Szukaj także (przeglądarka):             │
│  [↗MakerWorld] [↗Thangs] [↗MyMiniFactory] │
│  [↗Cults3D] [↗Creality] [↗GrabCAD] [↗Yeggi]│
├──────────────────────────────────────────┤
│ [Odkrywaj] [Biblioteka] [Drukarki] [Ust.] │
└──────────────────────────────────────────┘
```

Arkusz „Filtry” (ModalBottomSheet):
```
┌──────────────────────────────────────────┐
│ Filtry                                    │
│ Źródła: [✓Printables (nieoficjalne API)]  │
│         [Thingiverse (wymaga klucza)]     │
│ Sortowanie: [Trafność][✓Popularne]        │
│   [Najnowsze][Najczęściej pobierane]      │
│   [Najbardziej lubiane][Najwyżej oceniane]│
│ Kategoria: [Dom ▾]          (etap 2)      │
│ Licencja: [CC0][✓CC BY][CC BY-SA][CC BY-NC]│
│   [CC BY-NC-SA][CC BY-ND][GPL][Inna]…      │
│ Tylko z prawem użytku komercyjnego  [ ]   │
│ ───────────────────────────────────────── │
│ Druk                                      │
│ Tylko darmowe                       [■]   │
│ Tylko z gotowym G-code / 3MF        [ ]   │
│ Tylko z profilem Snapmaker U1       [ ]   │
│ Mieści się w 270×270×270 mm         [ ]   │
│ ℹ wartości nieznane oznaczane „?”          │
│ [Wyczyść]            [Pokaż wyniki]       │
└──────────────────────────────────────────┘
```

Szczegóły zachowania:
- **Sortowanie** mapowane per adapter (`supportedSorts`); niewspierane → najbliższy odpowiednik (Printables „Najczęściej pobierane” → `makes_count`) z adnotacją w UI (etap 2).
- **Filtr źródła** — tylko źródła z API; serwisy bez API w stopce jako „szukaj także w…” (WebView z tą samą frazą).
- **Tylko darmowe** — domyślnie włączone; Printables `premium/price`, Cults3D `price`.
- **Tylko z G-code/3MF** — Printables zna `gcodes{}` już w wynikach; inne źródła → weryfikacja w szczegółach.
- **Profil U1** — heurystyka nazw plików G-code/3MF („U1”, „Snapmaker U1”) + (etap 3) odczyt `Metadata/project_settings.config` z 3MF po pobraniu.
- **Mieści się w U1** — wymiary rzadko są w API: liczone po pobraniu (bounding box STL / 3MF, etap 2) i cache’owane; w liście ikona „?”, w szczegółach ostrzeżenie.
- **Ulubione** (serce na karcie i w szczegółach), **historia** (wejścia, pobrania, wysyłki), **zapisane wyszukiwania** (pełny `SearchQuery` w JSON; uruchomienie z Biblioteki; etap 4 — powiadomienia o nowych wynikach).
- **Infinite scroll**: `PagingConfig(pageSize=20, prefetchDistance=10)`, `cachedIn(viewModelScope)`, stopka ładowania/błędu z „Ponów”.
- Ostrzeżenia częściowe (np. „Thingiverse: brak tokenu”) jako snackbar, lista działa dalej.

### 6.2 Szczegóły modelu
```
┌──────────────────────────────────────────┐
│ ←  Organizer szuflady          ♥   ↗     │
│ ┌──────────────────────────────────────┐ │
│ │        [galeria, swipe 1/5]          │ │
│ └──────────────────────────────────────┘ │
│ Organizer szuflady modułowy               │
│ Printables · maker123 · Dom               │
│ ♥ 2104   ⬇ 14 230                         │
│ ┌ Licencja: CC BY-NC-SA ─────────────────┐│
│ │ ✗ tylko niekomercyjnie  ✓ modyfikacje  ││
│ │ Pamiętaj o uznaniu autorstwa.          ││
│ └────────────────────────────────────────┘│
│ Wymiary: 120 × 80 × 40 mm — mieści się    │
│ Pliki                                     │
│  organizer.stl   STL · 2.3 MB  [⬇][Tnij i drukuj]│
│  organizer.3mf   3MF · 1.1 MB  [⬇][Tnij i drukuj]│
│  org_U1_PETG.gcode G-code · profil U1 [⬇][Drukuj]│
│ Opis …  Tagi …                            │
└──────────────────────────────────────────┘
```

### 6.3 Przygotuj wydruk
```
┌──────────────────────────────────────────┐
│ ←  Przygotuj wydruk                       │
│ organizer.3mf                             │
│ ⚠ Plik pocięty dla „Prusa MK4” (dla G-code)│
│ Drukarka: [✓U1 Warsztat] [U1 #2]          │
│ Stan: standby (Klipper: ready)            │
│ ┌ Narzędzie z pliku: T0 ─────────────────┐│
│ │ Głowica: [●1 PETG][✓○2 PLA][●3 PLA][●4]││  kolor + typ z print_task_config
│ │ ⚠ w głowicy 2 jest PLA, plik zakłada PETG││
│ │ Materiał: [PLA][✓PETG][TPU][ABS][ASA]… ││
│ │ Dysza: 245 °C  ───────●──              ││
│ └────────────────────────────────────────┘│
│ Stół: 75 °C ─────●────                    │
│ Slicing (serwer w LAN)                    │
│ Warstwa: [0.12][0.16][✓0.20][0.24][0.28]   │
│ Wypełnienie: 15 %  ──●────────             │
│ Podpory: [✓Brak][Normalne][Drzewiaste]     │
│ Brim [ ]   Uruchom od razu [■]            │
│ [ Potnij na serwerze ] → [ Wyślij i drukuj ]│
└──────────────────────────────────────────┘
```

### 6.4 Drukarki
```
┌──────────────────────────────────────────┐
│ Drukarki                                  │
│ Zapisane: U1 Warsztat 192.168.1.50:7125  │
│ ┌ U1 Warsztat ───────────────────────────┐│
│ │ [snapshot kamery, odśw. 5 s]           ││
│ │ printing · 5.7% · warstwa 233/2511     ││
│ │ Stół 80/80 °C · komora 28 °C           ││
│ │ Głowica 1: 25/0 · Generic PETG         ││
│ │ Głowica 2 (aktywna): 220/220 · PLA Basic││
│ │ …  [Pauza] [Anuluj] (etap 2)            ││
│ └────────────────────────────────────────┘│
│ [Wyszukaj w sieci (mDNS)] [Dodaj ręcznie] │
│ Znalezione: U1 (Snapmaker U1) 192.168.1.50 [Dodaj]│
└──────────────────────────────────────────┘
```

### 6.5 Biblioteka i Ustawienia
```
Biblioteka: [Ulubione][Pliki][Historia][Wyszukiwania]
  Pliki: nazwa · typ · status (QUEUED/DOWNLOADING/READY/FAILED) · [Drukuj]
Ustawienia: serwer slicingu (URL, token, [Zapisz i testuj]) · Thingiverse token ·
  Aktualizacje: wersja, [Sprawdź aktualizacje], notatki wydania, [Pobierz i zainstaluj]
```

---

## 7. Przepływ użytkownika

```
Odkrywaj ──wyszukaj/filtruj──► Szczegóły ──[Drukuj/Tnij i drukuj]──► DownloadWorker
                                                             │
                                  ┌──────────────────────────┘
                                  ▼
                         Przygotuj wydruk (czeka na READY)
                   ┌──────────────┴───────────────┐
             plik G-code                     STL/3MF/OBJ/STEP
       GcodeInspector + ostrzeżenia     parametry slicingu → serwer LAN
       (patch temperatur, mapowanie)         → G-code (+ metadane)
                   └──────────────┬───────────────┘
                                  ▼
       SET_PRINT_EXTRUDER_MAP ×N → upload print=true → status WS → historia
```
Ścieżki dodatkowe: MakerWorld/Thangs w WebView → przechwycone pobranie → Biblioteka → Przygotuj wydruk; „Udostępnij/Otwórz w” z innych aplikacji (intent-filter, etap 2).

Pierwsze uruchomienie: (1) skan mDNS i dodanie drukarki, (2) opcjonalnie adres serwera slicingu, (3) opcjonalnie token Thingiverse, (4) zgoda na powiadomienia (OTA, status druku).

---

## 8. Obsługa błędów

| Sytuacja | Zachowanie |
|---|---|
| Brak sieci / timeout serwisu | `SourceException.Network` → snackbar; inne źródła działają; „Ponów” w stopce listy |
| Zmiana schematu Printables | `SchemaChanged` → komunikat „API serwisu się zmieniło”, wyłączenie źródła do aktualizacji aplikacji (OTA) |
| 401/403 Thingiverse | `Unauthorized` → link do Ustawień |
| 429 | `RateLimited` + `Retry-After`, backoff; cache HTTP 50 MB |
| Pobieranie | WorkManager: retry ×3 z wykładniczym backoff, plik `.part` → rename; status w Bibliotece |
| Drukarka niedostępna | `MoonrakerException("Brak połączenia…")`, sugestia: ta sama sieć, brak izolacji klientów, ręczny IP |
| Klipper nie `ready` / drukarka zajęta | blokada przycisku „Wyślij”, komunikat ze stanem |
| Plik nieobsługiwany (3MF, .bgcode) | walidacja przed uploadem (Moonraker przyjmuje tylko .gcode/.g/.gco/.ufp) |
| G-code innej drukarki | ostrzeżenie (z `printer_model`), wymagane potwierdzenie (etap 2) |
| Niezgodny filament w głowicy | ostrzeżenie (porównanie `filament_type` z `print_task_config` z wybranym materiałem) |
| Serwer slicingu | brak adresu → komunikat; `failed` → log z serwera; timeout 10 min |
| WebSocket zerwany | ponowne połączenie z backoff (etap 2), `notify_klippy_disconnected` → stan „disconnected” |
| OTA | patrz §11 |

---

## 9. Bezpieczeństwo

- Ruch do internetu tylko HTTPS. Cleartext dopuszczony globalnie (Moonraker i serwer slicingu w LAN działają po HTTP; adresów nie da się wyliczyć w `network_security_config`). Etap 3: `LanAddressPolicy` — HTTP tylko do adresów prywatnych (RFC1918/link-local/`.local`).
- Brak sekretów w APK; tokeny użytkownika w DataStore (prywatny katalog, `allowBackup=false`); etap 3: szyfrowanie kluczem z Android Keystore.
- Moonraker U1 nie ma autoryzacji dla klientów z LAN — każdy w sieci może sterować drukarką; aplikacja nie pogarsza tego, ale ostrzega w dokumentacji. Wspierany `X-Api-Key`.
- Akcje fizyczne (start, anuluj) wymagają jawnego kliknięcia; anuluj/pauza z potwierdzeniem. Brak automatycznego startu z tła.
- Nazwy plików sanityzowane (`[^A-Za-z0-9._ -]` → `_`), pliki w prywatnym katalogu aplikacji.
- WebView: JavaScript włączony tylko w ekranie serwisów; brak `addJavascriptInterface`; pobrania tylko przez jawne kliknięcie użytkownika.
- Serwer slicingu: token Bearer, limit rozmiaru, timeout, jeden proces naraz, pliki w katalogu zadania, nazwa modelu ignorowana (zapis jako `model.<ext>`).
- OTA: weryfikacja SHA‑256, certyfikatu podpisu, packageName, brak downgrade (§11).

---

## 10. Kwestie prawne

- **ToS serwisów**: Printables — nieudokumentowane API; treść ToS nie została zweryfikowana (403). Ryzyko łagodzone: prywatny użytek, niski wolumen zapytań, identyfikujący User‑Agent, cache, brak masowego pobierania. Przed jakąkolwiek dystrybucją publiczną — kontakt z Prusa Research.
- MakerWorld/Thangs/inne bez API: tylko WebView sterowany przez użytkownika (bez scrapingu, bez obchodzenia Cloudflare/captcha).
- Thingiverse/MyMiniFactory/Cults3D: klucze API użytkownika, zgodnie z warunkami każdego API (MMF: pobieranie wymaga zalogowania użytkownika, scraping dla wyszukiwarek — tylko po kontakcie).
- **Licencje modeli**: licencja zawsze widoczna przed pobraniem/drukiem; zapis rodziny licencji przy pliku w Bibliotece; filtr „użytek komercyjny”; przypomnienie o uznaniu autorstwa (CC BY*). Aplikacja nie redystrybuuje plików.
- Znaki towarowe: „Snapmaker”, „U1”, nazwy serwisów — nazwa aplikacji „U1 Companion” bez logotypów; przy publicznej dystrybucji rozważyć zmianę nazwy.
- Firmware U1 i OrcaSlicer są GPLv3/AGPL — aplikacja tylko komunikuje się przez API/CLI (brak linkowania).

---

## 11. OTA — samoaktualizacja z GitHub Releases

**Źródło:** `GET https://api.github.com/repos/{owner}/{repo}/releases/latest` (pomija draft/prerelease). `owner`/`repo` z `BuildConfig.UPDATE_REPO_OWNER/NAME`, ustawiane w `gradle.properties` (`updateRepoOwner=…`, `updateRepoName=…`) lub `-P`. Wartość domyślna `CHANGE_ME` wyłącza OTA. Repo musi być publiczne (prywatne wymagałoby tokenu w APK — odradzane). Limit API bez tokenu: 60 zapytań/h/IP — wystarczy przy sprawdzaniu raz na dobę.

**Wersjonowanie:**
- `versionCode` — rosnąca liczba całkowita, jedyne kryterium „nowsze”; `versionName` — SemVer.
- Tag: `v<versionName>+<versionCode>`, np. `v0.3.1+7` (regex `^v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\+(\d+)$`).
- Asset: `U1Companion-<versionName>-<versionCode>.apk` + suma: `…apk.sha256` lub linia `SHA-256: <hex>` w opisie; jeśli GitHub zwraca pole `digest: "sha256:…"` assetu — używane w pierwszej kolejności ([NIEZWERYFIKOWANE] dostępność pola dla danego konta).

**Podpisywanie:** jeden stały keystore release dla wszystkich wersji (`keystore.properties` poza repo: `storeFile, storePassword, keyAlias, keyPassword`; kopia zapasowa offline). Zmiana klucza = brak możliwości aktualizacji (konieczna reinstalacja z utratą danych). Buildy debug są podpisane kluczem debug — OTA między debug a release nie zadziała (weryfikacja podpisu odrzuci).

**Przepływ:**
1. `UpdateCheckWorker` — okresowo co 24 h (sieć wymagana) → `UpdateRepository.check()` → powiadomienie „Dostępna aktualizacja X” (kanał `updates`). Nic nie jest pobierane bez zgody.
2. Ustawienia → „Sprawdź aktualizacje” → notatki wydania → „Pobierz i zainstaluj”.
3. `downloadAndVerify`: HTTPS only, pobranie do `cacheDir/updates`, SHA‑256 liczona w locie; **brak sumy = blokada** (chyba że użytkownik świadomie zezwoli — opcja domyślnie wyłączona).
4. `verifyApk`: `getPackageArchiveInfo` → packageName == nasz, `versionCode` > bieżący i == z tagu, zestaw certyfikatów podpisu (SHA‑256) == zainstalowanej aplikacji.
5. `UpdateInstaller.install`: wymagane `REQUEST_INSTALL_PACKAGES` + zgoda „Instaluj nieznane aplikacje” (przekierowanie do `ACTION_MANAGE_UNKNOWN_APP_SOURCES`); sesja `PackageInstaller` (`MODE_FULL_INSTALL`, `USER_ACTION_REQUIRED`) → `InstallResultReceiver` uruchamia systemowe okno potwierdzenia. Alternatywa (fallback): `ACTION_VIEW` z `FileProvider` — niepotrzebna przy sesji PackageInstaller.
6. **Rollback / brak downgrade**: nigdy nie instalujemy niższego `versionCode` (Android też by odrzucił). „Rollback” = wydanie nowej wersji z wyższym `versionCode` zawierającej poprzedni kod. Migracje Room tylko w przód; nie ma `fallbackToDestructiveMigration` w release.

**Błędy OTA:** limit API (403/429) → retry następnego dnia; brak APK w release / zły tag → komunikat; zła suma / podpis / packageName → plik usuwany, `SecurityException`, komunikat; użytkownik anulował instalację → stan `Failed`, możliwość ponowienia; brak miejsca → komunikat.

**Proces wydania (ręczny, poza aplikacją):** podbij `versionCode/versionName` → `./gradlew assembleRelease` → `sha256sum app-release.apk > U1Companion-x.y.z-N.apk.sha256` → GitHub Release z tagiem `vx.y.z+N`, dwa assety. (Etap 4: GitHub Actions z keystore w sekretach.)

---

## 12. Plan etapów

| Etap | Zakres | Kryterium ukończenia |
|---|---|---|
| **0 (zrobione)** | Research, DESIGN.md, szkielet Gradle, interfejsy, Printables/Thingiverse, klient Moonraker, mDNS, ekrany Compose, OTA szkielet, serwer slicingu szkielet | `assembleDebug` przechodzi |
| **1 — MVP druku** | Test na U1 (gdy wolna): upload + start małego G-code, `SET_PRINT_EXTRUDER_MAP` vs `PRINT_START`, mDNS z telefonu; ekran statusu z pauzą/anulowaniem; obsługa intencji „Otwórz w” dla G-code | Benchy z telefonu na U1 |
| **2 — Moduł projektów** | Printables: kategorie, licencje, pełne sortowanie; Thingiverse z prawdziwym tokenem; cache Room detali; wymiary z STL/3MF (bounding box); filtr „mieści się”; testy kontraktowe na nagranych odpowiedziach; ostrzeżenia z potwierdzeniem | Wyszukiwanie w 2 serwisach z wszystkimi filtrami |
| **3 — Slicing** | Uruchomienie slicer-server z OrcaSlicer i profilami U1 (PC), weryfikacja CLI, profile z `/profiles`, 3MF z MakerWorld (reset ustawień Bambu), multi-material; mDNS serwera; MyMiniFactory/Cults3D adaptery; `LanAddressPolicy`, szyfrowanie tokenów | STL z Printables → G-code → druk PETG |
| **4 — Komfort** | Powiadomienia o postępie druku (foreground service/WS), zapisane wyszukiwania z powiadomieniami, timelapse/kamera, CI wydań OTA, podział na moduły Gradle | — |

---

## 13. Ryzyka

| Ryzyko | Wpływ | Mitigacja |
|---|---|---|
| Printables zmieni/zablokuje GraphQL | Utrata głównego źródła | Izolacja w adapterze, `SchemaChanged`, szybka poprawka przez OTA, Thingiverse jako drugie źródło |
| ToS serwisów zabraniają automatycznego dostępu | Prawne | Prywatny użytek, oficjalne API gdzie są, WebView zamiast scrapingu |
| OrcaSlicer CLI niestabilne/zmienne | Slicing nie działa | Przypięta wersja w Dockerze, testy na zestawie modeli, log błędów do aplikacji |
| Brak buildu Orca na RPi arm64 | Serwer tylko na PC | Udokumentować; ewentualnie kompilacja ze źródeł |
| Firmware U1 zmieni API (`print_task_config`, mDNS) | Mapowanie/odkrywanie | Fallback ręcznego IP, tolerancyjne parsowanie, wersja firmware w `printers` |
| Mapowanie głowic nadpisywane przez `PRINT_START` | Druk złym filamentem | Test w etapie 1; alternatywa: przepisanie `T<n>` w G-code lub mapowanie na serwerze slicingu |
| G-code z innej drukarki | Uszkodzenie wydruku/maszyny | Inspekcja nagłówka, ostrzeżenie + potwierdzenie, domyślnie preferowane pliki U1 |
| Brak autoryzacji Moonrakera w LAN | Bezpieczeństwo sieci domowej | Poza zakresem aplikacji; rekomendacja izolacji VLAN |
| Utrata keystore | Koniec OTA | Kopia offline, dokumentacja |

---

## 14. Otwarte pytania do użytkownika

1. **Host serwera slicingu**: PC z Linuksem/Windows (pewne buildy OrcaSlicer) czy RPi (arm64 — brak potwierdzonego oficjalnego buildu)? Wpływa na Dockerfile.
2. **Snapmaker Orca vs upstream OrcaSlicer**: na drukarce działa G-code z OrcaSlicer 2.4.2 (upstream ma profile U1). Proponuję upstream (łatwiejszy CLI/Docker). Zgoda?
3. **Mapowanie głowic**: preferujesz mapowanie w aplikacji (`SET_PRINT_EXTRUDER_MAP` przed startem) czy na serwerze slicingu (cięcie od razu pod fizyczne głowice)? Do decyzji po teście z etapu 1.
4. **Kamera**: wystarczy snapshot co kilka sekund, czy instalacja nieoficjalnego firmware (WebRTC) wchodzi w grę?
5. **OTA**: nazwa repozytorium GitHub (owner/name) i czy repo będzie publiczne.

---

## 15. Źródła

- Snapmaker: [U1 Firmware: Now on GitHub](https://snapmaker.com/blog/snapmaker-u1-firmware-now-on-github), [github.com/Snapmaker/u1-moonraker](https://github.com/Snapmaker/u1-moonraker), [github.com/Snapmaker/u1-klipper](https://github.com/Snapmaker/u1-klipper)
- [All3DP — Snapmaker open-sources U1 Klipper code](https://all3dp.com/4/snapmaker-open-sources-its-u1-klipper-code-with-a-day-to-spare/), [Fabbaloo — U1 firmware forks](https://www.fabbaloo.com/?p=236294)
- [Printago — Connecting Snapmaker (Moonraker 7125)](https://docs.printago.io/docs/connecting-printers/snapmaker)
- [3DJake — Snapmaker U1 specyfikacja](https://www.3djake.com/snapmaker/u1)
- Kamera: [SimplyPrint — webcam on U1](https://help.simplyprint.io/en/article/how-to-enable-webcam-streaming-on-the-snapmaker-u1-ia9os7/), [OctoEverywhere — U1 WebRTC guide](https://blog.octoeverywhere.com/snapmaker-u1-full-klipper-webrtc-camera-setup-guide/), [Forum Snapmaker — custom firmware](https://forum.snapmaker.com/t/custom-firmware-for-snapmaker-u1-enhanced-features-webrtc-camera/40531)
- OrcaSlicer: [profile Snapmaker U1](https://github.com/OrcaSlicer/OrcaSlicer/tree/main/resources/profiles/Snapmaker), [CLI mode wiki](https://www.orcaslicer.com/wiki/cli/cli_mode), [Printago — Orca CLI reference](https://printago.io/blog/orca-slicer-cli-reference)
- Printables: [Forum Prusa — publiczne REST API?](https://forum.prusa3d.com/forum/general-discussion-user-experience-ideas/is-there-a-publicly-available-rest-api), [Forum Bambu — scraping/API](https://forum.bambulab.com/t/scrape-website-data-api/113401)
- Adaptery referencyjne (kod zewnętrzny): [codeofaxel/Kiln marketplaces](https://github.com/codeofaxel/Kiln)
- MyMiniFactory: [API guidelines](https://www.myminifactory.com/pages/api-guidelines), [For developers](https://www.myminifactory.com/pages/for-developers)
- CGTrader: [API docs](https://api.cgtrader.com/docs/overview.html), [Microsoft connector](https://learn.microsoft.com/en-us/connectors/cgtrader)
- Thangs: [TCT Magazine — Physna launches Thangs](https://www.tctmagazine.com/additive-manufacturing-3d-printing-news/physna-launches-thangs-geometric-search-engine-3d-model-crea/)
