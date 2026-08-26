# Procedura di test

Prerequisiti: EXE compilato, APK installato, Debug USB attivo, PC autorizzato.
Tieni aperto il log: `%LOCALAPPDATA%\RemoteVolumeMixer\logs\rvm-*.log`
(oppure avvia con `RemoteVolumeMixer.exe --console --verbose`).

| # | Test | Passi | Atteso |
|---|---|---|---|
| 1 | Avvio client Windows | avvia `RemoteVolumeMixer.exe` | icona nella tray, log `started`, `USB transport initialized` |
| 2 | Collegamento Android | collega il cavo | tray: `USB: phone detected, waiting for the app` |
| 3 | Rilevamento USB | apri l'app sul telefono | header `● USB Connected`, log `Handshake complete`, tray `USB: connected` |
| 4 | Rilevamento Spotify | apri Spotify e fai partire un brano | la card Spotify appare da sola con icona reale e percentuale |
| 5 | Cambio volume Spotify | trascina lo slider di Spotify | il volume di Spotify cambia mentre trascini, percentuale live, log `Spotify volume changed: X -> Y` |
| 6 | Cambio volume Discord | avvia Discord (test audio) e trascina | cambia solo Discord: Spotify e il master restano invariati |
| 7 | Mute Spotify | tocca il pulsante mute | Spotify muto, Discord udibile, master non toccato; icona animata |
| 8 | Modifica da Windows | mixer di Windows → porta Spotify a 40% | il telefono passa a 40% entro ~1 s senza toccare nulla |
| 9 | Sincronizzazione inversa | alterna 2-3 modifiche da PC e da telefono | nessun rimbalzo, i due lati convergono sempre |
| 10 | Chiusura Spotify | chiudi Spotify | la card scompare con animazione, log `Audio session gone` |
| 11 | Riapertura Spotify | riapri Spotify | la card ricompare da sola, senza riavviare nulla |
| 12 | Scollegamento USB | stacca il cavo | telefono: `○ USB Disconnected`, slider disabilitati, nessun errore tecnico; tray torna a `no phone connected` |
| 13 | Ricollegamento USB | ricollega il cavo | riconnessione automatica entro ~2 s e snapshot completo ricaricato |
| 14 | Più applicazioni | Spotify + Chrome + Discord + Steam + System Sounds | tutte presenti, volumi indipendenti, ordinamento sensato (attive prima) |
| 15 | PC senza Internet | disabilita la scheda di rete | tutto continua a funzionare identico |
| 16 | Wi-Fi telefono spento | attiva la modalità aereo sul telefono (USB collegato) | tutto continua a funzionare identico |

## Test aggiuntivi consigliati

| # | Test | Atteso |
|---|---|---|
| 17 | App senza icona estraibile (processo protetto) | riquadro di fallback con iniziale, nessuna immagine rotta |
| 18 | Rotazione schermo e tablet | phone verticale: 1 colonna; orizzontale/tablet: 2 colonne, testo leggibile |
| 19 | Cambio tema Dark/Light/System | applicazione immediata, dark curato |
| 20 | App Android in background 5 minuti | il foreground service tiene il canale, al rientro lo stato è già aggiornato |
| 21 | Cambio dispositivo di output di Windows | le sessioni vengono ricostruite sul nuovo device |
| 22 | `Start with Windows` on/off | voce di registro `HKCU\...\Run\RemoteVolumeMixer` creata/rimossa |
| 23 | Doppio avvio dell'EXE | la seconda istanza esce in silenzio (mutex) |
| 24 | Pacchetto malformato (`printf '{oops\n' | nc`) | log `Invalid packet ignored`, sessione ancora viva |
| 25 | adb.exe rimosso | tray `USB: adb not found`, nessun crash |
