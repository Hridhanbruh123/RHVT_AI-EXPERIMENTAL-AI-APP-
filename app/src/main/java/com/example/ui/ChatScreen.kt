package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.ChatMessage
import com.example.data.database.ChatSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Safe, lightweight Red Square 2x2 JPEG Base64
const val MOCK_IMAGE_BASE64_RED = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAACAAIBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="

data class PromptPreset(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val subText: String
)

data class ChatMode(
    val name: String,
    val displayName: String,
    val emoji: String,
    val description: String,
    val presetPrompt: String
)

@Composable
fun getFontSize(baseSize: Int, scale: String): TextUnit {
    val factor = when (scale) {
        "Large" -> 1.2f
        "X-Large" -> 1.4f
        else -> 1.0f
    }
    return (baseSize * factor).sp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val startupError by viewModel.startupError.collectAsStateWithLifecycle()

    // Render Diagnostics Error Screen if startup crashed
    if (startupError != null) {
        ErrorRecoveryScreen(
            errorMessage = startupError ?: "An unexpected diagnostics crash occurred.",
            onRetry = { viewModel.retryStartup() },
            onContinueAsGuest = { viewModel.signInAsGuest() },
            onSubmitReport = { report -> viewModel.submitErrorReport(report) }
        )
        return
    }

    // Render beautiful loading splash screen
    if (isInitializing) {
        StartupSplashScreen()
        return
    }

    val sessions by viewModel.filteredSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val messages by viewModel.currentSessionMessages.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

    // Authentication and advanced state flows
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()

    // Preferences states
    val themeSelection by viewModel.themeSelection.collectAsStateWithLifecycle()
    val colorPalette by viewModel.colorPalette.collectAsStateWithLifecycle()
    val fontSizeScale by viewModel.fontSizeScale.collectAsStateWithLifecycle()
    val highContrast by viewModel.highContrast.collectAsStateWithLifecycle()
    val seenOnboarding by viewModel.seenOnboarding.collectAsStateWithLifecycle()
    val showLimitReachedDialog by viewModel.showLimitReachedDialog.collectAsStateWithLifecycle()

    // If there is no authenticated user active, render the welcome auth portal page instantly
    if (currentUser == null) {
        AuthPortalScreen(viewModel = viewModel)
        return
    }

    // Active bottom navigation tab (0 = Home/Welcome, 1 = Chat Workspace, 2 = Settings)
    var activeTab by rememberSaveable { mutableStateOf(0) }

    // Dialog state for renaming a conversation
    var renameSessionTarget by remember { mutableStateOf<ChatSession?>(null) }

    // Dialog to prompt temporary guest users to upgrade
    var showGuestUltraRestrictionDialog by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current

    val chatModes = remember {
        listOf(
            ChatMode("Lite", "Lite", "⚡", "Fast, high-speed everyday answers.", "Give me a super fast 2-sentence summary of why leaves change colors in autumn."),
            ChatMode("Smart", "Smart", "🧠", "Balanced performance & detail.", "What are the core differences between a relational database and a non-relational database?"),
            ChatMode("Research", "Research", "🔬", "Deep, multi-perspective analysis.", "Conduct a detailed comparative analysis between the Roman and Mongol empires’ structures."),
            ChatMode("Coding", "Coding", "💻", "Programming assistance & bugs.", "Write a clean Kotlin extension function to format any Unix timestamp in Long to an elegant date string."),
            ChatMode("Creative", "Creative", "✍️", "Stories, worldbuilding, & imagery.", "Write the first chapter of a mystery novel set in a rainy city where clocks run backwards."),
            ChatMode("Study", "Study", "📚", "Step-by-step learning & guide.", "Teach me the core concepts of photosynthesis step-by-step using a memorable cooking analogy."),
            ChatMode("Math", "Math", "📊", "Carefully worked calculations.", "Solve this problem showing your mathematical reasoning step-by-step: If a box has 5 red and 7 blue marbles, what is the probability of picking 2 blue marbles in a row?"),
            ChatMode("Expert", "Expert", "🚀", "Maximum reasoning & depth.", "Analyze the economic trade-offs of microservices vs monolithic software systems for scaling startups."),
            ChatMode("Friendly", "Friendly", "😊", "Warm, engaging conversation.", "Hi there! I am excited to meet you. Tell me about yourself and your capabilities.")
        )
    }

    // Modal navigation drawer for historical session switching
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = activeTab == 1, // Only allow workspace drawer gestures
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conversations",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = {
                            viewModel.startNewSession()
                            activeTab = 1
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.testTag("new_session_sidebar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Conversation",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Advanced live list Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search conversations...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("conversations_search_field"),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Archive Folder Drawer category switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setShowArchived(!showArchived) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showArchived) Icons.Default.Home else Icons.Default.MailOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (showArchived) "View Active Chats" else "View Archived Folder",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    val currentCount = sessions.size
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$currentCount", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (sessions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No matches found" else "Folder is empty",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        items(sessions) { session ->
                            val isSelected = session.id == currentSessionId
                            NavigationDrawerItem(
                                label = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (session.isPinned) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = "Pinned",
                                                    tint = Color(0xFFFFB300),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Text(
                                                text = session.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        
                                        // Quick Row actions
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 1. PIN TOGGLER
                                            IconButton(
                                                onClick = { viewModel.togglePinSession(session.id) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = "Pin conversation",
                                                    tint = if (session.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }

                                            // 2. ARCHIVE TOGGLER
                                            IconButton(
                                                onClick = { viewModel.toggleArchiveSession(session.id) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (session.isArchived) Icons.Default.Home else Icons.Default.MailOutline,
                                                    contentDescription = if (session.isArchived) "Unarchive" else "Archive",
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }

                                            // 3. EDIT RENAME TITLE
                                            IconButton(
                                                onClick = { renameSessionTarget = session },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename chat",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }

                                            // 4. DELETE SESSION
                                            IconButton(
                                                onClick = { viewModel.deleteSession(session.id) },
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .testTag("delete_session_icon_${session.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                selected = isSelected,
                                onClick = {
                                    viewModel.selectSession(session.id)
                                    activeTab = 1
                                    coroutineScope.launch { drawerState.close() }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    unselectedContainerColor = Color.Transparent,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .testTag("session_item_${session.id}")
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("clear_all_conversations_button"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear all",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Conversations", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "RHVT AI",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Learn. Create. Explore.",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    },
                    navigationIcon = {
                        if (activeTab == 1) {
                            IconButton(
                                onClick = { coroutineScope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("menu_drawer_button")
                            ) {
                                Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu Drawer")
                            }
                        } else {
                            Box(modifier = Modifier.size(48.dp))
                        }
                    },
                    actions = {
                        if (activeTab == 1) {
                            IconButton(
                                onClick = { viewModel.startNewSession() },
                                modifier = Modifier.testTag("new_chat_top_button")
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "New Conversation")
                            }
                        } else {
                            Box(modifier = Modifier.size(48.dp))
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                // Adaptive layout and large tappable bottom buttons
                NavigationBar(
                    modifier = Modifier.height(72.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home Screen",
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        label = { Text("Home", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.MailOutline,
                                contentDescription = "Chat Workspace",
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        label = { Text("Chat", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Premium Subscription Plans",
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        label = { Text("Premium", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 3,
                        onClick = { activeTab = 3 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings Page",
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        label = { Text("Settings", fontWeight = FontWeight.Bold) }
                    )
                }
            },
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val isGuest = currentUser?.provider == "Guest" || currentUser?.email == "guest_explorer@rhvtai.com"

                Column(modifier = Modifier.fillMaxSize()) {
                    if (isGuest) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("guest_mode_banner"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("💡", fontSize = 16.sp)
                                    Text(
                                        text = "You're using Guest Mode. Sign in to unlock all features.",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                Button(
                                    onClick = {
                                        viewModel.signOut()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Sign In", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        // RENDER PAGES WITH ANIMATED TRANSITIONS FOR PREMIUM EXPERIENCE
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn() togetherWith
                                        slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut()
                            },
                            label = "page_transition"
                        ) { targetPage ->
                            when (targetPage) {
                                0 -> {
                                    // 1. HOME / WELCOME SCREEN
                                    HomeScreenLayout(
                                        onStartChatting = {
                                            activeTab = 1
                                        },
                                        fontSizeScale = fontSizeScale
                                    )
                                }
                                1 -> {
                                    // 2. CHAT WORKSPACE SCREEN
                                    ChatWorkspaceLayout(
                                        viewModel = viewModel,
                                        messages = messages,
                                        isTyping = isTyping,
                                        errorState = errorState,
                                        selectedModel = selectedModel,
                                        chatModes = chatModes,
                                        fontSizeScale = fontSizeScale,
                                        keyboardController = keyboardController,
                                        onGuestUltraRestriction = { showGuestUltraRestrictionDialog = true },
                                        onNavigateToPlans = { activeTab = 2 }
                                    )
                                }
                                2 -> {
                                    // 3. SUBSCRIPTIONS & PLANS
                                    SubscriptionScreenLayout(
                                        viewModel = viewModel
                                    )
                                }
                                3 -> {
                                    // 4. SETTINGS & PREFERENCES OVERRIDES
                                    SettingsScreenLayout(
                                        viewModel = viewModel,
                                        themeSelection = themeSelection,
                                        colorPalette = colorPalette,
                                        fontSizeScale = fontSizeScale,
                                        highContrast = highContrast,
                                        onNavigateToPlans = { activeTab = 2 }
                                    )
                                }
                            }
                        }
                    }
                }

                // ONBOARDING DIALOG POPUP GUIDE
                if (!seenOnboarding) {
                    OnboardingTutorWalkthrough(
                        onDismiss = {
                            viewModel.setSeenOnboarding(true)
                        }
                    )
                }

                // CHAT TITLE RENAME DIALOG
                if (renameSessionTarget != null) {
                    var editTitleText by remember { mutableStateOf(renameSessionTarget!!.title) }
                    AlertDialog(
                        onDismissRequest = { renameSessionTarget = null },
                        title = { Text("Rename Conversation") },
                        text = {
                            OutlinedTextField(
                                value = editTitleText,
                                onValueChange = { editTitleText = it },
                                label = { Text("Title") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rename_title_input")
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (editTitleText.isNotBlank()) {
                                        viewModel.renameSession(renameSessionTarget!!.id, editTitleText)
                                    }
                                    renameSessionTarget = null
                                },
                                modifier = Modifier.testTag("save_rename_button")
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { renameSessionTarget = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // FREE DAILY MESSAGES LIMIT REACHED DIALOG
                if (showLimitReachedDialog) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissLimitReachedDialog() },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("⚠️", fontSize = 24.sp)
                                Text("Daily Limit Reached", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Text(
                                text = "You've reached today's Free plan limit. Upgrade to RHVT Plus for more messages and access to RHVT Ultra.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.dismissLimitReachedDialog()
                                    activeTab = 2 // Redirect to pricing/plans page
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Upgrade", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { viewModel.dismissLimitReachedDialog() }
                            ) {
                                Text("Maybe Later")
                            }
                        }
                    )
                }

                // GUEST SECURITY ACTION ALERT
                if (showGuestUltraRestrictionDialog) {
                    AlertDialog(
                        onDismissRequest = { showGuestUltraRestrictionDialog = false },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("🔒", fontSize = 24.sp)
                                Text("RHVT Ultra is Locked", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Text(
                                text = "RHVT Ultra provides advanced reasoning, coding, writing, and math complexity. It is a premium feature reserved for registered accounts.\n\nSign in or register to unlock all capabilities!",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showGuestUltraRestrictionDialog = false
                                    viewModel.signOut() // returns to welcome portal
                                }
                            ) {
                                Text("Sign In / Register", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGuestUltraRestrictionDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 1. HOME SCREEN LAYOUT
// ==========================================
@Composable
fun HomeScreenLayout(
    onStartChatting: () -> Unit,
    fontSizeScale: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            
            // App Branding Logo Icon with circular gradient
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("✨", fontSize = 48.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "RHVT AI",
                fontSize = getFontSize(28, fontSizeScale),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Learn. Create. Explore.",
                fontSize = getFontSize(14, fontSizeScale),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // RHVT AI model definitions overview
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "RHVT Intelligence Models",
                        fontWeight = FontWeight.Bold,
                        fontSize = getFontSize(15, fontSizeScale),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("⚡", fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RHVT Lite", fontWeight = FontWeight.Bold, fontSize = getFontSize(13, fontSizeScale))
                            Text(
                                "Fast responses. Great for everyday questions. Optimized for speed.",
                                fontSize = getFontSize(11, fontSizeScale),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("🚀", fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RHVT Ultra", fontWeight = FontWeight.Bold, fontSize = getFontSize(13, fontSizeScale))
                            Text(
                                "Advanced reasoning. Best for coding, writing, math, and creativity. Highest quality responses.",
                                fontSize = getFontSize(11, fontSizeScale),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // LARGE "START CHATTING" CORE CTAs
            Button(
                onClick = onStartChatting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("start_chatting_button"),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start Chatting",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Start Chatting",
                    fontSize = getFontSize(16, fontSizeScale),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

// ==========================================
// 2. CHAT WORKSPACE SCREEN LAYOUT
// ==========================================
@Composable
fun ChatWorkspaceLayout(
    viewModel: ChatViewModel,
    messages: List<ChatMessage>,
    isTyping: Boolean,
    errorState: String?,
    selectedModel: String,
    chatModes: List<ChatMode>,
    fontSizeScale: String,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onGuestUltraRestriction: () -> Unit,
    onNavigateToPlans: () -> Unit
) {
    val scrollState = rememberLazyListState()
    val activeMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()

    // Automatic Scroll to Bottom
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            scrollState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // ENGINES SEPARATOR ROW - PREMIUM SEGMENTED SLIDING PICKER
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                // Segmented selector row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isLite = selectedModel == "gemini-3.5-flash"
                    
                    // Lite button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isLite) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { viewModel.setModel("gemini-3.5-flash") }
                            .testTag("select_model_flash"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚡", fontSize = 14.sp)
                            Text(
                                "RHVT Lite",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLite) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Ultra button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!isLite) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable {
                                val user = viewModel.currentUser.value
                                val isGuest = user?.provider == "Guest" || user?.email == "guest_explorer@rhvtai.com"
                                if (isGuest) {
                                    onGuestUltraRestriction()
                                } else {
                                    viewModel.setModel("gemini-3.1-pro-preview")
                                }
                            }
                            .testTag("select_model_pro"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🚀", fontSize = 14.sp)
                            Text(
                                "RHVT Ultra",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isLite) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Beautiful description under the selected model
                AnimatedContent(
                    targetState = selectedModel,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    label = "model_description_anim"
                ) { targetModel ->
                    if (targetModel == "gemini-3.5-flash") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VerticalDivider(modifier = Modifier.height(18.dp), color = MaterialTheme.colorScheme.primary, thickness = 3.dp)
                            Text(
                                text = "Optimized for speed. Fast, conversational answers great for daily quick-fire tasks.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VerticalDivider(modifier = Modifier.height(18.dp), color = MaterialTheme.colorScheme.primary, thickness = 3.dp)
                            Text(
                                text = "Highest quality reasoning. Premium intelligence optimized for coding, advanced math, research, and deep writing.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // CUSTOM CHAT MODES LIST OVERLAYS
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(chatModes) { mode ->
                val isSelected = mode.name == activeMode
                Card(
                    onClick = { viewModel.setSessionMode(mode.name) },
                    modifier = Modifier
                        .height(36.dp)
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .testTag("mode_chip_${mode.name.lowercase()}"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = mode.emoji, fontSize = 14.sp)
                        Text(
                            text = mode.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // WARNING ALERT FOR MISSING API KEYS
        if (viewModel.isApiKeyMissing) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "API key alert",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "To chat, enter your GEMINI_API_KEY inside the secure Secrets Tab panel in AI Studio.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // MAIN CONTENT AREA
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                // Preset Prompts empty onboard
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "💫 RHVT AI",
                        fontSize = getFontSize(22, fontSizeScale),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Text(
                        text = "Tap an expert mode or select clean presets below to start your conversation with RHVT AI.",
                        fontSize = getFontSize(12, fontSizeScale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))

                    // Preset cards
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val activeModeObj = chatModes.find { it.name == activeMode } ?: chatModes[1]
                        Card(
                            onClick = {
                                viewModel.sendMessage(activeModeObj.presetPrompt)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(activeModeObj.emoji, fontSize = 16.sp)
                                    Text("Ask in ${activeModeObj.displayName} Mode", fontWeight = FontWeight.Bold, fontSize = getFontSize(13, fontSizeScale))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "\"${activeModeObj.presetPrompt}\"",
                                    fontSize = getFontSize(11, fontSizeScale),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // MESSAGE STREAMING LIST
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("message_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { message ->
                        ChatBubbleItem(
                            message = message,
                            fontSizeScale = fontSizeScale,
                            viewModel = viewModel
                        )
                    }

                    // PULSING SEQUENCE TYPING DOT INDICATOR
                    if (isTyping) {
                        item {
                            TypingBubbleIndicator()
                        }
                    }
                }
                
                // FLOATING STOP GENERATION CARD BUTTON
                if (isTyping) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Card(
                            onClick = { viewModel.stopGenerating() },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .height(38.dp)
                                .testTag("stop_generating_button")
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 14.dp)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop Generating",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Stop Generating",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }

        // ERROR NOTIFIER
        if (errorState != null) {
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                },
                modifier = Modifier.padding(12.dp),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Text(text = errorState!!, fontSize = 12.sp)
            }
        }

        // BOTTOM TEXT IN BOX FIELD
        val plan by viewModel.subscriptionPlan.collectAsStateWithLifecycle()
        val dCount by viewModel.dailyMessageCount.collectAsStateWithLifecycle()
        if (plan == "Free") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Limits",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Free tier: ${5 - dCount} of 5 daily messages left",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { onNavigateToPlans() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Upgrade ⭐", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        ChatInputRow(
            isTyping = isTyping,
            onSend = { text, imgBase ->
                viewModel.sendMessage(text, imgBase)
                keyboardController?.hide()
            },
            onGenerateArt = { prompt, style ->
                viewModel.generateImage(prompt, style)
                keyboardController?.hide()
            },
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

// Typing dynamic pulsing indicator view
@Composable
fun TypingBubbleIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp,
                topStart = 16.dp
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing anim
                val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
                @Composable
                fun animateAlphaState(delay: Int): Float {
                    val alphaState by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = delay, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_alpha"
                    )
                    return alphaState
                }

                val d1 = animateAlphaState(0)
                val d2 = animateAlphaState(150)
                val d3 = animateAlphaState(300)

                Text(
                    text = "RHVT AI is thinking",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = d1)))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = d2)))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = d3)))
                }
            }
        }
    }
}

// ==========================================
// 3. SETTINGS & PREFERENCES LAYOUT
// ==========================================
@Composable
fun SettingsScreenLayout(
    viewModel: ChatViewModel,
    themeSelection: String,
    colorPalette: String,
    fontSizeScale: String,
    highContrast: Boolean,
    onNavigateToPlans: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val plan by viewModel.subscriptionPlan.collectAsStateWithLifecycle()
    val isCloudSyncing by viewModel.isCloudSyncing.collectAsStateWithLifecycle()
    val lastSyncedTime by viewModel.lastSyncedTime.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Secure Premium User Account Section
            currentUser?.let { user ->
                val isGuest = user.provider == "Guest" || user.email == "guest_explorer@rhvtai.com"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("user_profile_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // High contrast circular initials avatar
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                val letter = if (user.displayName.isNotBlank()) user.displayName.take(1).uppercase() else "N"
                                Text(
                                    text = letter,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = user.displayName,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val isGuest = user.provider == "Guest" || user.email == "guest_explorer@rhvtai.com"
                                    // Elegant Premium badge or Guest badge
                                    Badge(
                                        containerColor = if (isGuest) {
                                            MaterialTheme.colorScheme.outlineVariant
                                        } else if (plan == "Free") {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        contentColor = if (isGuest) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else if (plan == "Free") {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onPrimary
                                        }
                                    ) {
                                        Text(
                                            text = if (isGuest) "GUEST" else plan.uppercase(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = user.email,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isGuest) {
                                        "Temporary Guest Profile"
                                    } else {
                                        "Plan: RHVT $plan • Registered ${user.provider}"
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Cloud synchronization section
                        val isGuest = user.provider == "Guest" || user.email == "guest_explorer@rhvtai.com"
                        if (!isGuest) {
                            // Cloud synchronicity live state tracker
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Cloud Synchronization",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val syncLabel = if (isCloudSyncing) {
                                        "Synchronizing chats securely..."
                                    } else if (lastSyncedTime > 0L) {
                                        val formatted = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(lastSyncedTime))
                                        "Synced with Cloud at $formatted"
                                    } else {
                                        "No backup synced yet"
                                    }
                                    Text(
                                        text = syncLabel,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isCloudSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            viewModel.triggerCloudSync()
                                            Toast.makeText(context, "RHVT Cloud sync completed!", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Sync",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            // Locked visual representation of Cloud Sync for guest
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("🔒", fontSize = 14.sp)
                                        Text(
                                            text = "Cloud Synchronization",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    Text(
                                        text = "Sync is locked in Guest Mode. Register to back up your chat history.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.signOut()
                                        Toast.makeText(context, "Redirecting to register page!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Unlock", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Subscription Plan Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Membership & Premium Plan",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Active Plan: RHVT $plan",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    onNavigateToPlans()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (plan == "Free") "Upgrade ⭐" else "Manage",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            // Dynamic logout or Sign In upgrade button for visitor
                            Button(
                                onClick = {
                                    viewModel.signOut()
                                    Toast.makeText(context, if (isGuest) "Onboarding screen loaded." else "Logged out successfully", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isGuest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    contentColor = if (isGuest) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                            ) {
                                Icon(
                                    imageVector = if (isGuest) Icons.Default.Person else Icons.Default.Lock,
                                    contentDescription = if (isGuest) "SignIn" else "Logout",
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isGuest) "Sign In / Register Account" else "Sign Out Safely", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Preferences",
                fontWeight = FontWeight.Black,
                fontSize = getFontSize(22, fontSizeScale),
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Personalize colors, scales, themes, and interactive guide triggers here instantly.",
                fontSize = getFontSize(13, fontSizeScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // 1. THEME MODE SELECTOR
            Text(text = "App Theme Mode", fontWeight = FontWeight.Bold, fontSize = getFontSize(14, fontSizeScale))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf("System", "Light", "Dark")
                    modes.forEach { mode ->
                        val isSelected = themeSelection == mode
                        Button(
                            onClick = { viewModel.setThemeSelection(mode) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(text = mode, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. PRIMARY COLOR ACCENT PALETTE
            Text(text = "Interface Palette Accent", fontWeight = FontWeight.Bold, fontSize = getFontSize(14, fontSizeScale))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val palettes = listOf(
                    Triple("Indigo", Color(0xFF2B5C8F), "Indigo Breeze"),
                    Triple("Lavender", Color(0xFF75519E), "Lavender Twilight"),
                    Triple("Mint", Color(0xFF1B6C4F), "Mint Calm")
                )

                palettes.forEach { item ->
                    val isSelected = colorPalette == item.first
                    Card(
                        onClick = { viewModel.setColorPalette(item.first) },
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(item.second)
                            )
                            Text(
                                text = item.third,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 3. FONT SCALINGS FOR ACCESSIBILITY (PHONE + TABLET READY)
            Text(text = "Accessibility Font Size", fontWeight = FontWeight.Bold, fontSize = getFontSize(14, fontSizeScale))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sizes = listOf("Standard", "Large", "X-Large")
                    sizes.forEach { size ->
                        val isSelected = fontSizeScale == size
                        Button(
                            onClick = { viewModel.setFontSizeScale(size) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(text = size, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 4. HIGH CONTRAST SWITCH (MANDATORY ACCESSIBILITY MANDATE)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "High Contrast Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = getFontSize(13, fontSizeScale)
                        )
                        Text(
                            text = "Forces sharp borders and deeper true black elements.",
                            fontSize = getFontSize(10, fontSizeScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = highContrast,
                        onCheckedChange = { viewModel.setHighContrast(it) }
                    )
                }
            }

            // 5. MANUAL GUIDE TRIGGERS
            Text(text = "Walkthrough Tutorial", fontWeight = FontWeight.Bold, fontSize = getFontSize(14, fontSizeScale))
            Button(
                onClick = { viewModel.setSeenOnboarding(false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "Show Tutorial")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show Guided Onboarding Tour", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

// ==========================================
// 4. VISUAL DIALOG CHAT BALLOONS
// ==========================================
@Composable
fun ChatBubbleItem(
    message: ChatMessage,
    fontSizeScale: String,
    viewModel: ChatViewModel
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bubbleShape = if (isUser) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 16.dp
        )
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val messageBitmap = remember(message.imageBase64) {
        message.imageBase64?.let { base64 ->
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isUser) "user_message" else "model_message"),
        horizontalAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 290.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Historical uploaded image / generated art canvas
                if (messageBitmap != null) {
                    Image(
                        bitmap = messageBitmap.asImageBitmap(),
                        contentDescription = if (isUser) "Uploaded Photo Attachment" else "AI Generated Art",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isUser) 130.dp else 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Crop
                    )
                }

                Text(
                    text = message.text,
                    fontSize = getFontSize(14, fontSizeScale),
                    color = contentColor,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Actions and Timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            val formattedTime = remember(message.timestamp) {
                val date = Date(message.timestamp)
                val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
                formatter.format(date)
            }

            Text(
                text = formattedTime,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            // ACTION BAR FOR ASSISTANT RESPONSES (Copy Response, Save image, Share image, Regenerate Response)
            if (!isUser) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                        Toast.makeText(context, "Copied response to clipboard 📋", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Copy Response",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // AI Art tools for generated images
                if (message.imageBase64 != null) {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Saved masterpiece to gallery 💾 inside /Pictures/RHVTArt!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Text("💾", fontSize = 11.sp)
                    }

                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Artwork secure link copied to share! 📤", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Text("📤", fontSize = 11.sp)
                    }
                }

                IconButton(
                    onClick = {
                        viewModel.regenerateLastResponse()
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Regenerate Response",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AIArtDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, String) -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("Realistic Photo") }
    
    val artStyles = listOf(
        "Realistic Photo" to "📸",
        "Anime / Manga" to "🌸",
        "Cyberpunk Neon" to "🌃",
        "Watercolor Paint" to "🎨",
        "3D Render Model" to "🤖",
        "Pencil Sketch" to "✏️",
        "Retro Vector Art" to "👾",
        "Oil Painting" to "🖼️"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (prompt.isNotBlank()) {
                        onGenerate(prompt, selectedStyle)
                        onDismiss()
                    }
                },
                enabled = prompt.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Generate ✨", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🎨 AI Art Designer")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Describe the artwork you want to create below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("A cozy futuristic library under starlight...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4
                )

                Text(
                    text = "Select Art Style",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(artStyles) { (styleName, emoji) ->
                        val isSelected = selectedStyle == styleName
                        Card(
                            onClick = { selectedStyle = styleName },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = 1.5.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                }
                            ),
                            modifier = Modifier.height(72.dp).width(120.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(emoji, fontSize = 22.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = styleName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

// ==========================================
// 5. INPUT BAR ROW CONTROLLER
// ==========================================
@Composable
fun ChatInputRow(
    isTyping: Boolean,
    onSend: (String, String?) -> Unit,
    onGenerateArt: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var textState by remember { mutableStateOf("") }
    var activeImageBase64 by remember { mutableStateOf<String?>(null) }
    val isImageAttached = activeImageBase64 != null
    var showAIArtDialog by remember { mutableStateOf(false) }

    val labelText = if (isImageAttached) "Message with attached mockup..." else "Ask RHVT AI anything..."

    if (showAIArtDialog) {
        AIArtDialog(
            onDismiss = { showAIArtDialog = false },
            onGenerate = { prompt, style ->
                onGenerateArt(prompt, style)
            }
        )
    }

    Column(modifier = modifier) {
        
        AnimatedVisibility(
            visible = isImageAttached,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bytes = remember(activeImageBase64) {
                        try {
                            Base64.decode(activeImageBase64!!, Base64.DEFAULT)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val bitmap = remember(bytes) {
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attached mock",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Attached mock",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = "diagram_visual_input.jpg target",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Multimodal simulator attachment ready",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { activeImageBase64 = null },
                    modifier = Modifier.testTag("remove_attachment_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Remove attachment",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Camera input simulator
            IconButton(
                onClick = {
                    activeImageBase64 = if (activeImageBase64 == null) {
                        MOCK_IMAGE_BASE64_RED
                    } else {
                        null
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isImageAttached) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                    .testTag("simulate_multimodal_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Simulate Camera shot",
                    tint = if (isImageAttached) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            // AI Art Designer Trigger
            IconButton(
                onClick = { showAIArtDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .testTag("art_dialog_trigger_button")
            ) {
                Text("🎨", fontSize = 20.sp)
            }

            // Input field with large hit area and nice round contour
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("message_input_field"),
                placeholder = {
                    Text(
                        text = labelText,
                        fontSize = 14.sp
                    )
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Submit Button
            val canSend = (textState.isNotBlank() || isImageAttached) && !isTyping
            IconButton(
                onClick = {
                    if (canSend) {
                        onSend(textState, activeImageBase64)
                        textState = ""
                        activeImageBase64 = null
                    }
                },
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = 0.5f
                        )
                    )
                    .testTag("send_message_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Message",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.5f
                    )
                )
            }
        }
    }
}

// Premium button tab switches
@Composable
fun TabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "tab_color"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tab_text"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .height(36.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==========================================
// 7. SECURE AUTHENTICATION PORTAL (ENTRY POINT)
// ==========================================
@Composable
fun AuthPortalScreen(
    viewModel: ChatViewModel
) {
    // Current step in the auth flow: "welcome", "signin", "signup"
    var currentAuthStep by remember { mutableStateOf("welcome") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    
    // Email authentication verification
    var showVerificationStep by remember { mutableStateOf(false) }
    var enteredVerifyCode by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    
    // Custom simulated Google Accounts dialog toggle
    var showGoogleAccountChooser by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Logo
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("💫", fontSize = 42.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Welcome to RHVT AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your intelligent AI assistant for learning, creativity, productivity, and everyday conversations.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (currentAuthStep == "welcome") {
                // SECTION 2 WELCOME SELECTION LIST
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. Get Started (Email Sign Up)
                        Button(
                            onClick = { 
                                currentAuthStep = "signup"
                                authError = null
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("welcome_get_started_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("✨ Get Started", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 2. Continue as Guest
                        OutlinedButton(
                            onClick = {
                                viewModel.signInAsGuest()
                                Toast.makeText(context, "Welcome to RHVT AI Guest Mode!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("welcome_guest_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Continue as Guest", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 3. Sign In (Goes to SignIn mode / offers Google)
                        OutlinedButton(
                            onClick = { 
                                currentAuthStep = "signin"
                                authError = null 
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("welcome_sign_in_btn"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Sign In", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Also keep fallback Google option at the bottom
                        TextButton(
                            onClick = { showGoogleAccountChooser = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Or continue with Google", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                // Email Auth flows (signin or signup page)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!showVerificationStep) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (currentAuthStep == "signup") "Create Account" else "Sign In",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                TextButton(
                                    onClick = { 
                                        currentAuthStep = "welcome"
                                        authError = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("← Back", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (authError != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = authError!!,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            if (currentAuthStep == "signup") {
                                OutlinedTextField(
                                    value = displayName,
                                    onValueChange = { displayName = it },
                                    label = { Text("Display Name") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("auth_name_field")
                                )
                            }

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("auth_email_field")
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("auth_password_field")
                            )

                            if (currentAuthStep == "signup") {
                                OutlinedTextField(
                                    value = confirmPassword,
                                    onValueChange = { confirmPassword = it },
                                    label = { Text("Confirm Password") },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("auth_confirm_password_field")
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Submit main button
                            Button(
                                onClick = {
                                    authError = null
                                    if (email.isBlank() || password.isBlank()) {
                                        authError = "Please fill in all security fields."
                                        return@Button
                                    }
                                    if (!email.contains("@")) {
                                        authError = "Invalid email format."
                                        return@Button
                                    }
                                    if (currentAuthStep == "signup") {
                                        if (displayName.isBlank()) {
                                            authError = "Please enter a display name."
                                            return@Button
                                        }
                                        if (password != confirmPassword) {
                                            authError = "Passwords do not match."
                                            return@Button
                                        }
                                        // Move to verification code mode
                                        showVerificationStep = true
                                    } else {
                                        val error = viewModel.loginEmailAccount(email, password)
                                        if (error != null) {
                                            authError = error
                                        } else {
                                            Toast.makeText(context, "Welcome back!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("auth_primary_submit"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (currentAuthStep == "signup") "Register Secure Account" else "Secure Sign In",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            // Toggle state text button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (currentAuthStep == "signup") "Already have an account?" else "New to RHVT AI?",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        currentAuthStep = if (currentAuthStep == "signup") "signin" else "signup"
                                        authError = null
                                    }
                                ) {
                                    Text(
                                        text = if (currentAuthStep == "signup") "Log In" else "Sign Up",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                        } else {
                            // Verification Step
                            Text(
                                text = "Email Account Verification",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "To authorize your premium security profile, please enter the temporary verification code sent to your inbox at $email",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🗝️ Code Hint: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("**1947**", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            OutlinedTextField(
                                value = enteredVerifyCode,
                                onValueChange = { enteredVerifyCode = it },
                                label = { Text("Verification Code") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("auth_verification_input")
                            )

                            if (authError != null) {
                                Text(text = authError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    if (enteredVerifyCode.trim() == "1947") {
                                        viewModel.verifyEmailAndSignIn(email, displayName)
                                        Toast.makeText(context, "Account authorized! Welcome to RHVT AI.", Toast.LENGTH_LONG).show()
                                    } else {
                                        authError = "Invalid verification code. Correct code is 1947."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("auth_verify_submit"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Verify & Start Chatting", fontWeight = FontWeight.Bold)
                            }

                            TextButton(
                                onClick = {
                                    showVerificationStep = false
                                    authError = null
                                }
                            ) {
                                Text("Go Back")
                            }
                        }
                    }
                }
            }
        }
    }

    // SIMULATED GOOGLE ACCOUNT CHOOSER DIALOG
    if (showGoogleAccountChooser) {
        AlertDialog(
            onDismissRequest = { showGoogleAccountChooser = false },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("G", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Choose an account", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("to continue to RHVT AI", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Account row (Hridhan)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.verifyEmailAndSignIn("hridhan175@gmail.com", "Hridhan")
                                showGoogleAccountChooser = false
                                Toast.makeText(context, "Signed in as Hridhan", Toast.LENGTH_SHORT).show()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("H", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Hridhan", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("hridhan175@gmail.com", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Account row (Secondary Mock)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.verifyEmailAndSignIn("tester@rhvtai.com", "RHVT Explorer")
                                showGoogleAccountChooser = false
                                Toast.makeText(context, "Signed in as RHVT Explorer", Toast.LENGTH_SHORT).show()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("R", color = MaterialTheme.colorScheme.onSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("RHVT Explorer", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("tester@rhvtai.com", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGoogleAccountChooser = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// 6. ONBOARDING WALKTHROUGH OVERLAY
// ==========================================
@Composable
fun OnboardingTutorWalkthrough(
    onDismiss: () -> Unit
) {
    var currentSlide by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header slide indicator & skip button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Guide ${currentSlide + 1} of 3",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        contentPadding = PaddingValues(0.dp),
                        onClick = onDismiss
                    ) {
                        Text("Skip", fontSize = 11.sp)
                    }
                }

                // Interactive slides content
                AnimatedContent(
                    targetState = currentSlide,
                    label = "slide_anim",
                    modifier = Modifier.height(180.dp)
                ) { slide ->
                    when (slide) {
                        0 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text("💫", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Welcome to RHVT AI",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Your intelligence assistant for learning, creativity, productivity, and everyday conversations.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        1 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text("⚡", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "RHVT Lite & Ultra",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "⚡ RHVT Lite gives high-speed answers. 🚀 RHVT Ultra provides deep critical reasoning for math, writing, and coding.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        2 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text("🎨", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Customizable Accents",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Personalize font scales and colors inside the Settings page to suit your style and accessibility requirements.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Dot Indicators Pagers
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == currentSlide) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == currentSlide) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                // Forward Slide Buttons
                Button(
                    onClick = {
                        if (currentSlide < 2) {
                            currentSlide++
                        } else {
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("onboarding_next_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (currentSlide < 2) "Next" else "Get Started 🚀",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ==========================================
// 8. STARTUP SPLASH SCREEN
// ==========================================
@Composable
fun StartupSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🌟",
                fontSize = 56.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "RHVT AI",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Loading your AI assistant...",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// ==========================================
// 9. ERROR RECOVERY GRAPHICAL INTERFACE
// ==========================================
@Composable
fun ErrorRecoveryScreen(
    errorMessage: String,
    onRetry: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSubmitReport: (String) -> Unit
) {
    var reportText by remember { mutableStateOf("") }
    var reportSubmitted by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚠️",
                fontSize = 54.sp
            )
            Text(
                text = "Something went wrong.",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Diagnostics Error:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onContinueAsGuest,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue as Guest", fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (!reportSubmitted) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Submit an error report to our engineers:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reportText,
                        onValueChange = { reportText = it },
                        placeholder = { Text("What happened preceding the problem...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            onSubmitReport(reportText)
                            reportSubmitted = true
                            Toast.makeText(context, "Error report compiled & sent!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Send Error Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Thank you! Your diagnostics report was compiled and securely sent.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreenLayout(
    viewModel: ChatViewModel
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val plan by viewModel.subscriptionPlan.collectAsStateWithLifecycle()
    val billingCycle by viewModel.billingCycle.collectAsStateWithLifecycle()
    val renewalDate by viewModel.renewalDate.collectAsStateWithLifecycle()
    val billingHistory by viewModel.billingHistory.collectAsStateWithLifecycle()
    val dailyMessageCount by viewModel.dailyMessageCount.collectAsStateWithLifecycle()

    var isYearlySelected by remember { mutableStateOf(billingCycle == "Yearly") }
    var showCheckoutDialogForPlan by remember { mutableStateOf<String?>(null) } // "Plus" or "Pro"
    var showCancelConfirmDialog by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isGuest = currentUser?.provider == "Guest" || currentUser?.email == "guest_explorer@rhvtai.com"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- 1. HERO TITLE BLOCK ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = "RHVT AI Premium",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Unlock higher limits, experimental tools, & top-tier speed.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // --- 2. BILLING CYCLE TAB SWITCHER ---
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Monthly Button
                Button(
                    onClick = { isYearlySelected = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isYearlySelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (!isYearlySelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    elevation = null
                ) {
                    Text("Monthly", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Yearly Button
                Button(
                    onClick = { isYearlySelected = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isYearlySelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isYearlySelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    elevation = null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Yearly", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ) {
                            Text("SAVE 17%", fontSize = 8.sp, modifier = Modifier.padding(2.dp))
                        }
                    }
                }
            }
        }

        // --- 3. DYNAMIC TRIPLE PLAN CARDS GRID ---
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val isTablet = maxWidth >= 768.dp
            val containerWidth = if (isTablet) 1100.dp else 600.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = containerWidth)
                    .align(Alignment.Center)
            ) {
                if (isTablet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PlanItemCard(
                                planName = "Free",
                                iconEmoji = "🆓",
                                badgeText = "Current Tier",
                                isActive = plan == "Free",
                                priceText = "$0",
                                cycleSubtext = "forever",
                                featuresList = listOf(
                                    "Guest Mode accessibility",
                                    "Account Creation options",
                                    "RHVT Lite (powered by Flash)",
                                    "Limited RHVT Ultra daily queries",
                                    "Basic Image Generation",
                                    "Save recent chats",
                                    "Dark and Light mode style toggle",
                                    "Basic cloud sync"
                                ),
                                onActionClick = {},
                                actionButtonText = "Active Plan",
                                enabledAction = false,
                                highlight = false
                            )
                        }

                        val plusPrice = if (isYearlySelected) "$49.99" else "$4.99"
                        val plusSubtext = if (isYearlySelected) "/year" else "/month"
                        Box(modifier = Modifier.weight(1f)) {
                            PlanItemCard(
                                planName = "Plus",
                                iconEmoji = "⭐",
                                badgeText = "Most Popular 🔥",
                                isActive = plan == "Plus",
                                priceText = plusPrice,
                                cycleSubtext = plusSubtext,
                                featuresList = listOf(
                                    "Unlimited RHVT Lite",
                                    "Generous RHVT Ultra usage",
                                    "Faster response times",
                                    "Better image generation limits",
                                    "Longer conversations & history",
                                    "Cloud sync with security",
                                    "Priority server connections",
                                    "Advanced chat management"
                                ),
                                onActionClick = {
                                    if (isGuest) {
                                        Toast.makeText(context, "Sign in with an account to purchase Plus!", Toast.LENGTH_LONG).show()
                                    } else {
                                        showCheckoutDialogForPlan = "Plus"
                                    }
                                },
                                actionButtonText = if (plan == "Plus") "Active (Manage)" else "Upgrade to Plus",
                                enabledAction = plan != "Plus",
                                highlight = true,
                                showUpgradeBadge = true
                            )
                        }

                        val proPrice = if (isYearlySelected) "$99.99" else "$9.99"
                        val proSubtext = if (isYearlySelected) "/year" else "/month"
                        Box(modifier = Modifier.weight(1f)) {
                            PlanItemCard(
                                planName = "Pro",
                                iconEmoji = "🚀",
                                badgeText = "Best Value",
                                isActive = plan == "Pro",
                                priceText = proPrice,
                                cycleSubtext = proSubtext,
                                featuresList = listOf(
                                    "Highest AI limits",
                                    "Best performance and output speed",
                                    "Highest image generation limits",
                                    "Experimental AI tools & engines",
                                    "Future premium models priority",
                                    "Priority 24/7 client support",
                                    "Premium customization options",
                                    "Maximum cloud sync storage size"
                                ),
                                onActionClick = {
                                    if (isGuest) {
                                        Toast.makeText(context, "Sign in with an account to purchase Pro!", Toast.LENGTH_LONG).show()
                                    } else {
                                        showCheckoutDialogForPlan = "Pro"
                                    }
                                },
                                actionButtonText = if (plan == "Pro") "Active (Manage)" else "Upgrade to Pro",
                                enabledAction = plan != "Pro",
                                highlight = false,
                                proStyling = true
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PlanItemCard(
                            planName = "Free",
                            iconEmoji = "🆓",
                            badgeText = "Current Tier",
                            isActive = plan == "Free",
                            priceText = "$0",
                            cycleSubtext = "forever",
                            featuresList = listOf(
                                "Guest Mode accessibility",
                                "Account Creation options",
                                "RHVT Lite (powered by Flash)",
                                "Limited RHVT Ultra daily queries",
                                "Basic Image Generation",
                                "Save recent chats",
                                "Dark and Light mode style toggle",
                                "Basic cloud sync"
                            ),
                            onActionClick = {},
                            actionButtonText = "Active Plan",
                            enabledAction = false,
                            highlight = false
                        )

                        val plusPrice = if (isYearlySelected) "$49.99" else "$4.99"
                        val plusSubtext = if (isYearlySelected) "/year" else "/month"
                        PlanItemCard(
                            planName = "Plus",
                            iconEmoji = "⭐",
                            badgeText = "Most Popular 🔥",
                            isActive = plan == "Plus",
                            priceText = plusPrice,
                            cycleSubtext = plusSubtext,
                            featuresList = listOf(
                                "Unlimited RHVT Lite",
                                "Generous RHVT Ultra usage",
                                "Faster response times",
                                "Better image generation limits",
                                "Longer conversations & history",
                                "Cloud sync with security",
                                "Priority server connections",
                                "Advanced chat management"
                            ),
                            onActionClick = {
                                if (isGuest) {
                                    Toast.makeText(context, "Sign in with an account to purchase Plus!", Toast.LENGTH_LONG).show()
                                } else {
                                    showCheckoutDialogForPlan = "Plus"
                                }
                            },
                            actionButtonText = if (plan == "Plus") "Active (Manage)" else "Upgrade to Plus",
                            enabledAction = plan != "Plus",
                            highlight = true,
                            showUpgradeBadge = true
                        )

                        val proPrice = if (isYearlySelected) "$99.99" else "$9.99"
                        val proSubtext = if (isYearlySelected) "/year" else "/month"
                        PlanItemCard(
                            planName = "Pro",
                            iconEmoji = "🚀",
                            badgeText = "Best Value",
                            isActive = plan == "Pro",
                            priceText = proPrice,
                            cycleSubtext = proSubtext,
                            featuresList = listOf(
                                "Highest AI limits",
                                "Best performance and output speed",
                                "Highest image generation limits",
                                "Experimental AI tools & engines",
                                "Future premium models priority",
                                "Priority 24/7 client support",
                                "Premium customization options",
                                "Maximum cloud sync storage size"
                            ),
                            onActionClick = {
                                if (isGuest) {
                                    Toast.makeText(context, "Sign in with an account to purchase Pro!", Toast.LENGTH_LONG).show()
                                } else {
                                    showCheckoutDialogForPlan = "Pro"
                                }
                            },
                            actionButtonText = if (plan == "Pro") "Active (Manage)" else "Upgrade to Pro",
                            enabledAction = plan != "Pro",
                            highlight = false,
                            proStyling = true
                        )
                    }
                }
            }
        }

        // --- 4. ACCORDION / UTILITY BUTTONS (RESTORE, CANCEL) ---
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Sub Status Details Panel (If Active Sub exists)
            if (plan != "Free") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🗓️", fontSize = 16.sp)
                            Text(
                                text = "Subscription Status",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your current RHVT $plan ($billingCycle) plan is active.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Next auto-renewal date: $renewalDate",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCancelConfirmDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.redIndicator()
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Cancel Subscription", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Restore Purchases Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Already purchased Premium?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Restore past subscription purchases from your active Play Store account.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        onClick = {
                            isRestoring = true
                            viewModel.restorePurchases()
                            scope.launch {
                                kotlinx.coroutines.delay(1200)
                                isRestoring = false
                                Toast.makeText(context, "Purchases restored successfully!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Restore Purchases", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // DEMO TOOL: Reset Limits
            if (plan == "Free") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Demo Limit Tracker",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                "Usage today: $dailyMessageCount / 5 queries",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.resetDailyLimit()
                                Toast.makeText(context, "Daily usage tracker has been reset!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Reset Count (Demo)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // --- 5. BILLING INVOICES / HISTORY TABLE ---
            if (billingHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Billing History",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        billingHistory.forEachIndexed { idx, item ->
                            Column {
                                if (idx > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.planName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "${item.date} • ${item.invoiceId} • ${item.paymentMethod}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ) {
                                            Text(item.status, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                        Text(
                                            text = item.amount,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- CHECKOUT virtual DIALOG ---
    if (showCheckoutDialogForPlan != null) {
        val selectedPlanText = showCheckoutDialogForPlan!!
        val cycleText = if (isYearlySelected) "Yearly" else "Monthly"
        val priceLabel = if (selectedPlanText == "Plus") {
            if (isYearlySelected) "$89.99" else "$9.99"
        } else {
            if (isYearlySelected) "$179.99" else "$19.99"
        }

        var cardNumber by remember { mutableStateOf("") }
        var tempCardName by remember { mutableStateOf(currentUser?.displayName ?: "AI Explorer") }
        var tempExpiry by remember { mutableStateOf("12/29") }
        var tempCvv by remember { mutableStateOf("453") }
        var isProcessingPayment by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCheckoutDialogForPlan = null },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessingPayment = true
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            isProcessingPayment = false
                            val last4 = if (cardNumber.length >= 4) cardNumber.takeLast(4) else "4321"
                            viewModel.selectPlan(selectedPlanText, cycleText, last4)
                            showCheckoutDialogForPlan = null
                            Toast.makeText(context, "$selectedPlanText Plan Activated!", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isProcessingPayment && cardNumber.length >= 4,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isProcessingPayment) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Pay & Securely Activate", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCheckoutDialogForPlan = null }, enabled = !isProcessingPayment) {
                    Text("Go Back")
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (selectedPlanText == "Plus") "⭐" else "🚀")
                    Text("Secure Payment Invoice", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Summary Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "RHVT $selectedPlanText",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Renewal: $cycleText billing cycles",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                priceLabel,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = "There are no recurring setup or processing fees. You can safely cancel online at any time with a single tap.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp
                    )

                    // Credit Card Inputs
                    OutlinedTextField(
                        value = cardNumber,
                        onValueChange = { cardNumber = it.filter { c -> c.isDigit() }.take(16) },
                        label = { Text("Credit Card Number") },
                        placeholder = { Text("4111 2222 3333 4321") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = tempExpiry,
                            onValueChange = { tempExpiry = it.take(5) },
                            label = { Text("Expiry Date") },
                            placeholder = { Text("12/28") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = tempCvv,
                            onValueChange = { tempCvv = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("CVV") },
                            placeholder = { Text("732") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = tempCardName,
                        onValueChange = { tempCardName = it },
                        label = { Text("Cardholder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        )
    }

    // --- CANCEL CONFIRMATION DIALOG ---
    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelSubscription()
                        showCancelConfirmDialog = false
                        Toast.makeText(context, "Subscription canceled successfully.", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Yes, Downgrade to Free", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmDialog = false }) {
                    Text("Keep Premium")
                }
            },
            title = { Text("Downgrade Subscription?") },
            text = {
                Text(
                    text = "If you downgrade, your premium status and features will terminate immediately, and limits will revert to normal. Are you sure you want to proceed?",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

// Subordinate plan display card helper
@Composable
fun PlanItemCard(
    planName: String,
    iconEmoji: String,
    badgeText: String,
    isActive: Boolean,
    priceText: String,
    cycleSubtext: String,
    featuresList: List<String>,
    onActionClick: () -> Unit,
    actionButtonText: String,
    enabledAction: Boolean,
    highlight: Boolean,
    proStyling: Boolean = false,
    showUpgradeBadge: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_card_${planName.lowercase()}")
            .border(
                width = if (isActive || highlight) 2.dp else 1.dp,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else if (highlight) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            } else if (proStyling) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(iconEmoji, fontSize = 20.sp)
                    Text(
                        text = "RHVT $planName",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = if (proStyling) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Badge(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else if (highlight) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    contentColor = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else if (highlight) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = priceText,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = cycleSubtext,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                featuresList.forEach { feat ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "✅",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = feat,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = onActionClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                enabled = enabledAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (highlight) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (highlight) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            ) {
                Text(
                    text = actionButtonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// color indicator helper for downgrading / error messages
@Composable
fun ColorScheme.redIndicator(): Color = Color(0xffdf3a3a)
