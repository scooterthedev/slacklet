package com.scooter.slackwear.core.auth

import android.net.Uri
import com.scooter.slackwear.core.network.unwrap

class InternalSlackAuthenticator(
    private val api: InternalAuthApi,
    private val tokenStore: TokenStore,
) {

    suspend fun resolveWorkspace(domain: String): Result<FindTeamResponse> = runCatching {
        api.findTeam(domain.trim()).unwrap()
    }

    suspend fun prepareSso(domain: String): Result<SsoReady> = runCatching {
        val team = api.findTeam(domain.trim()).unwrap()
        val teamId = team.teamId
            ?: throw AuthException("Slack couldn't find \"$domain\"")
        if (!team.sso) {
            throw AuthException("$domain doesn't use SSO - it needs a different sign-in path")
        }
        val url = api.sso(team = teamId, redir = SSO_REDIRECT).unwrap().url
            ?: throw AuthException("Slack did not return an SSO url")
        SsoReady(teamId = teamId, url = url)
    }

    suspend fun ssoUrl(team: String, redir: String): Result<String> = runCatching {
        val url = api.sso(team = team, redir = redir).unwrap().url
            ?: throw AuthException("Slack did not return an SSO url")
        url
    }

    suspend fun requestMagicLink(email: String, team: String? = null): Result<Unit> = runCatching {

        api.emailToken(user = email, email = email, team = team).unwrap().let { }
    }

    suspend fun redeemSsoToken(teamId: String, magicToken: String, domain: String): Result<SlackSession> =
        runCatching {
            val http = api.loginMagicSso(team = teamId, magicToken = magicToken)
            val body = http.body() ?: throw AuthException("Slack returned an empty sign-in response")
            val d = dCookieFrom(http.headers().values("Set-Cookie"))
            complete(body.unwrap(), domain, d)
        }

    suspend fun redeemMagicToken(team: String, magicToken: String): Result<SlackSession> =
        runCatching {
            val http = api.loginMagic(team = team, magicToken = magicToken)
            val response = http.body() ?: throw AuthException("Slack returned an empty sign-in response")
            val unwrapped = response.unwrap()

            unwrapped.token?.takeIf(String::isNotBlank)
                ?: throw AuthException(
                    unwrapped.redir?.let { "Slack wants another step: $it" }
                        ?: unwrapped.reason
                        ?: "Slack returned no session token",
                )

            complete(unwrapped, team, dCookieFrom(http.headers().values("Set-Cookie")))
        }

    fun signOut() = tokenStore.clear()

    private fun dCookieFrom(setCookies: List<String>): String? =
        setCookies.firstNotNullOfOrNull { raw ->
            raw.split(';')
                .map(String::trim)
                .firstOrNull { it.startsWith("d=") }
                ?.substringAfter('=')
                ?.takeIf(String::isNotBlank)
        }

    private fun complete(response: TokenAuthResponse, domain: String, secondaryToken: String?): SlackSession {
        if (!response.ok) throw AuthException(response.error ?: "sign_in_failed")

        val token = response.token?.takeIf(String::isNotBlank)
            ?: throw AuthException("Slack returned no session token")
        val teamId = response.teamId
            ?: throw AuthException("Slack returned no team id")
        val userId = response.userId
            ?: throw AuthException("Slack returned no user id")

        return SlackSession(
            accessToken = token,
            userId = userId,
            teamId = teamId,
            teamName = domain,
            tokenType = SlackTokenType.CLIENT,
            secondaryToken = secondaryToken,
            teamDomain = domain,
        ).also(tokenStore::save)
    }

    companion object {
        private const val TAG = "SlackAuth"

        const val SSO_REDIRECT = "ul-slack://"

        fun magicTokenFromRedirect(rawUrl: String): String? {
            val uri = Uri.parse(rawUrl)
            val segments = uri.pathSegments
            val i = segments.indexOf("magic-login-sso")
            if (i >= 0 && i + 1 < segments.size) {
                segments[i + 1].takeIf(String::isNotBlank)?.let { return it }
            }
            return uri.getQueryParameter("magic_token")
                ?: uri.getQueryParameter("token")
                ?: uri.fragment?.let(::tokenFromKeyValuePairs)
        }

        private fun tokenFromKeyValuePairs(raw: String): String? {
            val query = Uri.parse("https://wear/?$raw")
            return query.getQueryParameter("magic_token") ?: query.getQueryParameter("token")
        }
    }
}

data class SsoReady(
    val teamId: String,
    val url: String,
)
