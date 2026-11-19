# KENO V2 — vizuelni pomoćnik za 8 Ball Pool (Android)

KENO crta predviđenu putanju preko igre. **Radi isključivo vizuelnom analizom ekrana** (Android MediaProjection).
Ne dira memoriju igre, ne mijenja igru i ne zaobilazi nikakvu zaštitu; nema dodiranja igre ni automatskih poteza.

Paket: `com.keno.billiards` · minSdk 26 · targetSdk 35 · Java i Kotlin JVM target 17 · bez vanjskih biblioteka.

## Šta radi (i kako je provjereno)

| Funkcija | Stanje |
|---|---|
| Prozor za crtanje preko cijelog ekrana s `FLAG_NOT_TOUCHABLE` (dodiri idu igri) + `alpha 0.8` (Android 12+ pravilo) | implementirano |
| Posebno malo plutajuće **K** dugme (prevlačenje, dodir otvara panel) i panel s prekidačem za svaku funkciju | implementirano |
| Prepoznavanje stola (boja sukna se određuje sama), ivica jastuka, 6 rupa | testirano na sintetičkim kadrovima (4 boje sukna) |
| Prepoznavanje svih kugli (i složenog trougla od 15), bijele kugle | testirano, greška ≤ ~1 px |
| Čitanje **linije ciljanja koju igra sama crta** iz bijele kugle (štap se odbacuje) | testirano, greška < 0.5° |
| Putanja ciljne i bijele kugle, odbijanja od jastuka, ulazak u rupu | fizika testirana (vidi `selftest/izvjestaj_testova.txt`) |
| Spin: top spin, back spin, lijevo/desno — u fizici (klizanje → kotrljanje, trenje o jastuk) | testirano |
| Čitanje spina iz igre (crvena tačka na spin lopti) | **radi samo ako se spin lopta nađe / kalibriše** — vidi dolje |
| Produžena linija; mali pomak ostaje, veliki skriva i preračuna | testirano (logika), podesivo u panelu |
| Zaštita od „povratne sprege” (KENO ne vidi vlastiti crtež kao pomjeranje kugli) | testirano |

**Svih 82 provjere u `selftest/` prolaze.** Testovi rade na sintetičkim slikama koje sam nacrtao, ne na pravim snimcima igre.
Aplikacija **nije** isprobana na stvarnom telefonu ni s pravom igrom — vidi „Ograničenja”.

## Postavljanje na GitHub i pravljenje APK-a
1. **Obriši stari sadržaj repozitorija** (posebno stari `app/src/...TableDetector.kt` koji ne kompajlira) i postavi cijeli sadržaj ovog ZIP-a u korijen repozitorija.
2. GitHub → *Actions* → *Build KENO APK* → *Run workflow*. APK je artifact `KENO-debug-apk` (`app-debug.apk`).
3. Ili otvori folder u Android Studiju (Koala+, JDK 17) i pokreni *Build → Build APK(s)*.

## Instalacija i test na Xiaomi Redmi 14C
1. Prebaci `app-debug.apk` na telefon, instaliraj (dozvoli instalaciju iz nepoznatih izvora).
2. Otvori KENO → **Dozvoli prikaz preko drugih aplikacija**.
3. Postavke → Aplikacije → KENO → Ostale dozvole → uključi **„Prikaži iskačuće prozore dok radi u pozadini”**; Baterija → **Bez ograničenja**.
4. **Pokreni KENO** → kad Android pita za snimanje ekrana izaberi **„Cijeli ekran” / „Započni”**. Otvara se 8 Ball Pool.
5. Na ekranu je zeleno **K** dugme. Dodir = panel. Uključi **Debug prikaz** da vidiš šta KENO prepoznaje (žuti pravougaonik = sto, krugovi = kugle/rupe).
6. Ciljaj u igri: igra crta bijelu liniju — KENO je čita i dodaje svoju liniju, putanju ciljne kugle (žuta) i bijele (roze).
7. Prvo testiraj u vježbi/praksi (ne u rangiranoj igri).

