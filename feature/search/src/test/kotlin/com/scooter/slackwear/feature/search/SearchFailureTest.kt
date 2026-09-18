package com.scooter.slackwear.feature.search

import androidx.lifecycle.ViewModelStore
import com.scooter.slackwear.core.model.SlackFailureCode
import com.scooter.slackwear.core.model.repository.SearchHit
import com.scooter.slackwear.core.model.repository.SearchRepository
import com.scooter.slackwear.core.model.repository.SearchSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchFailureTest {
    private val store = ViewModelStore()
    private var failure: Exception = object : Exception("private query U123"), SlackFailureCode {
        override val slackFailureCode = "missing_scope"
    }
    private val repository = object : SearchRepository {
        override suspend fun search(query: String, sort: SearchSort): Result<List<SearchHit>> = Result.failure(failure)
    }

    @Before
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun failedSearchShowsVettedCode() = runTest {
        val vm = viewModel()
        vm.search("synthetic query")
        runCurrent()
        assertEquals("missing_scope", vm.state.value.failure?.code)
        assertEquals("Search failed - Slack: missing_scope", vm.state.value.error)
    }

    @Test
    fun arbitraryExceptionTextIsNotDisplayed() = runTest {
        failure = IllegalStateException("not_allowed query=private token=xoxb-synthetic U123")
        val vm = viewModel()
        vm.search("synthetic query")
        runCurrent()
        assertEquals("Search failed - Unknown error", vm.state.value.error)
        vm.clear()
        assertNull(vm.state.value.error)
    }

    @Test
    fun returnedCancellationDoesNotBecomeVisibleFailure() = runTest {
        failure = CancellationException("private")
        val vm = viewModel()
        vm.search("synthetic query")
        runCurrent()
        assertNull(vm.state.value.failure)
    }

    private fun viewModel() = SearchViewModel(repository).also { store.put("search", it) }
}
