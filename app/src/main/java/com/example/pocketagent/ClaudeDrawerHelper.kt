package com.example.pocketagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Snapshot of drawer state supplied by the host Activity when opening or refreshing
 * [ClaudeSlideDrawer].
 */
data class DrawerData(
    val sessions: List<ConversationSession>,
    val activeSessionId: String?,
    val activeProjectName: String,
    val documentCount: Int,
    val artifactCount: Int,
    val activeModelLabel: String,
    val activeBackendLabel: String,
    val appVersionLabel: String = "v1.3 · Offline",
)

/**
 * Navigation and action callbacks invoked by [ClaudeSlideDrawer].
 */
data class DrawerCallbacks(
    val onNewChat: () -> Unit,
    val onSelectSession: (ConversationSession) -> Unit,
    val onSessionLongPress: ((ConversationSession) -> Unit)? = null,
    val onOpenProjects: () -> Unit,
    val onOpenDocumentVault: () -> Unit,
    val onOpenArtifactVault: () -> Unit,
    val onOpenNotesMemory: () -> Unit,
    val onOpenModelTuning: () -> Unit,
    val onOpenPrivacyTheme: () -> Unit,
    val onDismiss: (() -> Unit)? = null,
)

/**
 * Pure-Kotlin local filter for recent chat sessions.
 *
 * Matches case-insensitively against [ConversationSession.title] and any message content in
 * [ConversationSession.messages]. Returns [sessions] unchanged when [query] is blank.
 */
fun filterSessions(
    sessions: List<ConversationSession>,
    query: String,
): List<ConversationSession> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.contains(trimmed, ignoreCase = true) ||
            session.messages.any { msg -> msg.content.contains(trimmed, ignoreCase = true) }
    }
}

/**
 * Formats [updatedAtMs] into a concise relative or calendar label relative to [nowMs].
 */
fun formatRelativeSessionTime(
    updatedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val diff = (nowMs - updatedAtMs).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 3_600_000L
    val dayMs = 86_400_000L

    return when {
        diff < minuteMs -> "Just now"
        diff < hourMs -> "${diff / minuteMs}m ago"
        diff < dayMs -> "${diff / hourMs}h ago"
        diff < 7L * dayMs -> "${diff / dayMs}d ago"
        else -> {
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val targetCal = Calendar.getInstance().apply { timeInMillis = updatedAtMs }
            val pattern = if (nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)) {
                "MMM d"
            } else {
                "MMM d, yyyy"
            }
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(updatedAtMs))
        }
    }
}

/**
 * Claude-style slide-in left navigation drawer implemented purely with Android Views.
 *
 * Attaches an overlay [FrameLayout] (scrim + ~82% width panel capped at 340dp) to
 * [rootContainer] and animates horizontally with [ObjectAnimator].
 */