### Ako nešto ne radi
* **Piše „Stol nije pronađen”** → igraj u landscape modu i provjeri da nema dijaloga/prozora koji prekriva stol.
* **Linija je pomaknuta u odnosu na ivicu stola** → u panelu pomjeri „Podešavanje ivica stola” (±px) dok se odbijanja ne poklope s igrom.
* **„Ciljna linija igre: NIJE VIDLJIVA”** → KENO čita liniju koju igra crta; ako je igra ne crta (tuđi red, animacija) nema šta čitati.
* **Putanje su prekratke/predugačke** → snaga udarca se **ne može pročitati s ekrana**; podesi klizač „Snaga udarca”.

## Spin — šta je stvarno moguće
Igra pokazuje spin samo kao **malu bijelu loptu s crvenom tačkom** (u 8 Ball Poolu gore desno na ekranu). KENO čita položaj te tačke **samo ako zna gdje je ta lopta**:
* **Kalibriši** (preporučeno): panel → „Kalibriši spin loptu iz igre” → prevuci narandžasti krug preko spin lopte u igri → *Gotovo*.
* **Auto-pronađi**: pokušaj panel → „Pokušaj automatski…”. Traži bijelu okruglu površinu s crvenom tačkom; ne mora uspjeti.
* Ako se tačka ne vidi, KENO piše **crvenom bojom pored bijele kugle da računa BEZ spina** — ne glumi da je spin pročitan.
* Alternativa: način **Ručno** (dodirni gdje bi štap pogodio kuglu).
Spin tačka je vezana za smjer udarca (gore = top spin/follow, dolje = back spin/draw).

## Ograničenja (iskreno)
* **Provjereno na 2 prava snimka ekrana** (tabla se nalazi unutar sukna, kugle i spin lopta gore desno se nalaze; tabla 6/6 džepova). Ostalo je testirano na sintetičkim slikama. **Poznato ograničenje:** gusto složene kugle (početni rack) se još ne razdvajaju pouzdano, pa predikcija nakon udarca u rack nije tačna. KENO više ne crta preko bijele linije igre (crta od kruga udara dalje, linije su skraćene na 1–2 odbijanja). Zato postoje: kalibracija spina, podešavanje ivica, debug prikaz.
* Ako je jastuk iste svjetline kao sukno, ivica može biti pogrešna za širinu jastuka (~18 px) dok je ne podesiš klizačem.
* Fizika je realna za pravi bilijar (klizanje/kotrljanje, restitucija, trenje jastuka), ali **igra ima vlastitu fiziku**; dužine kretanja i spin efekti su procjena. Ne modelira se: „throw” između kugli, masse, štap pod uglom.
* Snaga udarca se ne čita s ekrana (ručni klizač).
* Predviđanje se prekida nakon nekoliko odbijanja (označeno malom tačkom, ne krugom).
* Performanse na Helio G81 nisu mjerene (na desktopu ~30 ms po kadru); analiza se sama usporava na ~9 kadrova/s.
* Android 14+: u dijalogu za snimanje izaberi cijeli ekran. MediaProjection se može kreirati samo jednom po odobrenju, pa pri rotaciji KENO mijenja veličinu istog virtualnog ekrana.

## Struktura
* `app/src/main/java/com/keno/billiards/` — `MainActivity`, `CaptureOverlayService` (prozori + analiza), `ScreenCapturer` (MediaProjection), `OverlayViews`, `ControlPanel`, `SettingsStore`
* `.../core/` — čista Kotlin logika bez Androida: `TableDetector`, `BallDetector`, `AimDetector`, `SpinReader`, `Physics`, `ShotPredictor`, `AimTracker`, `Analyzer`, `Scene`
* `selftest/` — testovi koji rade na desktop JVM-u (`./selftest/run.sh`, treba `kotlinc`) i izvještaj posljednjeg pokretanja
