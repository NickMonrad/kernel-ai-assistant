# Jandal AI Privacy Policy

> **Draft for owner and release review. This is not the published or effective policy.** Provider-specific practices, the final release dependency set, contact details, and the exact signed release candidate still require review before publication.

Jandal AI is an Android assistant that runs its language-model inference on the device. Some features you choose to use connect to online services or pass information to another app. This policy draft describes those code paths; it does not claim that every voice, location, or third-party service operates offline.

## Information kept on your device

Jandal stores app data in its local database and app storage. This can include conversations and messages, memories, notes, lists and their items, meal-planning data, preferences, and settings you create or change. Downloaded model and voice files are stored in app-specific storage. The app manifest disables Android app backup (`allowBackup=false`). This is not a promise that information already sent to another service or app is removed there.

Jandal's language-model inference uses models running on your device; the app does not send conversation prompts to a Jandal-operated cloud language-model endpoint. If you invoke a feature that uses an online service, the specific inputs needed for that feature may be sent as described below.

## Features that connect to services

### Speech, microphone, and wake word

Speech features require microphone access. Vosk and Sherpa-ONNX speech recognition process audio with local models. Android native speech recognition may use on-device recognition when available or the recognition provider configured on the device. Jandal requests offline preference, but the configured provider may use network access; the actual backend and processing depend on the device and provider. Jandal does not control or verify that provider's processing, retention, or deletion practices.

If you enable **Listen for Hey Jandal**, the app keeps a microphone foreground service active and analyses audio on-device to detect the wake phrase. When the wake phrase is detected, Jandal starts a voice command using the selected speech-recognition engine. The selected engine therefore determines whether command audio may be handled by an Android-provided recognition service. You can turn off the wake-word feature in the app and revoke microphone permission in Android settings.

On HONOR BKQ-N49 only, wake-word listening also requires the special Usage Access that you grant in Android settings. Jandal reads recent app-foreground events through Android to detect the stock HONOR Camera and release its microphone capture while the camera is in front. The implementation processes those events in memory, retains the resolved foreground package, and does not persist or transmit the usage-event history. You can revoke Usage Access in Android settings.

Spoken responses use the output engine selected in Settings. Android TTS delegates text to the Android text-to-speech engine selected on the device. Sherpa Piper and Inflect Micro use downloaded local voice/model files and can fall back to Android TTS when their local runtime or files are unavailable. Jandal does not control an Android speech engine's processing or retention practices.

### Weather, location, currency, and Wikipedia

For a user-invoked current-location weather request, Jandal obtains coarse location through Google Play services' Fused Location Provider and rounds latitude and longitude to 2 decimal places before sending them to OpenStreetMap Nominatim for reverse geocoding and Open-Meteo for weather and air-quality data. This is approximate location (about 1 km), not precise GPS coordinates. For a typed place, the native weather skill sends the place name to Nominatim search; the `get-weather-city` skill uses Open-Meteo geocoding. A country-capital lookup sends the typed country name to REST Countries. Weather responses are cached locally. These requests use HTTPS. Jandal does not control or verify provider retention or deletion practices.

When you request a currency conversion, Frankfurter receives currency codes for its catalog and rate requests; the amount is calculated locally and is not sent in the request. When you request a Wikipedia lookup, Jandal sends the search text to Wikipedia over HTTPS. These requests are feature-specific and are not used to run the on-device language model. Jandal does not control provider retention or deletion.

### Model and voice downloads; Hugging Face sign-in

Some model or voice downloads are started in Settings; some feature setup can queue required files automatically. Jandal retrieves files from the configured Hugging Face or GitHub host over HTTPS and rejects insecure download redirects. The optional Hugging Face OAuth flow uses Authorization Code + PKCE and requests the `openid`, `profile`, and `gated-repos` scopes. It receives an access token and available account name; the token and account name are stored in encrypted preferences backed by Android Keystore. The token is sent to Hugging Face only for gated model downloads. Hugging Face handles sign-in and its account data; Jandal does not receive your Hugging Face password. You can sign out in the app, which removes the stored token and account name from local preferences.

Hugging Face sign-in is optional. No model in the current catalog is marked gated, so current model downloads do not require sign-in or use the stored token. Downloaded model and voice files can be removed through the app's storage controls. Jandal does not control or verify file-host retention of request metadata.

### Optional Nextcloud list sync

If you configure a Nextcloud server and enable sync for a list, Jandal exchanges that list and its task items with the server using CalDAV. The server URL, username, and app password are saved in encrypted preferences backed by Android Keystore. Jandal can create, update, and delete corresponding remote list/task resources as you use sync. The Nextcloud server operator controls server-side access and retention.

Nextcloud login and CalDAV sync require HTTPS. Explicit HTTP is rejected before a connection is opened. Redirects are followed only to the same HTTPS origin (same host and effective port); redirects to HTTP or another origin are refused. The app's network security policy denies cleartext traffic.

### Actions handed to other apps

When you ask Jandal to use a device capability, it may pass the relevant text or destination to an Android app or service. Email and SMS actions open a composer with the chosen recipient and content; you decide whether to send. A Report response action opens an email composer containing the selected assistant reply, app version, model, and an editable reason. You choose whether to send it through your email app; Jandal does not send or retain the report. The dedicated support address has not been set, so the app still uses a placeholder. Map actions pass a destination or search query to Google Maps or another compatible maps app. A call action can hand a resolved number to Android's call action; media actions may pass a search query to a media app or browser. Those apps and their service providers handle information they receive under their own policies.

### Files and sharing

The chat attachment picker can select an image, audio item, or file. In the current chat flow, Jandal reads the selected item's display name and adds its type and name to the chat text; it does not read the selected file's contents through that chat picker. Other user-selected file flows are separate: a wallpaper image can be copied into app storage, and Lists can import a Jandal list package selected through Android's document picker. When you export or share a list, note, or diagnostic bundle, Jandal passes the chosen content to the Android share sheet and the app you select.

Some features can read contacts or calendar entries when you grant the corresponding Android access and invoke the feature that needs them. This access does not, by itself, mean that Jandal uploads your contacts or calendar.

### Logs and sharing

Jandal writes diagnostic messages while features run. Some logs can contain request parameters or bounded results, which may include text you entered. The About screen offers a user-triggered **Export logs** action that can include recent process-exit information and current-process logs. If you choose an app from Android's share sheet, that app receives the export and handles it under its own policy. Review the contents before sharing. The retained last-uncaught-exception file is installed only in debuggable builds, not release builds.

The current release dependency inventory contains no analytics, advertising, or crash-reporting SDKs. This does not describe data handling by the Android speech provider or other external services.

## Retention and deletion

Conversation history, notes, lists, and other saved local content remain in app storage until you delete them or use a feature-specific cleanup control. Archived conversations are automatically deleted according to the retention option in Chat preferences: 1, 3, 7, 14, or 30 days, or **Never**. The default is 7 days; cleanup runs periodically, so deletion may not happen at the exact cutoff time. Episodic memories are pruned after 30 days and when the local 500-item limit is exceeded. Core memories are limited to 200 items. Deletion controls are feature-specific; there is no single retention period for every category.

Deleting local app data does not delete content already sent to a speech, weather, lookup, maps, messaging, or other provider. For Nextcloud, use the server's controls to remove remote copies. Signing out of Hugging Face removes the locally stored token and account name; it does not delete your Hugging Face account or data held by Hugging Face.

## Provider practices

Jandal does not control third-party services' or user-selected apps' collection, retention, or deletion practices. Their own policies govern information they receive. Provider-specific retention periods have not been verified and are not stated here.
