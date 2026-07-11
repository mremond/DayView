# Virtual Camera DayView on macOS

## Status

Proposed feature. This functionality is not yet implemented.

## Objective

To provide a selectable camera under the name **DayView Camera** in video conferencing applications. Its image combines:

- the stream of the selected webcam chosen by the user;
- the ring of progress of the day;
- the remaining time;
- the intention and the remaining Focus when a session is active.

The processing remains entirely local. The feature does not publish audio: the user continues to select their usual microphone in the video conferencing application.

## User Experience Targeted

A **Virtual Camera** section in macOS settings would allow for:

1. installing or activating the system extension during the first use;
2. choosing the webcam source;
3. enabling or disabling the overlay;
4. choosing the corner, size, and opacity of the camera;
5. displaying a preview;
6. selecting **DayView Camera** in Zoom, Meet, Teams, FaceTime, or any other compatible application.

The overlay should remain discreet: a small ring in a corner, duration at the center, and intention on one or two lines next to it. By default, the intention is only displayed when a Focus is active. An option must allow for quickly hiding the overlay without uninstalling the camera.

## Choice of Technology

Using a **Camera Extension Core Media I/O**. This API, available since macOS 12.3, is the modern recommended solution by Apple for publishing a logical camera. It is compatible with the minimum target macOS 13 of DayView.

References Apple:

