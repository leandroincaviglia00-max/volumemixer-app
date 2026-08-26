# Protocollo USB - versione 1

- **Trasporto**: socket unix astratto `localabstract:remotevolumemixer`,
  esposto al PC via `adb forward` attraverso il cavo USB.
- **Ruoli**: il telefono è il server del socket, il PC è il client.
- **Formato**: NDJSON. Una riga (`\n`) = un messaggio JSON. UTF-8.
- **Versionamento**: ogni messaggio porta `"v"`. L'handshake rifiuta le versioni
  non supportate con un messaggio comprensibile, non con un crash.
- **Debug**: essendo testo, basta leggere il log o `adb logcat` per capire tutto.

## Handshake

```
PC       -> {"type":"hello","v":1,"host":"DESKTOP-K7","appVersion":"1.0.0","minProtocolVersion":1}
TELEFONO -> {"type":"client_hello","client":"Google Pixel 7 (Android 14)","v":1}
PC       -> {"type":"snapshot","v":1,"applications":[ ... ]}
```

Se la versione non è compatibile il PC risponde
`{"type":"error","code":"protocol_version","message":"..."}` e chiude.

## PC -> telefono

| type | payload | quando |
|---|---|---|
| `hello` | `host`, `appVersion`, `minProtocolVersion` | subito dopo la connessione |
| `snapshot` | `applications[]` | dopo l'handshake e su richiesta |
| `app_added` | `application` | nuova sessione audio rilevata |
| `app_updated` | `application` | cambio nome o stato attivo/inattivo |
| `app_removed` | `sessionId` | sessione chiusa o scaduta |
| `volume_changed` | `sessionId`, `volume`, `muted` | cambio da Windows o conferma di un comando |
| `icon` | `iconKey`, `png` (base64, può essere `null`) | risposta a `request_icon` |
| `ack` | `requestId`, `ok`, `error` | esito di un comando |
| `pong` | `nonce` | risposta al keepalive |
| `error` | `code`, `message` | problema comunicabile all'utente |

### Oggetto application

```json
{
  "sessionId": "9f2c1ab34d5e6f708190",
  "name": "Spotify",
  "processName": "Spotify",
  "pid": 9184,
  "volume": 72,
  "muted": false,
  "state": "active",
  "isSystemSounds": false,
  "isMaster": false,
  "iconKey": "3b1f77c0a9de4412"
}
```

`state`: `active` | `inactive` | `expired`.
`iconKey`: chiave stabile e condivisa tra sessioni dello stesso eseguibile
(l'icona viaggia una volta sola). Stringa vuota = nessuna icona disponibile.
`isMaster`: card del dispositivo di output, `sessionId` = `__master__`.

## Telefono -> PC

| type | payload |
|---|---|
| `client_hello` | `client` |
| `set_volume` | `sessionId`, `volume` (0-100), `requestId` |
| `set_mute` | `sessionId`, `muted`, `requestId` |
| `request_snapshot` | - |
| `request_icon` | `iconKey` |
| `ping` | `nonce` |

Esempi:

```json
{"type":"set_volume","sessionId":"9f2c1ab34d5e6f708190","volume":72,"requestId":41,"v":1}
{"type":"set_mute","sessionId":"9f2c1ab34d5e6f708190","muted":true,"requestId":42,"v":1}
```

## Regole di flusso

1. **Ottimismo locale**: il telefono muove lo slider subito, poi invia.
2. **Throttling**: massimo un `set_volume` ogni 40 ms per sessione (canale
   conflated), valore finale sempre inviato al rilascio.
3. **Eco-suppression**: i `volume_changed` che arrivano entro 450 ms dall'ultimo
   gesto locale su quella sessione vengono ignorati (evita lo slider che salta).
4. **Keepalive**: `ping` ogni 3 s. Nessun dato per 12 s = sessione considerata
   morta su entrambi i lati, con riconnessione automatica.
5. **Tolleranza**: una riga non parsabile viene loggata e scartata; la sessione
   continua.
6. **Riconnessione**: alla riconnessione il PC rimanda sempre uno `snapshot`
   completo, quindi non serve alcuno stato persistente sul telefono.
