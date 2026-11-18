import Foundation
import Testing
@testable import DayPlanKit

private let writing = AreaID()
private let health = AreaID()

private let formatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    f.timeZone = TimeZone(identifier: "Europe/Paris")
    return f
}()

private func text(_ language: CueLanguage) -> CueText {
    CueText(
        language: language,
        areaName: { id in
            switch (id, language) {
            case (writing, .en): "Writing"
            case (writing, .fr): "Écriture"
            case (_, .en): "Health"
            case (_, .fr): "Santé"
            }
        },
        time: { formatter.string(from: $0) }
    )
}

private func block(_ start: String, area: AreaID, _ intention: String) -> PlannedBlock {
    PlannedBlock(id: BlockID(), start: at(start), end: at("23:00"), areaID: area, intention: intention)
}

private func cue(_ kind: CueKind, at time: String, block: PlannedBlock?) -> ScheduledCue {
    ScheduledCue(at: at(time), kind: kind, block: block)
}

struct CueTextTests {
    @Test func languageCode() {
        #expect(CueLanguage(languageCode: "fr") == .fr)
        #expect(CueLanguage(languageCode: "fr-CA") == .fr)
        #expect(CueLanguage(languageCode: "en") == .en)
        #expect(CueLanguage(languageCode: "de") == .en)
        #expect(CueLanguage(languageCode: nil) == .en)
    }

    @Test func start() {
        let b = block("09:00", area: writing, "Chapter 3")
        let c = cue(.blockStart, at: "09:00", block: b)
        #expect(text(.en).sentence(for: c, takeoverOf: nil) == "09:00. Writing. Chapter 3.")
        #expect(text(.fr).sentence(for: c, takeoverOf: nil) == "09:00. Écriture. Chapter 3.")
    }

    @Test func startWithBlankIntention() {
        let c = cue(.blockStart, at: "09:00", block: block("09:00", area: writing, "  \n"))
        #expect(text(.en).sentence(for: c, takeoverOf: nil) == "09:00. Writing.")
        #expect(text(.fr).sentence(for: c, takeoverOf: nil) == "09:00. Écriture.")
    }

    @Test func startIntentionWithPunctuationAndPadding() {
        let padded = cue(.blockStart, at: "09:00", block: block("09:00", area: writing, " Why? "))
        #expect(text(.en).sentence(for: padded, takeoverOf: nil) == "09:00. Writing. Why?")
        let dotted = cue(.blockStart, at: "09:00", block: block("09:00", area: writing, "Done."))
        #expect(text(.en).sentence(for: dotted, takeoverOf: nil) == "09:00. Writing. Done.")
    }

    @Test func takeover() {
        let c = cue(.blockStart, at: "14:00", block: block("14:00", area: health, "Run"))
        #expect(text(.en).sentence(for: c, takeoverOf: writing) == "14:00. Health. Your Writing focus ends here.")
        #expect(text(.fr).sentence(for: c, takeoverOf: writing) == "14:00. Santé. Votre focus Écriture s'arrête ici.")
    }

    @Test func endingWithEachNext() {
        let b = block("11:00", area: writing, "")
        func sentence(_ next: NextUp, _ language: CueLanguage) -> String {
            text(language).sentence(for: cue(.blockEnding(next: next), at: "11:58", block: b), takeoverOf: nil)
        }
        let next = NextUp.block(areaID: health, start: at("13:00"))
        #expect(sentence(next, .en) == "Writing ends in 2 minutes. Next: Health at 13:00.")
        #expect(sentence(.meeting, .en) == "Writing ends in 2 minutes. Next: a meeting.")
        #expect(sentence(.freeTime, .en) == "Writing ends in 2 minutes. Next: free time.")
        #expect(sentence(.endOfPlan, .en) == "Writing ends in 2 minutes. That's the end of today's plan.")
        #expect(sentence(next, .fr) == "Écriture se termine dans 2 minutes. Ensuite : Santé à 13:00.")
        #expect(sentence(.meeting, .fr) == "Écriture se termine dans 2 minutes. Ensuite : une réunion.")
        #expect(sentence(.freeTime, .fr) == "Écriture se termine dans 2 minutes. Ensuite : du temps libre.")
        #expect(sentence(.endOfPlan, .fr) == "Écriture se termine dans 2 minutes. C'est la fin du plan de la journée.")
    }

    @Test func resume() {
        let c = cue(.resume(until: at("12:00")), at: "11:00", block: block("10:00", area: writing, "x"))
        #expect(text(.en).sentence(for: c, takeoverOf: nil) == "Back to Writing until 12:00.")
        #expect(text(.fr).sentence(for: c, takeoverOf: nil) == "Retour à Écriture jusqu'à 12:00.")
    }

    @Test func unplanned() {
        let c = cue(.unplanned(until: at("13:00")), at: "12:00", block: nil)
        #expect(text(.en).sentence(for: c, takeoverOf: nil) == "Unplanned time until 13:00.")
        #expect(text(.fr).sentence(for: c, takeoverOf: nil) == "Temps non planifié jusqu'à 13:00.")
    }

    @Test func missingBlockGivesEmptySentence() {
        #expect(text(.en).sentence(for: cue(.blockStart, at: "09:00", block: nil), takeoverOf: nil) == "")
    }
}

struct CueComposerTests {
    private func manualWriting() -> FocusSession {
        FocusSession(areaID: writing, intention: "", origin: .manual, start: at("13:00"), end: at("13:30"))
    }

    @Test func takeoverEventSelectsTakeoverWording() {
        let h = block("14:00", area: health, "Run")
        let planned = FocusSession(areaID: health, intention: "Run", origin: .planned(h.id), start: at("14:00"), end: at("15:00"))
        let record = FocusRecord(session: manualWriting(), actualEnd: at("14:00"), focused: 3600, reason: .tookOver)
        let sentences = CueComposer.sentences(
            due: [cue(.blockStart, at: "14:00", block: h)],
            events: [.tookOver(interrupted: record, by: planned)],
            text: text(.en)
        )
        #expect(sentences == ["14:00. Health. Your Writing focus ends here."])
    }

    @Test func noEventGivesStartSentence() {
        let h = block("14:00", area: health, "Run")
        let sentences = CueComposer.sentences(due: [cue(.blockStart, at: "14:00", block: h)], events: [], text: text(.en))
        #expect(sentences == ["14:00. Health. Run."])
    }

    @Test func keepsOrderAndDropsEmpty() {
        let a = block("09:00", area: writing, "")
        let b = block("10:00", area: health, "")
        let sentences = CueComposer.sentences(
            due: [cue(.blockStart, at: "09:00", block: a), cue(.blockStart, at: "09:30", block: nil), cue(.blockStart, at: "10:00", block: b)],
            events: [],
            text: text(.en)
        )
        #expect(sentences == ["09:00. Writing.", "10:00. Health."])
    }
}
