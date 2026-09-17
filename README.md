# FindMe

Sistema Android composto da due app:

- **transmitter**: pubblica posizione e, su comando, audio/video;
- **receiver**: mostra trasmettitori, stato, mappa e flusso LiveKit.

Il backend applicativo è Supabase; i media realtime passano attraverso LiveKit SFU.

## Prerequisiti

- JDK 17
- Android SDK 35
- account Supabase
- progetto LiveKit Cloud

## Configurazione locale

1. Copiare `local.properties.example` in `local.properties`.
2. Inserire URL e chiave **publishable** Supabase e URL WebSocket LiveKit.
3. Non inserire mai `sb_secret`, `service_role` o `LIVEKIT_API_SECRET` nel progetto Android.

## Build

```powershell
.\gradlew.bat test
.\gradlew.bat :transmitter:assembleDebug :receiver:assembleDebug
```

APK:

- `transmitter/build/outputs/apk/debug/transmitter-debug.apk`
- `receiver/build/outputs/apk/debug/receiver-debug.apk`

## Primo utilizzo

1. Installare il ricevitore: l'app crea automaticamente un'identità anonima sicura
   e mostra il proprio codice di associazione.
2. Installare/provisionare il trasmettitore passando quel codice come
   `findme_receiver_code`; l'associazione avviene in modo invisibile.
3. Alla prima apertura compare direttamente la domanda configurata dal
   ricevitore; rispondere per aprire la dashboard.
4. Sul trasmettitore attivare gli switch dei permessi e premere
   **Avvia monitoraggio**.
5. Sul ricevitore selezionare il dispositivo e attivare audio/video.

Non servono email o password. Il pairing iniziale usa un codice univoco di 10
caratteri fornito durante il provisioning; il nome del dispositivo rimane
soltanto un'etichetta leggibile. Dopo l'accesso è possibile cambiare ricevitore
dalla dashboard.
La risposta alla domanda non viene salvata in chiaro: il database conserva
soltanto un hash bcrypt.

Per database, Edge Function, LiveKit, mappe e Device Owner vedere
[`docs/EXTERNAL_SETUP.md`](docs/EXTERNAL_SETUP.md).

## Limiti intenzionali

- Notifica foreground e indicatori privacy Android restano visibili.
- In modalità standard, dopo reboot è necessario aprire l'app trasmittente.
- Il riavvio completamente automatico richiede il provisioning Device Owner.
- L'MVP mostra un flusso video alla volta; database e stanze sono già multi-trasmettitore.
