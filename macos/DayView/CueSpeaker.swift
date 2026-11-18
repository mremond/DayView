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
        set {
            objectWillChange.send()
            defaults.set(newValue, forKey: Self.enabledKey)
            if !newValue { synthesizer.stopSpeaking(at: .immediate) }
        }
    }

    var volume: Double {
        get { min(max(defaults.double(forKey: Self.volumeKey), 0), 1) }
        set { objectWillChange.send(); defaults.set(newValue, forKey: Self.volumeKey) }
    }

    var usesChime: Bool {
        get { defaults.bool(forKey: Self.chimeKey) }
        set {
            objectWillChange.send()
            defaults.set(newValue, forKey: Self.chimeKey)
            if newValue { synthesizer.stopSpeaking(at: .immediate) }
        }
    }

    /// Speaks one utterance per sentence in order, or plays a single chime for the batch.
    func speak(_ sentences: [String]) {
        guard isEnabled else { return }
        play(sentences)
    }

    private func play(_ sentences: [String]) {
        guard !sentences.isEmpty else { return }
        if usesChime {
            let sound = NSSound(named: "Glass")
            sound?.volume = Float(volume)
            sound?.play()
            return
        }
        let voice = Self.voice(for: Self.language)
        for sentence in sentences {
            let utterance = AVSpeechUtterance(string: sentence)
            utterance.voice = voice
            utterance.volume = Float(volume)
            synthesizer.speak(utterance)
        }
    }

    /// A soft chime at the cue volume, only when cues are enabled.
    func chime() {
        guard isEnabled else { return }
        let sound = NSSound(named: "Glass")
        sound?.volume = Float(volume)
        sound?.play()
    }

    /// Speaks the sentences for a tick's cues.
    func speak(due: [ScheduledCue], events: [FocusEvent], areas: [Area]) {
        speak(CueComposer.sentences(due: due, events: events, text: Self.text(areas: areas)))
    }

    /// Plays a sample "Back to <first area> until <in one hour>" cue, bypassing the enabled switch without changing it.
    func test(areas: [Area], now: Date = Date()) {
        guard let area = areas.first else { return }
        let block = PlannedBlock(id: BlockID(), start: now, end: now.addingTimeInterval(3600), areaID: area.id, intention: "")
        let cue = ScheduledCue(at: now, kind: .resume(until: now.addingTimeInterval(3600)), block: block)
        let sentences = CueComposer.sentences(due: [cue], events: [], text: Self.text(areas: areas))
        play(sentences)
    }

    /// The cue language: the system language when it is French, else English.
    private static var language: CueLanguage {
        CueLanguage(languageCode: Locale.current.language.languageCode?.identifier)
    }

    /// A voice that speaks the cue language, so a German system gets English text read by an
    /// English voice: the system locale (BCP-47, keeping its region) when its language matches,
    /// else "en-US" / "fr-FR".
    private static func voice(for language: CueLanguage) -> AVSpeechSynthesisVoice? {
        let fallback = language == .fr ? "fr-FR" : "en-US"
        let code = language == .fr ? "fr" : "en"
        let locale = Locale.current
        guard locale.language.languageCode?.identifier == code else {
            return AVSpeechSynthesisVoice(language: fallback)
        }
        return AVSpeechSynthesisVoice(language: locale.identifier(.bcp47))
            ?? AVSpeechSynthesisVoice(language: fallback)
    }

    private static func text(areas: [Area]) -> CueText {
        CueText(
            language: language,
            areaName: { id in areas.first { $0.id == id }?.name ?? "" },
            time: { $0.formatted(.dateTime.hour().minute()) }
        )
    }
}