- [Creating a camera extension with Core Media I/O](https://developer.apple.com/documentation/CoreMediaIO/creating-a-camera-extension-with-core-media-i-o)
- [Core Media I/O](https://developer.apple.com/documentation/coremediaio)
- [Setting up a capture session](https://developer.apple.com/documentation/avfoundation/setting-up-a-capture-session)

The old plug-ins DAL should not be used. They are less secure, more difficult to distribute, and incompatible with certain applications that require library validation.

## Proposed Architecture

```mermaid
flowchart LR
    DV["DayView Kotlin/Compose"] -->|"state JSON via helper Swift"| AG["App Group Container"]
    AG -->|"hours, Focus, intention, overlay preferences"| CE["Camera Extension Swift"]
    WC["Physical Webcam"] -->|"AVFoundation / CVPixelBuffer"| CE
    CE -->|"Core Image or Metal"| MIX["Webcam image + overlay"]
    MIX -->|"CMSampleBuffer"| CMIO["Media I/O Stream"]
    CMIO --> APP["Zoom, Meet, Teams, FaceTime…"]
```

The feature requires three elements:

### 1. Existing DayView Application

The existing Kotlin/Compose application remains the source of truth for:

- hours of start and end of day;
- display of seconds;
- duration and deadline of Focus;
- intention;
- visual settings of the camera.

It pilots a small Swift helper according to the same principle as `MacFocusStatusHelper.swift`. The helper writes an instant snapshot of state into the shared App Group container and emits an inter-process notification after each modification.

The currently used Java preferences in `DesktopDayPreferences` should not be read directly by the extension. Their location and format are not a stable contract, and an extension sandboxed does not naturally access them.

### 2. Native macOS Host

A Swift signed component carries the necessary capabilities and submits the activation request with `OSSystemExtensionManager`. It also provides:

- state of installation of the extension;
- selection of the webcam;
- authorization requests;
- simple diagnostic in case of unavailability.

For a first version, the solution that is least fragile consists in delivering a small **DayView Camera.app** native alongside DayView in the DMG. It embeds the Camera Extension and can be launched from the settings DayView. An integration into the same bundle as the application jpackage will be studied later, but it imposes rebuilding and signing of the entire hierarchy of bundles after packaging.

At term, a single host could envelop the runtime JVM and the extension to offer a single visible application.

### 3. Camera Extension

The Swift extension publishes a provider, a device, and a stream Core Media I/O:

- `CMIOExtensionProvider` represents DayView;
- `CMIOExtensionDevice` publishes **DayView Camera**;
- `CMIOExtensionStream` produces images, for example at 1280 × 720 at 30 frames per second.

It opens the physical webcam with `AVCaptureSession` and retrieves images with `AVCaptureVideoDataOutput`. The virtual camera DayView must be explicitly excluded from the list of sources to avoid a capture loop.

Each image follows this pipeline:

1. reception of a `CVPixelBuffer` of the webcam;
2. correction of orientation and application of chosen cropping;
3. local calculation of progress based on hours and current time;
4. rendering of ring, duration, and intention with Core Image or Metal;
5. conversion to `CMSampleBuffer` dated;
6. sending to the stream Core Media I/O.

The calculation of time must be carried out in a small Swift testable implementation according to the same cases as `DayProgressTest` and `PomodoroTest`. The shared state contains deadlines and settings, not an overlay recalculated every second.

## Shared State

A JSON versioned and replaced atomically in the App Group is sufficient for the first version:

```json
{
  "schemaVersion": 1,
  "updatedAtMillis": 0,
  "day": {
    "startMinutes": 480,
    "endMinutes": 1080,
    "showSeconds": true
  },
  "focus": {
    "endMillis": null,
    "intention": ""
  },
  "camera": {
    "enabled": true,
    "sourceDeviceId": null,
    "position": "bottomLeading",
    "scale": 1.0,
    "opacity": 0.92,
    "mirrorSource": false
  }
}
```

Rules of exchange:

- writing in a temporary file and renaming atomically;
- field `schemaVersion` mandatory;
- values missing replaced by safe default values;
- intention limited to the length already accepted by DayView;
- notification Darwin after writing and periodic reading backup;
- conservation of the last valid state in case of partial or corrupted read.

## Permissions, Signature, and Installation

The host and extension must share an App Group. The host needs particularly the capability to install System Extension. The extension is sandboxed and requires access to the camera if it captures another camera.

Exact files of entitlements will be generated by the Xcode project, but they must cover at least:

- `com.apple.developer.system-extension.install` for the host;
- `com.apple.security.application-groups` for the host and the extension;
- `com.apple.security.device.camera` for the process that captures the webcam;
- a readable description of camera understandable by the user.

The application must be installed in `/Applications` to activate the extension. macOS requests explicit validation during the first activation; according to configuration, administrative rights may be necessary. The product must explain this step and show the actual state of activation.

## Packaging in This Repository

Add a separate Xcode project, for example:

```text
macosCamera/
├── DayViewCamera.xcodeproj
├── Host/
├── CameraExtension/
├── Shared/
└── Tests/
```

Gradle can orchestrate `xcodebuild`, but Xcode remains responsible of compilation, entitlements, and signing of the native bundle. The proposed packaging flow is:

1. compile and test the Kotlin code;
2. build `DayView Camera.app` and its extension with `xcodebuild archive`;
3. produce the application Compose with `createDistributable`;
4. place the two applications in the DMG;
5. sign, notarize, and verify the installed result.

The secrets of signature and Team ID should not be written in the repository. They are provided by local configuration or CI.

## Resilience and Degraded Cases

The extension must continue to provide a valid image in the following situations:

- **webcam absent or occupied**: mirror DayView with a short message, without aggressive loop restart;
- **DayView stopped**: last state valid for a short period, then overlay neutral;
- **Focus ended**: disappearance of intention automatically;
- **camera change**: controlled switching, with an image transition;
- **format not taken into account**: conversion to the format published by the stream;
- **client disconnected**: stop of capture to conserve camera, CPU, and battery;
- **putting to sleep or wake-up**: reconstruction of the capture session if necessary.

The local preview can be mirror for corresponding habits of video conferencing, but the sent flow should not be mirror by default. This choice must be explicit in settings.

## Performance Target

The first version targets 720p at 30 images per second. It must:

- avoid any CPU conversion per image;
- reuse pixel buffer pools;
- pre-render text elements and only regenerate them when they change;
- use Core Image or Metal on the GPU;
- suspend the session when no client consumes the camera;
- measure lost images, without logging video content or intention.

The 1080p can be added after measuring on Intel Mac and Apple Silicon.

## Security and Confidentiality

- No image leaves the Mac;
- No image is recorded on disk;
- No telepresence contains intention;
- The camera is only opened when **DayView Camera** or preview are explicitly visible;
- An indicator in DayView shows that the virtual camera is in use;
- The user can quickly hide the overlay or disable the extension.

## Delivery Plan

### Phase 0 — Validation with OBS

Create a transparent overlay view and compose it with the webcam in OBS Virtual Camera. This step allows validating readability, dimensions, and options, but does not constitute the final architecture.

### Phase 1 — Native Prototype

- Xcode project with Camera Extension template;
- image of test published as **DayView Camera**;
- capture of physical webcam;
- overlay static;
- installation from `/Applications`.

### Phase 2 — Integration DayView

- helper synchronization to App Group;
- ring and dynamic calculations;
- intention Focus;
- selection of camera and preview;
- permission and error handling.

### Phase 3 — Distribution

- packaging in DMG;
- signing, notarization, and verification;
- tests Zoom, Meet, Teams, FaceTime, QuickTime, and Photo Booth;
- tests Intel and Apple Silicon;
- documentation of installation and uninstallation.

## Testing Strategy

### Unit Tests Swift

- calculation of progress before, during, and after the day;
- passage of midnight and change of hour;
- states Focus inactive, active, and ended;
- migration and validation of JSON;
- placement of overlay for each corner and ratio of image.

### Integration Tests

- activation, update, and deactivation of the extension;
- appearance of **DayView Camera** in clients;
- opening and closing of webcam source;
- propagation of new intention without re-launching stream;
- behavior after sleep, disconnection camera, and reactivation DayView.

### Visual Performance and Security Validation

- absence of text cut off in 16:9 and 4:3;
- contrast on light and dark backgrounds;
- stable frame rate at 720p30;
- CPU/GPU consumption and memory;
- latency added by composition.

## Main Risks

| Risk | Proposed Response |
| --- | --- |
| Complexity of signing a System Extension in the Compose bundle | Deliver first native host separately in the same DMG |
| Inaccessible Kotlin state from the sandbox of the extension | Swift helper and App Group with versioned JSON schema |
| Webcam already used or refusal of authorization | Degraded state explicit and recovery controlled |
| Capture loop when selecting virtual camera as source | Exclude DayView identifier from device |
| Overlay illegible or too intrusive | Preview, positions, scale, opacity, and quick hiding |
| High GPU cost or latency | 720p30 initial, buffers pools, and measurement before 1080p |
| Differences between video conferencing clients | Test matrix with system applications, browser, and native clients |

## Acceptance Criteria for the First Version Distributable

- **DayView Camera** appears after a guided installation on macOS 13 or later;
- it composes the webcam selected with the DayView ring;
- time and intention are updated without re-launching the call;
- intention disappears at the end of Focus;
- no image is recorded or sent to a DayView service;
- the physical webcam is released when no client uses the stream;
- the signed and notarized DMG is installed without non-standard security procedure;
- the functionality works at minimum in FaceTime, QuickTime, Zoom, Meet, Teams.
## Testing Strategy

### Unit Tests Swift

- calculation of progress before, during, and after the day;
- passage of midnight and change of hour;
- states Focus inactive, active, and ended;
- migration and validation of JSON;
- placement of overlay for each corner and ratio of image.

### Integration Tests

- activation, update, and deactivation of the extension;
- appearance of **DayView Camera** in clients;
- opening and closing of webcam source;
- propagation of new intention without re-launching stream;
- behavior after sleep, disconnection camera, and reactivation DayView.

### Visual Performance and Security Validation

- absence of text cut off in 16:9 and 4:3;
- contrast on light and dark backgrounds;
- stable frame rate at 720p30;
- CPU/GPU consumption and memory;
- latency added by composition.

## Main Risks

| Risk | Proposed Response |
| --- | --- |
| Complexity of signing a System Extension in the Compose bundle | Deliver first native host separately in the same DMG |
| Inaccessible Kotlin state from the sandbox of the extension | Swift helper and App Group with versioned JSON schema |
| Webcam already used or refusal of authorization | Degraded state explicit and recovery controlled |
| Capture loop when selecting virtual camera as source | Exclude DayView identifier from device |
| Overlay illegible or too intrusive | Preview, positions, scale, opacity, and quick hiding |
| High GPU cost or latency | 720p30 initial, buffers pools, and measurement before 1080p |
| Differences between video conferencing clients | Test matrix with system applications, browser, and native clients |

## Acceptance Criteria for the First Version Distributable

- **DayView Camera** appears after a guided installation on macOS 13 or later;
- it composes the webcam selected with the DayView ring;
- time and intention are updated without re-launching the call;
- intention disappears at the end of Focus;
- no image is recorded or sent to a DayView service;
- the physical webcam is released when no client uses the stream;
- the signed and notarized DMG is installed without non-standard security procedure;
- the functionality works at minimum in FaceTime, QuickTime, Zoom, Meet, Teams.
