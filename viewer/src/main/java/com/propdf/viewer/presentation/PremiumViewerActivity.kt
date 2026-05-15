package com.propdf.viewer.presentation

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.propdf.viewer.R
import com.propdf.viewer.databinding.ActivityPremiumViewerBinding
import com.propdf.viewer.gesture.SmoothScroller
import com.propdf.viewer.model.ViewMode
import com.propdf.viewer.model.ViewerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Premium PDF Viewer Activity with all advanced features:
 * - Smooth scrolling and pinch zoom with momentum
 * - Thumbnail sidebar with RecyclerView
 * - Page scrubber for fast jumping
 * - Dark/Night/Sepia/High-contrast themes
 * - Multi-tab viewing with horizontal RecyclerView
 * - Floating action buttons
 * - Bottom sheet controls
 * - Gesture-based UI toggle (tap to hide/show)
 * - System bar insets handling
 */
@AndroidEntryPoint
class PremiumViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPremiumViewerBinding
    private val viewModel: PremiumViewerViewModel by viewModels()

    private lateinit var pageAdapter: PremiumPageAdapter
    private lateinit var thumbnailAdapter: ThumbnailAdapter
    private lateinit var tabAdapter: TabAdapter

    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var smoothScroller: SmoothScroller? = null

    private var isUiVisible = true
    private var currentZoom = 1.0f

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val UI_ANIMATION_DURATION = 250L
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 10.0f

        fun createIntent(context: Context, filePath: String): Intent {
            return Intent(context, PremiumViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityPremiumViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        setupViewPager()
        setupThumbnailSidebar()
        setupTabBar()
        setupToolbar()
        setupBottomSheet()
        setupGestureHandling()
        setupPageScrubber()
        setupFloatingControls()
        observeViewModel()

        // Load document
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            viewModel.loadDocument(File(filePath))
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }
            binding.bottomControls.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
            }
            insets
        }
    }

    private fun setupViewPager() {
        pageAdapter = PremiumPageAdapter(
            onPageTap = { _, _ -> toggleUiVisibility() },
            onPageScale = { scale -> handlePageScale(scale) }
        )

        binding.viewPager.apply {
            adapter = pageAdapter
            offscreenPageLimit = 2
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.goToPage(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    when (state) {
                        ViewPager2.SCROLL_STATE_DRAGGING -> hideFloatingControls()
                        ViewPager2.SCROLL_STATE_IDLE -> if (isUiVisible) showFloatingControls()
                    }
                }
            })
        }
    }

    private fun setupThumbnailSidebar() {
        thumbnailAdapter = ThumbnailAdapter(
            onThumbnailClick = { pageIndex ->
                viewModel.goToPage(pageIndex)
                binding.drawerLayout.closeDrawers()
            }
        )

        binding.thumbnailRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PremiumViewerActivity)
            adapter = thumbnailAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupTabBar() {
        tabAdapter = TabAdapter(
            onTabClick = { index -> viewModel.switchTab(index) },
            onTabClose = { index -> viewModel.closeTab(index) }
        )

        binding.tabRecyclerView.apply {
            layoutManager = LinearLayoutManager(
                this@PremiumViewerActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = tabAdapter
        }
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            setNavigationOnClickListener { finish() }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_search -> { /* TODO: Search dialog */ true }
                    R.id.action_bookmark -> { /* TODO: Bookmark */ true }
                    R.id.action_share -> { /* TODO: Share */ true }
                    R.id.action_more -> { showBottomSheet(); true }
                    else -> false
                }
            }
        }
    }

    private fun setupBottomSheet() {
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        binding.bottomSheet.findViewById<View>(R.id.btnFitWidth)?.setOnClickListener {
            viewModel.fitToWidth()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        binding.bottomSheet.findViewById<View>(R.id.btnFitPage)?.setOnClickListener {
            viewModel.fitToPage()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        binding.bottomSheet.findViewById<View>(R.id.btnRotate)?.setOnClickListener {
            /* TODO: Rotate */
        }
        binding.bottomSheet.findViewById<View>(R.id.btnTheme)?.setOnClickListener {
            viewModel.toggleTheme()
        }
        binding.bottomSheet.findViewById<View>(R.id.btnNightMode)?.setOnClickListener {
            viewModel.toggleNightMode()
        }
        binding.bottomSheet.findViewById<View>(R.id.btnContinuousScroll)?.setOnClickListener {
            viewModel.toggleContinuousScroll()
        }
        binding.bottomSheet.findViewById<View>(R.id.btnHorizontal)?.setOnClickListener {
            viewModel.toggleHorizontalMode()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureHandling() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val newZoom = (currentZoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    viewModel.setZoom(newZoom, detector.focusX, detector.focusY)
                    currentZoom = newZoom
                    return true
                }
            })

        binding.viewPager.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            false
        }
    }

    private fun setupPageScrubber() {
        binding.pageScrubber.apply {
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.pageNumberOverlay.text = "${progress + 1} / ${viewModel.uiState.value.totalPages}"
                        binding.pageNumberOverlay.isVisible = true
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                    binding.pageNumberOverlay.isVisible = true
                }
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    seekBar?.let {
                        viewModel.goToPage(it.progress)
                        binding.pageNumberOverlay.isVisible = false
                    }
                }
            })
        }
    }

    private fun setupFloatingControls() {
        binding.fabAddTab.setOnClickListener {
            /* TODO: Open file picker for new tab */
        }
        binding.fabNightMode.setOnClickListener {
            viewModel.toggleNightMode()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        updateUi(state)
                    }
                }
                launch {
                    viewModel.events.collectLatest { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun updateUi(state: PremiumViewerViewModel.PremiumViewerUiState) {
        // Toolbar
        binding.toolbar.title = state.documentName
        binding.toolbar.subtitle = "${state.currentPage + 1} / ${state.totalPages}"

        // Page scrubber
        binding.pageScrubber.max = (state.totalPages - 1).coerceAtLeast(0)
        binding.pageScrubber.progress = state.currentPage

        // ViewPager
        if (pageAdapter.itemCount != state.totalPages) {
            pageAdapter.submitPageCount(state.totalPages)
        }
        if (binding.viewPager.currentItem != state.currentPage) {
            binding.viewPager.setCurrentItem(state.currentPage, true)
        }

        // Theme
        applyTheme(state.theme)

        // Tabs
        tabAdapter.submitList(state.tabs, state.activeTabIndex)
        binding.tabRecyclerView.isVisible = state.tabs.size > 1

        // Loading
        binding.progressBar.isVisible = state.isLoading || state.isRendering

        // Thumbnail sidebar
        binding.thumbnailRecyclerView.isVisible = state.isThumbnailSidebarVisible
    }

    private fun handleEvent(event: PremiumViewerViewModel.ViewerEvent) {
        when (event) {
            is PremiumViewerViewModel.ViewerEvent.SmoothScrollToPage -> {
                binding.viewPager.setCurrentItem(event.pageIndex, true)
            }
            is PremiumViewerViewModel.ViewerEvent.ShowPageScrubber -> {
                binding.pageScrubber.isVisible = true
            }
            is PremiumViewerViewModel.ViewerEvent.ShowPagePreview -> {
                showPagePreview(event.pageIndex)
            }
            is PremiumViewerViewModel.ViewerEvent.ModeChanged -> {
                updateViewerMode(event.mode)
            }
            is PremiumViewerViewModel.ViewerEvent.ThemeChanged -> {
                applyTheme(event.theme)
            }
            is PremiumViewerViewModel.ViewerEvent.FitToWidth -> {
                animateZoom(1.0f) // View will calculate actual fit
            }
            is PremiumViewerViewModel.ViewerEvent.FitToPage -> {
                animateZoom(1.0f)
            }
            is PremiumViewerViewModel.ViewerEvent.ShowError -> {
                /* TODO: Show Snackbar */
            }
        }
    }

    private fun applyTheme(theme: ViewerTheme) {
        val backgroundColor = when (theme) {
            ViewerTheme.LIGHT -> android.R.color.white
            ViewerTheme.DARK -> android.R.color.black
            ViewerTheme.NIGHT -> R.color.night_background
            ViewerTheme.SEPIA -> R.color.sepia_background
            ViewerTheme.HIGH_CONTRAST -> android.R.color.black
        }

        val toolbarBg = when (theme) {
            ViewerTheme.LIGHT -> R.color.toolbar_light
            else -> R.color.toolbar_dark
        }

        binding.root.setBackgroundColor(ContextCompat.getColor(this, backgroundColor))
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, toolbarBg))
        pageAdapter.setTheme(theme)
    }

    private fun updateViewerMode(mode: ViewMode) {
        binding.viewPager.orientation = when (mode) {
            ViewMode.SINGLE_PAGE_HORIZONTAL,
            ViewMode.CONTINUOUS_HORIZONTAL -> ViewPager2.ORIENTATION_HORIZONTAL
            else -> ViewPager2.ORIENTATION_VERTICAL
        }
    }

    private fun toggleUiVisibility() {
        isUiVisible = !isUiVisible

        val toolbarAnim = if (isUiVisible) {
            ObjectAnimator.ofFloat(binding.toolbar, View.TRANSLATION_Y, 0f)
        } else {
            ObjectAnimator.ofFloat(binding.toolbar, View.TRANSLATION_Y, -binding.toolbar.height.toFloat())
        }

        val bottomAnim = if (isUiVisible) {
            ObjectAnimator.ofFloat(binding.bottomControls, View.TRANSLATION_Y, 0f)
        } else {
            ObjectAnimator.ofFloat(binding.bottomControls, View.TRANSLATION_Y, binding.bottomControls.height.toFloat())
        }

        toolbarAnim.apply {
            duration = UI_ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            start()
        }

        bottomAnim.apply {
            duration = UI_ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            start()
        }

        if (isUiVisible) showFloatingControls() else hideFloatingControls()
    }

    private fun showFloatingControls() {
        binding.fabAddTab.animate()
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(UI_ANIMATION_DURATION)
            .start()

        binding.fabNightMode.animate()
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(UI_ANIMATION_DURATION)
            .start()
    }

    private fun hideFloatingControls() {
        binding.fabAddTab.animate()
            .scaleX(0f).scaleY(0f)
            .alpha(0f)
            .setDuration(UI_ANIMATION_DURATION)
            .start()

        binding.fabNightMode.animate()
            .scaleX(0f).scaleY(0f)
            .alpha(0f)
            .setDuration(UI_ANIMATION_DURATION)
            .start()
    }

    private fun animateZoom(targetZoom: Float) {
        smoothScroller?.cancel()
        smoothScroller = SmoothScroller(
            durationMs = 300,
            onUpdate = { _, _ -> /* Interpolate zoom in view */ },
            onComplete = {
                viewModel.setZoom(targetZoom)
                currentZoom = targetZoom
            }
        )
    }

    private fun handlePageScale(scale: Float) {
        currentZoom = scale
        viewModel.setZoom(scale)
    }

    private fun showBottomSheet() {
        val behavior = BottomSheetBehavior.from(binding.bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun showPagePreview(pageIndex: Int) {
        /* TODO: Show page preview dialog with high-res thumbnail */
    }

    override fun onDestroy() {
        super.onDestroy()
        smoothScroller?.cancel()
    }
}
