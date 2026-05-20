import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'companion_device_manager_method_channel.dart';
import 'companion_device_manager_types.dart';

abstract class CompanionDeviceManagerPlatform extends PlatformInterface {
  /// Constructs a CompanionDeviceManagerPlatform.
  CompanionDeviceManagerPlatform() : super(token: _token);

  static final Object _token = Object();

  static CompanionDeviceManagerPlatform _instance =
      MethodChannelCompanionDeviceManager();

  /// The default instance of [CompanionDeviceManagerPlatform] to use.
  ///
  /// Defaults to [MethodChannelCompanionDeviceManager].
  static CompanionDeviceManagerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [CompanionDeviceManagerPlatform] when
  /// they register themselves.
  static set instance(CompanionDeviceManagerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<bool> isAvailable() {
    throw UnimplementedError('isAvailable() has not been implemented.');
  }

  Future<List<CompanionDeviceAssociation>> getAssociations() {
    throw UnimplementedError('getAssociations() has not been implemented.');
  }

  Future<CompanionDeviceAssociation> associate(
    CompanionDeviceAssociationRequest request,
  ) {
    throw UnimplementedError('associate() has not been implemented.');
  }

  Future<CompanionDeviceAssociation> associateByMacAddress(String macAddress) {
    final normalizedMacAddress = _normalizeClassicMacAddress(macAddress);
    return associate(
      CompanionDeviceAssociationRequest(
        displayName: 'Companion device $normalizedMacAddress',
        filters: <CompanionDeviceFilter>[
          CompanionDeviceFilter.bluetooth(address: normalizedMacAddress),
          CompanionDeviceFilter.bluetoothLe(address: normalizedMacAddress),
        ],
      ),
    );
  }

  Future<void> disassociate(CompanionDeviceAssociation association) {
    throw UnimplementedError('disassociate() has not been implemented.');
  }

  Future<void> disassociateByMacAddress(String macAddress) {
    final normalizedMacAddress = _normalizeClassicMacAddress(macAddress);
    return disassociate(
      CompanionDeviceAssociation(macAddress: normalizedMacAddress),
    );
  }

  Future<void> registerBackgroundCallback(
    CompanionDeviceBackgroundCallback callback,
  ) {
    throw UnimplementedError(
      'registerBackgroundCallback() has not been implemented.',
    );
  }

  Future<void> clearBackgroundCallback() {
    throw UnimplementedError(
      'clearBackgroundCallback() has not been implemented.',
    );
  }

  Future<CompanionDeviceEvent?> getLastBackgroundEvent() {
    throw UnimplementedError(
      'getLastBackgroundEvent() has not been implemented.',
    );
  }

  Stream<CompanionDeviceEvent> get backgroundEvents {
    throw UnimplementedError('backgroundEvents has not been implemented.');
  }
}

String _normalizeClassicMacAddress(String macAddress) {
  final trimmed = macAddress.trim();
  final pattern = RegExp(r'^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$');
  if (!pattern.hasMatch(trimmed)) {
    throw ArgumentError.value(
      macAddress,
      'macAddress',
      'Expected MAC address in Android classic format (for example 00:11:22:33:44:55).',
    );
  }
  return trimmed.toUpperCase();
}
