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

enum DayPlanMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [DayPlanSchemaV1.self, DayPlanSchemaV2.self] }
    static var stages: [MigrationStage] { [.lightweight(fromVersion: DayPlanSchemaV1.self, toVersion: DayPlanSchemaV2.self)] }
}
