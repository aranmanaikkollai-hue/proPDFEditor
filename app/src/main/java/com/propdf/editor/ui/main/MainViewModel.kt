    // Settings
    private val _isDarkMode = MutableStateFlow<Boolean?>(null)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    private val _useDynamicColors = MutableStateFlow(true)
    val useDynamicColors: StateFlow<Boolean> = _useDynamicColors.asStateFlow()

    init {
        observeFiles()
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch(dispatchers.io) {
            val prefs = context.getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE)
            _isDarkMode.value = prefs.getBoolean("dark_mode", context.resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            _useDynamicColors.value = prefs.getBoolean("dynamic_colors", true)
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            context.getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("dark_mode", enabled)
                .apply()
            _isDarkMode.value = enabled
        }
    }

    fun setDynamicColors(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            context.getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("dynamic_colors", enabled)
                .apply()
            _useDynamicColors.value = enabled
        }
    }
