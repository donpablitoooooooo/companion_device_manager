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

  Future<CompanionDeviceAssociation> associateByMacAddress(String macAddress) {
    return CompanionDeviceManagerPlatform.instance.associateByMacAddress(
      macAddress,
    );
  }

  Future<void> disassociate(CompanionDeviceAssociation association) {
    return CompanionDeviceManagerPlatform.instance.disassociate(association);
  }

  Future<void> disassociateByMacAddress(String macAddress) {
    return CompanionDeviceManagerPlatform.instance.disassociateByMacAddress(
      macAddress,
    );
  }

  Future<void> registerBackgroundCallback(
    CompanionDeviceBackgroundCallback callback,
  ) {
    return CompanionDeviceManagerPlatform.instance.registerBackgroundCallback(
      callback,
    );
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
