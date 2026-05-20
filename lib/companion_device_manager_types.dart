import 'dart:convert';

import 'package:flutter/foundation.dart';

@immutable
abstract class CompanionDeviceFilter {
  const CompanionDeviceFilter._();

  const factory CompanionDeviceFilter.bluetooth({
    String? address,
    String? namePattern,
  }) = _BluetoothCompanionDeviceFilter;

  const factory CompanionDeviceFilter.bluetoothLe({
    String? address,
    String? namePattern,
  }) = _BluetoothLeCompanionDeviceFilter;

  String get type;
  String? get address;
  String? get namePattern;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'type': type,
      'address': address,
      'namePattern': namePattern,
    };
  }

  static CompanionDeviceFilter fromMap(Map<Object?, Object?> map) {
    final type = map['type'] as String?;
    if (type == null) {
      throw ArgumentError('Missing filter type.');
    }

    final address = map['address'] as String?;
    final namePattern = map['namePattern'] as String?;

    switch (type) {
      case 'bluetooth':
        return CompanionDeviceFilter.bluetooth(
          address: address,
          namePattern: namePattern,
        );
      case 'bluetoothLe':
        return CompanionDeviceFilter.bluetoothLe(
          address: address,
          namePattern: namePattern,
        );
      default:
        return _SerializedCompanionDeviceFilter(
          type: type,
          address: address,
          namePattern: namePattern,
        );
    }
  }
}

class _BluetoothCompanionDeviceFilter extends CompanionDeviceFilter {
  const _BluetoothCompanionDeviceFilter({
    this.address,
    this.namePattern,
  }) : super._();

  @override
  final String? address;

  @override
  final String? namePattern;

  @override
  String get type => 'bluetooth';
}

class _BluetoothLeCompanionDeviceFilter extends CompanionDeviceFilter {
  const _BluetoothLeCompanionDeviceFilter({
    this.address,
    this.namePattern,
  }) : super._();

  @override
  final String? address;

  @override
  final String? namePattern;

  @override
  String get type => 'bluetoothLe';
}

class _SerializedCompanionDeviceFilter extends CompanionDeviceFilter {
  const _SerializedCompanionDeviceFilter({
    required this.type,
    this.address,
    this.namePattern,
  }) : super._();

  @override
  final String type;

  @override
  final String? address;

  @override
  final String? namePattern;
}

@immutable
class CompanionDeviceAssociationRequest {
  const CompanionDeviceAssociationRequest({
    required this.displayName,
    this.filters = const <CompanionDeviceFilter>[],
    this.selfManaged = false,
    this.singleDevice = true,
    this.deviceProfile,
  });

  final String displayName;
  final List<CompanionDeviceFilter> filters;
  final bool selfManaged;
  final bool singleDevice;
  final String? deviceProfile;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'displayName': displayName,
      'filters': filters.map((CompanionDeviceFilter filter) => filter.toMap()).toList(),
      'selfManaged': selfManaged,
      'singleDevice': singleDevice,
      'deviceProfile': deviceProfile,
    };
  }

  static CompanionDeviceAssociationRequest fromMap(Map<Object?, Object?> map) {
    final displayName = map['displayName'] as String?;
    if (displayName == null || displayName.isEmpty) {
      throw ArgumentError('displayName is required.');
    }

    final rawFilters = (map['filters'] as List<Object?>? ?? const <Object?>[]);
    final filters = rawFilters
        .whereType<Map<Object?, Object?>>()
        .map(CompanionDeviceFilter.fromMap)
        .toList();

    return CompanionDeviceAssociationRequest(
      displayName: displayName,
      filters: filters,
      selfManaged: map['selfManaged'] as bool? ?? false,
      singleDevice: map['singleDevice'] as bool? ?? true,
      deviceProfile: map['deviceProfile'] as String?,
    );
  }
}

@immutable
class CompanionDeviceAssociation {
  const CompanionDeviceAssociation({
    required this.macAddress,
    this.associationId,
    this.displayName,
    this.deviceProfile,
    this.selfManaged = false,
    this.lastTimeConnectedMs,
  });

  final int? associationId;
  final String? macAddress;
  final String? displayName;
  final String? deviceProfile;
  final bool selfManaged;
  final int? lastTimeConnectedMs;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'associationId': associationId,
      'macAddress': macAddress,
      'displayName': displayName,
      'deviceProfile': deviceProfile,
      'selfManaged': selfManaged,
      'lastTimeConnectedMs': lastTimeConnectedMs,
    };
  }

  static CompanionDeviceAssociation fromMap(Map<Object?, Object?> map) {
    return CompanionDeviceAssociation(
      associationId: map['associationId'] as int?,
      macAddress: map['macAddress'] as String?,
      displayName: map['displayName'] as String?,
      deviceProfile: map['deviceProfile'] as String?,
      selfManaged: map['selfManaged'] as bool? ?? false,
      lastTimeConnectedMs: map['lastTimeConnectedMs'] as int?,
    );
  }
}

@immutable
class CompanionDeviceEvent {
  const CompanionDeviceEvent({
    required this.type,
    required this.timestampMs,
    this.association,
    this.rawPayload,
  });

  final String type;
  final int timestampMs;
  final CompanionDeviceAssociation? association;
  final Map<String, Object?>? rawPayload;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'type': type,
      'timestampMs': timestampMs,
      'association': association?.toMap(),
      'rawPayload': rawPayload,
    };
  }

  static CompanionDeviceEvent fromMap(Map<Object?, Object?> map) {
    final associationMap = map['association'];
    final rawPayload = map['rawPayload'];

    return CompanionDeviceEvent(
      type: map['type'] as String? ?? 'unknown',
      timestampMs: map['timestampMs'] as int? ?? DateTime.now().millisecondsSinceEpoch,
      association: associationMap is Map<Object?, Object?>
          ? CompanionDeviceAssociation.fromMap(associationMap)
          : null,
      rawPayload: rawPayload is Map<Object?, Object?>
          ? rawPayload.map<String, Object?>((Object? key, Object? value) {
              return MapEntry<String, Object?>(key.toString(), value);
            })
          : null,
    );
  }

  String toJson() => jsonEncode(toMap());
}

typedef CompanionDeviceBackgroundCallback = Future<void> Function();

