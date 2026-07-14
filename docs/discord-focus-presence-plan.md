# Discord Integration Plan for Focus Sessions

## Summary

DayView will propose two independent and disabled by default Discord functions:

1. A **Rich Presence personal** during a Focus session, visible on the user's Discord profile;
2. The **optional publication of results** from the session in a Discord channel via an incoming webhook.

The integration must remain strictly optional, never blocking local Focus functionality, and limit shared information by default. A failure, disconnection, or bandwidth limitation should not affect the timer or local history.

## Target Experience

### During a Focus Session

If Rich Presence is enabled and the connected account, the profile will display an activity of this type:

```text
DayView
Focus in progress
Preparing the presentation
18 minutes remaining
```

The countdown is produced by Discord from the session's deadline. DayView does not publish updates every second.

By default, intention is **not** included. The user can explicitly activate sharing. Without intention, the presence displays only `Focus in progress` and the remaining time.

The presence disappears when the session is stopped, closed, or expired. When relaunched, a previously active session restores the presence after reconnecting to Discord.

### At Session Closure

If publication in a channel is enabled, DayView publishes a single message when the user chooses a result:

```text
✅ Focus completed — 25 minutes
Result: Completed
```

The three possible results from DayView are represented as follows:

| Result | Label Discord | Icon |
| --- | --- | --- |
| `COMPLETED` | Terminated | ✅ |
| `PROGRESSED` | Advanced | ➡️ |
| `TO_RESUME` | To resume | 🔁 |

Intention can be included in the message only if the option is enabled. A session without closure does not publish anything. An expiration of the timer does not publish anything until the user chooses their result.

## Integration Scope

Inclues:

- macOS and Android;
- explicit association of a Discord account for Rich Presence;
- presence created at startup and removed at end;
- reactivation after redemarrage for an active session;
- configuration of a webhook linked to a channel;
- testing the webhook from settings;
- publishing a message when closing;
- separate options for sharing intention in presence and channel.

Excluded initial scope:

- slash commands and permanent Discord bot;
- starting or piloting a Focus from Discord;
- team statistics or ranking;
- messages at each startup, interruption, or minute elapsed;
- cloud synchronization of the Discord configuration;
- publishing of the long-term goal or work hours.

## Settings and Privacy

Add a `Discord` section to the Settings screen with two independent sub-sections.

### Presence Personal

- toggle `Show my Focus on Discord`, disabled by default;
- action `Connect Discord` / `Disconnect`;
- understandable connection state: connected, Discord unavailable, authorization required;
- toggle `Include my intention`, disabled by default;
- preview of the content that will be made public.

### Publication in a Channel

- toggle `Publish result in a channel`, disabled by default;
- masked field for the webhook URL;
- action `Test Connection`, which sends an explicit test message;
- toggle `Include my intention`, disabled by default;
- action `Remove configuration from channel`.

The interface should explain that:

- A Rich Presence is visible according to the sharing of activity settings on the user's Discord account;
- An intention may contain personal or professional data;
- The URL of a webhook allows publishing in the associated channel and must be treated as confidential.

The token Discord and the complete webhook URL should never appear in logs, error messages, or crash reports.

## Proposed Architecture

The business logic remains in `commonMain`. External integrations are exposed through interfaces and implemented by platform.

```text
Actions Focus in DayViewApp
        |
        v
FocusSharingCoordinator (commonMain)
        |                         |
        v                         v
DiscordPresenceClient       FocusResultPublisher
(native implementation)     (HTTP webhook client)
        |                         |
        v                         v
Discord Social SDK          API Webhook Discord
```

### Business Events

Introduce explicit events to avoid inferring transitions from the display loop:

```kotlin
sealed interface FocusSharingEvent {
    data class Started(
        val sessionId: String,
        val intention: String,
        val durationMinutes: Int,
        val startedAtMillis: Long,
        val endsAtMillis: Long,
    ) : FocusSharingEvent

    data class Stopped(val sessionId: String) : FocusSharingEvent

    data class Closed(
        val sessionId: String,
        val intention: String,
        val durationMinutes: Int,
        val outcome: FocusClosureOutcome,
        val closedAtMillis: Long,
    ) : FocusSharingEvent
}
```

