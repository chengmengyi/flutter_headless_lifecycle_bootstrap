## 0.0.4

* Add an opt-in first-UI-launch gate that blocks Service-created FlutterEngines until the user has opened the app once.
* Persist the first real UI launch across process death and device restart, while resetting naturally on uninstall or app-data clear.

## 0.0.3

* Add an opt-in manifest switch to destroy the shared Engine when the UI Activity is destroyed outside a configuration change.
* Clear all native Engine coordination state before destruction so the next UI or headless launch starts cleanly.
* Recreate a missing Engine from service `onCreate`, `onBind`, or `onStartCommand`.

## 0.0.2

* Expose the initial UI-request state for headless-aware SDK initialization.
* Document the safe Adjust headless lifecycle pulse and UI handoff.

## 0.0.1

* Add an exported Android lifecycle discovery service.
* Add one process-wide, long-lived FlutterEngine shared by service and UI.
* Add reusable FlutterActivity and FlutterFragmentActivity base classes.
* Add Dart background/UI bootstrap coordination and diagnostics.
* Add manifest merging, consumer R8 rules, and full Chinese integration docs.
