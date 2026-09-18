package com.scooter.slackwear.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal class DeviceSignIn(
    private val client: HttpClient,
    private val flows: PendingAuthStore = PendingAuthStore(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun flow(code: String): PendingAuth? = flows.get(code)

    suspend fun requestLogin(code: String, email: String): Result<Unit> {
        val flow = flows.get(code) ?: return Result.failure(DeviceSignInException("unknown code"))
        return completer(flow).requestLogin(email)
    }

    suspend fun completeCode(code: String, accessCode: String): Boolean {
        val flow = flows.get(code) ?: return false
        val token = completer(flow).completeCode(accessCode) ?: return false
        flow.magicToken = token
        return true
    }

    private fun completer(flow: PendingAuth): SamlCompleter =
        flow.saml ?: synchronized(flow) {
            flow.saml ?: SamlCompleter(flow.ssoUrl).also { flow.saml = it }
        }

    fun capture(rawUrl: String): Boolean {
        val token = extractMagicToken(rawUrl) ?: return false
        val flow = flows.latest() ?: return false
        flow.magicToken = token
        return true
    }

    suspend fun start(domain: String): Started {
        val team = findTeam(domain)
        if (!team.sso) {
            throw DeviceSignInException("$domain does not use SSO")
        }

        val ssoBody = client.submitForm(
            url = "$API/auth.sso",
            formParameters = Parameters.build {
                append("team", team.id)
                append("redir", SSO_REDIRECT)
                append("v2", "true")
            },
        ).bodyAsText()
        val ssoUrl = field(ssoBody, "url")
            ?: throw DeviceSignInException("Slack did not return an SSO url")

        val code = flows.add(PendingAuth(team.id, ssoUrl))
        return Started(code, ssoUrl)
    }

    private suspend fun findTeam(domain: String): Team {
        val body = client.submitForm(
            url = "$API/auth.findTeam",
            formParameters = Parameters.build { append("domain", domain) },
        ).bodyAsText()
        if (field(body, "ok") != "true") {
            throw DeviceSignInException("Slack could not resolve \"$domain\"")
        }
        return Team(
            id = field(body, "team_id") ?: throw DeviceSignInException("No team id for \"$domain\""),
            sso = field(body, "sso") == "true",
        )
    }

    private fun field(body: String, key: String): String? =
        json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.content

    private companion object {
        const val API = "https://slack.com/api"
        const val SSO_REDIRECT = "ul-slack://"
    }
}

internal data class PendingAuth(
    val teamId: String,
    val ssoUrl: String,
    @Volatile var magicToken: String? = null,
    @Volatile var saml: SamlCompleter? = null,
)

internal data class Team(val id: String, val sso: Boolean)

internal data class Started(val code: String, val ssoUrl: String)

class DeviceSignInException(message: String) : RuntimeException(message)

@Serializable
data class AuthStartRequest(val domain: String)

@Serializable
data class AuthStartResponse(val code: String, val ssoUrl: String)

@Serializable
data class AuthStatusResponse(
    val ready: Boolean,
    val teamId: String? = null,
    val magicToken: String? = null,
    val error: String? = null,
)

internal class PendingAuthStore : AutoCloseable {

    private val random = SecureRandom()
    private val flows = ConcurrentHashMap<String, PendingAuth>()

    @Volatile
    private var latestCode: String? = null

    fun add(flow: PendingAuth): String {
        val code = newCode()
        flows[code] = flow
        latestCode = code
        return code
    }

    fun get(code: String): PendingAuth? = flows[code]

    fun latest(): PendingAuth? = latestCode?.let(flows::get)

    override fun close() {
        flows.clear()
        latestCode = null
    }

    private fun newCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

fun extractMagicToken(raw: String): String? {
    val url = raw.trim().ifBlank { return null }

    if (url.none { it == '/' || it == ':' }) return url

    val marker = "magic-login-sso"
    val index = url.indexOf(marker)
    if (index < 0) return null

    val token = url.substring(index + marker.length)
        .trimStart('/')
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    return token.takeIf { it.isNotBlank() }
}

internal fun signInPage(code: String): String = page(code, """
    <p>What is the Hack Club email your Slack uses? We'll email you a sign-in link.</p>
    <form method="post" action="/auth/$code/login">
      <input name="email" type="email" placeholder="you@hackclub.com" autocomplete="off" autofocus required>
      <button type="submit">Email my sign-in link</button>
    </form>
""")

internal fun magicLinkPrompt(code: String): String = page(code, """
    <p>Sent. Open the email from <b>Hack Club</b> and copy the <b>sign-in code</b>, then paste it here.</p>
    <form method="post" action="/auth/$code/code">
      <input name="access_code" type="text" inputmode="numeric" placeholder="123456" autocomplete="one-time-code" required>
      <button type="submit">Finish sign-in</button>
    </form>
    <p><small>The watch completes by itself once this succeeds.</small></p>
""")

internal fun donePage(code: String): String = page(code, """
    <p>Sign-in complete. Your watch is finishing up now - you can close this tab.</p>
""")

private fun page(code: String, body: String): String = """
    <!doctype html>
    <html>
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Sign in - Slacklet</title>
      <link rel="icon" href="data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20108%20108%22%3E%3Crect%20width%3D%22108%22%20height%3D%22108%22%20rx%3D%2224%22%20fill%3D%22%23101214%22%2F%3E%3Cpath%20fill%3D%22%235A1F5B%22%20d%3D%22M50.9%2C18.56%20L57.1%2C18.56%20Q62.72%2C18.56%2062.72%2C24.18%20L62.72%2C28.13%20Q62.72%2C33.75%2057.1%2C33.75%20L50.9%2C33.75%20Q45.28%2C33.75%2045.28%2C28.13%20L45.28%2C24.18%20Q45.28%2C18.56%2050.9%2C18.56%20Z%22%2F%3E%3Cpath%20fill%3D%22%235A1F5B%22%20d%3D%22M50.9%2C74.25%20L57.1%2C74.25%20Q62.72%2C74.25%2062.72%2C79.87%20L62.72%2C83.82%20Q62.72%2C89.44%2057.1%2C89.44%20L50.9%2C89.44%20Q45.28%2C89.44%2045.28%2C83.82%20L45.28%2C79.87%20Q45.28%2C74.25%2050.9%2C74.25%20Z%22%2F%3E%3Cpath%20fill%3D%22%234A154B%22%20d%3D%22M25.88%2C54.0%20A28.12%2C28.12%200%201%2C0%2082.12%2C54.0%20A28.12%2C28.12%200%201%2C0%2025.88%2C54.0%20Z%22%2F%3E%3Cpath%20fill%3D%22none%22%20stroke%3D%22%238B4A8C%22%20stroke-width%3D%221.41%22%20d%3D%22M25.88%2C54.0%20A28.12%2C28.12%200%201%2C0%2082.12%2C54.0%20A28.12%2C28.12%200%201%2C0%2025.88%2C54.0%20Z%22%2F%3E%3Cpath%20fill%3D%22%231A1D21%22%20d%3D%22M30.09%2C54.0%20A23.91%2C23.91%200%201%2C0%2077.91%2C54.0%20A23.91%2C23.91%200%201%2C0%2030.09%2C54.0%20Z%22%2F%3E%3Cpath%20fill%3D%22%23FFFFFF%22%20d%3D%22M46.41%2C60.75%20L46.41%2C70.59%20L53.72%2C62.16%20Z%22%2F%3E%3Cpath%20fill%3D%22%23FFFFFF%22%20d%3D%22M42.739999999999995%2C42.75%20L65.25999999999999%2C42.75%20Q70.88%2C42.75%2070.88%2C48.37%20L70.88%2C57.38%20Q70.88%2C63.0%2065.25999999999999%2C63.0%20L42.739999999999995%2C63.0%20Q37.12%2C63.0%2037.12%2C57.38%20L37.12%2C48.37%20Q37.12%2C42.75%2042.739999999999995%2C42.75%20Z%22%2F%3E%3Cpath%20fill%3D%22%234A154B%22%20d%3D%22M51.38%2C46.69%20L53.91%2C46.69%20L51.28%2C59.06%20L48.75%2C59.06%20Z%22%2F%3E%3Cpath%20fill%3D%22%234A154B%22%20d%3D%22M56.72%2C46.69%20L59.25%2C46.69%20L56.62%2C59.06%20L54.09%2C59.06%20Z%22%2F%3E%3Cpath%20fill%3D%22%234A154B%22%20d%3D%22M48.03%2C49.22%20L61.53%2C49.22%20L60.99%2C51.75%20L47.49%2C51.75%20Z%22%2F%3E%3Cpath%20fill%3D%22%234A154B%22%20d%3D%22M47.01%2C54.0%20L60.51%2C54.0%20L59.97%2C56.53%20L46.47%2C56.53%20Z%22%2F%3E%3Cpath%20fill%3D%22%23E01E5A%22%20stroke%3D%22%231A1D21%22%20stroke-width%3D%221.69%22%20d%3D%22M67.22%2C35.16%20A5.62%2C5.62%200%201%2C0%2078.46000000000001%2C35.16%20A5.62%2C5.62%200%201%2C0%2067.22%2C35.16%20Z%22%2F%3E%3C%2Fsvg%3E">
      <style>
        body { font-family: -apple-system, system-ui, sans-serif; max-width: 40em; margin: 3em auto; padding: 0 1em; color: #1a1d21; line-height: 1.5; }
        h1 { font-size: 1.4em; }
        .logo { margin: 0 0 .1em; }
        code { background: #f4f4f4; padding: 0 .25em; border-radius: 4px; word-break: break-all; }
        input { width: 100%; box-sizing: border-box; font: inherit; padding: .6em; margin: .4em 0 1em; }
        button { font: inherit; padding: .6em 1.3em; background: #4A154B; color: #fff; border: 0; border-radius: 6px; cursor: pointer; }
        small { color: #6b7c87; }
      </style>
    </head>
    <body>
      <p class="logo"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="88" height="88" role="img" aria-label="Slacklet"><path fill="#5A1F5B" d="M50.9,18.56 L57.1,18.56 Q62.72,18.56 62.72,24.18 L62.72,28.13 Q62.72,33.75 57.1,33.75 L50.9,33.75 Q45.28,33.75 45.28,28.13 L45.28,24.18 Q45.28,18.56 50.9,18.56 Z"/><path fill="#5A1F5B" d="M50.9,74.25 L57.1,74.25 Q62.72,74.25 62.72,79.87 L62.72,83.82 Q62.72,89.44 57.1,89.44 L50.9,89.44 Q45.28,89.44 45.28,83.82 L45.28,79.87 Q45.28,74.25 50.9,74.25 Z"/><path fill="#4A154B" d="M25.88,54.0 A28.12,28.12 0 1,0 82.12,54.0 A28.12,28.12 0 1,0 25.88,54.0 Z"/><path fill="none" stroke="#8B4A8C" stroke-width="1.41" d="M25.88,54.0 A28.12,28.12 0 1,0 82.12,54.0 A28.12,28.12 0 1,0 25.88,54.0 Z"/><path fill="#1A1D21" d="M30.09,54.0 A23.91,23.91 0 1,0 77.91,54.0 A23.91,23.91 0 1,0 30.09,54.0 Z"/><path fill="#FFFFFF" d="M46.41,60.75 L46.41,70.59 L53.72,62.16 Z"/><path fill="#FFFFFF" d="M42.739999999999995,42.75 L65.25999999999999,42.75 Q70.88,42.75 70.88,48.37 L70.88,57.38 Q70.88,63.0 65.25999999999999,63.0 L42.739999999999995,63.0 Q37.12,63.0 37.12,57.38 L37.12,48.37 Q37.12,42.75 42.739999999999995,42.75 Z"/><path fill="#4A154B" d="M51.38,46.69 L53.91,46.69 L51.28,59.06 L48.75,59.06 Z"/><path fill="#4A154B" d="M56.72,46.69 L59.25,46.69 L56.62,59.06 L54.09,59.06 Z"/><path fill="#4A154B" d="M48.03,49.22 L61.53,49.22 L60.99,51.75 L47.49,51.75 Z"/><path fill="#4A154B" d="M47.01,54.0 L60.51,54.0 L59.97,56.53 L46.47,56.53 Z"/><path fill="#E01E5A" stroke="#1A1D21" stroke-width="1.69" d="M67.22,35.16 A5.62,5.62 0 1,0 78.46000000000001,35.16 A5.62,5.62 0 1,0 67.22,35.16 Z"/></svg></p>
      <h1>Sign in to Slack</h1>
      <p>Code <b>$code</b></p>
      $body
    </body>
    </html>
""".trimIndent()
