# OTA updates

Solar checks **Settings → App Version** and may nudge once per day on the home screen when a newer build is published.

## Install path (Y1 system app)

On devices where Solar is installed under `/system/app/com.solar.launcher.apk`, updates copy the new APK via `su` and **reboot** — there is no plain `pm install` path for the system slot.

You’ll see a blocking **Installing update… device will reboot** message before restart.

## Catalog

Releases are listed at [solar-update](https://thesolarproject.github.io/solar-update/updates.xml) (stable and nightly channels).

## After reboot

Allow a minute for the first boot after an OTA; your queue and settings are kept in app storage.
