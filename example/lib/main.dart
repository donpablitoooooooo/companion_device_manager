import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:ui';

import 'package:companion_device_manager/companion_device_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const CompanionDeviceManagerExampleApp());
}

@pragma('vm:entry-point')
Future<void> companionDeviceWakeCallback() async {
  // The system spins up a headless FlutterEngine to run this callback, so the
  // Flutter bindings are NOT initialized yet. Initialize them before touching
  // any MethodChannel-backed API (otherwise ServicesBinding.instance throws).
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  final timestamp = DateTime.now();
  final isoTime = timestamp.toIso8601String();
  debugPrint('[CDM Background Callback] Invoked at $isoTime (${timestamp.millisecondsSinceEpoch}ms)');

  final manager = CompanionDeviceManager();
  final lastEvent = await manager.getLastBackgroundEvent();
  if (lastEvent != null) {
    debugPrint('[CDM Background Callback] Last background event: ${lastEvent.type} at ${lastEvent.timestamp}');
  }
}

class CompanionDeviceManagerExampleApp extends StatelessWidget {
  const CompanionDeviceManagerExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Companion Device Manager Example',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.blue),
      home: const _InitializeCallbackWrapper(child: CompanionDeviceManagerHomePage()),
    );
  }
}

class _InitializeCallbackWrapper extends StatefulWidget {
  const _InitializeCallbackWrapper({required this.child});

  final Widget child;

  @override
  State<_InitializeCallbackWrapper> createState() =>
      _InitializeCallbackWrapperState();
}

class _InitializeCallbackWrapperState extends State<_InitializeCallbackWrapper> {
  @override
  void initState() {
    super.initState();
    _ensureCallbackRegistered();
  }

  Future<void> _ensureCallbackRegistered() async {
    final manager = CompanionDeviceManager();
    try {
      await manager.registerBackgroundCallback(companionDeviceWakeCallback);
      debugPrint('[CDM] Background callback auto-registered on app start.');
    } on ArgumentError {
      // Callback already registered or not a valid function
      debugPrint('[CDM] Background callback already registered.');
    } catch (error) {
      debugPrint('[CDM] Error auto-registering callback: $error');
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }
}

class CompanionDeviceManagerHomePage extends StatefulWidget {
  const CompanionDeviceManagerHomePage({super.key});

  @override
  State<CompanionDeviceManagerHomePage> createState() =>
      _CompanionDeviceManagerHomePageState();
}

class _CompanionDeviceManagerHomePageState extends State<CompanionDeviceManagerHomePage> {
  final CompanionDeviceManager _manager = CompanionDeviceManager();
  final TextEditingController _addressController = TextEditingController();
  StreamSubscription<CompanionDeviceEvent>? _eventSubscription;
  String? _lastEventSignature;

  bool _available = false;
  bool _callbackRegistered = false;
  bool _busy = false;
  String _status = 'Ready';
  String? _lastEventJson;
  List<CompanionDeviceAssociation> _associations = <CompanionDeviceAssociation>[];

   @override
   void initState() {
     super.initState();
     _refreshAvailability();
     _refreshAssociations();
     _refreshLastEvent();
     _checkCallbackRegistration();
     _subscribeToBackgroundEvents();
   }

   Future<void> _checkCallbackRegistration() async {
     try {
       final lastEvent = await _manager.getLastBackgroundEvent();
       if (!mounted) return;
       setState(() => _callbackRegistered = lastEvent != null);
     } catch (_) {
       // If we can't get last event, assume callback might not be registered
     }
   }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    _addressController.dispose();
    super.dispose();
  }

  void _subscribeToBackgroundEvents() {
    _eventSubscription?.cancel();
    _eventSubscription = _manager.backgroundEvents.listen(
      (event) {
        _applyEventUpdate(
          event,
          logIfChanged: true,
          updateStatusOnChange: true,
          logPrefix: '[CDM] New background event from stream:',
        );
      },
      onError: (Object error) {
        if (!mounted) return;
        setState(() => _status = 'Background event stream error: $error');
      },
    );
  }

