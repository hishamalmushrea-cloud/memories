package com.memorymap.ui.auth

import com.memorymap.domain.repository.AuthRepository
import com.memorymap.testing.RecordingAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sign-in screen's decisions.
 *
 * What is worth testing here is not that a form can be filled in: it is what the screen
 * refuses to do. A request with no email, a request with a password too short to be
 * accepted by the server, and a second tap while the first request is still in flight are
 * all things the screen must stop **before** anything leaves the device - the first two
 * because the server's answer would be a poor way to teach someone the rule, the third
 * because a second account request is not something a double tap should be able to send.
 *
 * The repository is a double that records the calls, so each test can ask what the screen
 * actually sent rather than only what it says on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: RecordingAuthRepository = RecordingAuthRepository()) =
        AuthViewModel(repository)

    @Test
    fun `restoring a stored session is the first thing it does`() = runTest {
        val repository = RecordingAuthRepository()

        viewModel(repository)
        advanceUntilIdle()

        // A returning user must not be shown the sign-in form again while the stored
        // session is still being read.
        assertEquals(listOf("restoreSession"), repository.calls)
    }

    @Test
    fun `an email is required before anything is sent`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.submit()
        advanceUntilIdle()

        assertEquals("auth_error_email_required", model.ui.value.messageKey)
        assertEquals(listOf("restoreSession"), repository.calls)
    }

    @Test
    fun `a password shorter than the server accepts is refused here`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onEmailChange("someone@example.org")
        model.onPasswordChange("12345")
        model.submit()
        advanceUntilIdle()

        assertEquals("auth_error_weak_password", model.ui.value.messageKey)
        assertEquals(listOf("restoreSession"), repository.calls)
    }

    @Test
    fun `a password of exactly the accepted length is sent`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onEmailChange("someone@example.org")
        model.onPasswordChange("123456")
        model.submit()
        advanceUntilIdle()

        assertNull(model.ui.value.messageKey)
        assertEquals(listOf("restoreSession", "signIn(someone@example.org,123456)"), repository.calls)
    }

    @Test
    fun `the email is trimmed before it is sent`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onEmailChange("  someone@example.org  ")
        model.onPasswordChange("123456")
        model.submit()
        advanceUntilIdle()

        // A space pasted in with an address is a real thing people do, and the server
        // would answer "invalid email" for a value the person cannot see.
        assertEquals(listOf("restoreSession", "signIn(someone@example.org,123456)"), repository.calls)
    }

    @Test
    fun `signing up sends the display name, trimmed`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onModeChange(AuthMode.SIGN_UP)
        model.onEmailChange("someone@example.org")
        model.onPasswordChange("123456")
        model.onDisplayNameChange("  أحمد  ")
        model.submit()
        advanceUntilIdle()

        assertEquals(
            listOf("restoreSession", "signUp(someone@example.org,123456,أحمد)"),
            repository.calls,
        )
    }

    @Test
    fun `a second tap while a request is in flight is ignored`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onEmailChange("someone@example.org")
        model.onPasswordChange("123456")
        model.submit()
        // Before the scheduler runs the launched call, which is exactly the window a
        // person's second tap lands in.
        model.submit()
        advanceUntilIdle()

        assertEquals(listOf("restoreSession", "signIn(someone@example.org,123456)"), repository.calls)
        assertFalse(model.ui.value.isSubmitting)
    }

    @Test
    fun `a failed sign in shows the reason the repository gave`() = runTest {
        val repository = RecordingAuthRepository(
            signInResult = AuthRepository.Result.Failure("auth_error_invalid_credentials"),
        )
        val model = viewModel(repository)

        model.onEmailChange("someone@example.org")
        model.onPasswordChange("123456")
        model.submit()
        advanceUntilIdle()

        assertEquals("auth_error_invalid_credentials", model.ui.value.messageKey)
        assertFalse(model.ui.value.isSubmitting)
    }

    @Test
    fun `a password reset does not need a password and goes back to signing in`() = runTest {
        val repository = RecordingAuthRepository()
        val model = viewModel(repository)

        model.onModeChange(AuthMode.RESET_PASSWORD)
        model.onEmailChange("someone@example.org")
        model.submit()
        advanceUntilIdle()

        assertEquals(listOf("restoreSession", "resetPassword(someone@example.org)"), repository.calls)
        assertEquals("auth_reset_sent", model.ui.value.messageKey)
        // The next thing that person does is sign in with the new password.
        assertEquals(AuthMode.SIGN_IN, model.ui.value.mode)
    }

    @Test
    fun `typing after a failure clears the message`() = runTest {
        val repository = RecordingAuthRepository(
            signInResult = AuthRepository.Result.Failure("auth_error_invalid_credentials"),
        )
        val model = viewModel(repository)

        model.onEmailChange("someone@example.org")
        model.onPasswordChange("123456")
        model.submit()
        advanceUntilIdle()
        assertEquals("auth_error_invalid_credentials", model.ui.value.messageKey)

        model.onPasswordChange("1234567")

        // The failure belonged to the old attempt; leaving it up while the person fixes
        // the password makes the screen look like it is still complaining.
        assertNull(model.ui.value.messageKey)
    }

    @Test
    fun `changing mode clears the message`() = runTest {
        val model = viewModel()

        model.submit()
        advanceUntilIdle()
        assertEquals("auth_error_email_required", model.ui.value.messageKey)

        model.onModeChange(AuthMode.SIGN_UP)

        assertNull(model.ui.value.messageKey)
        assertEquals(AuthMode.SIGN_UP, model.ui.value.mode)
    }

    @Test
    fun `continuing without an account uses the local identity`() = runTest {
        val repository = RecordingAuthRepository(userId = null)
        val model = viewModel(repository)

        model.onDisplayNameChange("  سارة  ")
        model.continueOffline()
        advanceUntilIdle()

        // The offline account is the reason the archive is readable with no server, and
        // the display name is what the person is called on their own device.
        assertTrue(repository.calls.contains("continueOffline(سارة)"))
    }

    @Test
    fun `the screen knows whether a cloud project is configured`() = runTest {
        val repository = RecordingAuthRepository()

        val model = viewModel(repository)

        assertTrue(model.isCloudConfigured)
    }
}
