# Remote Volume Mixer

Il tuo telefono Android diventa un mixer hardware per i volumi delle singole
applicazioni di Windows. Collegato **solo** con il cavo USB.

```
Spotify 72%  ━━━━━━━━━━━━━━●━━━━━━━━━
Discord 38%  ━━━━━━━●━━━━━━━━━━━━━━━━
Chrome  64%  ━━━━━━━━━━━━●━━━━━━━━━━━
```

Zero Wi-Fi, zero LAN, zero WebSocket, zero server HTTP, zero IP, zero cloud,
zero account. Funziona con il Wi-Fi spento, il PC senza Internet e il telefono
senza SIM.

---

## 1. Cosa fa

- Rileva **realmente** le sessioni audio di Windows (Core Audio / WASAPI).
- Legge e scrive **volume individuale** e **mute individuale** per ogni app:
  abbassare Spotify non tocca il volume master di Windows.
- Mostra l'**icona reale** dell'eseguibile (Spotify, Discord, Chrome, Steam...).
- Le app compaiono e scompaiono **da sole**: apri Spotify a mixer già avviato e
  la card appare, chiudilo e sparisce.
- **Sincronizzazione bidirezionale**: se cambi il volume dal mixer di Windows,
  il telefono si aggiorna da solo, e viceversa.
- Card opzionale del dispositivo di output (volume master), chiaramente separata.

---

## 2. Architettura

```
APP ANDROID (Kotlin + Compose)
        │  socket unix astratto  "localabstract:remotevolumemixer"
        ▼
      CAVO USB   ← l'unico canale fisico
        ▼
        ADB  (adb forward)
        ▼
RemoteVolumeMixer.exe (C# / .NET 8, tray)
        ▼
Windows Core Audio (MMDevice + Audio Session API + ISimpleAudioVolume)
        ▼
Spotify / Discord / Chrome / Steam / System Sounds / ...
```

### Perché ADB (e perché non è "rete")

L'app Android pubblica un **socket unix nel namespace astratto di Linux**
(`LocalServerSocket`). Il client Windows chiede ad adb di esporlo su una porta di
**loopback locale** con:

```
adb -s <serial> forward tcp:0 localabstract:remotevolumemixer
```

I byte viaggiano dentro il **cavo USB**. Non esiste un IP del telefono, non
esiste un socket raggiungibile dalla rete, non c'è nessun server in ascolto su
un'interfaccia di rete: se stacchi il cavo, il canale muore. È la stessa tecnica
usata da strumenti come scrcpy, ed è la soluzione USB-only più affidabile senza
scrivere un driver kernel o pretendere un telefono con supporto USB accessory.

Il ruolo dei due lati è invertito rispetto al solito: **il telefono è il server
del socket, il PC è il client**.

---

## 3. Requisiti

