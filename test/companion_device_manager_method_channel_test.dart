import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:companion_device_manager/companion_device_manager_method_channel.dart';
import 'package:companion_device_manager/companion_device_manager_types.dart';

@pragma('vm:entry-point')
Future<void> _testBackgroundCallback(CompanionDeviceEvent event) async {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  MethodChannelCompanionDeviceManager platform =
      MethodChannelCompanionDeviceManager();
  const MethodChannel channel = MethodChannel('companion_device_manager');
  MethodCall? lastMethodCall;

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastMethodCall = methodCall;
          switch (methodCall.method) {
            case 'isAvailable':
              return true;
            case 'getAssociations':
              return <Map<String, Object?>>[
                <String, Object?>{'macAddress': '00:11:22:33:44:55'},
              ];
            case 'associate':
              return <String, Object?>{'macAddress': '00:11:22:33:44:55'};
            case 'getLastBackgroundEvent':
              return <String, Object?>{
                'type': 'device_appeared',
                'timestampMs': 123,
              };
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('isAvailable', () async {
    expect(await platform.isAvailable(), isTrue);
  });

  test('associate', () async {
    final association = await platform.associate(
      const CompanionDeviceAssociationRequest(
        displayName: 'Example',
        filters: <CompanionDeviceFilter>[
          CompanionDeviceFilter.bluetooth(address: '00:11:22:33:44:55'),
        ],
      ),
    );

    expect(association.macAddress, '00:11:22:33:44:55');
  });

  test(
    'associateByMacAddress sends normalized classic MAC request payload',
    () async {
      await platform.associateByMacAddress('aa:bb:cc:dd:ee:ff');

      expect(lastMethodCall?.method, 'associate');
      final arguments = lastMethodCall?.arguments as Map<Object?, Object?>;
      expect(arguments['displayName'], 'Companion device AA:BB:CC:DD:EE:FF');
      final filters = arguments['filters'] as List<Object?>;
      expect(filters.length, 2);
      expect(
        (filters[0] as Map<Object?, Object?>)['address'],
        'AA:BB:CC:DD:EE:FF',
      );
      expect(
        (filters[1] as Map<Object?, Object?>)['address'],
        'AA:BB:CC:DD:EE:FF',
      );
    },
  );

  test(
    'disassociateByMacAddress sends normalized classic MAC payload',
    () async {
      await platform.disassociateByMacAddress('aa:bb:cc:dd:ee:ff');

      expect(lastMethodCall?.method, 'disassociate');
      final arguments = lastMethodCall?.arguments as Map<Object?, Object?>;
      expect(arguments['macAddress'], 'AA:BB:CC:DD:EE:FF');
    },
  );

  test('associateByMacAddress throws on invalid MAC format', () {
    expect(
      () => platform.associateByMacAddress('aa-bb-cc-dd-ee-ff'),
      throwsArgumentError,
    );
  });

  test('getLastBackgroundEvent', () async {
    final event = await platform.getLastBackgroundEvent();
    expect(event?.type, CompanionDeviceEventType.deviceAppeared);
  });

  test(
    'registerBackgroundCallback sends both callback and dispatcher handles',
    () async {
      await platform.registerBackgroundCallback(_testBackgroundCallback);

      expect(lastMethodCall?.method, 'registerBackgroundCallback');
      final arguments = lastMethodCall?.arguments as Map<Object?, Object?>;
      expect(arguments['callbackHandle'], isA<int>());
      expect(arguments['dispatcherHandle'], isA<int>());
    },
  );
}
