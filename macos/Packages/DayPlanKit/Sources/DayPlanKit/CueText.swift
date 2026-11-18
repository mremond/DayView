import Foundation

public enum CueLanguage: Sendable, Hashable {
    case en, fr

    /// `.fr` for "fr" and its regional variants ("fr-CA"); `.en` for anything else.
    public init(languageCode: String?) {
        self = languageCode?.lowercased().hasPrefix("fr") == true ? .fr : .en
    }
}

/// Builds the spoken sentence of a cue. Area names and time formatting are injected so the
/// package stays free of persistence and locale-settings dependencies.
public struct CueText {
    public let language: CueLanguage
    private let areaName: (AreaID) -> String
    private let time: (Date) -> String

    public init(language: CueLanguage, areaName: @escaping (AreaID) -> String, time: @escaping (Date) -> String) {
        self.language = language
        self.areaName = areaName
        self.time = time
    }

    /// The sentence for `cue`; "" when a block cue carries no block.
    /// `interrupted` is the area whose Focus this block-start cue took over, if any.
    public func sentence(for cue: ScheduledCue, takeoverOf interrupted: AreaID?) -> String {
        switch cue.kind {
        case .blockStart:
            guard let block = cue.block else { return "" }
            if let interrupted { return takeover(cue.at, block.areaID, interrupted) }
            return start(cue.at, block)
        case .blockEnding(let next):
            guard let block = cue.block else { return "" }
            return ending(block.areaID, next)
        case .resume(let until):
            guard let block = cue.block else { return "" }
            let area = areaName(block.areaID)
            return language == .fr
                ? "Retour à \(area) jusqu'à \(time(until))."
                : "Back to \(area) until \(time(until))."
        case .unplanned(let until):
            return language == .fr
                ? "Temps non planifié jusqu'à \(time(until))."
                : "Unplanned time until \(time(until))."
        }
    }

    private func start(_ at: Date, _ block: PlannedBlock) -> String {
        var result = "\(time(at)). \(areaName(block.areaID))."
        let intention = block.intention.trimmingCharacters(in: .whitespacesAndNewlines)
        if !intention.isEmpty {
            let terminated = intention.last.map { ".!?".contains($0) } ?? false
            result += " \(intention)\(terminated ? "" : ".")"
        }
        return result
    }

    private func takeover(_ at: Date, _ area: AreaID, _ interrupted: AreaID) -> String {
        let head = "\(time(at)). \(areaName(area))."
        return language == .fr
            ? "\(head) Votre focus \(areaName(interrupted)) s'arrête ici."
            : "\(head) Your \(areaName(interrupted)) focus ends here."
    }

    private func ending(_ area: AreaID, _ next: NextUp) -> String {
        let name = areaName(area)
        let fr = language == .fr
        let head = fr ? "\(name) se termine dans 2 minutes." : "\(name) ends in 2 minutes."
        let tail: String
        switch next {
        case .block(let nextArea, let start):
            tail = fr
                ? "Ensuite : \(areaName(nextArea)) à \(time(start))."
                : "Next: \(areaName(nextArea)) at \(time(start))."
        case .meeting: tail = fr ? "Ensuite : une réunion." : "Next: a meeting."
        case .freeTime: tail = fr ? "Ensuite : du temps libre." : "Next: free time."
        case .endOfPlan: tail = fr ? "C'est la fin du plan de la journée." : "That's the end of today's plan."
        }
        return "\(head) \(tail)"
    }
}

public enum CueComposer {
    /// One sentence per due cue, in `due` order. A block-start cue whose block started a Focus
    /// that took over another one gets the takeover wording. Empty sentences are dropped.
    public static func sentences(due: [ScheduledCue], events: [FocusEvent], text: CueText) -> [String] {
        var interruptedByBlock: [BlockID: AreaID] = [:]
        for case .tookOver(let interrupted, let session) in events {
            if case .planned(let id) = session.origin {
                interruptedByBlock[id] = interrupted.session.areaID
            }
        }
        return due
            .map { cue in
                let interrupted = cue.kind == .blockStart ? cue.block.flatMap { interruptedByBlock[$0.id] } : nil
                return text.sentence(for: cue, takeoverOf: interrupted)
            }
            .filter { !$0.isEmpty }
    }
}