**Windows**
- Windows 10 / 11 (x64)
- .NET SDK 8 *solo per compilare* (l'EXE pubblicato è self-contained)
- Android platform-tools (`adb.exe`)

**Android**
- Android 8.0 (API 26) o superiore
- Android Studio Ladybug o superiore (AGP 8.6.1, Gradle 8.9, JDK 17)

> La **prima compilazione** scarica le dipendenze (NuGet e Gradle) e quindi
> richiede Internet. L'app, una volta compilata, funziona completamente offline.

---

## 4. Struttura del progetto

```
RemoteVolumeMixer/
├── android/                       progetto Android Studio
│   ├── app/src/main/java/com/remotevolumemixer/
│   │   ├── MainActivity.kt
│   │   ├── RvmApplication.kt      container delle dipendenze
│   │   ├── protocol/              messaggi + codec JSON
│   │   ├── transport/             socket USB + foreground service
│   │   ├── data/                  repository, preferenze, cache icone
│   │   └── ui/                    schermata, ViewModel, tema, componenti
│   └── build.gradle.kts, settings.gradle.kts, gradle/
├── windows/
│   ├── RemoteVolumeMixer.sln
│   ├── publish.cmd                genera l'EXE distribuibile
│   ├── build-debug.cmd            build + avvio con log a video
│   └── RemoteVolumeMixer/
│       ├── Program.cs
│       ├── App/                   MixerHost, tray, impostazioni, autostart
│       ├── Audio/                 AudioEngine, SessionMonitor, IconProvider
│       ├── Transport/             AdbLocator, AdbClient, UsbTransport
│       ├── Protocol/              messaggi + codec
│       ├── Models/                ApplicationModel
│       └── Core/                  Logger
├── docs/
│   ├── PROTOCOL.md                protocollo v1, messaggio per messaggio
│   └── TEST-PLAN.md               procedura di test completa
└── README.md
```

---

## 5. Installazione Android

### 5.1 Abilitare Developer Options e USB debugging

1. **Impostazioni → Info sul telefono**
2. Tocca **Numero build** 7 volte → "Ora sei uno sviluppatore"
3. **Impostazioni → Sistema → Opzioni sviluppatore**
4. Attiva **Debug USB**

### 5.2 Autorizzare il PC

1. Collega il telefono al PC con il cavo USB (usa una porta e un cavo che
   supportino i dati, non solo la ricarica)
2. Sul telefono appare **"Consentire il debug USB?"** → spunta *Consenti sempre
   da questo computer* → **Consenti**
3. Verifica dal PC:

```
adb devices -l
```

Devi vedere il tuo dispositivo con stato `device`. Se leggi `unauthorized`,
ripeti il punto 2. Se leggi `offline`, scollega e ricollega il cavo.

### 5.3 Generare e installare l'APK

Da Android Studio:

1. **File → Open** → seleziona la cartella `android/`
2. Attendi il Gradle sync (la prima volta scarica le dipendenze)
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. L'APK è in `android/app/build/outputs/apk/debug/app-debug.apk`

Da riga di comando (dentro `android/`):

```
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> Se la cartella `gradle/wrapper/gradle-wrapper.jar` non è presente, apri il
> progetto in Android Studio (rigenera il wrapper da sé) oppure lancia
> `gradle wrapper` con un Gradle 8.9 già installato.

---

## 6. Installazione Windows

### 6.1 Compilare l'EXE distribuibile

```
cd windows
publish.cmd
```

Risultato: `windows\publish\RemoteVolumeMixer.exe`, singolo file
**self-contained**: chi lo riceve non deve installare il runtime .NET.

Alternativa manuale:

```
dotnet publish windows\RemoteVolumeMixer\RemoteVolumeMixer.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o windows\publish
```

Per lo sviluppo, con log a video:

```
cd windows
build-debug.cmd
```

oppure `RemoteVolumeMixer.exe --console --verbose`.

### 6.2 adb.exe

Il client cerca `adb.exe` in quest'ordine:

1. `"adbPath"` in `settings.json`
2. cartella `adb\` o `platform-tools\` accanto all'EXE (installazione portable)
3. `PATH`
4. `%ANDROID_HOME%` / `%ANDROID_SDK_ROOT%\platform-tools`
5. `%LOCALAPPDATA%\Android\Sdk\platform-tools`

Se non hai l'SDK completo: scarica "SDK Platform-Tools for Windows" da Google,
estrai e copia `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` in una cartella
`adb\` accanto a `RemoteVolumeMixer.exe`.

---

## 7. Come si usa

1. Avvia `RemoteVolumeMixer.exe` → compare l'icona nella tray
2. Collega il telefono con il cavo USB (Debug USB attivo e PC autorizzato)
3. Apri **Volume Mixer** sul telefono
4. L'header mostra **● USB Connected** e appaiono le applicazioni

Trascina uno slider: il volume dell'app su Windows cambia mentre muovi il dito.
La percentuale si aggiorna live, senza dover rilasciare.

Menu della tray:

```
Remote Volume Mixer  v1.0.0
USB: connected (Pixel 7)
Applications: 6
Reconnect USB
Start with Windows      ← avvio automatico opzionale
Open log folder
Open settings file
Exit
```

### Impostazioni Windows

`%APPDATA%\RemoteVolumeMixer\settings.json`

| Chiave | Default | Significato |
|---|---|---|
| `adbPath` | `null` | percorso di adb.exe (null = ricerca automatica) |
| `deviceSerial` | `null` | seriale da usare se hai più dispositivi collegati |
| `devicePollIntervalMs` | `1500` | ogni quanto cercare il telefono |
| `audioPollIntervalMs` | `1200` | riconciliazione delle sessioni audio |
| `includeMasterVolume` | `true` | mostra la card del dispositivo di output |
| `autoLaunchAndroidApp` | `false` | apre l'app sul telefono quando lo rileva |
| `logLevel` | `Info` | `Debug`, `Info`, `Warn`, `Error` |

Dopo una modifica, riavvia l'EXE.

### Impostazioni Android

Icona in alto a destra: tema (**Dark è il tema principale**), ordinamento,
mostrare o no le app che non stanno suonando, card dell'output di Windows,
schermo sempre acceso. Le preferenze sono salvate localmente con DataStore.
Nessuna password, nessun account, nessun cloud.

---

## 8. Log

`%LOCALAPPDATA%\RemoteVolumeMixer\logs\rvm-AAAAMMGG.log` (7 giorni di rotazione).

```
2026-08-27 01:12:03.114 [INFO] Remote Volume Mixer started (v1.0.0)
2026-08-27 01:12:03.190 [INFO] Default audio output: Speakers (Realtek)
2026-08-27 01:12:03.244 [INFO] Audio session detected: Spotify (pid 9184, 70%)
2026-08-27 01:12:03.245 [INFO] Audio session detected: Discord (pid 4412, 80%)
2026-08-27 01:12:04.010 [INFO] USB transport initialized
2026-08-27 01:12:06.551 [INFO] Android device connected: Pixel 7 (serial 1A2B3C, local port 51544)
2026-08-27 01:12:06.640 [INFO] Handshake complete with Google Pixel 7 (protocol v1)
2026-08-27 01:12:06.641 [INFO] Snapshot sent: 6 application(s)
2026-08-27 01:12:19.882 [INFO] Spotify volume changed: 70 -> 65
2026-08-27 01:14:02.117 [WARN] USB transport disconnected (Pixel 7)
```

Lato Android: `adb logcat -s RVM/Bridge RVM/Repo RVM/Icons RVM/Service`.

Gli errori tecnici finiscono nei log. L'interfaccia mostra solo messaggi
comprensibili: nessuno stack trace, mai.

---

## 9. Troubleshooting

| Sintomo | Causa | Soluzione |
|---|---|---|
| Tray: `USB: adb not found` | adb non installato/trovato | metti `adb.exe` in `adb\` accanto all'EXE o imposta `adbPath` |
| Tray: `USB: no phone connected` | cavo dati assente o Debug USB off | usa un cavo dati, attiva Debug USB |
| Tray: `authorize this PC on the phone` | prompt ADB non accettato | sblocca il telefono e conferma "Consenti sempre" |
| Tray: `waiting for the app` | telefono collegato ma app chiusa | apri **Volume Mixer** sul telefono |
| Telefono: `USB Disconnected` | EXE non avviato o cavo staccato | avvia l'EXE, ricollega il cavo: la sincronizzazione riparte da sola |
| Nessuna applicazione in lista | nessuna app sta usando l'audio | avvia Spotify/Chrome e fai partire un suono |
| Un'app non ha l'icona | processo protetto o icona non estraibile | l'app mostra il riquadro di fallback con l'iniziale (comportamento voluto) |
| `Version mismatch` sul telefono | APK ed EXE di release diverse | ricompila entrambi dalla stessa cartella |
| L'app non si vede più dopo la chiusura del processo | sessione scaduta | è corretto: la card viene rimossa automaticamente |
| Più telefoni collegati | scelta ambigua | imposta `deviceSerial` in `settings.json` |
| Volume che "rimbalza" mentre trascini | conferme del PC in ritardo | già gestito da eco-suppression 450 ms; se persiste alza `audioPollIntervalMs` |

---

## 10. Dettagli tecnici che contano

**Latenza e traffico.** Durante il trascinamento l'app invia al massimo un
pacchetto ogni 40 ms per sessione (canale conflated: il valore intermedio viene
scartato, quello finale mai). Al rilascio il valore definitivo viene rispedito
subito. Nessun polling aggressivo: il PC usa le notifiche push di Core Audio e
una riconciliazione leggera ogni 1,2 s come rete di sicurezza.

**Identità delle sessioni.** L'ID è l'hash del *session instance identifier* di
Core Audio: stabile per tutta la vita della sessione, diverso per due istanze
della stessa applicazione.

**Icone.** Estratte con `PrivateExtractIcons` (96x96) dall'eseguibile del
processo, con fallback su `ExtractAssociatedIcon`, inviate in PNG base64 una
sola volta per chiave e messe in cache su disco dal telefono. Se un'icona non è
estraibile, Android disegna un riquadro con l'iniziale: stessa dimensione,
stessi angoli, nessun placeholder brutto.

**Robustezza.** Handshake versionato, watchdog su entrambi i lati (12 s),
riconnessione automatica, pacchetti malformati ignorati e loggati, sessioni non
più valide rimosse e ricreate da sole, istanza singola dell'EXE via mutex.

**Limite noto e onesto.** ADB è il transport: serve Debug USB attivo e il PC
autorizzato una volta. In cambio non serve alcun driver, nessuna app di sistema
e nessun permesso speciale. Un canale USB accessory/AOA eliminerebbe ADB ma
richiede un dispositivo host USB compatibile e un driver dedicato: più fragile,
non più USB-only di questo.
