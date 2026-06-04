# Public API Reference

## Overview

The package root exports the full user-facing API:

```dart
import 'package:companion_device_manager/companion_device_manager.dart';
```

## Android API-level compatibility

- **API 26+ (Android 8.0+)**: `isAvailable`, `associate`, `associateByMacAddress`, `getAssociations`, `disassociate`, `disassociateByMacAddress`.
- **API 31+ (Android 12+)**: wake/presence path used by `registerBackgroundCallback`, `backgroundEvents`, and persisted presence events.
- **API 33+ (Android 13+)**: same wake/presence features, using id-based observation APIs under the hood.

## `CompanionDeviceManager`

The facade class used by application code.

### `Future<bool> isAvailable()`

Checks whether the Android Companion Device Manager API is available on the current device.

### `Future<List<CompanionDeviceAssociation>> getAssociations()`

Returns the associations currently known to the Android system.

### `Future<CompanionDeviceAssociation> associate(CompanionDeviceAssociationRequest request)`

Starts the Android association chooser flow.

The request currently focuses on Bluetooth address-based filters and a display name.

### `Future<CompanionDeviceAssociation> associateByMacAddress(String macAddress)`

Convenience API for MAC-only association requests.

- validates MAC format as `XX:XX:XX:XX:XX:XX`
- normalizes to uppercase before calling Android APIs
- uses both classic Bluetooth and BLE filters for the same address

### `Future<void> disassociate(CompanionDeviceAssociation association)`

Removes an association.

### `Future<void> disassociateByMacAddress(String macAddress)`

Convenience API for disassociation using only the companion MAC address.

The MAC format is the same Android classic form (`XX:XX:XX:XX:XX:XX`).

### `Future<void> registerBackgroundCallback(CompanionDeviceBackgroundCallback callback)`

Registers a Dart callback that will be executed when the companion service wakes the app.

Requires Android 12+ (API 31+) for presence observation to be active.

The callback must be:

- top-level or static
- marked with `@pragma('vm:entry-point')`

Breaking change in `0.2.2`:

- old callback type: `Future<void> Function()`
- new callback type: `Future<void> Function(CompanionDeviceEvent event)`

This gives direct access to event type and MAC address in the callback body.

Internally, callback execution now goes through a dispatcher entrypoint that initializes the headless Flutter engine safely before invoking your callback.

### `Future<void> clearBackgroundCallback()`

Removes the persisted background callback registration.

### `Future<CompanionDeviceEvent?> getLastBackgroundEvent()`

Returns the latest persisted background event.

### `Stream<CompanionDeviceEvent> backgroundEvents`

Emits real-time companion device service events while the Flutter app is running.

Presence events depend on Android 12+ (API 31+) CDM observation APIs.

Official event `type` values emitted by this stream are:

- `device_appeared`
- `device_disappeared`

## `CompanionDeviceAssociationRequest`

Represents the request used to start association.

Fields:

- `displayName`
- `filters`
- `selfManaged`
- `singleDevice`
- `deviceProfile`

## `CompanionDeviceFilter`

Filter object used in association requests.

Currently supported constructors:

- `CompanionDeviceFilter.bluetooth(...)`
- `CompanionDeviceFilter.bluetoothLe(...)`

The first release focuses on address-based filters.

## `CompanionDeviceAssociation`

Represents a known association.

Fields:

- `associationId`
- `macAddress`
- `displayName`
- `deviceProfile`
- `selfManaged`
- `lastTimeConnectedMs`

## `CompanionDeviceEvent`

Represents the last background event.

Fields:

- `type`
- `timestampMs`
- `association`
- `rawPayload`

`type` is a `CompanionDeviceEventType` enum.

`CompanionDeviceEventType` values:

- `deviceAppeared`
- `deviceDisappeared`
- `associationCreated`
- `unknown`

Use `event.type.wireValue` for wire string values (`device_appeared`, `device_disappeared`, ...).

## Example callback

```dart
@pragma('vm:entry-point')
Future<void> companionDeviceWakeCallback(CompanionDeviceEvent event) async {
  debugPrint(
    'Companion device service woke the app. '
    'type=${event.type.wireValue} mac=${event.association?.macAddress}',
  );
}
```

