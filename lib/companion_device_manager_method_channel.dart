import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'companion_device_manager_platform_interface.dart';
import 'companion_device_manager_types.dart';

const MethodChannel _backgroundCallbackChannel = MethodChannel(
  'companion_device_manager/background',
);

@pragma('vm:entry-point')
Future<void> _backgroundCallbackDispatcher() async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  _backgroundCallbackChannel.setMethodCallHandler((MethodCall call) async {
    if (call.method != 'dispatchBackgroundEvent') {
      throw MissingPluginException('Unknown background callback method: ${call.method}');
    }

    final arguments = call.arguments;
    if (arguments is! Map<Object?, Object?>) {
      throw const FormatException('Invalid background callback payload type.');
    }

    final rawEvent = arguments['event'];
    final rawCallbackHandle = arguments['callbackHandle'];

    if (rawEvent is! Map<Object?, Object?> || rawCallbackHandle is! int) {
      throw const FormatException('Invalid background callback payload fields.');
    }

    final callback = PluginUtilities.getCallbackFromHandle(
      CallbackHandle.fromRawHandle(rawCallbackHandle),
    );
    if (callback is! CompanionDeviceBackgroundCallback) {
      throw ArgumentError(
        'Unable to resolve a valid background callback. Re-register the callback with registerBackgroundCallback.',
      );
    }

    await callback(CompanionDeviceEvent.fromMap(rawEvent));
  });

  await _backgroundCallbackChannel.invokeMethod<void>('backgroundDispatcherInitialized');
}

/// An implementation of [CompanionDeviceManagerPlatform] that uses method channels.
class MethodChannelCompanionDeviceManager extends CompanionDeviceManagerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('companion_device_manager');

  @visibleForTesting
  final eventChannel = const EventChannel('companion_device_manager/events');

  Stream<CompanionDeviceEvent>? _backgroundEvents;

  @override
  Future<bool> isAvailable() async {
    return (await methodChannel.invokeMethod<bool>('isAvailable')) ?? false;
  }

  @override
  Future<List<CompanionDeviceAssociation>> getAssociations() async {
    final rawAssociations = await methodChannel.invokeMethod<List<Object?>>('getAssociations');
    return rawAssociations
            ?.whereType<Map<Object?, Object?>>()
            .map(CompanionDeviceAssociation.fromMap)
            .toList() ??
        <CompanionDeviceAssociation>[];
  }

  @override
  Future<CompanionDeviceAssociation> associate(
    CompanionDeviceAssociationRequest request,
  ) async {
    final result = await methodChannel.invokeMethod<Map<Object?, Object?>>(
      'associate',
      request.toMap(),
    );
    if (result == null) {
      throw PlatformException(
        code: 'association_failed',
        message: 'The platform returned no association result.',
      );
    }
    return CompanionDeviceAssociation.fromMap(result);
  }

  @override
  Future<void> disassociate(CompanionDeviceAssociation association) async {
    await methodChannel.invokeMethod<void>('disassociate', association.toMap());
  }

  @override
  Future<void> registerBackgroundCallback(
    CompanionDeviceBackgroundCallback callback,
  ) async {
    final callbackHandle = PluginUtilities.getCallbackHandle(callback);
    final dispatcherHandle = PluginUtilities.getCallbackHandle(
      _backgroundCallbackDispatcher,
    );

    if (callbackHandle == null || dispatcherHandle == null) {
      throw ArgumentError(
        'The callback must be a top-level or static function annotated with @pragma(\'vm:entry-point\').',
      );
    }

    await methodChannel.invokeMethod<void>(
      'registerBackgroundCallback',
      <String, Object?>{
        'callbackHandle': callbackHandle.toRawHandle(),
        'dispatcherHandle': dispatcherHandle.toRawHandle(),
      },
    );
  }

  @override
  Future<void> clearBackgroundCallback() async {
    await methodChannel.invokeMethod<void>('clearBackgroundCallback');
  }

  @override
  Future<CompanionDeviceEvent?> getLastBackgroundEvent() async {
    final result = await methodChannel.invokeMethod<Map<Object?, Object?>>('getLastBackgroundEvent');
    if (result == null) {
      return null;
    }
    return CompanionDeviceEvent.fromMap(result);
  }

  @override
  Stream<CompanionDeviceEvent> get backgroundEvents {
    return _backgroundEvents ??= eventChannel.receiveBroadcastStream().map((dynamic payload) {
      if (payload is! Map<Object?, Object?>) {
        throw const FormatException('Invalid background event payload type.');
      }
      return CompanionDeviceEvent.fromMap(payload);
    }).asBroadcastStream();
  }
}
