# Jandal AI Privacy Policy

> **Draft for owner and release review. This is not the published or effective policy.** Provider-specific practices, the final release dependency set, contact details, and the exact signed release candidate still require review before publication.

Jandal AI is an Android assistant that runs its language-model inference on the device. Some features you choose to use connect to online services or pass information to another app. This policy draft describes those code paths; it does not claim that every voice, location, or third-party service operates offline.

## Information kept on your device

Jandal stores app data in its local database and app storage. This can include conversations and messages, memories, notes, lists and their items, meal-planning data, preferences, and settings you create or change. Downloaded model and voice files are stored in app-specific storage. The app manifest disables Android app backup (`allowBackup=false`). This is not a promise that information already sent to another service or app is removed there.

Jandal's language-model inference uses models running on your device; the app does not send conversation prompts to a Jandal-operated cloud language-model endpoint. If you invoke a feature that uses an online service, the specific inputs needed for that feature may be sent as described below.

## Features that connect to services

### Speech, microphone, and wake word

Speech features require microphone access. Vosk and Sherpa-ONNX speech recognition process audio with local models. If you select Android native speech recognition, Jandal delegates recognition to the speech-recognition service configured on your device. Android native recognition may require network access; offline behavior is not guaranteed on every device. Jandal does not control that service's processing or retention practices.

If you enable **Listen for Hey Jandal**, the app keeps a microphone foreground service active and analyses audio on-device to detect the wake phrase. When the wake phrase is detected, Jandal starts a voice command using the selected speech-recognition engine. The selected engine therefore determines whether command audio may be handled by an Android-provided recognition service. You can turn off the wake-word feature in the app and revoke microphone permission in Android settings.

Spoken responses use the output engine selected in Settings. Android TTS delegates text to the Android text-to-speech engine selected on the device. Sherpa Piper and Inflect Micro use downloaded local voice/model files and can fall back to Android TTS when their local runtime or files are unavailable. Jandal does not control an Android speech engine's processing or retention practices.

### Weather, location, currency, and Wikipedia

When you request weather for a named place, Jandal sends the place name to Open-Meteo's geocoding service and requests weather for the returned coordinates. When you request GPS-based weather, Jandal obtains the device's last available location through Android's location API and sends the resulting coordinates to Open-Meteo. The app also caches weather responses locally. The location provider used by Android and Open-Meteo's own retention practices are outside Jandal's control.

When you request a currency conversion, Jandal requests currency and exchange-rate data from Frankfurter, which serves European Central Bank reference rates. When you request a Wikipedia lookup, Jandal sends the search text to Wikipedia. These requests are feature-specific; they are not used to run the on-device language model.

### Model and voice downloads; Hugging Face sign-in

Some model or voice downloads are started in Settings; some feature setup can queue required files automatically. For example, enabling wake-word listening can queue missing wake-verifier models. Jandal retrieves files from the host configured for each item, which can include Hugging Face or GitHub. Some resources are gated. If a gated download requires sign-in, Jandal opens Hugging Face's OAuth flow in a browser tab, receives an access token and available account name, and stores them in encrypted app preferences backed by Android Keystore. The token is sent as an authorization credential to Hugging Face for gated requests. Hugging Face handles sign-in and its own account data; Jandal does not receive your Hugging Face password. You can sign out in the app, which removes the stored token and account name from those preferences.

Hugging Face sign-in is not needed for every model or download. The app's required Gemma 4 E-2B download is configured as an ungated resource; gated resources may require sign-in and any upstream licence acceptance shown by Hugging Face.

### Optional Nextcloud list sync

If you configure a Nextcloud server and enable sync for a list, Jandal exchanges that list and its task items with the server using CalDAV. The server URL, username, and app password are saved in encrypted preferences backed by Android Keystore. Jandal can create, update, and delete corresponding remote list/task resources as you use sync. The Nextcloud server operator controls server-side access and retention.

HTTPS is used by default. Jandal also permits plain HTTP only when you explicitly enable **Use insecure HTTP** for that account. HTTP does not encrypt the connection. Stopping sync or disconnecting the account does not delete copies already stored on the Nextcloud server; remove those copies using that server's controls.

### Actions handed to other apps

When you ask Jandal to use a device capability, it may pass the relevant text or destination to an Android app or service. Email and SMS actions open a composer with the chosen recipient and content; you decide whether to send. Map actions pass a destination or search query to Google Maps or another compatible maps app. A call action can hand a resolved number to Android's call action; media actions may pass a search query to a media app or browser. Those apps and their service providers handle information they receive under their own policies.

### Files and sharing

The chat attachment picker can select an image, audio item, or file. In the current chat flow, Jandal reads the selected item's display name and adds its type and name to the chat text; it does not read the selected file's contents through that chat picker. Other user-selected file flows are separate: a wallpaper image can be copied into app storage, and Lists can import a Jandal list package selected through Android's document picker. When you export or share a list, note, or diagnostic bundle, Jandal passes the chosen content to the Android share sheet and the app you select.

Some features can read contacts or calendar entries when you grant the corresponding Android access and invoke the feature that needs them. This access does not, by itself, mean that Jandal uploads your contacts or calendar.

### Logs and sharing

Jandal writes diagnostic messages while features run. Some logs can contain request parameters or bounded results, which may include text you entered. The About screen offers a user-triggered **Export logs** action that can include recent process-exit information and current-process logs. If you choose an app from Android's share sheet, that app receives the export and handles it under its own policy. Review the contents before sharing. The retained last-uncaught-exception file is installed only in debuggable builds, not release builds.

## Retention and deletion

Conversation history, notes, lists, and other saved local content remain in app storage until you delete them or use a feature-specific cleanup control. Archived conversations are automatically deleted according to the retention option in Chat preferences: 1, 3, 7, 14, or 30 days, or **Never**. The default is 7 days; cleanup runs periodically, so deletion may not happen at the exact cutoff time. Episodic memories are pruned after 30 days and when the local 500-item limit is exceeded. Core memories are limited to 200 items. Deletion controls are feature-specific; there is no single retention period for every category.

Deleting local app data does not delete content already sent to a speech, weather, lookup, maps, messaging, or other provider. For Nextcloud, use the server's controls to remove remote copies. Signing out of Hugging Face removes the locally stored token and account name; it does not delete your Hugging Face account or data held by Hugging Face.

## Review status

This draft must be checked against the exact release candidate, its resolved dependencies and merged manifest, the speech and location services supported for release, provider-specific retention practices, and the publisher's approved privacy contact details before it is published or used for Google Play declarations.
