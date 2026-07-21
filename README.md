# Light Stick Studio

Android BLE research and light-effect controller for compatible light sticks. It deliberately does **not** claim support for any official SM Entertainment light stick until its BLE protocol is captured and verified on the exact hardware.

## What works now

- Requests Android 12+ Bluetooth permissions and scans nearby BLE advertisements.
- Connects to a selected peripheral and lists every readable service/characteristic UUID, so a real stick can be profiled without guessing.
- Provides a slow, smooth RGB rainbow generator (default: 18 seconds per loop) and sends RGB bytes to a user-selected writable characteristic.

## Important limitation

NFC normally supplies identity/pairing data; it is not the real-time concert lighting channel. Official concert sync commonly uses a private BLE protocol, sometimes with session authentication or encrypted packets. The app will not control a real official stick until the correct writable characteristic and packet format are established. Capture the services with this app (or nRF Connect) and provide the export/screenshot plus the exact group/model/generation.

## Open in Android Studio

Open this folder, let Gradle download dependencies, and run on an Android 12+ phone with Bluetooth enabled. Do not use the app to transmit to a device you do not own or have permission to test.
