package com.scooter.slackwear.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class VerifiedIdentity(val userId: String, val teamIds: Set<String>)

fun interface IdentityVerifier {
    suspend fun verify(slackToken: String): VerifiedIdentity?
}

internal fun validSlackToken(value: String): Boolean = value.length in 8..1024 &&
    value.startsWith("xox") && value.none { it.isWhitespace() || it.isISOControl() }

class SlackIdentityVerifier(private val client: HttpClient) : IdentityVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verify(slackToken: String): VerifiedIdentity? {
        if (!validSlackToken(slackToken)) return null
        val body = try {
            withTimeout(VERIFY_TIMEOUT_MILLIS) {
                client.submitForm(url = AUTH_TEST, formParameters = Parameters.Empty) {
                    header("Authorization", "Bearer $slackToken")
                }.bodyAsText()
            }
        } catch (_: TimeoutCancellationException) {
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return parseAuthTest(json, body)
    }

    private companion object {
        const val AUTH_TEST = "https://slack.com/api/auth.test"
        const val VERIFY_TIMEOUT_MILLIS = 8_000L
    }
}

internal fun parseAuthTest(json: Json, body: String): VerifiedIdentity? {
    val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    if (fields["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
    val userId = fields["user_id"]?.jsonPrimitive?.content?.takeIf(::validIdentity) ?: return null
    val teamIds = listOfNotNull(
        fields["team_id"]?.jsonPrimitive?.content,
        fields["enterprise_id"]?.jsonPrimitive?.content,
    ).filter(::validIdentity).toSet()
    return if (teamIds.isEmpty()) null else VerifiedIdentity(userId, teamIds)
}
