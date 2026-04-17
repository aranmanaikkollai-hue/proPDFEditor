override fun setEnabled(enabled: Boolean) {
    super.setEnabled(enabled)
    isDetectionEnabled = enabled
    if (!enabled) { corners = null; invalidate() }
}