# Example App Walkthrough

## Purpose

The example app shows how to use the plugin from Flutter and demonstrates the two core workflows:

1. launching the association chooser
2. registering a background callback that is woken by the Android companion service
3. consuming typed callback payload data (`CompanionDeviceEventType` + MAC address)

## What the example demonstrates

The example UI contains:

- a runtime availability indicator
- buttons to register or clear the background callback
- a Bluetooth MAC address field
- a button to start association
- a reactive listener for `backgroundEvents` (`device_appeared` / `device_disappeared`)
- a list of currently known associations
- the last persisted background event

## How to use the example

1. Run the example on a real Android device.
2. Register the background callback.
3. Optionally enter a target Bluetooth address — leave the field empty to scan
   all nearby BLE devices and pick one from the system chooser.
4. Start the association flow.
5. Complete the system chooser.
6. Trigger the companion device event on the Android side.
7. Observe the event being logged and rendered live while the app is open.
8. Re-open the app if needed and inspect the last persisted event.

## Why the example is intentionally simple

The example is designed for pub.dev and documentation clarity.
It does not try to hide the Android system chooser or the pairing requirements.

That makes the companion-device lifecycle easier to understand for plugin users.

## Testing guidance

When validating the example, remember that CDM flows depend on:

- Android version
- device profile
- Bluetooth permissions
- the actual companion hardware
- user consent

So the example should be treated as a real-device integration sample, not as a unit-test-only flow.