`Started` updates the Rich Presence. `Stopped` removes it without publishing a result. `Closed` removes the presence and programs to publish at most one publication of results.

The `sessionId` persistent serves as an idempotence key, preventing double messages after a double click, recomposition Compose, reconnection network or application restart.

### Common Contracts

```kotlin
interface DiscordPresenceClient {
    suspend fun connect(): PresenceConnectionResult
    suspend fun updateFocus(activity: FocusPresence): Result<Unit>
    suspend fun clear(): Result<Unit>
    suspend fun disconnect()
}

interface FocusResultPublisher {
    suspend fun test(webhook: DiscordWebhook): Result<Unit>
    suspend fun publish(result: SharedFocusResult): Result<Unit>
}
```

A `FocusSharingCoordinator` receives events, applies privacy settings, constructs content and calls these two contracts. It should never raise an error to the main flow of the timer.

### Branching in DayView

The transitions already exist in `DayViewApp`:

- `onPomodoroStart` starts and persists the Focus;
- `onPomodoroStop` interrupts it;
- `onPomodoroClose` receives the result of closure.

The first step of refactoring is to add a unique callback function, for example `onFocusSharingEvent`, alongside `onFocusAlarmChange`. The desktop and Android entry points create then the coordinator appropriate. This decoupling avoids placing network calls in composable and makes transitions testable.

The main loop of `Main.kt` remains charged to detect a restored session. It triggers only a presence restoration when the identifier and deadline of the session differ from the last sent state.

## Rich Presence Personal

### Technology

Use the **Discord Social SDK**, official recommended solution for new projects. The SDK supports macOS x64/ARM64 and Android 7 or later, matching current DayView targets.

The SDK exposes a C++ API. A Kotlin Multiplatform implementation will require a minimum native layer:

- macOS desktop: native universal library or two variants x64/ARM64, loaded from the JVM via JNA;
- Android: native `.so` by ABI and JNI bridge;
- interface identical on both platforms;
- packaging and signature verification in DMG and APK.

The SDK Discord archived (« Game SDK ») and non-official RPC libraries should not be used for a new integration.

### Lifecycle

1. The user activates the feature and authorizes their account.
2. DayView initializes the client Discord without blocking the interface.
3. At startup of a Focus, DayView sends an activity containing the deadline.
4. A modification of intention during the session updates the presence only if sharing is enabled, with anti-bounce.
5. After stopping, closing or expiring, DayView removes the activity.
6. Upon relaunching DayView, a previously active session restores the presence after reconnecting to Discord.

If Discord is not installed, not launched or not connected, DayView keeps the Focus and displays only a discreet state in settings.

### Content Transmitted

The payload must remain minimal:

- Application name configured in the Developer Portal: `DayView`;
- detail: `Focus in progress`;
- state: intention truncated and cleaned, only with consent;
- timestamp of end: `endsAtMillis`;
- visual DayView registered as asset Discord.

Do not transmit the long-term goal, application names at first place, attention signal or user identifier DayView.

## Publication Facultative in a Channel

### Technology

Use an incoming webhook Discord. It allows publishing in a channel without a bot, connection Gateway or DayView server.

The client HTTP can live in `commonMain` with a Kotlin Multiplatform implementation, under the condition of adding a permission Internet Android.

The webhook receives a message or embed containing:

- result of closure;
- duration expected Focus;
- optionally intention;
- a footer message `DayView`.

The first version publishes an autonomous message. It does not try to maintain a live message during the session.

### Security of Webhook

- macOS: store URL in a secure treasure via a small abstraction of storage;
- Android: store URL encrypted with the secure storage of the platform;
- never keep URL in ordinary preferences except for a boolean indicating if a webhook is configured;
- validate HTTPS schema and Discord host before registration;
- mask URL after input and offer an explicit replacement;
- never send URL in error messages.

A compromised webhook should be removed from DayView.

### Idempotence

The publication is a secondary consequence of the local closure:

- first, record the result locally;
- create then a publication pending associated with `sessionId`;
- try sending with a short delay;
- if error 5xx or `429`, retry with a backoff bounded;
- respect `401`, `403` and `404` errors without re-trying;
- mark the publication as sent before removing it from the queue;
- limit the local queue and its retention time.

