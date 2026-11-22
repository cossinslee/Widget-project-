# Print Timer: a Bambu Lab print widget for Android

A home-screen widget that shows how long is left on your Bambu Lab print. It shows:

- the printer name and state (Printing, Paused, Done, and so on)
- the name of the print
- a live countdown of the time left
- a progress bar, the percentage done, the finish time and the current layer

## How it works

Bambu Handy doesn't share its data with other apps. Instead, this app signs in to the
**same Bambu Lab cloud account** you use in Handy and reads the same live status feed that
Handy reads:

1. It signs in through the Bambu Lab cloud API. If Bambu asks for a code, it supports the
   e-mailed verification code and two-factor authentication.
2. It gets your list of printers from the cloud.
3. It briefly connects to Bambu's cloud MQTT broker, asks each printer for a full status
   report, reads the time left and the progress, then disconnects.

The widget counts down by itself between checks. In the background it checks the cloud
**every 5 minutes while a print is running** and every 15 minutes otherwise (15 minutes is
the shortest interval Android allows for regular background work). Tap the ↻ icon to check
straight away. Tapping anywhere else on the widget opens Bambu Handy.

The app never stores your password. It keeps only the sign-in token, in its own private storage.

## Install

1. Get the APK: download `print-timer-apk` from the latest run on this repo's
   **Actions → Build APK** page, or build it yourself (see below).
2. Copy it to your phone and open it. Android will ask you to allow installs from that
   source (your browser or file manager); allow it.
3. Open **Print Timer**, sign in with your Bambu account, and pick a printer (or leave it on
   *Automatic*, which shows whichever printer is printing).
4. Tap **Add widget to home screen**, or long-press your home screen → Widgets → *Print Timer*.

If Android stops the widget from updating, set Print Timer's battery setting to
*Unrestricted* (Settings → Apps → Print Timer → Battery).

## Build it yourself

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- This uses Bambu Lab's unofficial cloud API, the one Bambu Studio, Handy and community
  tools such as the Home Assistant integration use. Bambu may change it without notice.
- The widget only reads status. It never sends commands to your printer.
- Your sign-in token lasts about three months. When it expires, the widget says so; open the
  app and sign in again.
