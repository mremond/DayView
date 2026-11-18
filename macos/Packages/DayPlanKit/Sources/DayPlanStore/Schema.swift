import SwiftData

/// The stage 2 shape: areas and the weekly template.
enum DayPlanSchemaV1: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(1, 0, 0) }
    static var models: [any PersistentModel.Type] { [AreaRecord.self, TemplateBlockRecord.self] }
}

/// Adds accepted day plans.
enum DayPlanSchemaV2: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(2, 0, 0) }
    static var models: [any PersistentModel.Type] {
        [AreaRecord.self, TemplateBlockRecord.self, AcceptedPlanRecord.self]
    }
}

/// Adds closed Focus records.
enum DayPlanSchemaV3: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(3, 0, 0) }
    static var models: [any PersistentModel.Type] {
        [AreaRecord.self, TemplateBlockRecord.self, AcceptedPlanRecord.self, FocusRecordEntry.self]
    }
}

/// Adds ignored cues.
enum DayPlanSchemaV4: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(4, 0, 0) }
    static var models: [any PersistentModel.Type] {
        [
            AreaRecord.self, TemplateBlockRecord.self, AcceptedPlanRecord.self, FocusRecordEntry.self,
            IgnoredCueEntry.self,
        ]
    }
}

enum DayPlanMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [DayPlanSchemaV1.self, DayPlanSchemaV2.self, DayPlanSchemaV3.self, DayPlanSchemaV4.self] }
    static var stages: [MigrationStage] {
        [
            .lightweight(fromVersion: DayPlanSchemaV1.self, toVersion: DayPlanSchemaV2.self),
            .lightweight(fromVersion: DayPlanSchemaV2.self, toVersion: DayPlanSchemaV3.self),
            .lightweight(fromVersion: DayPlanSchemaV3.self, toVersion: DayPlanSchemaV4.self),
        ]
    }
}
