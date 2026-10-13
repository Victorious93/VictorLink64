# Victorious Framework — Universal N64 Android App Design
Date: 2026-10-13
Author: Victorious
Status: Proposed design; no APK built or gameplay tested.

## Goal and scope
Build an Android N64 gameplay app with touchscreen controls, Android-compatible Bluetooth/USB gamepads, and optional gameplay display on another device over Wi-Fi or supported wired connections. The first TV target is the user's Onn stick. Its exact model and operating system have not been verified. The first receiver implementation targets Onn devices running Android TV or Google TV; other platforms require separate receiver implementations.

Universal means one maintained Android codebase with runtime capability detection, adaptive layouts, and supported CPU builds. It does not promise every controller, TV protocol, phone, or USB cable works.

## Verified input files
The supplied ZIP contains one 33,554,432-byte z64 ROM. SHA-1: 41b3bdc48d98c48529219919015a1af22f5057c2. This matches public records for USA Ocarina of Time v1.2, rather than a modified homebrew ROM.
The supplied M64Plus FZ APK contains DEX files and emulator assets/license documents, but no standard lib/ native-library entries. Whether it needs split APKs or another delivery mechanism is unverified. It has not been installed or executed.

## Architecture
Use an existing N64 emulator core rebuilt from its maintained source with its license obligations preserved. Select and pin the exact core, renderer, dependencies, and toolchain after source inspection and compatibility evaluation; do not use the uploaded binary as a verified build dependency.
An Android application layer owns game import, settings, input profiles, save management, connection management, and capability reporting. A native integration layer owns emulation, rendering, audio, and input delivery.
Use three application modes: Play/Host, Display/Receiver, and Controller. Phone, tablet, and Android TV layouts share the same application identity where practical. Publish a universal APK containing the supported native ABIs; ABI coverage and minimum Android version must be justified by the selected core and build tools.

## Local gameplay
Import the user's ROM/ZIP through the Android file picker, validate format and size, safely extract supported entries into app-private storage, and identify the game by hash. Do not place the ROM in a public source repository. Public distribution packaging must keep emulator licensing and game-content permissions separate.
Provide landscape gameplay, adjustable aspect ratio, analog stick, A/B, four C buttons, Z, L/R, D-pad, and Start. Controls support multitouch, movable positions, size/opacity adjustment, and saved layouts.
Support persistent game saves, versioned save-state metadata, export/import, and resume. Never silently overwrite a user's save after state incompatibility.

## Controller support
Use Android InputDevice button/axis events for connected gamepads, including Bluetooth and USB controllers recognized by the OS. Provide remapping, analog calibration/dead zones, profiles, disconnect handling, and a system pairing entry point. Do not claim universal compatibility or rumble support without testing.
A separate phone in Controller mode can send authenticated inputs to the host. Only the paired controller may control the session. Losing connectivity releases held buttons and pauses the host by default.

## Onn TV and Wi-Fi mode
For an Android TV/Google TV Onn stick, install the same APK in Display/Receiver mode. The phone runs emulation and streams gameplay video/audio over the local network. The TV receiver decodes the stream; controls remain on the host phone or its connected controller.
Use a maintained real-time streaming stack supporting hardware encoding/decoding where available. Evaluate WebRTC during implementation rather than promising latency or codec availability before testing. Start with one active TV receiver and one active input authority. Other paired devices can switch roles; concurrent multi-display broadcasting is outside the first release.
Discover receivers on the LAN with a manual pairing fallback. Show a short-lived pairing code/QR, require host approval, authenticate sessions, and support revocation. Keep gameplay offline from cloud services. Handle network changes, receiver exit, and interrupted streams without save corruption.
If the Onn stick is not an Android TV/Google TV device, mark the native receiver unsupported and determine a platform-specific approach. Google Cast support is a separate sender/receiver integration, not implied by Wi-Fi support.
Wi-Fi Direct is an optional later transport for devices that expose the needed capability. Do not equate it with Miracast or assume a TV accepts it.

## Wired connections
USB-C display output uses the phone's existing hardware and OS support through a compatible adapter/display. A TV stick's power USB port is not treated as a video input.
USB tethering or USB Ethernet can provide an IP transport only when the receiving endpoint and Android routing support it. Direct phone-to-phone USB requires a defined host/accessory protocol and verified devices; a generic cord is not sufficient.
Ship capability checks and explanations instead of advertising unsupported wired modes. Prefer Wi-Fi for the initial Onn connection.

## Error handling and security
Bound archive expansion, reject traversal and malformed ROM inputs, minimize permissions, keep saves app-private, and require explicit pairing. Apply input authorization separately from viewing authorization. Stream cancellation must stop capture/encoding and release session resources. Pause on lost input ownership. Provide clear unsupported-feature messages.

## Delivery sequence and acceptance
1. Source/license audit and reproducible native core build.
2. Local ROM import and gameplay, sound, saves, and lifecycle handling.
3. Touchscreen and physical controller mapping.
4. Android TV receiver, authenticated Wi-Fi streaming, and phone controller mode.
5. Capability-gated wired transports and compatibility expansion.

Acceptance requires an installable APK, actual ROM boot/gameplay evidence on a compatible device, simultaneous touch-button testing, controller disconnect testing, save round trips, and an Onn Wi-Fi session with measured video/audio/input latency. Verify reconnect and pause behavior. Record the tested devices, Android versions, ABI coverage, and limitations.
Building an APK alone is not evidence gameplay, casting, or physical controllers work.

## Review
This document is the proposed architecture for review before implementation planning. It records the requested universal build and Onn target without assuming hardware capabilities.

