package com.scooter.slackwear.di

import android.content.Context
import com.scooter.slackwear.BuildConfig
import com.scooter.slackwear.core.auth.InternalAuthApi
import com.scooter.slackwear.core.auth.InternalSlackAuthenticator
import com.scooter.slackwear.core.auth.TokenStore
import com.scooter.slackwear.core.data.MentionNames
import com.scooter.slackwear.core.data.flatten
import com.scooter.slackwear.core.data.mentionedUserIds
import com.scooter.slackwear.core.data.InternalActivityRepository
import com.scooter.slackwear.core.data.InternalEmojiRepository
import com.scooter.slackwear.core.data.InternalHuddleRepository
import com.scooter.slackwear.core.data.InternalSidebarRepository
import com.scooter.slackwear.core.data.InternalThreadRepository
import com.scooter.slackwear.core.data.InternalUnreadRepository
import com.scooter.slackwear.core.data.SlackConversationRepository
import com.scooter.slackwear.core.data.LocalPreferenceRepository
import com.scooter.slackwear.core.data.LocalSettingsRepository
import com.scooter.slackwear.core.data.SlackSearchRepository
import com.scooter.slackwear.core.data.SlackMessageRepository
import com.scooter.slackwear.core.database.SlackDatabase
import com.scooter.slackwear.core.data.toActivityJson
import com.scooter.slackwear.core.data.toActivityUnreadJson
import com.scooter.slackwear.core.model.repository.ActivityEntryInput
import com.scooter.slackwear.core.model.repository.ActivityMarkReadRequest
import com.scooter.slackwear.core.model.repository.ActivityMutationGateway
import com.scooter.slackwear.core.network.unwrap
import com.scooter.slackwear.core.model.repository.ActivityRepository
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.EmojiRepository
import com.scooter.slackwear.core.model.repository.HuddleRepository
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import com.scooter.slackwear.core.model.repository.RelaySignInGateway
import com.scooter.slackwear.core.model.repository.RelaySignInStarted
import com.scooter.slackwear.core.model.repository.RelaySignInStatus
import com.scooter.slackwear.core.model.repository.SearchRepository
import com.scooter.slackwear.core.model.repository.SettingsRepository
import com.scooter.slackwear.core.model.repository.SidebarRepository
import com.scooter.slackwear.core.model.repository.ThreadRepository
import com.scooter.slackwear.core.model.repository.UnreadRepository
import com.scooter.slackwear.feature.conversation.WatchMediaStore
import com.scooter.slackwear.feature.notifications.PushNotifier
import com.scooter.slackwear.feature.notifications.PushRegistrar
import com.scooter.slackwear.feature.notifications.PushRegistrationCoordinator
import kotlinx.coroutines.flow.first
import com.scooter.slackwear.feature.notifications.RelayApi
import com.scooter.slackwear.feature.notifications.EncryptedRelayBindingStore
import com.scooter.slackwear.feature.notifications.NotificationAccount
import com.scooter.slackwear.feature.notifications.NotificationPolicy
import com.scooter.slackwear.feature.notifications.SharedPreferencesDeliveryLog
import com.scooter.slackwear.feature.notifications.RelayBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.scooter.slackwear.feature.notifications.AuthStartRequest
import com.scooter.slackwear.push.NotificationReplyReceiver
import android.content.Intent
import com.scooter.slackwear.MainActivity
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.SlackApiFactory
import com.scooter.slackwear.core.network.ClientApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val authedClient: OkHttpClient by lazy {
        SlackApiFactory.okHttpClient(
            tokenProvider = tokenStore::currentToken,
            deviceTokenProvider = tokenStore::currentSecondaryToken,
        )
    }

    private val anonymousClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    private val relayClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(15))
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }

    val slackApi: SlackApi by lazy { SlackApiFactory.create(authedClient) }

    val clientApi: ClientApi by lazy { SlackApiFactory.createClientApi(authedClient) }

    private val internalAuthApi: InternalAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://slack.com/api/")
            .callFactory(anonymousClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(InternalAuthApi::class.java)
    }

    val internalAuthenticator: InternalSlackAuthenticator by lazy {
        InternalSlackAuthenticator(
            api = internalAuthApi,
            tokenStore = tokenStore,
        )
    }

    private val database: SlackDatabase by lazy { SlackDatabase.create(appContext) }

    val conversationRepository: ConversationRepository by lazy {
        SlackConversationRepository(
            clientApi = clientApi,
            slackApi = slackApi,
            conversationDao = database.conversationDao(),
            activityDao = database.activityDao(),
            userDao = database.userDao(),
        )
    }

    val sidebarRepository: SidebarRepository by lazy {
        InternalSidebarRepository(
            clientApi = clientApi,
            conversationDao = database.conversationDao(),
            userDao = database.userDao(),
            currentUserId = ::currentUserId,
        )
    }

    val unreadRepository: UnreadRepository by lazy {
        InternalUnreadRepository(clientApi = clientApi, conversationDao = database.conversationDao(), slackApi = slackApi)
    }

    val activityRepository: ActivityRepository by lazy {
        InternalActivityRepository(
            clientApi = clientApi,
            slackApi = slackApi,
            activityDao = database.activityDao(),
            conversationDao = database.conversationDao(),
            userDao = database.userDao(),
            mutations = object : ActivityMutationGateway {
                override suspend fun markRead(request: ActivityMarkReadRequest): Long? =
                    clientApi.activityMarkRead(request.toActivityJson()).unwrap().undoKey

                override suspend fun markUnread(entries: List<ActivityEntryInput>) {
                    clientApi.activityMarkUnread(entries.toActivityUnreadJson()).unwrap()
                }
            },
        )
    }

    val emojiRepository: EmojiRepository by lazy {
        InternalEmojiRepository(clientApi = clientApi)
    }

    val threadRepository: ThreadRepository by lazy {
        InternalThreadRepository(clientApi = clientApi)
    }

    val huddleRepository: HuddleRepository by lazy {
        InternalHuddleRepository(clientApi = clientApi, userDao = database.userDao())
    }

    val messageRepository: MessageRepository by lazy {
        SlackMessageRepository(
            api = slackApi,

            uploadClient = anonymousClient,
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            currentUserId = ::currentUserId,
        )
    }

    val preferenceRepository: PreferenceRepository by lazy {
        LocalPreferenceRepository(
            usageDao = database.usageDao(),
            customEmojiDao = database.customEmojiDao(),
            api = slackApi,
        )
    }

    val searchRepository: SearchRepository by lazy {
        SlackSearchRepository(clientApi = clientApi, userDao = database.userDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        LocalSettingsRepository(
            context = appContext,
            api = slackApi,
            usageDao = database.usageDao(),
            onSignOut = {

                tokenStore.currentToken()?.let { token ->
                    runCatching { internalAuthApi.signOut(token = token, reason = "user_signout") }
                }

                runCatching { database.clearAllTables() }
                tokenStore.clear()
            },
        )
    }

    private val relayApi: RelayApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.RELAY_URL)
            .callFactory(relayClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RelayApi::class.java)
    }

    val relaySignInGateway: RelaySignInGateway by lazy {
        object : RelaySignInGateway {
            override suspend fun start(domain: String): Result<RelaySignInStarted> = runCatching {
                RelaySignInStarted(relayApi.startAuth(AuthStartRequest(domain)).code)
            }

            override suspend fun poll(code: String): Result<RelaySignInStatus> = runCatching {
                val status = relayApi.authStatus(code)
                RelaySignInStatus(status.ready, status.teamId, status.magicToken, status.error)
            }
        }
    }

    val pushRegistrar: PushRegistrar by lazy {
        PushRegistrar(
            relay = relayApi,
            bindings = relayBindingStore,
            currentAccount = ::notificationAccount,
            currentSlackToken = { tokenStore.session.value?.accessToken },
        )
    }

    val pushRegistration by lazy {
        val registrar = pushRegistrar
        PushRegistrationCoordinator(
            sessions = tokenStore.session,
            currentSession = { tokenStore.session.value },
            isValidSession = {
                it.accessToken.isNotBlank() && it.userId.isNotBlank() && it.teamId.isNotBlank()
            },
            currentSettings = { settingsRepository.observeNotificationSettings().first() },
            register = { _, settings ->
                registrar.register(settings)
            },
        )
    }

    val relayBindingStore: RelayBindingStore by lazy { EncryptedRelayBindingStore(appContext) }

    val watchMediaStore: WatchMediaStore by lazy { WatchMediaStore(appContext) }

    val notificationPolicy: NotificationPolicy by lazy {
        NotificationPolicy(SharedPreferencesDeliveryLog(appContext), relayBindingStore::current, ::notificationAccount).also { policy ->
            applicationScope.launch {
                settingsRepository.observeNotificationSettings().collect(policy::settings)
            }
        }
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun notificationAccount(): NotificationAccount? = tokenStore.session.value
        ?.takeIf { it.accessToken.isNotBlank() }
        ?.let { NotificationAccount(it.teamId, it.userId) }

    val pushNotifier: PushNotifier by lazy {
        PushNotifier(
            context = appContext,
            resolveTitle = ::notificationTitle,
            resolveText = ::notificationBody,
            openIntent = { conversationId ->
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                }
            },
            replyIntent = { conversationId, threadTs, registrationId, teamId, userId ->
                Intent(appContext, NotificationReplyReceiver::class.java).apply {
                    action = NotificationReplyReceiver.ACTION_REPLY
                    putExtra(PushNotifier.EXTRA_CONVERSATION_ID, conversationId)
                    putExtra(PushNotifier.EXTRA_THREAD_TS, threadTs)
                    putExtra(PushNotifier.EXTRA_REGISTRATION_ID, registrationId)
                    putExtra(PushNotifier.EXTRA_TEAM_ID, teamId)
                    putExtra(PushNotifier.EXTRA_USER_ID, userId)
                }
            },
        )
    }

    private fun notificationBody(text: String): String {
        val users = database.userDao()
        val names = mentionedUserIds(text)
            .mapNotNull { id -> users.displayNameOfBlocking(id)?.let { id to it } }
            .toMap()
        return flatten(text, MentionNames(currentUserId(), names))
    }

    private fun notificationTitle(conversationId: String, authorId: String): String {
        val author = database.userDao().displayNameOfBlocking(authorId)
        val conversation = database.conversationDao().nameOfBlocking(conversationId)

        return when {
            author != null && conversation != null -> "$author in #$conversation"
            author != null -> author
            conversation != null -> "#$conversation"
            else -> "Slacklet"
        }
    }

    private fun currentUserId(): String = tokenStore.session.value?.userId.orEmpty()
}
