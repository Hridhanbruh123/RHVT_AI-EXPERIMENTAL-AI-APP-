package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.database.ChatDatabase
import com.example.data.database.ChatMessage
import com.example.data.database.ChatSession
import com.example.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class UserAccount(
    val email: String,
    val displayName: String,
    val profilePicUrl: String? = null,
    val isEmailVerified: Boolean = false,
    val provider: String = "Email" // "Google" or "Email"
)

data class BillingRecord(
    val invoiceId: String,
    val date: String,
    val planName: String,
    val amount: String,
    val paymentMethod: String,
    val status: String
)

class ChatViewModel(
    application: Application,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    // USER PREFERENCES CONTROL (Survives app lifecycle gracefully via SharedPreferences)
    private val prefs = getApplication<Application>().getSharedPreferences("nova_chat_prefs", android.content.Context.MODE_PRIVATE)

    // Current active signed in user state
    private val _currentUser = MutableStateFlow<UserAccount?>(
        run {
            val email = prefs.getString("current_user_email", null)
            if (email != null) {
                UserAccount(
                    email = email,
                    displayName = prefs.getString("current_user_name", "AI Explorer") ?: "AI Explorer",
                    isEmailVerified = prefs.getBoolean("current_user_verified", false),
                    provider = prefs.getString("current_user_provider", "Email") ?: "Email"
                )
            } else {
                null
            }
        }
    )
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    // --- SUBSCRIPTION STATE MANAGEMENT ---
    private val _subscriptionPlan = MutableStateFlow(prefs.getString("sub_plan", "Free") ?: "Free")
    val subscriptionPlan: StateFlow<String> = _subscriptionPlan.asStateFlow()

    private val _billingCycle = MutableStateFlow(prefs.getString("billing_cycle", "Monthly") ?: "Monthly")
    val billingCycle: StateFlow<String> = _billingCycle.asStateFlow()

    private val _renewalDate = MutableStateFlow(prefs.getString("renewal_date", "N/A") ?: "N/A")
    val renewalDate: StateFlow<String> = _renewalDate.asStateFlow()

    private val _dailyMessageCount = MutableStateFlow(prefs.getInt("daily_msg_count", 0))
    val dailyMessageCount: StateFlow<Int> = _dailyMessageCount.asStateFlow()

    private val _showLimitReachedDialog = MutableStateFlow(false)
    val showLimitReachedDialog: StateFlow<Boolean> = _showLimitReachedDialog.asStateFlow()

    private val _billingHistory = MutableStateFlow<List<BillingRecord>>(emptyList())
    val billingHistory: StateFlow<List<BillingRecord>> = _billingHistory.asStateFlow()

    private fun loadBillingHistory(): List<BillingRecord> {
        val raw = prefs.getString("billing_history_raw", null)
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            raw.split(";").filter { it.isNotBlank() }.map { chunk ->
                val parts = chunk.split("|")
                BillingRecord(
                    invoiceId = parts.getOrElse(0) { "INV-UNKNOWN" },
                    date = parts.getOrElse(1) { "2026-06-11" },
                    planName = parts.getOrElse(2) { "RHVT Free" },
                    amount = parts.getOrElse(3) { "$0.00" },
                    paymentMethod = parts.getOrElse(4) { "N/A" },
                    status = parts.getOrElse(5) { "Completed" }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveBillingHistory(list: List<BillingRecord>) {
        val raw = list.joinToString(";") { r ->
            "${r.invoiceId}|${r.date}|${r.planName}|${r.amount}|${r.paymentMethod}|${r.status}"
        }
        prefs.edit().putString("billing_history_raw", raw).apply()
        _billingHistory.value = list
    }

    // Cloud Synchronicity Status indicators
    private val _isCloudSyncing = MutableStateFlow(false)
    val isCloudSyncing: StateFlow<Boolean> = _isCloudSyncing.asStateFlow()

    private val _lastSyncedTime = MutableStateFlow(prefs.getLong("last_synced_time", 0L))
    val lastSyncedTime: StateFlow<Long> = _lastSyncedTime.asStateFlow()

    // Advanced search, pin, and archive flows
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    // List of all past conversational sessions (raw from DB)
    val allSessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Reactive filtered chat sessions list (Filters query and Archive/Pin layout dynamically)
    val filteredSessions: StateFlow<List<ChatSession>> = combine(allSessions, _searchQuery, _showArchived) { sessions, query, showArchived ->
        var list = sessions.filter { it.isArchived == showArchived }
        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true)
            }
        }
        // Pin priority sort, then descending timestamp
        list.sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.timestamp })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current active session ID
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Selected model for generation (Saved toPreferences for future sessions)
    private val _selectedModel = MutableStateFlow(prefs.getString("selected_model", "gemini-3.5-flash") ?: "gemini-3.5-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // Active conversational mode/template for current session
    val currentSessionMode: StateFlow<String> = combine(_currentSessionId, allSessions) { sessionId, sessions ->
        sessions.find { it.id == sessionId }?.mode ?: "Smart"
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "Smart"
    )

    private val _themeSelection = MutableStateFlow(prefs.getString("theme_selection", "System") ?: "System")
    val themeSelection: StateFlow<String> = _themeSelection.asStateFlow()

    private val _colorPalette = MutableStateFlow(prefs.getString("color_palette", "Indigo") ?: "Indigo")
    val colorPalette: StateFlow<String> = _colorPalette.asStateFlow()

    private val _fontSizeScale = MutableStateFlow(prefs.getString("font_size_scale", "Standard") ?: "Standard")
    val fontSizeScale: StateFlow<String> = _fontSizeScale.asStateFlow()

    private val _highContrast = MutableStateFlow(prefs.getBoolean("high_contrast", false))
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    private val _seenOnboarding = MutableStateFlow(prefs.getBoolean("seen_onboarding", false))
    val seenOnboarding: StateFlow<Boolean> = _seenOnboarding.asStateFlow()

    // Active coroutine job to allow cleanly canceling generation mid-flight
    private var activeGenerationJob: kotlinx.coroutines.Job? = null

    fun setThemeSelection(selection: String) {
        _themeSelection.value = selection
        prefs.edit().putString("theme_selection", selection).apply()
    }

    fun setColorPalette(palette: String) {
        _colorPalette.value = palette
        prefs.edit().putString("color_palette", palette).apply()
    }

    fun setFontSizeScale(scale: String) {
        _fontSizeScale.value = scale
        prefs.edit().putString("font_size_scale", scale).apply()
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        prefs.edit().putBoolean("high_contrast", enabled).apply()
    }

    fun setSeenOnboarding(seen: Boolean) {
        _seenOnboarding.value = seen
        prefs.edit().putBoolean("seen_onboarding", seen).apply()
    }

    fun stopGenerating() {
        activeGenerationJob?.cancel()
        _isTyping.value = false
    }

    // Live stream of messages for the currently selected session
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSessionMessages: StateFlow<List<ChatMessage>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(emptyList())
            } else {
                repository.getMessagesForSession(sessionId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Indicates that the Gemini API is calculating/generating a response
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    // Holds any UI specific error message
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    // Check if Gemini API key is missing (i.e. is placeholder or blank)
    val isApiKeyMissing: Boolean
        get() {
            val key = BuildConfig.GEMINI_API_KEY
            return key.isBlank() || key == "MY_GEMINI_API_KEY" || key.contains("PLACEHOLDER")
        }

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    init {
        _billingHistory.value = loadBillingHistory()
        loadStartupData()
    }

    private fun loadStartupData() {
        _isInitializing.value = true
        _startupError.value = null
        viewModelScope.launch {
            try {
                // Ensure clear database/session flow initialized elegantly
                // Let's first ensure we wait for Room database to start up and load or crash safely
                allSessions.first()
                val sessions = allSessions.value
                if (sessions.isNotEmpty()) {
                    if (_currentSessionId.value == null) {
                        _currentSessionId.value = sessions.first().id
                    }
                } else {
                    startNewSession()
                }
                
                // Keep splash screen visible for a beautiful transition
                kotlinx.coroutines.delay(1000)
                _isInitializing.value = false
            } catch (e: Exception) {
                _startupError.value = "Room DB Corruption / SharedPreferences failure: ${e.message ?: "Launch failed"}"
                _isInitializing.value = false
            }
        }
    }

    fun retryStartup() {
        loadStartupData()
    }

    fun signInAsGuest() {
        val account = UserAccount(
            email = "guest_explorer@novachat.ai",
            displayName = "Guest Explorer",
            profilePicUrl = null,
            isEmailVerified = false,
            provider = "Guest"
        )
        saveCurrentUser(account)
    }

    fun submitErrorReport(report: String) {
        // Pretend to submit error safely without blocking/crashing
        viewModelScope.launch {
            _isTyping.value = true
            kotlinx.coroutines.delay(600)
            _isTyping.value = false
        }
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
        _errorState.value = null
    }

    fun startNewSession(mode: String = "Smart") {
        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()
            repository.createNewSession(newId, "New Conversation", mode = mode)
            _currentSessionId.value = newId
            _errorState.value = null
        }
    }

    fun setSessionMode(mode: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            repository.updateSessionMode(sessionId, mode)
        }
    }

    fun setModel(modelName: String) {
        val isGuest = currentUser.value?.provider == "Guest" || currentUser.value?.email == "guest_explorer@novachat.ai"
        if (isGuest && modelName == "gemini-1.5-pro") {
            // Block Ultra model for guests
            return
        }
        _selectedModel.value = modelName
        prefs.edit().putString("selected_model", modelName).apply()
    }

    // --- PREMIUM ACCOUNTS & AUTHENTICATION SYSTEM ---
    fun googleSignIn(email: String = "hridhan175@gmail.com") {
        viewModelScope.launch {
            _isTyping.value = true
            kotlinx.coroutines.delay(1000) // Beautiful authentic auth delay loading indicator
            val account = UserAccount(
                email = email,
                displayName = "Hridhan",
                profilePicUrl = null,
                isEmailVerified = true,
                provider = "Google"
            )
            saveCurrentUser(account)
            _isTyping.value = false
        }
    }

    fun registerEmailAccount(email: String, name: String, pass: String): Boolean {
        if (email.isBlank() || name.isBlank() || pass.isBlank()) return false
        val cleanEmail = email.trim().lowercase()
        prefs.edit()
            .putString("auth_pwd_$cleanEmail", pass)
            .putString("auth_name_$cleanEmail", name)
            .apply()
        return true
    }

    fun verifyEmailAndSignIn(email: String, name: String) {
        val account = UserAccount(email.trim().lowercase(), name, null, true, "Email")
        saveCurrentUser(account)
    }

    fun loginEmailAccount(email: String, pass: String): String? {
        val cleanEmail = email.trim().lowercase()
        val storedPass = prefs.getString("auth_pwd_$cleanEmail", null)
        if (storedPass == null) {
            return "No account found with this email. Please create an account."
        }
        if (storedPass != pass) {
            return "Invalid password. Please try again."
        }
        val name = prefs.getString("auth_name_$cleanEmail", "AI Explorer") ?: "AI Explorer"
        val account = UserAccount(cleanEmail, name, null, true, "Email")
        saveCurrentUser(account)
        return null
    }

    fun signOut() {
        prefs.edit()
            .remove("current_user_email")
            .remove("current_user_name")
            .remove("current_user_verified")
            .remove("current_user_provider")
            .apply()
        _currentUser.value = null
    }

    fun incrementDailyMessageCount() {
        val nextVal = _dailyMessageCount.value + 1
        _dailyMessageCount.value = nextVal
        prefs.edit().putInt("daily_msg_count", nextVal).apply()
    }

    fun resetDailyLimit() {
        _dailyMessageCount.value = 0
        prefs.edit().putInt("daily_msg_count", 0).apply()
    }

    fun dismissLimitReachedDialog() {
        _showLimitReachedDialog.value = false
    }

    fun selectPlan(plan: String, cycle: String, mockCardNumber: String = "4321") {
        _subscriptionPlan.value = plan
        _billingCycle.value = cycle
        prefs.edit()
            .putString("sub_plan", plan)
            .putString("billing_cycle", cycle)
            .apply()

        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        if (plan != "Free") {
            if (cycle == "Monthly") {
                cal.add(java.util.Calendar.MONTH, 1)
            } else {
                cal.add(java.util.Calendar.YEAR, 1)
            }
            val formattedDate = sdf.format(cal.time)
            _renewalDate.value = formattedDate
            prefs.edit().putString("renewal_date", formattedDate).apply()

            // Add billing record
            val sdfFull = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val dateStr = sdfFull.format(java.util.Date())
            val amount = if (plan == "Plus") {
                if (cycle == "Monthly") "$4.99" else "$49.99"
            } else {
                if (cycle == "Monthly") "$9.99" else "$99.99"
            }
            val record = BillingRecord(
                invoiceId = "INV-${(10000..99999).random()}",
                date = dateStr,
                planName = "RHVT $plan ($cycle)",
                amount = amount,
                paymentMethod = "Card (•••• $mockCardNumber)",
                status = "Paid"
            )
            val currentList = _billingHistory.value.toMutableList()
            currentList.add(0, record) // newest first
            saveBillingHistory(currentList)
        } else {
            _renewalDate.value = "N/A"
            prefs.edit().putString("renewal_date", "N/A").apply()
        }
        triggerCloudSync()
    }

    fun cancelSubscription() {
        _subscriptionPlan.value = "Free"
        prefs.edit().putString("sub_plan", "Free").apply()
        _renewalDate.value = "N/A"
        prefs.edit().putString("renewal_date", "N/A").apply()
        triggerCloudSync()
    }

    fun restorePurchases() {
        viewModelScope.launch {
            _isTyping.value = true
            kotlinx.coroutines.delay(1000)
            _isTyping.value = false
            
            val user = _currentUser.value
            if (user != null && user.provider != "Guest" && user.email != "guest_explorer@novachat.ai") {
                // Restore Plus for existing accounts as free bonus/saved purchase
                selectPlan("Plus", "Monthly", "7732")
            } else {
                _subscriptionPlan.value = "Free"
                prefs.edit().putString("sub_plan", "Free").apply()
            }
        }
    }

    private fun saveCurrentUser(account: UserAccount) {
        _currentUser.value = account
        prefs.edit()
            .putString("current_user_email", account.email)
            .putString("current_user_name", account.displayName)
            .putBoolean("current_user_verified", account.isEmailVerified)
            .putString("current_user_provider", account.provider)
            .apply()
        triggerCloudSync()
    }

    // --- CLOUD SYNC SYSTEM ---
    fun triggerCloudSync() {
        viewModelScope.launch {
            _isCloudSyncing.value = true
            kotlinx.coroutines.delay(1200) // Realistic loading to simulate uploading db contents
            val now = System.currentTimeMillis()
            _lastSyncedTime.value = now
            prefs.edit().putLong("last_synced_time", now).apply()
            _isCloudSyncing.value = false
        }
    }

    // --- ADVANCED CHAT MANAGEMENT ---
    fun togglePinSession(sessionId: String) {
        viewModelScope.launch {
            val session = allSessions.value.find { it.id == sessionId } ?: return@launch
            repository.updateSessionPinned(sessionId, !session.isPinned)
            triggerCloudSync()
        }
    }

    fun toggleArchiveSession(sessionId: String) {
        viewModelScope.launch {
            val session = allSessions.value.find { it.id == sessionId } ?: return@launch
            val nextArchived = !session.isArchived
            repository.updateSessionArchived(sessionId, nextArchived)
            triggerCloudSync()

            // If we archived the active session, switch to another non-archived session
            if (_currentSessionId.value == sessionId && nextArchived) {
                val nonArchived = allSessions.value.filter { it.id != sessionId && !it.isArchived }
                if (nonArchived.isNotEmpty()) {
                    _currentSessionId.value = nonArchived.first().id
                } else {
                    startNewSession()
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            
            // If we deleted the active session, switch to another session
            if (_currentSessionId.value == sessionId) {
                val sessions = allSessions.value.filter { it.id != sessionId && !it.isArchived }
                if (sessions.isNotEmpty()) {
                    _currentSessionId.value = sessions.first().id
                } else {
                    startNewSession()
                }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
            startNewSession()
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            repository.updateSessionTitle(sessionId, newTitle)
        }
    }

    fun regenerateLastResponse() {
        val sessionId = _currentSessionId.value ?: return
        
        activeGenerationJob?.cancel()

        activeGenerationJob = viewModelScope.launch {
            _isTyping.value = true
            _errorState.value = null

            val apiKey = BuildConfig.GEMINI_API_KEY
            if (isApiKeyMissing) {
                _errorState.value = "Gemini API Key is missing! Set it up in the Secrets tab to start chatting."
                _isTyping.value = false
                return@launch
            }

            try {
                repository.regenerateMessage(
                    sessionId = sessionId,
                    apiKey = apiKey,
                    model = _selectedModel.value,
                    mode = currentSessionMode.value
                )
            } catch (e: Exception) {
                _errorState.value = e.message ?: "An unknown error occurred"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun sendMessage(userText: String, imageBase64: String? = null) {
        val sessionId = _currentSessionId.value ?: return
        if (userText.trim().isEmpty() && imageBase64 == null) return

        if (_subscriptionPlan.value == "Free" && _dailyMessageCount.value >= 5) {
            _showLimitReachedDialog.value = true
            return
        }

        activeGenerationJob?.cancel()

        activeGenerationJob = viewModelScope.launch {
            _isTyping.value = true
            _errorState.value = null

            val isFirstMsg = currentSessionMessages.value.isEmpty()
            val apiKey = BuildConfig.GEMINI_API_KEY

            if (isApiKeyMissing) {
                // Graceful check in case the user did not set up their secrets yet
                _errorState.value = "Gemini API Key is missing! Set it up in the Secrets tab to start chatting."
                _isTyping.value = false
                return@launch
            }

            try {
                repository.sendMessage(
                    sessionId = sessionId,
                    userText = userText,
                    imageBase64 = imageBase64,
                    apiKey = apiKey,
                    model = _selectedModel.value,
                    mode = currentSessionMode.value,
                    isFirstMessage = isFirstMsg
                )
                incrementDailyMessageCount()
            } catch (e: Exception) {
                _errorState.value = e.message ?: "An unknown error occurred"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun generateImage(prompt: String, style: String) {
        val sessionId = _currentSessionId.value ?: return
        if (prompt.trim().isEmpty()) return

        if (_subscriptionPlan.value == "Free" && _dailyMessageCount.value >= 5) {
            _showLimitReachedDialog.value = true
            return
        }

        activeGenerationJob?.cancel()

        activeGenerationJob = viewModelScope.launch {
            _isTyping.value = true
            _errorState.value = null

            val isFirstMsg = currentSessionMessages.value.isEmpty()
            val apiKey = BuildConfig.GEMINI_API_KEY

            if (isFirstMsg) {
                val shortTitle = if (prompt.length > 20) prompt.take(17) + "..." else prompt
                repository.updateSessionTitle(sessionId, "Art: $shortTitle")
            }

            val userMessage = ChatMessage(
                sessionId = sessionId,
                role = "user",
                text = "Generate image: $prompt (Style: $style)",
                timestamp = System.currentTimeMillis()
            )

            if (isApiKeyMissing) {
                _errorState.value = "Gemini API Key is missing! Set it up in the Secrets tab to start chatting."
                _isTyping.value = false
                return@launch
            }

            try {
                // First insert the user prompt to database
                repository.insertMessage(userMessage)
                
                val result = repository.generateImage(
                    sessionId = sessionId,
                    prompt = prompt,
                    style = style,
                    apiKey = apiKey
                )
                if (result != "Success" && !result.startsWith("Generated Image")) {
                    _errorState.value = result
                }
                incrementDailyMessageCount()
            } catch (e: Exception) {
                _errorState.value = e.message ?: "An unknown error occurred"
            } finally {
                _isTyping.value = false
            }
        }
    }

    fun clearError() {
        _errorState.value = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                val database = ChatDatabase.getDatabase(application)
                val repository = ChatRepository(database.chatDao())
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
