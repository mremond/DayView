package fr.dayview.app.sync

import io.ktor.client.HttpClient

expect fun createSyncHttpClient(): HttpClient
