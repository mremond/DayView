package fr.dayview.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncConfig(val baseUrl: String, val userId: String, val token: String)