The user should be able to see `Last successful publication` or an error message in settings. An error does not trigger a modal window upon closure.

## Preferences Model

Extend `DayPreferences` with a versioned structure rather than a succession of independent parameters:

```kotlin
data class DiscordSharingSettings(
    val presenceEnabled: Boolean = false,
    val presenceIncludesIntention: Boolean = false,
    val resultPublishingEnabled: Boolean = false,
    val resultIncludesIntention: Boolean = false,
    val hasWebhook: Boolean = false,
)
```

Secrets and tokens remain outside `DayPreferences`. Create a distinct contract:

```kotlin
interface DiscordSecretStore {
    suspend fun loadWebhookUrl(): String?
    suspend fun saveWebhookUrl(value: String)
    suspend fun deleteWebhookUrl()
}
```

The session Focus must also receive a stable identifier, conserved with its end time, to ensure idempotence of events and publications.

## Implementation Strategy

### Phase 1 — Events and Confidentiality

- extract transitions Focus in business events;
- add the `sessionId` persistent;
- add the Discord settings disabled by default;
- create inert interfaces and implementations;
- test startup, stop, closure, and session restoration.

This phase does not contact Discord yet and reduces the risk of subsequent phases.

### Phase 2 — Webhook Result

- implement secure storage on macOS and Android;
- add client HTTP and schema validation;
- create a test action;
- publish three results;
- add idempotence, `429` and reconnection management;
- verify that no secret appears in logs.

This phase delivers the first user value with limited complexity.

### Phase 3 — Rich Presence macOS

- create DayView application in the Discord Developer Portal;
- configure name, icon, asset Rich Presence and OAuth;
- integrate Social SDK and JNA bridge;
- manage connection, update, removal and reconnection;
- package architectures Intel and Apple Silicon;
- test with Discord closed, open, offline and with a test account.

### Phase 4 — Rich Presence Android

- integrate native libraries by ABI and JNI;
- add mobile authorization flow and deep link Discord;
- restore presence after recreation of activity or relaunch;
- verify behavior in background and Android restrictions;
- test at minimum Android 7 and a recent version.

### Phase 5 — Durcissement and Launch

- audit confidentiality and remove secrets;
- tests migration from an existing installation;
- documentation for the user and procedure for revocation;
- verification of bandwidth limits;
- test with a reserved Discord account development before release.

## Expected Tests

### Common Unit Tests

- functions are inert with default settings;
- intention is never included without explicit consent;
- `Started` produces presence with correct timestamp;
- `Stopped` removes presence and publishes nothing;
- `Closed` removes presence and publishes exactly once;
- two `Closed` events of the same `sessionId` create only one message;
- a restored session suppresses presence;
- an error Discord does not affect Focus state.

### Integration Webhook Tests

- payload conforms to three results;
- intention is absent or present according to setting;
- idempotence, `204`, `400`, `401`, `404`, `429` and `5xx` management;
- timeout, no network and reconnection retry;
- URL and token expurgated from errors.

### Manual Rich Presence Tests

- connection and revocation OAuth;
- public display with activity sharing enabled;
- absence of display when Discord or sharing is disabled;
- countdown correct;
- presence removal after stop or closure;
- presence restoration upon relaunch;
- absence of intention with default setting.

## Acceptance Criteria

The feature is ready when:

1. no data leaves DayView without explicit activation;
2. an active Focus session produces at most one Rich Presence correct;
3. presence disappears at the end of the session;
4. a closure produces at most one message in the configured channel;
5. no message is sent during a simple stop or non-closed expiration;
6. intention remains private by default for both channels;
7. Discord can be disconnected without degrading the timer;
8. secrets are stored in secure mechanisms of platforms and absent from logs;
9. behavior is covered by common tests and integration tests;
10. disconnection Discord and webhook removal are accessible from settings.

## Discord References

- [Rich Presence](https://docs.discord.com/developers/platform/rich-presence)
- [Discord Social SDK](https://docs.discord.com/developers/discord-social-sdk/overview)
- [Platform Compatibility](https://docs.discord.com/developers/discord-social-sdk/core-concepts/platform-compatibility)
- [Incoming Webhooks](https://docs.discord.com/developers/resources/webhook)
