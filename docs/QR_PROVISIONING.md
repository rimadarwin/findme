# Provisioning Device Owner tramite QR

Il QR viene letto dal Setup Wizard di Android su un telefono nuovo o appena
ripristinato. Android configura la rete, scarica l'APK FindMe da HTTPS, ne
verifica il checksum, lo installa e assegna all'app il ruolo Device Owner.
L'app non deve quindi essere già installata.

## 1. Creare e conservare la chiave release

Eseguire una sola volta:

```powershell
keytool -genkeypair -v `
  -keystore C:\secure\findme-release.jks `
  -alias findme `
  -keyalg RSA -keysize 4096 -validity 10000
Copy-Item keystore.properties.example keystore.properties
```

Inserire i valori reali in `keystore.properties`. Il file e il keystore non
devono essere committati. Conservare una copia sicura del keystore: gli
aggiornamenti futuri dell'app devono usare la stessa firma.

Creare l'APK:

```powershell
.\gradlew.bat :transmitter:assembleRelease
```

Output: `transmitter\build\outputs\apk\release\transmitter-release.apk`.
Con Android SDK installato è possibile verificare la firma:

```powershell
apksigner verify --verbose `
  transmitter\build\outputs\apk\release\transmitter-release.apk
```

## 2. Pubblicare l'APK

Caricare l'APK firmato su un URL HTTPS pubblico, diretto e accessibile senza
cookie o autenticazione. L'URL deve restituire l'APK anche dal telefono durante
il Setup Wizard.

Il checksum identifica esattamente il file: dopo ogni nuova build occorre
caricare il nuovo APK e rigenerare il QR. Non sovrascrivere un APK lasciando in
uso un QR con il checksum precedente.

## 3. Generare payload e QR

Usare il codice di associazione di 10 caratteri mostrato dal ricevitore:

```powershell
.\tools\New-FindMeProvisioningQr.ps1 `
  -ApkPath transmitter\build\outputs\apk\release\transmitter-release.apk `
  -ApkUrl "https://download.example.com/findme-transmitter-0.4.0.apk" `
  -ReceiverCode "7E3D42DB25" `
  -WifiSsid "NomeRete" `
  -WifiPassword "PasswordRete"
```

Lo script:

- calcola il SHA-256 dell'APK nel formato URL-safe richiesto da Android;
- crea `build\provisioning\findme-provisioning.json`;
- genera localmente `build\provisioning\findme-provisioning.png` tramite
  `npx qrcode`, senza inviare password o payload a servizi web.

Per una rete aperta usare `-WifiSecurity NONE`. Si possono omettere tutti i
parametri Wi-Fi se la rete viene scelta manualmente nel Setup Wizard. Usare
`-SkipQr` per creare soltanto il JSON.

Il JSON contiene il componente DPC, URL e checksum dell'APK, il codice del
ricevitore negli admin extras e, se indicati, i dati Wi-Fi. QR e JSON possono
contenere credenziali: non condividerli e non aggiungerli a Git.

## 4. Provisionare il telefono

1. Eseguire un factory reset del trasmettitore.
2. Nella schermata iniziale del Setup Wizard toccare sei volte lo stesso punto
   dello schermo. Il gesto esatto può cambiare in base al produttore.
3. Se richiesto, collegarsi a Internet e installare il lettore QR temporaneo.
4. Scansionare `findme-provisioning.png`.
5. Confermare la gestione del dispositivo e completare il Setup Wizard.
6. Aprire FindMe, rispondere alla domanda di sicurezza e avviare il
   monitoraggio.

Android 12 e successivi invocano le attività FindMe
`GET_PROVISIONING_MODE` e `ADMIN_POLICY_COMPLIANCE`; Android precedenti usano
anche il callback legacy. Durante la compliance Android conferisce a FindMe il
ruolo Device Owner e FindMe chiede ad Android di pre-approvare camera,
microfono, posizione in primo piano/background e notifiche.

## Limiti Android

- MediaProjection per il mirroring schermo richiede comunque una conferma
  visibile dopo riavvio o revoca: il Device Owner non può concederla.
- L'esenzione dalle ottimizzazioni batteria e le impostazioni proprietarie di
  Xiaomi/Samsung possono richiedere un intervento manuale.
- Account già presenti impediscono il provisioning Device Owner: occorre il
  factory reset.
- Testare il QR sul modello Android effettivo; i Setup Wizard dei produttori
  possono aggiungere passaggi propri.

Per lo sviluppo senza factory reset resta disponibile il comando ADB descritto
in `EXTERNAL_SETUP.md`, ma non trasporta gli admin extras del QR.
