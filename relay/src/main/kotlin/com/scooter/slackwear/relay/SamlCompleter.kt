package com.scooter.slackwear.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("Saml")

internal class SamlCompleter(
    private val ssoUrl: String,
) : AutoCloseable {

    private val client: HttpClient = HttpClient(CIO) {
        install(HttpCookies)
        followRedirects = false
    }

    @Volatile private var codeForm: Form? = null
    @Volatile private var codeBase: String? = null

    private val codeRequested = AtomicBoolean(false)

    suspend fun requestLogin(email: String): Result<Unit> {
        if (!codeRequested.compareAndSet(false, true)) {
            return Result.success(Unit)
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                var page: Page = get(ssoUrl)
                repeat(MAX_HOPS) {
                    val location = page.location
                    if (location != null) {
                        page = get(resolve(location, page.url))
                        return@repeat
                    }
                    val forms = Form.findAll(page.html)
                    val emailForm = forms.firstOrNull { "email" in it.fields }
                    val skipForm = forms.firstOrNull { it.action.contains("/webauthn/skip") }
                    val codeEntry = forms.firstOrNull { "code" in it.fields }

                    when {
                        emailForm != null -> {
                            val data = emailForm.fields + mapOf("email" to email, "commit" to "true")
                            val resp = post(emailForm.resolveAction(page.url), data)
                            page = Page(resp, resp.bodyAsText())
                        }
                        skipForm != null -> {
                            val resp = post(skipForm.resolveAction(page.url), skipForm.fields)
                            page = Page(resp, resp.bodyAsText())
                        }
                        codeEntry != null -> {
                            codeForm = codeEntry
                            codeBase = page.url
                            return@runCatching
                        }
                        else -> return@runCatching
                    }
                }
            }
        }
        if (result.isFailure) codeRequested.set(false)
        return result
    }

    suspend fun completeCode(code: String): String? = withContext(Dispatchers.IO) {
        val form = codeForm ?: return@withContext null
        val base = codeBase ?: return@withContext null
        val data = form.fields + mapOf("code" to code, "commit" to "true")
        val resp = post(form.resolveAction(base), data)
        walkToToken(Page(resp, resp.bodyAsText()))
    }

    private suspend fun walkToToken(start: Page): String? {
        var page = start
        repeat(MAX_HOPS) {
            val location = page.location
            if (location != null) {
                if (location.isHandback()) return extractMagicToken(location)
                page = get(resolve(location, page.url))
                return@repeat
            }
            val form = Form.parse(page.html) ?: return null
            val response = post(form.resolveAction(page.url), form.fields)
            val target = response.headers[HttpHeaders.Location]
            if (target != null && target.isHandback()) return extractMagicToken(target)
            page = Page(response, response.bodyAsText())
        }
        return null
    }

    override fun close() = client.close()

    private suspend fun get(url: String): Page {
        val resp: HttpResponse = client.get(url) { header(HttpHeaders.UserAgent, BROWSER_UA) }
        logger.info("SAML GET {} -> {}", short(url), resp.status)
        return Page(resp, resp.bodyAsText())
    }

    private suspend fun post(url: String, params: Map<String, String>): HttpResponse {
        logger.info("SAML POST {} (fields={})", short(url), params.keys)
        return client.submitForm(url = url, formParameters = Parameters.build {
            params.forEach { (k, v) -> append(k, v) }
        }) { header(HttpHeaders.UserAgent, BROWSER_UA) }
    }

    private fun resolve(raw: String, base: String): String =
        if (raw.startsWith("http")) raw else java.net.URI(base).resolve(raw).toString()

    private fun short(url: String): String = url.substringBefore('?').removePrefix("https://auth.hackclub.com")

    private companion object {
        const val MAX_HOPS = 15
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}

internal class Page(
    val response: HttpResponse,
    val html: String,
) {
    val url: String = response.call.request.url.toString()

    val location: String? = response.headers[HttpHeaders.Location] ?: metaRefresh()

    private fun metaRefresh(): String? =
        Regex("""<meta[^>]*http-equiv=["']refresh["'][^>]*content=["']\d+;\s*url=([^"']+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
}

internal data class Form(val action: String, val fields: Map<String, String>) {

    fun resolveAction(base: String): String =
        if (action.startsWith("http")) action
        else java.net.URI(base).resolve(action).toString()

    companion object {
        private val FORM_RE = Regex(
            "<form[^>]*?action=[\"']([^\"']+)[\"'][^>]*>(.*?)</form>",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val INPUT_RE = Regex("<input[^>]*>")
        private val NAME_RE = Regex("name=[\"']([^\"']+)[\"']")
        private val VALUE_RE = Regex("value=[\"']([^\"']*)[\"']")

        fun parse(html: String): Form? = findAll(html).firstOrNull()

        fun findAll(html: String): List<Form> {
            val result = mutableListOf<Form>()
            for (m in FORM_RE.findAll(html)) {
                val action = m.groupValues[1]
                val inner = m.groupValues[2]
                val fields = linkedMapOf<String, String>()
                for (im in INPUT_RE.findAll(inner)) {
                    val tag = im.value
                    val name = NAME_RE.find(tag)?.groupValues?.get(1) ?: continue
                    val value = VALUE_RE.find(tag)?.groupValues?.get(1) ?: ""
                    if (name !in fields) fields[name] = value
                }
                result += Form(action, fields)
            }
            return result
        }
    }
}

private fun String.isHandback(): Boolean =
    (startsWith("ul-slack") || startsWith("slack://") || contains("magic-login-sso"))
