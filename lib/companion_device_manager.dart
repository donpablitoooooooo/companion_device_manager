export 'companion_device_manager_types.dart';

import 'companion_device_manager_platform_interface.dart';
import 'companion_device_manager_types.dart';

class CompanionDeviceManager {
  Future<bool> isAvailable() {
    return CompanionDeviceManagerPlatform.instance.isAvailable();
  }

  Future<List<CompanionDeviceAssociation>> getAssociations() {
    return CompanionDeviceManagerPlatform.instance.getAssociations();
  }

  Future<CompanionDeviceAssociation> associate(
    CompanionDeviceAssociationRequest request,
  ) {
    return CompanionDeviceManagerPlatform.instance.associate(request);
  }

  Future<void> disassociate(CompanionDeviceAssociation association) {
    return CompanionDeviceManagerPlatform.instance.disassociate(association);
  }

  /// Registers a top-level / static Dart callback that is invoked in a fresh
  /// headless FlutterEngine when the system reports a companion-device
  /// presence change. The callback must be annotated with
  /// `@pragma('vm:entry-point')`.
  ///
  /// Because it runs in a headless engine, the callback body MUST start with
  ///
  /// ```dart
  /// WidgetsFlutterBinding.ensureInitialized();
  /// DartPluginRegistrant.ensureInitialized();
  /// ```
  ///
  /// before invoking any `MethodChannel`-backed API, otherwise
  /// `ServicesBinding.instance` is not available and the call throws
  /// "Binding has not yet been initialized".
  Future<void> registerBackgroundCallback(
    CompanionDeviceBackgroundCallback callback,
  ) {
    return CompanionDeviceManagerPlatform.instance.registerBackgroundCallback(callback);
  }

  Future<void> clearBackgroundCallback() {
    return CompanionDeviceManagerPlatform.instance.clearBackgroundCallback();
  }

  Future<CompanionDeviceEvent?> getLastBackgroundEvent() {
    return CompanionDeviceManagerPlatform.instance.getLastBackgroundEvent();
  }

  Stream<CompanionDeviceEvent> get backgroundEvents {
    return CompanionDeviceManagerPlatform.instance.backgroundEvents;
  }
}


