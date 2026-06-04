import 'package:flutter_test/flutter_test.dart';
import 'package:companion_device_manager/companion_device_manager.dart';
import 'package:companion_device_manager/companion_device_manager_platform_interface.dart';
import 'package:companion_device_manager/companion_device_manager_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockCompanionDeviceManagerPlatform
    with MockPlatformInterfaceMixin
    implements CompanionDeviceManagerPlatform {
  String? lastDisassociatedMacAddress;

  String _normalizeMac(String macAddress) {
    final trimmed = macAddress.trim();
    final pattern = RegExp(r'^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$');
    if (!pattern.hasMatch(trimmed)) {
      throw ArgumentError.value(
        macAddress,
        'macAddress',
        'Expected classic MAC format XX:XX:XX:XX:XX:XX.',
      );
    }
    return trimmed.toUpperCase();
  }

  @override
  Future<bool> isAvailable() => Future.value(true);

  @override
  Future<List<CompanionDeviceAssociation>> getAssociations() =>
      Future.value(<CompanionDeviceAssociation>[]);

  @override
  Future<CompanionDeviceAssociation> associate(
    CompanionDeviceAssociationRequest request,
  ) => Future.value(
    CompanionDeviceAssociation(macAddress: request.filters.first.address),
  );

  @override
  Future<CompanionDeviceAssociation> associateByMacAddress(String macAddress) =>
      Future.value(
        CompanionDeviceAssociation(macAddress: _normalizeMac(macAddress)),
      );

  @override
  Future<void> disassociate(CompanionDeviceAssociation association) {
    lastDisassociatedMacAddress = association.macAddress;
    return Future.value();
  }

  @override
  Future<void> disassociateByMacAddress(String macAddress) {
    lastDisassociatedMacAddress = _normalizeMac(macAddress);
    return Future.value();
  }

  @override
  Future<void> registerBackgroundCallback(
    CompanionDeviceBackgroundCallback callback,
  ) => Future.value();

  @override
  Future<void> clearBackgroundCallback() => Future.value();

  @override
  Future<CompanionDeviceEvent?> getLastBackgroundEvent() => Future.value(null);

  @override
  Stream<CompanionDeviceEvent> get backgroundEvents =>
      Stream<CompanionDeviceEvent>.value(
        const CompanionDeviceEvent(
          type: CompanionDeviceEventType.deviceAppeared,
          timestampMs: 1,
        ),
      );
}

void main() {
  final CompanionDeviceManagerPlatform initialPlatform =
      CompanionDeviceManagerPlatform.instance;

  test('$MethodChannelCompanionDeviceManager is the default instance', () {
    expect(
      initialPlatform,
      isInstanceOf<MethodChannelCompanionDeviceManager>(),
    );
  });

  test('isAvailable delegates to the platform interface', () async {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    MockCompanionDeviceManagerPlatform fakePlatform =
        MockCompanionDeviceManagerPlatform();
    CompanionDeviceManagerPlatform.instance = fakePlatform;

    expect(await companionDeviceManagerPlugin.isAvailable(), isTrue);
  });

  test('associate delegates to the platform interface', () async {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    MockCompanionDeviceManagerPlatform fakePlatform =
        MockCompanionDeviceManagerPlatform();
    CompanionDeviceManagerPlatform.instance = fakePlatform;

    final association = await companionDeviceManagerPlugin.associate(
      CompanionDeviceAssociationRequest(
        displayName: 'Example',
        filters: <CompanionDeviceFilter>[
          CompanionDeviceFilter.bluetooth(address: '00:11:22:33:44:55'),
        ],
      ),
    );

    expect(association.macAddress, '00:11:22:33:44:55');
  });

  test('associateByMacAddress normalizes MAC format and delegates', () async {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    final fakePlatform = MockCompanionDeviceManagerPlatform();
    CompanionDeviceManagerPlatform.instance = fakePlatform;

    final association = await companionDeviceManagerPlugin
        .associateByMacAddress('00:11:22:33:44:55');

    expect(association.macAddress, '00:11:22:33:44:55');
  });

  test('disassociateByMacAddress delegates using normalized MAC', () async {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    final fakePlatform = MockCompanionDeviceManagerPlatform();
    CompanionDeviceManagerPlatform.instance = fakePlatform;

    await companionDeviceManagerPlugin.disassociateByMacAddress(
      'aa:bb:cc:dd:ee:ff',
    );

    expect(fakePlatform.lastDisassociatedMacAddress, 'AA:BB:CC:DD:EE:FF');
  });

  test('associateByMacAddress rejects invalid format', () {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    CompanionDeviceManagerPlatform.instance =
        MockCompanionDeviceManagerPlatform();

    expect(
      () => companionDeviceManagerPlugin.associateByMacAddress('001122334455'),
      throwsArgumentError,
    );
  });

  test('backgroundEvents delegates to the platform interface', () async {
    final CompanionDeviceManager companionDeviceManagerPlugin =
        CompanionDeviceManager();
    MockCompanionDeviceManagerPlatform fakePlatform =
        MockCompanionDeviceManagerPlatform();
    CompanionDeviceManagerPlatform.instance = fakePlatform;

    final event = await companionDeviceManagerPlugin.backgroundEvents.first;

    expect(event.type, CompanionDeviceEventType.deviceAppeared);
  });
}
