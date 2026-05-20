# Companion Device Manager Plugin Architecture

## Goal

This plugin provides a simple Flutter-facing API for Android's [`CompanionDeviceManager`](https://developer.android.com/reference/android/companion/CompanionDeviceManager) so an app can:

1. Start a system-managed association flow.
2. Keep track of the current associations.
3. Register a Dart callback that is invoked when the companion device service wakes the app.
4. Read the last background event after the app has been started again.

From `0.2.2`, the background callback is informative: Dart receives a typed `CompanionDeviceEvent` payload (including enum event type and association MAC address) instead of a no-argument callback.

The plugin is Android-only. On other platforms, the package is intentionally unsupported.

### Android compatibility at a glance

- **API 26+ (Android 8.0+)**: core association/disassociation APIs are available.
- **API 31+ (Android 12+)**: background wake and presence observation become available.
- **API 33+ (Android 13+)**: plugin uses id-based observation (`ObservingDevicePresenceRequest`) where supported.

## Product direction

The plugin is designed for apps that pair with a companion device such as:

- Bluetooth accessories
- Wearables
- Embedded controllers
- Other Android-managed companion devices

The key value of Companion Device Manager is that Android can manage the association lifecycle and can wake the app when the companion device appears or disappears, even if the app process is not currently running.

## Public API shape

The Dart API is intentionally small:

- `CompanionDeviceManager.isAvailable()`
- `CompanionDeviceManager.getAssociations()`
- `CompanionDeviceManager.associate(...)`
- `CompanionDeviceManager.associateByMacAddress(...)`
- `CompanionDeviceManager.disassociate(...)`
- `CompanionDeviceManager.disassociateByMacAddress(...)`
- `CompanionDeviceManager.registerBackgroundCallback(...)`
- `CompanionDeviceManager.clearBackgroundCallback()`
- `CompanionDeviceManager.getLastBackgroundEvent()`
- `CompanionDeviceManager.backgroundEvents`

### Data objects

The plugin exposes typed data classes instead of raw maps:

- `CompanionDeviceAssociationRequest`
- `CompanionDeviceAssociation`
- `CompanionDeviceEvent`
- `CompanionDeviceFilter`

This keeps the app code readable and pub.dev-friendly.

## Android implementation strategy

### Method channel + event channel

The plugin uses a single method channel named `companion_device_manager`.
For reactive delivery, it also exposes an event channel named `companion_device_manager/events`.

The native side handles:

- capability checks
- association queries
- association request launch
- disassociation
- callback registration
- last-event persistence

The event channel emits `device_appeared` and `device_disappeared` payloads to Flutter while the app is running, enabling reactive UI updates without polling.

### Activity-aware association flow

Association requires a foreground Android `Activity` because the system chooser must be launched from UI context.

The plugin therefore implements `ActivityAware` and launches the system chooser via the attached activity.

### Background wake flow

For the wake-on-device event, the plugin uses an Android companion-device service:

- the Android service is declared in the plugin manifest
- the service stores the latest event payload in shared preferences
- the service reads the registered Dart callback handle
- the service reads a dispatcher callback handle
- the service starts a headless Flutter engine and executes the dispatcher
- the dispatcher forwards the typed event payload to the registered app callback

This means the host app only has to provide a top-level or static Dart callback.

### Event persistence

The native side stores the last event payload so the app can inspect the last wake event after relaunch.

This is useful because a dead app cannot directly update visible UI at the moment it is woken.

## Android lifecycle considerations

The plugin deliberately separates three concerns:

1. **Association creation** — requires an activity.
2. **Background wake callback** — handled by the companion service.
3. **Post-wake inspection** — handled by `getLastBackgroundEvent()`.

This makes the plugin usable in both foreground and background scenarios.

## Limitations of this first release

The first release focuses on the most important path:

- Bluetooth MAC-address-based filters
- Android companion association creation
- background callback wake-up

Unsupported or intentionally deferred areas:

- complex device filter builders for every Android profile type
- iOS support
- automatic permission orchestration for every Bluetooth use case
- custom event stream multiplexing into Dart isolates

These can be added later without changing the basic architecture.

## Host app requirements

The host app must provide:

- an Android device running API 26+ for association APIs
- an Android device running API 31+ for wake/presence behavior
- a top-level or static Dart callback for background wake-up
- any Bluetooth/runtime permissions required by the target device type
- a real companion device to test against

## Publishing notes

Before publishing to pub.dev, verify that:

- the README explains Android-only support clearly
- the example app demonstrates the intended flow
- the API names are stable and documented
- unsupported platforms fail gracefully
- the package version is bumped correctly

