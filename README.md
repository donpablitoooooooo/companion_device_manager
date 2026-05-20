# companion_device_manager

An Android-only Flutter plugin that wraps the Android
[Companion Device Manager](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
API and provides a simple Dart-facing association flow plus a background wake callback.

## Features

- Android-only companion device association flow
- typed Dart models for requests, associations, and events
- convenience APIs for cross-session association/disassociation by MAC address
- background callback registration for device-appearance wake-ups
- reactive stream for real-time `device_appeared` / `device_disappeared` events
- stored last background event for post-wake inspection
- example app showing the complete flow

## Supported platforms

- Android: supported (see API-level matrix below)
- iOS: not supported
- Desktop: not supported
- Web: not supported

## Android API-level support

This plugin wraps multiple Android CDM APIs that were introduced in different Android releases.

- **Android 8.0+ (API 26+)**: base Companion Device Manager support (`isAvailable`, `associate`, `getAssociations`, `disassociate`).
- **Android 12+ (API 31+)**: device presence observation (`startObservingDevicePresence`) used for background wake and presence events.
- **Android 13+ (API 33+)**: id-based presence observation path (`ObservingDevicePresenceRequest`) used by this plugin when available.

In short:

- if you only need association flow, Android 8.0+ is enough
- if you need wake/background presence events, target Android 12+

## Documentation

Detailed design and implementation notes live in the `doc/` folder:

- `doc/project-architecture.md`
- `doc/public-api.md`
- `doc/example-app.md`

## Installation

Add the dependency to your `pubspec.yaml`:

```yaml
dependencies:
  companion_device_manager:
	path: ../companion_device_manager
```

Or, for a published version:

```yaml
dependencies:
  companion_device_manager: ^0.2.2
```

## Basic usage

```dart
import 'package:companion_device_manager/companion_device_manager.dart';

final manager = CompanionDeviceManager();

@pragma('vm:entry-point')
Future<void> companionDeviceWakeCallback(CompanionDeviceEvent event) async {
  print(
    'Companion device wake callback invoked. '
    'type=${event.type} mac=${event.association?.macAddress}',
  );
}

Future<void> setup() async {
  final available = await manager.isAvailable();
  if (!available) {
	return;
  }

  await manager.registerBackgroundCallback(companionDeviceWakeCallback);

  final association = await manager.associateByMacAddress('00:11:22:33:44:55');

  print('Associated device: ${association.macAddress}');
}

void watchBackgroundEvents() {
  // Note: this stream only emits events while the app is running in foreground.
  // To react to events when the app is backgrounded or killed, use the background callback.
  manager.backgroundEvents.listen((event) {
    print('Companion event: ${event.type.wireValue} at ${event.timestamp}');
  });
}
```

## Breaking change in 0.2.2 (background callback)

`registerBackgroundCallback` now expects an **informative callback**:

- old signature: `Future<void> Function()`
- new signature: `Future<void> Function(CompanionDeviceEvent event)`

This gives the app immediate access to:

- event type as enum (`CompanionDeviceEventType`)
- associated MAC address via `event.association?.macAddress`
- full native payload for advanced logic (`event.rawPayload`)

### Event type enum

`CompanionDeviceEvent.type` is now `CompanionDeviceEventType` instead of raw `String`.

Use `event.type.wireValue` when you need the serialized string (`device_appeared`, `device_disappeared`, ...).

## MAC address format (for new convenience APIs)

`associateByMacAddress` and `disassociateByMacAddress` accept only the Android classic MAC format:

- `XX:XX:XX:XX:XX:XX` (hex pairs separated by `:`)
- examples: `00:11:22:33:44:55`, `AA:BB:CC:DD:EE:FF`

Behavior:

- input is validated strictly
- input is normalized to uppercase before being sent to Android APIs
- the same normalized format is compatible with `CompanionDeviceAssociation.macAddress`

If you need full control (custom display name / advanced filters), keep using `associate(CompanionDeviceAssociationRequest(...))`.

## Background callback requirements

The callback passed to `registerBackgroundCallback` must be:

- a top-level or static function
- annotated with `@pragma('vm:entry-point')`
- start with the standard headless-engine init lines, before touching any
  `MethodChannel`-backed API (this plugin or any other):

From `0.2.2`, the plugin initializes the headless Flutter binding before invoking your callback and passes the event payload directly.

You can still call `WidgetsFlutterBinding.ensureInitialized()` and `ui.DartPluginRegistrant.ensureInitialized()` inside your callback (safe and idempotent), but it is no longer mandatory.

Example:

```dart
import 'dart:ui' as ui;
import 'package:flutter/widgets.dart';

@pragma('vm:entry-point')
Future<void> companionDeviceWakeCallback(CompanionDeviceEvent event) async {
  WidgetsFlutterBinding.ensureInitialized();
  ui.DartPluginRegistrant.ensureInitialized();

  final type = event.type;
  final macAddress = event.association?.macAddress;
  debugPrint('Background event: ${type.wireValue} for $macAddress');
}
```

This is required because Android may need to start a headless Flutter engine when the companion device service wakes the app.

## Example app

The example app shows how to:

- register and clear the background callback
- start an association request
- use MAC-only convenience APIs for cross-session operations
- react to real-time `backgroundEvents` while the app is running
- inspect current associations
- read the last persisted background event

Run it with:

```bash
cd example
flutter run
```

## Android notes

The plugin targets Android devices that support Companion Device Manager.

`CompanionDeviceManager.isAvailable()` returns `true` only on Android 8.0+ (API 26+).

Background presence observation and wake callbacks require Android 12+ (API 31+), because they rely on newer CDM presence APIs.

The Bluetooth permissions required by the CDM scan (`BLUETOOTH_SCAN`,
`BLUETOOTH_CONNECT`, and the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` for API ≤ 30)
are declared in the plugin's `AndroidManifest.xml` and merged into the host app
automatically — you do not need to add them yourself. You may still need to
request the matching runtime permissions in the host app.

Use `CompanionDeviceFilter.bluetoothLe(...)` for BLE peripherals and `CompanionDeviceFilter.bluetooth(...)` for classic Bluetooth devices.

`address` is optional: omit it (or pass `null`) to let the system show every
nearby device of that type in the chooser, then pin it down once you know the
MAC. Combine it with `singleDevice: false` so the chooser presents a list.

### Event delivery

- **When app is running in foreground**: subscribe to `manager.backgroundEvents` stream for real-time `device_appeared` and `device_disappeared` events.
- **When app is backgrounded or killed**: the `CompanionDeviceService` broadcasts events to the background callback (if registered).
- **Persisted events**: the last event payload is always stored on device and can be retrieved via `getLastBackgroundEvent()`.
- **Stream and persistence are synchronized**: both paths use the same native event source, so the UI stays in sync.

## Publishing checklist

Before publishing to pub.dev, verify that:

- the example app works on a real Android device
- the background callback is documented in the host app README
- the version is bumped appropriately
- the Android-only support statement stays visible
- the docs folder remains in sync with the public API