  Future<void> _refreshAvailability() async {
    setState(() => _busy = true);
    try {
      final available = await _manager.isAvailable();
      if (!mounted) return;
      setState(() {
        _available = available;
        _status = available
            ? 'Companion Device Manager is available on this Android device.'
            : 'Companion Device Manager is not available on this Android device.';
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Availability check failed: ${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshAssociations() async {
    setState(() => _busy = true);
    try {
      final associations = await _manager.getAssociations();
      if (!mounted) return;
      setState(() => _associations = associations);
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Unable to load associations: ${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshLastEvent({
    bool logIfChanged = false,
    bool updateStatusOnChange = false,
  }) async {
    try {
      final event = await _manager.getLastBackgroundEvent();
      _applyEventUpdate(
        event,
        logIfChanged: logIfChanged,
        updateStatusOnChange: updateStatusOnChange,
        logPrefix: '[CDM] New background event from persisted state:',
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Unable to load the last event: ${error.message}');
    }
  }

  void _applyEventUpdate(
    CompanionDeviceEvent? event, {
    required bool logIfChanged,
    required bool updateStatusOnChange,
    required String logPrefix,
  }) {
    if (!mounted) return;

    final prettyJson = event == null
        ? null
        : const JsonEncoder.withIndent('  ').convert(event.toMap());
    final nextSignature = event?.toJson();
    final changed = nextSignature != _lastEventSignature;

    if (!changed && _lastEventJson == prettyJson) {
      return;
    }

    if (changed) {
      _lastEventSignature = nextSignature;
      if (logIfChanged) {
        developer.log(
          event == null ? '[CDM] Last background event cleared (null).' : '$logPrefix $prettyJson',
          name: 'CDMExample',
        );
      }
    }

    setState(() {
      _lastEventJson = prettyJson;
      if (updateStatusOnChange && changed && event != null) {
        _status = 'New background event received: ${event.type}';
      }
    });
  }

  Future<void> _logLastEvent() async {
    try {
      final event = await _manager.getLastBackgroundEvent();
      _lastEventSignature = event?.toJson();
      final message = event == null
          ? '[CDM] No background event captured yet.'
          : '[CDM] Last background event: ${const JsonEncoder.withIndent('  ').convert(event.toMap())}';
      developer.log(message, name: 'CDMExample');
      if (!mounted) return;
      setState(() {
        _lastEventJson = event == null ? null : const JsonEncoder.withIndent('  ').convert(event.toMap());
        _status = event == null ? 'No background event captured yet.' : 'Last background event logged to console.';
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Unable to log the last event: ${error.message}');
    }
  }



  Future<void> _associate() async {
    final address = _addressController.text.trim();
    final hasAddress = address.isNotEmpty;

    setState(() {
      _busy = true;
      _status = hasAddress
          ? 'Launching the Android companion device chooser for $address...'
          : 'Scanning all nearby BLE devices. Pick yours from the system dialog...';
    });

    try {
      final association = await _manager.associate(
        CompanionDeviceAssociationRequest(
          displayName: 'Companion Device Manager Example',
          filters: <CompanionDeviceFilter>[
            CompanionDeviceFilter.bluetoothLe(
              address: hasAddress ? address : null,
            ),
          ],
          singleDevice: hasAddress,
        ),
      );

      if (!mounted) return;
      setState(() {
        _status = 'Association completed for ${association.macAddress ?? 'unknown device'}.';
      });
      await _refreshAssociations();
      await _refreshLastEvent();
      _showSnackBar('Association completed.');
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Association failed: ${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _disassociate(CompanionDeviceAssociation association) async {
    setState(() => _busy = true);
    try {
      await _manager.disassociate(association);
      if (!mounted) return;
      setState(() => _status = 'Association removed for ${association.macAddress ?? 'unknown device'}');
      await _refreshAssociations();
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _status = 'Unable to remove association: ${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Companion Device Manager'),
        actions: [
          IconButton(
            onPressed: _busy
                ? null
                : () async {
                    await _refreshAvailability();
                    await _refreshAssociations();
                    await _refreshLastEvent();
                  },
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: <Widget>[
          _InfoCard(
            title: 'Status',
            child: Text(_status),
          ),
          const SizedBox(height: 12),
          _InfoCard(
            title: 'Runtime availability',
            child: Text(_available ? 'Available' : 'Not available'),
          ),
          const SizedBox(height: 12),
           _InfoCard(
             title: 'Background callback',
             child: Column(
               crossAxisAlignment: CrossAxisAlignment.start,
               children: [
                 Text(_callbackRegistered ? 'Registered (auto)' : 'Not registered'),
                 const SizedBox(height: 12),
                 Wrap(
                   spacing: 12,
                   runSpacing: 12,
                   children: [
                     OutlinedButton(
                       onPressed: _busy ? null : _refreshLastEvent,
                       child: const Text('Reload last event'),
                     ),
                     OutlinedButton(
                       onPressed: _busy ? null : _logLastEvent,
                       child: const Text('Log last event'),
                     ),
                   ],
                 ),
               ],
             ),
           ),
          const SizedBox(height: 12),
          _InfoCard(
            title: 'Association setup',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: _addressController,
                  decoration: const InputDecoration(
                    labelText: 'Bluetooth MAC address (optional)',
                    helperText:
                        'Leave empty to scan all nearby BLE devices and pick one '
                        'from the system chooser.',
                    helperMaxLines: 2,
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: _busy ? null : _associate,
                  child: const Text('Start association'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          _InfoCard(
            title: 'Current associations',
            child: _associations.isEmpty
                ? const Text('No associations found yet.')
                : Column(
                    children: _associations
                        .map(
                          (association) => ListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(association.displayName ?? association.macAddress ?? 'Unknown device'),
                            subtitle: Text(
                              'MAC: ${association.macAddress ?? 'n/a'}\n'
                              'Association ID: ${association.associationId?.toString() ?? 'n/a'}',
                            ),
                            isThreeLine: true,
                            trailing: TextButton(
                              onPressed: _busy ? null : () => _disassociate(association),
                              child: const Text('Remove'),
                            ),
                          ),
                        )
                        .toList(),
                  ),
          ),
          const SizedBox(height: 12),
          _InfoCard(
            title: 'Last background event',
            child: SelectableText(
              _lastEventJson ?? 'No background event captured yet.',
            ),
          ),
          const SizedBox(height: 12),
           const _InfoCard(
             title: 'Notes',
             child: Text(
               'The background callback is auto-registered on app startup and executes with full Dart access, '
               'even when the app is backgrounded or killed. Device presence events arrive via CompanionDeviceService '
               'and trigger the callback in a headless Flutter engine with full plugin and storage access.',
             ),
           ),
        ],
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}
