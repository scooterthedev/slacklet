package com.scooter.slackwear.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.scooter.slackwear.core.designsystem.theme.SlackWearTheme
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.network.toNetworkFailure
import com.scooter.slackwear.BuildConfig
import com.scooter.slackwear.di.AppContainer
import com.scooter.slackwear.feature.conversation.ConversationScreen
import com.scooter.slackwear.feature.conversation.ConversationViewModel
import com.scooter.slackwear.feature.conversation.FilePickerScreen
import com.scooter.slackwear.feature.conversation.FilePickerViewModel
import com.scooter.slackwear.feature.conversation.ReactionPicker
import com.scooter.slackwear.feature.conversation.ThreadScreen
import com.scooter.slackwear.feature.conversation.ThreadViewModel
import com.scooter.slackwear.feature.conversation.rememberTextInput
import com.scooter.slackwear.feature.conversation.rememberMessageComposer
import com.scooter.slackwear.feature.home.ActivityScreen
import com.scooter.slackwear.feature.home.ChannelsScreen
import com.scooter.slackwear.feature.home.DirectMessagesScreen
import com.scooter.slackwear.feature.home.HomeViewModel
import com.scooter.slackwear.feature.home.unreadForChannelList
import com.scooter.slackwear.feature.search.SearchScreen
import com.scooter.slackwear.feature.search.SearchViewModel
import com.scooter.slackwear.feature.settings.SettingsScreen
import com.scooter.slackwear.feature.notifications.PushRegistrationStatus
import com.scooter.slackwear.feature.settings.DeliveryUiState
import com.scooter.slackwear.feature.settings.SettingsViewModel
import com.scooter.slackwear.feature.signin.SignInScreen
import com.scooter.slackwear.feature.signin.SignInViewModel

private object Route {
    const val HOME = "home"
    const val CONVERSATION = "conversation/{conversationId}?highlight={highlight}"
    const val REACT = "react/{conversationId}/{messageTs}"
    const val THREAD = "thread/{conversationId}/{threadTs}?highlight={highlight}"
    const val FILES = "files/{conversationId}"
    const val ARG_THREAD_TS = "threadTs"
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_MESSAGE_TS = "messageTs"
    const val ARG_HIGHLIGHT = "highlight"

    fun conversation(id: String, highlight: String? = null) =
        "conversation/$id?highlight=${highlight.orEmpty()}"

    fun react(conversationId: String, messageTs: String) = "react/$conversationId/$messageTs"

    fun thread(conversationId: String, threadTs: String, highlight: String? = null) =
        "thread/$conversationId/$threadTs?highlight=${highlight.orEmpty()}"

    fun files(conversationId: String) = "files/$conversationId"
}

private const val PAGE_CHANNELS = 0
private const val PAGE_DMS = 1
private const val PAGE_ACTIVITY = 2
private const val PAGE_SEARCH = 3
private const val PAGE_SETTINGS = 4
private const val PAGE_COUNT = 5

