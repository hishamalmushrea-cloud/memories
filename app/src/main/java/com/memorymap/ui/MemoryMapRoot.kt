package com.memorymap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.memorymap.R
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.LockState
import com.memorymap.navigation.MemoryMapNavHost
import com.memorymap.navigation.TopLevelDestination
import com.memorymap.ui.auth.AuthScreen
import com.memorymap.ui.auth.AuthViewModel
import com.memorymap.ui.lock.LockScreen
import com.memorymap.ui.lock.LockViewModel

/**
 * The app shell.
 *
 * The lock gate comes first: while it is on and not passed, nothing else - not
 * even the sign-in form - is shown, because the archive must not be one tap away
 * on a device that is already unlocked. After it, the auth gate: while the
 * stored session is being restored the screen shows a spinner, so a signed-in
 * user is never shown the sign-in form at a cold start. Then the map is the home
 * screen, the five destinations live in the bottom bar, and "+ add" is reachable
 * from anywhere.
 */
@Composable
fun MemoryMapRoot(
    authViewModel: AuthViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val lockState by lockViewModel.lockState.collectAsStateWithLifecycle()

    // Give up the unlock when the app leaves the foreground, so handing the phone
    // to someone else does not hand them the archive. The state machine ignores
    // this while the lock is off, so it is safe to always ask.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lockViewModel.lock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (lockState == LockState.Locked) {
        LockScreen(onUnlocked = lockViewModel::onUnlockConfirmed)
        return
    }

    when (authState) {
        AuthState.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        AuthState.SignedOut -> AuthScreen()

        is AuthState.SignedIn -> AppShell()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var quickAddOpen by remember { mutableStateOf(false) }

    val isTopLevel = TopLevelDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isTopLevel) {
                ExtendedFloatingActionButton(
                    onClick = { quickAddOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add)) },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            MemoryMapNavHost(navController = navController)
        }
    }

    if (quickAddOpen) {
        val sheetState = rememberModalBottomSheetState()
        QuickAddSheet(
            sheetState = sheetState,
            onDismiss = { quickAddOpen = false },
            onNavigate = { route ->
                quickAddOpen = false
                navController.navigate(route)
            },
        )
    }
}
