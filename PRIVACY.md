# Privacy

Keel collects nothing. It has no analytics, crash reporting, advertising or tracking code, and it
doesn't use Google Play services.

The app only connects to the TrueNAS servers you add. It sends them the requests needed to show
your data and perform the actions you confirm, using the API key you created. Nothing is sent
anywhere else, and the developer never sees your server address, your key or your data.

On the phone, the derived login keys for each server are stored encrypted in the Android Keystore
and can only be decrypted right after you unlock with your fingerprint or device PIN. If you turn
on alert notifications, the derived keys for the separate alert-only API key are stored encrypted
without that unlock requirement so the background check can run. The app's data is excluded from
Android backups. Uninstalling the app, or choosing "Forget this server", deletes what it stored.

Questions about this policy can be raised as an issue at https://github.com/ah410/keel/issues.