@Composable
fun SlackWearApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    initialConversationId: String? = null,
) {
    val session by container.tokenStore.session.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    SlackWearTheme {
        AppScaffold(modifier = modifier.fillMaxSize()) {
            if (session == null) {
                SignIn(container = container)
            } else {
                SignedIn(container = container, initialConversationId = initialConversationId)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {  }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun SignIn(container: AppContainer) {
    val viewModel: SignInViewModel = viewModel(
        factory = remember(container) { signInViewModelFactory(container) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val domain by viewModel.domain.collectAsStateWithLifecycle()

    SignInScreen(
        state = state,
        domain = domain,
        onDomainChanged = viewModel::setDomain,
        onSignIn = viewModel::signIn,
        onCancel = viewModel::cancel,
        onDismissError = viewModel::dismissError,
    )
}

@Composable
private fun SignedIn(container: AppContainer, initialConversationId: String? = null) {
    val navController = rememberSwipeDismissableNavController()

    LaunchedEffect(initialConversationId) {
        initialConversationId?.takeIf(String::isNotBlank)?.let {
            navController.navigate(Route.conversation(it))
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Route.HOME,
    ) {
        composable(Route.HOME) {
            Home(
                container = container,
                onOpenConversation = { id -> navController.navigate(Route.conversation(id)) },
                onOpenThread = { conversationId, threadTs ->
                    navController.navigate(Route.thread(conversationId, threadTs))
                },
            )
        }

        composable(
            route = Route.CONVERSATION,
            arguments = listOf(
                navArgument(Route.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(Route.ARG_HIGHLIGHT) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val conversationId = entry.arguments
                ?.getString(Route.ARG_CONVERSATION_ID)
                .orEmpty()

            Conversation(
                container = container,
                conversationId = conversationId,
                highlightTs = entry.arguments?.getString(Route.ARG_HIGHLIGHT)?.takeIf(String::isNotBlank),
                onReact = { messageTs ->
                    navController.navigate(Route.react(conversationId, messageTs))
                },
                onOpenThread = { threadTs ->
                    navController.navigate(Route.thread(conversationId, threadTs))
                },
                onAttach = { navController.navigate(Route.files(conversationId)) },
                onOpenQuoted = { targetId, threadTs, messageTs ->
                    if (threadTs != null) {
                        navController.navigate(Route.thread(targetId, threadTs, highlight = messageTs))
                    } else {
                        navController.navigate(Route.conversation(targetId, highlight = messageTs))
                    }
                },
            )
        }

        composable(
            route = Route.FILES,
            arguments = listOf(navArgument(Route.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) { entry ->
            Files(
                container = container,
                conversationId = entry.arguments?.getString(Route.ARG_CONVERSATION_ID).orEmpty(),
                onSent = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.THREAD,
            arguments = listOf(
                navArgument(Route.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(Route.ARG_THREAD_TS) { type = NavType.StringType },
                navArgument(Route.ARG_HIGHLIGHT) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val conversationId = entry.arguments?.getString(Route.ARG_CONVERSATION_ID).orEmpty()
            Thread(
                container = container,
                conversationId = conversationId,
                threadTs = entry.arguments?.getString(Route.ARG_THREAD_TS).orEmpty(),
                highlightTs = entry.arguments?.getString(Route.ARG_HIGHLIGHT)?.takeIf(String::isNotBlank),
                onReact = { messageTs ->
                    navController.navigate(Route.react(conversationId, messageTs))
                },
            )
        }

        composable(
            route = Route.REACT,
            arguments = listOf(
                navArgument(Route.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(Route.ARG_MESSAGE_TS) { type = NavType.StringType },
            ),
        ) { entry ->
            React(
                container = container,
                conversationId = entry.arguments?.getString(Route.ARG_CONVERSATION_ID).orEmpty(),
                messageTs = entry.arguments?.getString(Route.ARG_MESSAGE_TS).orEmpty(),
                onPicked = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun Home(
    container: AppContainer,
    onOpenConversation: (String) -> Unit,
    onOpenThread: (String, String) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember(container) { homeViewModelFactory(container) },
    )
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val unreadTotal by viewModel.unreadTotal.collectAsStateWithLifecycle()
    val activityViews by viewModel.activityViewFilters.collectAsStateWithLifecycle()
    val activityBadges by viewModel.badges.collectAsStateWithLifecycle()
    val sectionConfig by viewModel.sectionConfig.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val refreshFailures by viewModel.refreshFailures.collectAsStateWithLifecycle()
    val readPending by viewModel.activityReadPending.collectAsStateWithLifecycle()
    val readFailures by viewModel.activityReadFailures.collectAsStateWithLifecycle()
    val activityPageState by viewModel.activityPageState.collectAsStateWithLifecycle()
    val activityUnreadOnly by viewModel.activityUnreadOnly.collectAsStateWithLifecycle()
    val isActivityLoading by viewModel.isActivityLoading.collectAsStateWithLifecycle()
    val threadPageState by viewModel.threadPageState.collectAsStateWithLifecycle()

    val homeEmoji by remember(container) { container.preferenceRepository.observeCustomEmoji() }
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    val unreadConversations = remember(conversations) { conversations.unreadForChannelList() }

    val pagerState = rememberPagerState(
        initialPage = PAGE_CHANNELS,
        pageCount = { PAGE_COUNT },
    )

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                when (page) {
                    PAGE_CHANNELS -> ChannelsScreen(
                        conversations = unreadConversations,
                        onConversationClick = onOpenConversation,
                        sectionConfig = sectionConfig,
                        totalUnread = unreadTotal,
                        refreshFailures = refreshFailures,
                        isLoading = isLoading,
                        onRefresh = viewModel::refresh,
                        customEmoji = homeEmoji,
                    )

                    PAGE_DMS -> DirectMessagesScreen(
                        conversations = conversations,
                        onConversationClick = onOpenConversation,
                        customEmoji = homeEmoji,
                    )

                    PAGE_ACTIVITY -> ActivityScreen(
                        activity = activity,
                        onItemClick = { item ->
                            item.target?.let { target ->
                                viewModel.markActivityRead(item)
                                val threadTs = target.threadTs
                                if (threadTs != null) {
                                    onOpenThread(target.conversationId, threadTs)
                                } else {
                                    onOpenConversation(target.conversationId)
                                }
                            }
                        },
                        views = activityViews,
                        badges = activityBadges,
                        isLoading = isActivityLoading,
                        refreshFailures = refreshFailures,
                        isMarkingRead = readPending.isNotEmpty(),
                        readFailures = readFailures.values.toList(),
                        onRefresh = viewModel::refresh,
                        onRetryRead = viewModel::retryActivityReads,
                        activityPageState = activityPageState,
                        threadPageState = threadPageState,
                        onLoadMore = viewModel::loadMoreActivity,
                        unreadOnly = activityUnreadOnly,
                        onToggleUnreadOnly = viewModel::toggleActivityUnreadOnly,
                    )

                    PAGE_SEARCH -> Search(
                        container = container,
                        onOpenConversation = onOpenConversation,
                    )

                    PAGE_SETTINGS -> Settings(container = container)
                }
            }
        }
    }
}

@Composable
private fun Conversation(
    container: AppContainer,
    conversationId: String,
    onReact: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onAttach: () -> Unit,
    highlightTs: String? = null,

    onOpenQuoted: (conversationId: String, threadTs: String?, messageTs: String) -> Unit = { _, _, _ -> },
) {
    val viewModel: ConversationViewModel = viewModel(
        key = conversationId,
        factory = remember(container, conversationId, highlightTs) {
            conversationViewModelFactory(container, conversationId, highlightTs = highlightTs)
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val quickReplies by viewModel.quickReplies.collectAsStateWithLifecycle()
    val customEmoji by viewModel.customEmoji.collectAsStateWithLifecycle()
    val huddle by viewModel.huddle.collectAsStateWithLifecycle()
    val knocked by viewModel.knocked.collectAsStateWithLifecycle()
    val reaction by viewModel.reaction.collectAsStateWithLifecycle()
    val quoted by viewModel.quoted.collectAsStateWithLifecycle()
    val highlight by viewModel.highlight.collectAsStateWithLifecycle()

    val compose = rememberMessageComposer(
        title = state.title,
        quickReplies = quickReplies,
        onMessage = viewModel::send,
    )
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val pagination by viewModel.pagination.collectAsStateWithLifecycle()

    ConversationScreen(
        state = state,
        onCompose = compose,
        pagination = pagination,
        onLoadOlder = viewModel::loadOlderHistory,
        upload = upload,
        onAttach = onAttach,
        onReact = { message -> onReact(message.ts) },
        onOpenThread = { message -> onOpenThread(message.threadTs ?: message.ts) },
        customEmoji = customEmoji,
        huddle = huddle,
        knocked = knocked,
        onKnock = viewModel::knock,
        onCancelKnock = viewModel::cancelKnock,
        onToggleReaction = { message, emoji -> viewModel.react(message.ts, emoji) },
        reaction = reaction,
        quoted = quoted,
        onOpenQuoted = { card ->

            if (card.threadTs == null && card.conversationId == conversationId) {
                viewModel.highlight(card.ts)
            } else {
                onOpenQuoted(card.conversationId, card.threadTs, card.ts)
            }
        },
        highlightTs = highlight,
    )
}

@Composable
private fun Search(
    container: AppContainer,
    onOpenConversation: (String) -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = remember(container) { viewModelFactory { SearchViewModel(container.searchRepository, failureMapper = { it.toNetworkFailure() }) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startSearch = rememberTextInput(title = "Search Slack", onText = viewModel::search)

    SearchScreen(
        state = state,
        onStartSearch = startSearch,
        onCycleSort = viewModel::cycleSort,
        onOpenConversation = onOpenConversation,
    )
}

@Composable
private fun Settings(container: AppContainer) {
    val viewModel: SettingsViewModel = viewModel(
        factory = remember(container) { viewModelFactory { SettingsViewModel(container.settingsRepository) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val registration by container.pushRegistration.status.collectAsStateWithLifecycle()
    val delivery = DeliveryUiState(
        title = "Notification delivery",
        summary = when (registration) {
            PushRegistrationStatus.Registered -> "This watch receives notifications"
            PushRegistrationStatus.Registering -> "Connecting\u2026"
            PushRegistrationStatus.NoSession -> "Sign in to receive notifications"
            PushRegistrationStatus.Failed -> "Can't reach the relay \u2014 tap to retry"
            else -> "Connecting\u2026"
        },
        canRetry = registration == PushRegistrationStatus.Failed,
    )

    SettingsScreen(
        state = state,
        onUpdate = viewModel::update,
        onToggleSnooze = viewModel::toggleSnooze,
        onClearLearnedReactions = viewModel::clearLearnedReactions,
        onSignOut = viewModel::signOut,
        delivery = delivery,
        onRetryDelivery = { scope.launch { container.pushRegistration.registerCurrent() } },
    )
}

@Composable
private fun Files(
    container: AppContainer,
    conversationId: String,
    onSent: () -> Unit,
) {
    RequestMediaPermissions()

    val viewModel: FilePickerViewModel = viewModel(
        key = "files/$conversationId",
        factory = remember(container, conversationId) {
            viewModelFactory {
                FilePickerViewModel(
                    conversationId = conversationId,
                    threadTs = null,
                    media = container.watchMediaStore,
                    messages = container.messageRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    FilePickerScreen(state = state, onPick = { item -> viewModel.send(item, onSent) })
}

@Composable
private fun RequestMediaPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {  }

    LaunchedEffect(Unit) {
        launcher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
        )
    }
}

@Composable
private fun Thread(
    container: AppContainer,
    conversationId: String,
    threadTs: String,
    onReact: (String) -> Unit,
    highlightTs: String? = null,
) {
    val viewModel: ThreadViewModel = viewModel(
        key = "$conversationId/$threadTs",
        factory = remember(container, conversationId, threadTs) {
            viewModelFactory {
                ThreadViewModel(
                    conversationId = conversationId,
                    threadTs = threadTs,
                    messages = container.messageRepository,
                    conversations = container.conversationRepository,
                    preferences = container.preferenceRepository,
                    threads = container.threadRepository,
                    failureMapper = { it.toNetworkFailure() },
                    initialHighlightTs = highlightTs,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val quickReplies by viewModel.quickReplies.collectAsStateWithLifecycle()
    val threadEmoji by viewModel.customEmoji.collectAsStateWithLifecycle()
    val pagination by viewModel.pagination.collectAsStateWithLifecycle()
    val quoted by viewModel.quoted.collectAsStateWithLifecycle()
    val threadHighlight by viewModel.highlight.collectAsStateWithLifecycle()

    val reply = rememberMessageComposer(
        title = "Reply",
        quickReplies = quickReplies,
        onMessage = viewModel::reply,
    )

    ThreadScreen(
        state = state,
        onReply = reply,
        onRetry = viewModel::retry,
        onReact = { message -> onReact(message.ts) },
        customEmoji = threadEmoji,
        pagination = pagination,
        onLoadMore = viewModel::loadMoreReplies,
        quoted = quoted,
        highlightTs = threadHighlight,
    )
}

@Composable
private fun React(
    container: AppContainer,
    conversationId: String,
    messageTs: String,
    onPicked: () -> Unit,
) {
    val viewModel: ConversationViewModel = viewModel(
        key = "react/$conversationId",
        factory = remember(container, conversationId) {
            conversationViewModelFactory(container, conversationId, markConversationRead = false)
        },
    )
    val frequent by viewModel.frequentReactions.collectAsStateWithLifecycle()
    val search by viewModel.emojiSearch.collectAsStateWithLifecycle()
    val customEmoji by viewModel.customEmoji.collectAsStateWithLifecycle()

    val reaction by viewModel.reaction.collectAsStateWithLifecycle()
    val searchLauncher = rememberTextInput(title = "Search emoji", onText = viewModel::searchEmoji)

    ReactionPicker(
        frequent = frequent,
        searchResults = search.results,
        query = search.query,
        customEmoji = customEmoji,
        onSearch = searchLauncher,
        onPick = { emoji -> viewModel.react(messageTs, emoji) },
        reaction = reaction,
        onSuccess = onPicked,
    )
}

private fun homeViewModelFactory(container: AppContainer) = viewModelFactory {
    HomeViewModel(
        repository = container.conversationRepository,
        settings = container.settingsRepository,
        sidebarRepository = container.sidebarRepository,
        unreadRepository = container.unreadRepository,
        activityRepository = container.activityRepository,
        threadRepository = container.threadRepository,
        failureMapper = { it.toNetworkFailure() },
    )
}

private fun signInViewModelFactory(container: AppContainer) = viewModelFactory {
    SignInViewModel(
        authenticator = container.internalAuthenticator,
        gateway = container.relaySignInGateway,
        relayBaseUrl = BuildConfig.RELAY_URL,
    )
}

private fun conversationViewModelFactory(
    container: AppContainer,
    conversationId: String,
    markConversationRead: Boolean = true,
    highlightTs: String? = null,
) = viewModelFactory {
    val conversations = if (markConversationRead) container.conversationRepository else {
        object : ConversationRepository by container.conversationRepository {
            override suspend fun markRead(conversationId: String, ts: String): Result<Unit> = Result.success(Unit)
        }
    }
    ConversationViewModel(
        conversationId = conversationId,
        messages = container.messageRepository,
        conversations = conversations,
        preferences = container.preferenceRepository,
        emojiRepository = container.emojiRepository,
        huddles = container.huddleRepository,
        loadOnInit = markConversationRead,
        initialHighlightTs = highlightTs,
    )
}

private inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline create: () -> VM,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
