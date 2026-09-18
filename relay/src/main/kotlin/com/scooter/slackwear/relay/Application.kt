package com.scooter.slackwear.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("Relay")

fun main() {
    val config = Config.fromEnvironment()
    logger.info("Starting relay on port ${config.port}")
    embeddedServer(Netty, port = config.port) { relayModule(config) }.start(wait = true)
}

fun Application.relayModule(
    config: Config,
    pushSender: PushSender? = null,
    deliveryStore: DeliveryStore = InMemoryDeliveryStore(),
    dndChecker: DndChecker? = null,
    identityVerifier: IdentityVerifier? = null,
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val httpClient = HttpClient(CIO)

    val devices = DeviceStore(config.stateDirectory)
    val push = pushSender ?: FcmPushSender(config.firebaseCredentialsPath)
    val router = EventRouter(devices)
    val delivery = DeliveryService(devices, push, dndChecker ?: WatchDndChecker(), deliveryStore)
    val signIn = DeviceSignIn(httpClient)
    val identities = identityVerifier ?: SlackIdentityVerifier(httpClient)

    val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    worker.launch { delivery.run() }
    monitor.subscribe(ApplicationStopped) {
        worker.cancel()
        httpClient.close()
    }

    install(ContentNegotiation) { json(json) }
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<DeviceSignInException> { call, cause ->
            logger.warn("Device sign-in failed: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, cause.message ?: "sign-in failed")
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled failure", cause)
            call.respond(HttpStatusCode.InternalServerError, "error")
        }
    }

    routing {
        get("/health") {
            call.respondText(if (push.isReady) "ok" else "ok (push unavailable)")
        }

        post("/slack/events") {
            val rawBody = call.receiveText()

            val verified = SlackSignature.isValid(
                signingSecret = config.slackSigningSecret,
                timestampHeader = call.request.headers["X-Slack-Request-Timestamp"],
                signatureHeader = call.request.headers["X-Slack-Signature"],
                rawBody = rawBody,
            )
            if (!verified) {
                logger.warn("Rejected an unsigned or stale callback")
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val envelope = runCatching { json.decodeFromString<SlackEventEnvelope>(rawBody) }
                .getOrElse {
                    logger.warn("Unparseable event payload", it)
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

            if (envelope.type == "url_verification") {
                call.respondText(envelope.challenge.orEmpty())
                return@post
            }

            if (envelope.type == "event_callback" &&
                (envelope.eventId.isNullOrBlank() || envelope.teamId.isNullOrBlank())
            ) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val queued = delivery.enqueue(router.plan(envelope))
            call.respond(if (queued) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }

        notificationEnrollmentRoutes(devices, identities)

        post("/devices") { call.respond(HttpStatusCode.Gone) }
        post("/devices/settings") { call.respond(HttpStatusCode.Gone) }

        post("/auth/start") {
            val started = signIn.start(call.receive<AuthStartRequest>().domain)
            call.respond(AuthStartResponse(started.code, started.ssoUrl))
        }

        get("/auth/{code}/status") {
            val flow = signIn.flow(call.parameters["code"].orEmpty())
            if (flow == null) {
                call.respond(HttpStatusCode.NotFound, AuthStatusResponse(ready = false, error = "unknown code"))
            } else {
                val token = flow.magicToken
                call.respond(
                    AuthStatusResponse(
                        ready = token != null,
                        teamId = flow.teamId,
                        magicToken = token,
                    ),
                )
            }
        }

        post("/auth/{code}/complete") {
            val code = call.parameters["code"].orEmpty()
            val flow = signIn.flow(code)
            if (flow == null) {
                call.respond(HttpStatusCode.NotFound, "This sign-in link has expired - start again on the watch.")
                return@post
            }
            val token = extractMagicToken(call.receiveParameters()["url"].orEmpty())
            if (token == null) {
                call.respond(HttpStatusCode.BadRequest, "Could not find a magic token in that address.")
                return@post
            }
            flow.magicToken = token
            logger.info("Captured magic token for auth flow $code")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/capture") {
            val url = call.receiveParameters()["url"].orEmpty()
            if (!signIn.capture(url)) {
                call.respond(HttpStatusCode.BadRequest, "No token or no sign-in in progress.")
                return@post
            }
            logger.info("Captured hand-back token automatically")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/{code}/login") {
            val code = call.parameters["code"].orEmpty()
            val email = call.receiveParameters()["email"].orEmpty()
            signIn.requestLogin(code, email).fold(
                onSuccess = { call.respondText(magicLinkPrompt(code), ContentType.Text.Html) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, it.message ?: "Could not start sign-in") },
            )
        }

        post("/auth/{code}/code") {
            val code = call.parameters["code"].orEmpty()
            val accessCode = call.receiveParameters()["access_code"].orEmpty()
            if (!signIn.completeCode(code, accessCode)) {
                call.respond(HttpStatusCode.BadRequest, "That code did not complete sign-in.")
                return@post
            }
            call.respondText(donePage(code), ContentType.Text.Html)
        }

        get("/auth/{code}") {
            val code = call.parameters["code"].orEmpty()
            if (signIn.flow(code) == null) {
                call.respondText("This sign-in link has expired - start again on the watch.", status = HttpStatusCode.Gone)
                return@get
            }
            call.respondText(signInPage(code), ContentType.Text.Html)
        }
    }
}
