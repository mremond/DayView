import AppKit
import AVFoundation
import DayPlanKit

/// Speaks plan cues (voice or chime). Settings live in `UserDefaults` so the Settings UI and
/// the speaker share them.
@MainActor
final class CueSpeaker: ObservableObject {
    static let enabledKey = "cues.enabled"
    static let volumeKey = "cues.volume"
    static let chimeKey = "cues.chime"

    private let defaults: UserDefaults
    private let synthesizer = AVSpeechSynthesizer()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        defaults.register(defaults: [Self.enabledKey: true, Self.volumeKey: 0.8, Self.chimeKey: false])
    }

    var isEnabled: Bool {
        get { defaults.bool(forKey: Self.enabledKey) }
        set { objectWillChange.send(); defaults.set(newValue, forKey: Self.enabledKey) }
    }

    var volume: Double {
        get { min(max(defaults.double(forKey: Self.volumeKey), 0), 1) }
        set { objectWillChange.send(); defaults.set(newValue, forKey: Self.volumeKey) }
    }

    var usesChime: Bool {
        get { defaults.bool(forKey: Self.chimeKey) }
        set { objectWillChange.send(); defaults.set(newValue, forKey: Self.chimeKey) }
    }

    /// Speaks one utterance per sentence in order, or plays a single chime for the batch.
    func speak(_ sentences: [String]) {
        guard isEnabled, !sentences.isEmpty else { return }
        if usesChime {
            let sound = NSSound(named: "Glass")
            sound?.volume = Float(volume)
            sound?.play()
            return
        }
        let voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
            ?? AVSpeechSynthesisVoice(language: Locale.current.language.languageCode?.identifier ?? "en")
        for sentence in sentences {
            let utterance = AVSpeechUtterance(string: sentence)
            utterance.voice = voice
            utterance.volume = Float(volume)
            synthesizer.speak(utterance)
        }
    }

    /// Speaks the sentences for a tick's cues.
    func speak(due: [ScheduledCue], events: [FocusEvent], areas: [Area]) {
        speak(CueComposer.sentences(due: due, events: events, text: Self.text(areas: areas)))
    }

    /// Plays a sample "Back to <first area> until <in one hour>" cue, bypassing the enabled switch.
    func test(areas: [Area], now: Date = Date()) {
        guard let area = areas.first else { return }
        let block = PlannedBlock(id: BlockID(), start: now, end: now.addingTimeInterval(3600), areaID: area.id, intention: "")
        let cue = ScheduledCue(at: now, kind: .resume(until: now.addingTimeInterval(3600)), block: block)
        let sentences = CueComposer.sentences(due: [cue], events: [], text: Self.text(areas: areas))
        let wasEnabled = isEnabled
        if !wasEnabled { defaults.set(true, forKey: Self.enabledKey) }
        speak(sentences)
        if !wasEnabled { defaults.set(false, forKey: Self.enabledKey) }
    }

    private static func text(areas: [Area]) -> CueText {
        CueText(
            language: CueLanguage(languageCode: Locale.current.language.languageCode?.identifier),
            areaName: { id in areas.first { $0.id == id }?.name ?? "" },
            time: { $0.formatted(.dateTime.hour().minute()) }
        )
    }
}