class ClaudeSlideDrawer(
    private val context: Context,
    private val rootContainer: ViewGroup,
) {
    constructor(activity: Activity) : this(
        context = activity,
        rootContainer = activity.findViewById(android.R.id.content),
    )

    private var isDrawerOpen = false
    private var currentAnimator: AnimatorSet? = null
    private var currentData: DrawerData? = null
    private var currentPalette: UiPalette? = null
    private var currentCallbacks: DrawerCallbacks? = null
    private var currentQuery: String = ""

    private var insetTopPx: Int = 0
    private var insetBottomPx: Int = 0

    private val overlayRoot: FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        elevation = dp(context, 24).toFloat()
        visibility = View.GONE
    }

    private val scrimView: View = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        alpha = 0f
        isClickable = true
        isFocusable = true
        contentDescription = "Close navigation drawer"
        setOnClickListener { close() }
    }

    private val panelWidthPx: Int = computePanelWidthPx(context)

    private val panelView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            panelWidthPx,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START,
        )
        elevation = dp(context, 16).toFloat()
        isClickable = true
        isFocusable = true
        translationX = -panelWidthPx.toFloat()
    }

    private var searchInput: EditText? = null
    private var sessionsListContainer: LinearLayout? = null

    init {
        overlayRoot.addView(scrimView)
        overlayRoot.addView(panelView)
        overlayRoot.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            if (top != insetTopPx || bottom != insetBottomPx) {
                insetTopPx = top
                insetBottomPx = bottom
                applyPanelPadding()
            }
            insets
        }
        rootContainer.addView(overlayRoot)
    }

    /** Returns true if the drawer is currently open or animating open. */
    fun isOpen(): Boolean = isDrawerOpen

    /** Opens the drawer with [data], [palette], and [callbacks], animating smoothly from the left. */
    fun open(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ) {
        currentData = data
        currentPalette = palette
        currentCallbacks = callbacks
        currentQuery = ""

        bindPanel(data, palette, callbacks)

        currentAnimator?.cancel()
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.requestApplyInsets()

        if (isDrawerOpen) {
            panelView.translationX = 0f
            scrimView.alpha = 1f
            return
        }

        isDrawerOpen = true
        panelView.translationX = -panelWidthPx.toFloat()
        scrimView.alpha = 0f

        val slideIn = ObjectAnimator.ofFloat(panelView, View.TRANSLATION_X, -panelWidthPx.toFloat(), 0f).apply {
            interpolator = DecelerateInterpolator(2.0f)
        }
        val fadeIn = ObjectAnimator.ofFloat(scrimView, View.ALPHA, 0f, 1f).apply {
            interpolator = DecelerateInterpolator(1.5f)
        }
        currentAnimator = AnimatorSet().apply {
            duration = OPEN_DURATION_MS
            playTogether(slideIn, fadeIn)
            start()
        }
    }

    /** Refreshes the drawer contents in place while preserving the active search query. */
    fun updateData(
        data: DrawerData,
        palette: UiPalette,
    ) {
        currentData = data
        currentPalette = palette
        val callbacks = currentCallbacks ?: return
        bindPanel(data, palette, callbacks)
    }

    /** Toggles the drawer open or closed. */
    fun toggle(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ) {
        if (isDrawerOpen) close() else open(data, palette, callbacks)
    }

    /** Closes the drawer with a slide-out animation and invokes [DrawerCallbacks.onDismiss]. */
    fun close() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        hideKeyboard()

        currentAnimator?.cancel()
        val slideOut = ObjectAnimator.ofFloat(panelView, View.TRANSLATION_X, panelView.translationX, -panelWidthPx.toFloat()).apply {
            interpolator = AccelerateInterpolator(1.5f)
        }
        val fadeOut = ObjectAnimator.ofFloat(scrimView, View.ALPHA, scrimView.alpha, 0f).apply {
            interpolator = AccelerateInterpolator(1.5f)
        }
        var cancelled = false
        currentAnimator = AnimatorSet().apply {
            duration = CLOSE_DURATION_MS
            playTogether(slideOut, fadeOut)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && !isDrawerOpen) {
                        overlayRoot.visibility = View.GONE
                        currentCallbacks?.onDismiss?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun bindPanel(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ) {
        scrimView.setBackgroundColor(palette.scrimOverlay)
        panelView.background = roundedShape(
            color = palette.bg,
            radiusPx = 0f,
            strokeWidthPx = maxOf(1, dp(context, 1)),
            strokeColor = palette.border,
        )
        applyPanelPadding()
        panelView.removeAllViews()

        panelView.addView(buildHeaderSection(data, palette, callbacks))
        panelView.addView(buildSearchBar(palette))
        panelView.addView(buildRecentChatsSection(data, palette, callbacks))
        panelView.addView(buildWorkspaceSection(data, palette, callbacks))
        panelView.addView(buildFooterCard(data, palette, callbacks))

        renderFilteredSessions()
    }

    private fun buildHeaderSection(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(context, 16)
            val topPad = dp(context, 12)
            val bottomPad = dp(context, 10)
            setPadding(hPad, topPad, hPad, bottomPad)
        }

        val brandRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val logoView = ImageView(context).apply {
            val size = dp(context, 24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(context, 8)
            }
            setImageDrawable(tintedVectorDrawable(context, R.drawable.ic_brand_logo, palette.teal))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "PocketAgent"
        }

        val brandTitle = styledText(
            context = context,
            text = "PocketAgent",
            sizeSp = 19f,
            color = palette.ink,
            bold = true,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(context, 8)
            }
        }

        val versionBadge = styledText(
            context = context,
            text = data.appVersionLabel,
            sizeSp = 11f,
            color = palette.teal,
            bold = true,
        ).apply {
            background = roundedShape(
                color = palette.mint,
                radiusPx = dp(context, 12).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.userBubbleBorder,
            )
            val hPad = dp(context, 8)
            val vPad = dp(context, 3)
            setPadding(hPad, vPad, hPad, vPad)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val closeBtn = iconTouchButton(
            context = context,
            palette = palette,
            iconText = "✕",
            accessibilityLabel = "Close navigation drawer",
            textColor = palette.muted,
            textSizeSp = 16f,
        ) {
            performSubtleHaptic(panelView)
            close()
        }

        brandRow.addView(logoView)
        brandRow.addView(brandTitle)
        brandRow.addView(versionBadge)
        brandRow.addView(spacer)
        brandRow.addView(closeBtn)
        header.addView(brandRow)

        val newChatBtn = Button(context).apply {
            val minTouch = dp(context, 48)
            text = "+ New Chat"
            contentDescription = "Start a new chat"
            setAllCaps(false)
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = minTouch
            minimumHeight = minTouch
            minWidth = minTouch
            minimumWidth = minTouch
            background = rippleDrawable(
                gradientPillDrawable(
                    startColor = palette.heroGradientStart,
                    endColor = palette.heroGradientEnd,
                    radiusPx = dp(context, 24).toFloat(),
                ),
                palette.rippleOnAccent,
            )
            backgroundTintList = null
            stateListAnimator = null
            val hPad = dp(context, 16)
            val vPad = dp(context, 10)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
            }
            setOnClickListener {
                performSubtleHaptic(it, isConfirm = true)
                close()
                callbacks.onNewChat()
            }
        }
        header.addView(newChatBtn)
        return header
    }

    private fun buildSearchBar(palette: UiPalette): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(context, 16)
            val bPad = dp(context, 8)
            setPadding(hPad, 0, hPad, bPad)
        }

        val input = EditText(context).apply {
            hint = "Search chats…"
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            val minTouch = dp(context, 48)
            minHeight = minTouch
            minimumHeight = minTouch
            styleEditText(this, palette, cornerDp = 14)
            if (currentQuery.isNotEmpty()) {
                setText(currentQuery)
                setSelection(currentQuery.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    currentQuery = s?.toString().orEmpty()
                    renderFilteredSessions()
                }
            })
        }
        searchInput = input
        wrapper.addView(input)
        return wrapper
    }

    private fun buildRecentChatsSection(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val label = styledText(
            context = context,
            text = "RECENT CHATS",
            sizeSp = 11f,
            color = palette.muted,
            bold = true,
        ).apply {
            letterSpacing = 0.08f
            val hPad = dp(context, 18)
            val vPad = dp(context, 6)
            setPadding(hPad, vPad, hPad, vPad)
        }
        section.addView(label)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isVerticalScrollBarEnabled = false
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(context, 12)
            val bPad = dp(context, 8)
            setPadding(hPad, 0, hPad, bPad)
        }
        sessionsListContainer = listContainer
        scrollView.addView(listContainer)
        section.addView(scrollView)
        return section
    }

    private fun renderFilteredSessions() {
        val container = sessionsListContainer ?: return
        val data = currentData ?: return
        val palette = currentPalette ?: return
        val callbacks = currentCallbacks ?: return

        container.removeAllViews()

        val ordered = data.sessions.sortedWith(
            compareByDescending<ConversationSession> { it.isPinned }
                .thenByDescending { it.updatedAt },
        )
        val filtered = filterSessions(ordered, currentQuery)

        if (filtered.isEmpty()) {
            val emptyMessage = if (data.sessions.isEmpty()) {
                "No recent chats yet"
            } else {
                "No matching chats"
            }
            val emptyView = styledText(
                context = context,
                text = emptyMessage,
                sizeSp = 13f,
                color = palette.muted,
                bold = false,
            ).apply {
                gravity = Gravity.CENTER
                val pad = dp(context, 16)
                setPadding(pad, pad, pad, pad)
            }
            container.addView(emptyView)
            return
        }

        val nowMs = System.currentTimeMillis()
        for (session in filtered) {
            container.addView(buildSessionRow(session, session.id == data.activeSessionId, nowMs, palette, callbacks))
        }
    }

    private fun buildSessionRow(
        session: ConversationSession,
        isActive: Boolean,
        nowMs: Long,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val minTouch = dp(context, 48)
            minimumHeight = minTouch
            minimumWidth = minTouch
            isClickable = true
            isFocusable = true
            val bg = if (isActive) {
                rippleDrawable(
                    roundedShape(
                        color = palette.mint,
                        radiusPx = dp(context, 12).toFloat(),
                        strokeWidthPx = maxOf(1, dp(context, 1)),
                        strokeColor = palette.userBubbleBorder,
                    ),
                    palette.rippleOnMint,
                )
            } else {
                rippleDrawable(
                    roundedShape(
                        color = Color.TRANSPARENT,
                        radiusPx = dp(context, 12).toFloat(),
                    ),
                    palette.rippleOnSurface,
                )
            }
            background = bg
            val hPad = dp(context, 12)
            val vPad = dp(context, 8)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(context, 4)
            }
            setOnClickListener {
                performSubtleHaptic(it)
                close()
                callbacks.onSelectSession(session)
            }
            if (callbacks.onSessionLongPress != null) {
                setOnLongClickListener {
                    performSubtleHaptic(it, isConfirm = true)
                    callbacks.onSessionLongPress.invoke(session)
                    true
                }
            }
        }

        val displayTitle = if (session.isPinned) "★ ${session.title}" else session.title
        val titleView = styledText(
            context = context,
            text = displayTitle,
            sizeSp = 14f,
            color = if (isActive) palette.teal else palette.ink,
            bold = isActive || session.isPinned,
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val timeLabel = formatRelativeSessionTime(session.updatedAt, nowMs)
        val subtitleText = if (session.messageCount > 0) {
            "$timeLabel · ${session.messageCount} msgs"
        } else {
            timeLabel
        }
        val subtitleView = styledText(
            context = context,
            text = subtitleText,
            sizeSp = 12f,
            color = palette.muted,
            bold = false,
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        row.addView(titleView)
        row.addView(subtitleView)
        return row
    }

    private fun buildWorkspaceSection(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(context, 12)
            setPadding(hPad, 0, hPad, dp(context, 6))
        }

        section.addView(dividerView(context, palette))

        val header = styledText(
            context = context,
            text = "WORKSPACE",
            sizeSp = 11f,
            color = palette.muted,
            bold = true,
        ).apply {
            letterSpacing = 0.08f
            val hPad = dp(context, 6)
            val topPad = dp(context, 8)
            val bottomPad = dp(context, 4)
            setPadding(hPad, topPad, hPad, bottomPad)
        }
        section.addView(header)

        section.addView(
            buildWorkspaceRow(
                iconRes = R.drawable.ic_folder,
                title = "Projects",
                trailing = data.activeProjectName.ifBlank { "Default" },
                palette = palette,
                onClick = {
                    close()
                    callbacks.onOpenProjects()
                },
            ),
        )
        section.addView(
            buildWorkspaceRow(
                iconRes = R.drawable.ic_notes,
                title = "Document Vault",
                trailing = data.documentCount.toString(),
                palette = palette,
                onClick = {
                    close()
                    callbacks.onOpenDocumentVault()
                },
            ),
        )
        section.addView(
            buildWorkspaceRow(
                iconRes = R.drawable.ic_history,
                title = "Artifact Vault",
                trailing = data.artifactCount.toString(),
                palette = palette,
                onClick = {
                    close()
                    callbacks.onOpenArtifactVault()
                },
            ),
        )
        section.addView(
            buildWorkspaceRow(
                iconRes = R.drawable.ic_memory,
                title = "Notes & Memory",
                trailing = "Local",
                palette = palette,
                onClick = {
                    close()
                    callbacks.onOpenNotesMemory()
                },
            ),
        )

        return section
    }

    private fun buildWorkspaceRow(
        iconRes: Int,
        title: String,
        trailing: String,
        palette: UiPalette,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val minTouch = dp(context, 48)
            minimumHeight = minTouch
            minimumWidth = minTouch
            isClickable = true
            isFocusable = true
            background = rippleDrawable(
                roundedShape(
                    color = Color.TRANSPARENT,
                    radiusPx = dp(context, 12).toFloat(),
                ),
                palette.rippleOnSurface,
            )
            val hPad = dp(context, 8)
            val vPad = dp(context, 8)
            setPadding(hPad, vPad, hPad, vPad)

            val iconView = ImageView(context).apply {
                val size = dp(context, 18)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(context, 10)
                }
                setImageDrawable(tintedVectorDrawable(context, iconRes, palette.teal))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }

            val titleView = styledText(
                context = context,
                text = title,
                sizeSp = 14f,
                color = palette.ink,
                bold = false,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val trailingView = styledText(
                context = context,
                text = trailing,
                sizeSp = 12f,
                color = palette.muted,
                bold = true,
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                val badgeHPad = dp(context, 8)
                val badgeVPad = dp(context, 2)
                setPadding(badgeHPad, badgeVPad, badgeHPad, badgeVPad)
                background = roundedShape(
                    color = palette.surfaceElevated,
                    radiusPx = dp(context, 10).toFloat(),
                    strokeWidthPx = maxOf(1, dp(context, 1)),
                    strokeColor = palette.border,
                )
            }

            addView(iconView)
            addView(titleView)
            addView(trailingView)

            setOnClickListener {
                performSubtleHaptic(it)
                onClick()
            }
        }
    }

    private fun buildFooterCard(
        data: DrawerData,
        palette: UiPalette,
        callbacks: DrawerCallbacks,
    ): LinearLayout {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(context, 12)
            val bPad = dp(context, 12)
            setPadding(hPad, dp(context, 4), hPad, bPad)
        }

        val card = cardContainer(
            context = context,
            palette = palette,
            cornerDp = 16,
            elevated = true,
            paddingDp = 4,
        )

        val modelRow = buildFooterRow(
            title = "Model & Tuning",
            subtitle = "${data.activeModelLabel} · ${data.activeBackendLabel}",
            subtitleColor = palette.teal,
            iconRes = null,
            palette = palette,
            onClick = {
                close()
                callbacks.onOpenModelTuning()
            },
        )

        val privacyRow = buildFooterRow(
            title = "Privacy & Theme",
            subtitle = "100% Offline · Appearance",
            subtitleColor = palette.muted,
            iconRes = R.drawable.ic_shield,
            palette = palette,
            onClick = {
                close()
                callbacks.onOpenPrivacyTheme()
            },
        )

        card.addView(modelRow)
        card.addView(dividerView(context, palette))
        card.addView(privacyRow)
        outer.addView(card)
        return outer
    }

    private fun buildFooterRow(
        title: String,
        subtitle: String,
        subtitleColor: Int,
        iconRes: Int?,
        palette: UiPalette,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val minTouch = dp(context, 48)
            minimumHeight = minTouch
            minimumWidth = minTouch
            isClickable = true
            isFocusable = true
            background = rippleDrawable(
                roundedShape(
                    color = Color.TRANSPARENT,
                    radiusPx = dp(context, 12).toFloat(),
                ),
                palette.rippleOnSurface,
            )
            val hPad = dp(context, 10)
            val vPad = dp(context, 8)
            setPadding(hPad, vPad, hPad, vPad)

            if (iconRes != null) {
                val iconView = ImageView(context).apply {
                    val size = dp(context, 18)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = dp(context, 10)
                    }
                    setImageDrawable(tintedVectorDrawable(context, iconRes, palette.teal))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }
                addView(iconView)
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(
                styledText(
                    context = context,
                    text = title,
                    sizeSp = 13f,
                    color = palette.ink,
                    bold = true,
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            textCol.addView(
                styledText(
                    context = context,
                    text = subtitle,
                    sizeSp = 12f,
                    color = subtitleColor,
                    bold = false,
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(textCol)

            setOnClickListener {
                performSubtleHaptic(it)
                onClick()
            }
        }
    }

    private fun applyPanelPadding() {
        panelView.setPadding(0, insetTopPx, 0, insetBottomPx)
    }

    private fun hideKeyboard() {
        val target = searchInput ?: panelView
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
        searchInput?.clearFocus()
    }

    companion object {
        private const val OPEN_DURATION_MS = 240L
        private const val CLOSE_DURATION_MS = 200L
        private const val MAX_DRAWER_WIDTH_DP = 340
        private const val SCREEN_WIDTH_FRACTION = 0.82f

        internal fun computePanelWidthPx(context: Context): Int {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val maxPx = dp(context, MAX_DRAWER_WIDTH_DP)
            val desiredPx = (screenWidth * SCREEN_WIDTH_FRACTION).toInt()
            return minOf(desiredPx, maxPx).coerceAtLeast(dp(context, 260))
        }
    }
}
