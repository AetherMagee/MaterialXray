package com.material.xray.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer

/**
 * Navigation state that survives configuration changes and process death: one back stack per tab
 * and the selected tab. [topLevelKeys] must not change between compositions; pass every tab, hidden
 * ones included.
 */
@Composable
fun rememberNavigationState(startKey: TopLevelKey, topLevelKeys: Set<TopLevelKey>): NavigationState {
    val topLevelKey = rememberSerializable(
        startKey,
        topLevelKeys,
        serializer = MutableStateSerializer(NavKeySerializer<TopLevelKey>()),
    ) {
        mutableStateOf(startKey)
    }
    val backStacks = topLevelKeys.associateWith { key -> rememberNavBackStack(key) }
    return remember(startKey, topLevelKeys) {
        NavigationState(startKey = startKey, topLevelKey = topLevelKey, backStacks = backStacks)
    }
}

/**
 * Holds the tabs' back stacks and the selected tab. It does not change itself; [Navigator] does.
 *
 * @param startKey the tab the app exits through.
 */
class NavigationState(
    val startKey: TopLevelKey,
    topLevelKey: MutableState<TopLevelKey>,
    val backStacks: Map<TopLevelKey, NavBackStack<NavKey>>,
) {
    init {
        require(startKey in backStacks) { "No back stack for the start key $startKey" }
    }

    var topLevelKey: TopLevelKey by topLevelKey

    /** The current tab's stack, its root first. */
    val currentStack: NavBackStack<NavKey>
        get() = backStacks.getValue(topLevelKey)

    /**
     * The tabs whose stacks are on screen, bottom first. The start tab is always there, so back
     * from any other tab returns to it before leaving the app ("exit through Home").
     */
    val stacksInUse: List<TopLevelKey>
        get() = if (topLevelKey == startKey) listOf(startKey) else listOf(startKey, topLevelKey)

    /** The keys [toEntries] turns into entries, in the order `NavDisplay` gets them. */
    val keysInUse: List<NavKey>
        get() = stacksInUse.flatMap { backStacks.getValue(it) }
}

/**
 * Converts the state into the entries for `NavDisplay`. Each stack keeps its own saveable state and
 * ViewModel stores, so a tab that is not on screen keeps both until its entries are popped.
 */
@Composable
fun NavigationState.toEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
    val decoratedEntries = backStacks.mapValues { (_, stack) ->
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key -> entryProvider(key).withNavKey(key) },
        )
    }
    return stacksInUse.flatMap { decoratedEntries.getValue(it) }
}

/** Tabs stay in a clipped viewport; only the selected tab's detail goes into the window overlay. */
data class NavigationEntryLayers(
    val tabs: List<NavEntry<NavKey>>,
    val details: List<NavEntry<NavKey>>,
)

@Composable
fun NavigationState.toEntryLayers(entryProvider: (NavKey) -> NavEntry<NavKey>): NavigationEntryLayers = splitEntryLayers(toEntries(entryProvider), topLevelKey, currentDetailKey = currentStack.last() as? DetailKey)

internal fun splitEntryLayers(
    entries: List<NavEntry<NavKey>>,
    currentTab: TopLevelKey,
    currentDetailKey: DetailKey?,
): NavigationEntryLayers {
    // The empty backdrop lets detail scenes cover or dim the persistent chrome beneath them.
    val backdrop = NavEntry<NavKey>(currentTab) {}.withNavKey(currentTab)
    return NavigationEntryLayers(
        tabs = entries.filter { it.navKey is TopLevelKey },
        details = listOf(backdrop) + listOfNotNull(
            entries.lastOrNull()?.takeIf { currentDetailKey != null && it.navKey == currentDetailKey },
        ),
    )
}

/**
 * The key an entry was created for. `NavEntry` keeps its key private, and the scene strategy and the
 * transitions need it, so [toEntries] records it in the entry's metadata.
 */
internal val NavEntry<*>.navKey: NavKey?
    get() = metadata[NavKeyMetadata]

private object NavKeyMetadata : NavMetadataKey<NavKey>

internal fun NavEntry<NavKey>.withNavKey(key: NavKey): NavEntry<NavKey> {
    val entry = this
    return NavEntry(
        key = key,
        contentKey = entry.contentKey,
        metadata = entry.metadata + metadata { put(NavKeyMetadata, key) },
    ) { entry.Content() }
}
