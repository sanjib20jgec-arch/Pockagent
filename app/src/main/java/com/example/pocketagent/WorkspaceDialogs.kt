package com.example.pocketagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extracted workspace, history, memory, notes, projects, document vault, and artifact vault
 * dialogs for PocketAgent.
 */
object WorkspaceDialogs {

    private fun column(activity: Activity): LinearLayout =
        LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun marginBottom(activity: Activity, valueDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, valueDp) }

    /**
     * Displays the searchable conversation history dialog with pin, rename, export, and delete
     * options for each saved session.
     */
    fun showHistoryDialog(
        activity: Activity,
        palette: UiPalette,
        sessionStore: SessionStore,
        getCurrentSession: () -> ConversationSession?,
        setCurrentSession: (ConversationSession?) -> Unit,
        onLoadSession: (String) -> Unit,
        onStartNewConversation: () -> Unit,
    ) {
        var allSessions = sessionStore.listSessions().sortedWith(
            compareByDescending<ConversationSession> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)

        val searchBox = EditText(activity).apply {
            hint = "Search conversations…"
            textSize = 14f
            styleEditText(this, palette)
        }
        dialogView.addView(searchBox, marginBottom(activity, 12))

        val listContainer = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 340))
        }
        dialogView.addView(scroll)

        lateinit var dialog: AlertDialog

        fun populateList(query: String = "", reload: Boolean = false) {
            if (reload) {
                allSessions = sessionStore.listSessions().sortedWith(
                    compareByDescending<ConversationSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )
                dialog.setTitle("Conversations (${allSessions.size})")
            }
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) {
                allSessions
            } else {
                allSessions.filter { sess ->
                    sess.title.contains(query, ignoreCase = true) ||
                        sess.messages.any { it.content.contains(query, ignoreCase = true) }
                }
            }
            if (filtered.isEmpty()) {
                listContainer.addView(
                    styledText(activity, "No conversations found.", 13f, palette.muted).apply {
                        setPadding(0, dp(activity, 24), 0, dp(activity, 24))
                        gravity = Gravity.CENTER
                    }
                )
                return
            }

            val dateFormatter = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
            for (s in filtered) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    background = rippleDrawable(
                        roundedShape(
                            color = palette.surfaceElevated,
                            radiusPx = dp(activity, 14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(activity, 1)),
                            strokeColor = palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onLoadSession(s.id)
                        dialog.dismiss()
                    }
                }
                val info = column(activity).apply {
                    val titlePrefix = if (s.isPinned) "[Pinned] " else ""
                    addView(styledText(activity, "$titlePrefix${s.title}", 14.5f, palette.ink, true))
                    val previewSnippet = s.messages.lastOrNull()?.content
                        ?.trim()
                        ?.replace(Regex("\\s+"), " ")
                        .orEmpty()
                    if (previewSnippet.isNotEmpty()) {
                        addView(
                            styledText(activity, previewSnippet, 12.5f, palette.muted).apply {
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setPadding(0, dp(activity, 3), 0, dp(activity, 3))
                            }
                        )
                    }
                    val dateStr = dateFormatter.format(Date(s.updatedAt))
                    addView(styledText(activity, "$dateStr · ${s.messageCount} turns", 11f, palette.teal))
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val menuBtn = iconTouchButton(
                    context = activity,
                    palette = palette,
                    iconText = "⋮",
                    accessibilityLabel = "Options for ${s.title}",
                    textColor = palette.muted,
                    textSizeSp = 18f,
                ) {
                    showSessionOptionsMenu(
                        activity = activity,
                        palette = palette,
                        sessionStore = sessionStore,
                        session = s,
                        getCurrentSession = getCurrentSession,
                        setCurrentSession = setCurrentSession,
                        onStartNewConversation = onStartNewConversation,
                    ) {
                        populateList(searchBox.text.toString(), reload = true)
                    }
                }
                row.addView(menuBtn)
                listContainer.addView(row, marginBottom(activity, 8))
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog = AlertDialog.Builder(activity)
            .setTitle("Conversations (${allSessions.size})")
            .setView(dialogView)
            .setPositiveButton("+ New Chat") { _, _ ->
                onStartNewConversation()
            }
            .setNegativeButton("Close", null)
            .create()
        populateList()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showSessionOptionsMenu(
        activity: Activity,
        palette: UiPalette,
        sessionStore: SessionStore,
        session: ConversationSession,
        getCurrentSession: () -> ConversationSession?,
        setCurrentSession: (ConversationSession?) -> Unit,
        onStartNewConversation: () -> Unit,
        onUpdated: () -> Unit,
    ) {
        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)
        lateinit var menuDialog: AlertDialog

        val pinLabel = if (session.isPinned) "Unpin conversation" else "Pin to top"
        val actions = listOf(
            pinLabel to false,
            "Rename" to false,
            "Export as Markdown" to false,
            "Export as Text" to false,
            "Delete conversation" to true,
        )
        for ((index, spec) in actions.withIndex()) {
            val (label, isDanger) = spec
            val btn = if (isDanger) {
                dangerButton(activity, palette, label, accessibilityLabel = label) {
                    menuDialog.dismiss()
                    sessionStore.deleteSession(session.id)
                    if (getCurrentSession()?.id == session.id) {
                        onStartNewConversation()
                    }
                    onUpdated()
                }
            } else {
                secondaryOutlineButton(activity, palette, label, accessibilityLabel = label) {
                    menuDialog.dismiss()
                    when (index) {
                        0 -> {
                            sessionStore.pinSession(session.id, !session.isPinned)
                            onUpdated()
                        }
                        1 -> promptRenameSession(
                            activity = activity,
                            palette = palette,
                            sessionStore = sessionStore,
                            session = session,
                            getCurrentSession = getCurrentSession,
                            setCurrentSession = setCurrentSession,
                            onUpdated = onUpdated,
                        )
                        2 -> exportSessionToShare(activity, sessionStore, session.id, asMarkdown = true)
                        3 -> exportSessionToShare(activity, sessionStore, session.id, asMarkdown = false)
                    }
                }
            }
            dialogView.addView(
                btn,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (index < actions.lastIndex) bottomMargin = dp(activity, 8)
                },
            )
        }

        menuDialog = AlertDialog.Builder(activity)
            .setTitle(session.title)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(menuDialog, palette)
    }

    private fun promptRenameSession(
        activity: Activity,
        palette: UiPalette,
        sessionStore: SessionStore,
        session: ConversationSession,
        getCurrentSession: () -> ConversationSession?,
        setCurrentSession: (ConversationSession?) -> Unit,
        onUpdated: () -> Unit,
    ) {
        val container = styleDialogContainer(activity, palette, paddingDp = 16)
        val input = EditText(activity).apply {
            hint = "Conversation title…"
            textSize = 14f
            styleEditText(this, palette)
            setText(session.title)
            setSelection(text.length)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Rename Conversation")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    sessionStore.renameSession(session.id, newTitle)
                    if (getCurrentSession()?.id == session.id) {
                        setCurrentSession(getCurrentSession()?.withTitle(newTitle))
                    }
                    onUpdated()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Exports the conversation identified by [sessionId] via Android's share chooser as either
     * Markdown or plain text.
     */
    fun exportSessionToShare(
        activity: Activity,
        sessionStore: SessionStore,
        sessionId: String,
        asMarkdown: Boolean,
    ) {
        val text = if (asMarkdown) {
            sessionStore.exportSessionAsMarkdown(sessionId)
        } else {
            sessionStore.exportSessionAsText(sessionId)
        }
        if (text.isNullOrBlank()) return
        val mime = if (asMarkdown) "text/markdown" else "text/plain"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "PocketAgent Conversation Export")
        }
        activity.startActivity(Intent.createChooser(shareIntent, "Export Conversation"))
    }

    /**
     * Displays the Sovereign Memory Vault dialog for toggling, adding, inspecting, and clearing
     * on-device user memory facts.
     */
    fun showMemoryCenterDialog(
        activity: Activity,
        palette: UiPalette,
        userMemoryStore: UserMemoryStore,
        isMemoryEnabled: () -> Boolean,
        onToggleMemoryEnabled: (Boolean) -> Unit,
    ) {
        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggleButton = pillButton(
            context = activity,
            palette = palette,
            label = if (isMemoryEnabled()) "Memory: ON" else "Memory: OFF",
            accessibilityLabel = "Toggle sovereign long-term memory",
            compact = true,
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(activity, 8) }
        }
        headerRow.addView(toggleButton)

        val addFactBtn = pillButton(
            context = activity,
            palette = palette,
            label = "+ Add Fact",
            accessibilityLabel = "Manually record a user preference or fact",
            compact = true,
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(activity, 8) }
        }
        headerRow.addView(addFactBtn)

        val clearAllBtn = dangerButton(
            context = activity,
            palette = palette,
            label = "Clear All",
            accessibilityLabel = "Clear all sovereign memories",
            compact = true,
        ) {}
        headerRow.addView(clearAllBtn)

        val headerScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(headerRow)
        }
        dialogView.addView(headerScroll, marginBottom(activity, 10))

        val desc = styledText(
            activity,
            "100% on-device memory. PocketAgent remembers key facts and preferences across conversations without sending data anywhere.",
            12f,
            palette.muted,
        )
        dialogView.addView(desc, marginBottom(activity, 12))

        val listContainer = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 310))
        }
        dialogView.addView(scroll)

        fun refreshList() {
            listContainer.removeAllViews()
            val memories = userMemoryStore.listMemories()
            if (memories.isEmpty()) {
                listContainer.addView(
                    styledText(
                        activity,
                        "No memories recorded yet.\nAs you chat, preferences and facts will be remembered here.",
                        13f,
                        palette.muted,
                    ).apply {
                        setPadding(0, dp(activity, 30), 0, dp(activity, 30))
                        gravity = Gravity.CENTER
                    }
                )
                return
            }

            for (m in memories) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(activity, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(activity, 1)),
                        strokeColor = palette.border,
                    )
                }

                val info = column(activity).apply {
                    val catBadge = "[${m.displayLabel}]"
                    addView(styledText(activity, catBadge, 11f, palette.teal, true))
                    addView(
                        styledText(activity, m.content, 13.5f, palette.ink).apply {
                            setPadding(0, dp(activity, 2), 0, 0)
                        }
                    )
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val delBtn = vectorIconButton(
                    context = activity,
                    palette = palette,
                    drawableRes = R.drawable.ic_close,
                    accessibilityLabel = "Delete memory fact",
                    tintColor = palette.muted,
                    bgColor = Color.TRANSPARENT,
                    borderColor = null,
                    iconSizeDp = 16,
                ) {
                    userMemoryStore.deleteMemory(m.id)
                    refreshList()
                }
                card.addView(delBtn)

                listContainer.addView(card, marginBottom(activity, 8))
            }
        }

        toggleButton.setOnClickListener {
            val enabled = !isMemoryEnabled()
            onToggleMemoryEnabled(enabled)
            toggleButton.text = if (enabled) "Memory: ON" else "Memory: OFF"
            Toast.makeText(
                activity,
                if (enabled) "Long-term memory enabled" else "Long-term memory paused",
                Toast.LENGTH_SHORT,
            ).show()
        }

        addFactBtn.setOnClickListener {
            promptAddMemoryFact(activity, palette, userMemoryStore) { refreshList() }
        }

        clearAllBtn.setOnClickListener {
            val confirmDialog = AlertDialog.Builder(activity)
                .setTitle("Clear All Memories?")
                .setMessage("Are you sure you want to permanently delete all stored memory facts?")
                .setPositiveButton("Clear") { _, _ ->
                    userMemoryStore.clearMemories()
                    refreshList()
                    Toast.makeText(activity, "All memories cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            showStyledAlertDialog(confirmDialog, palette)
        }

        refreshList()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Sovereign Memory Vault")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptAddMemoryFact(
        activity: Activity,
        palette: UiPalette,
        userMemoryStore: UserMemoryStore,
        onAdded: () -> Unit,
    ) {
        val input = EditText(activity).apply {
            hint = "e.g., Prefers Kotlin, Speaks Bengali, Offline only…"
            textSize = 14f
            styleEditText(this, palette)
        }
        val categories = arrayOf("Preference", "Profile", "Constraint", "Goal", "Custom")
        var selectedCategoryIndex = 0

        val container = styleDialogContainer(activity, palette, paddingDp = 16).apply {
            addView(input, marginBottom(activity, 12))
            addView(styledText(activity, "Category", 12.5f, palette.muted, true), marginBottom(activity, 6))
        }

        val radioGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
        }
        val radioButtons = categories.mapIndexed { idx, label ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(palette.ink)
                buttonTintList = ColorStateList.valueOf(palette.teal)
                minHeight = dp(activity, 40)
                setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
                isChecked = idx == 0
                setOnClickListener { selectedCategoryIndex = idx }
            }.also { radioGroup.addView(it) }
        }
        if (radioButtons.isNotEmpty()) {
            radioGroup.check(radioButtons[0].id)
        }
        container.addView(radioGroup)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Add Memory Fact")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val fact = input.text.toString().trim()
                if (fact.isNotBlank()) {
                    val category = when (selectedCategoryIndex) {
                        0 -> UserMemoryStore.MemoryCategory.PREFERENCE
                        1 -> UserMemoryStore.MemoryCategory.PROFILE
                        2 -> UserMemoryStore.MemoryCategory.CONSTRAINT
                        3 -> UserMemoryStore.MemoryCategory.GOAL
                        else -> UserMemoryStore.MemoryCategory.CUSTOM
                    }
                    userMemoryStore.addFact(fact, category)
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Displays the Personal Notes manager dialog with live search, pinning, editing, deletion,
     * and Markdown export.
     */
    fun showNotesManagerDialog(
        activity: Activity,
        palette: UiPalette,
        notesStore: PersonalNotesStore,
    ) {
        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)

        val searchBox = EditText(activity).apply {
            hint = "Search personal notes…"
            textSize = 14f
            styleEditText(this, palette)
        }
        dialogView.addView(searchBox, marginBottom(activity, 12))

        val listContainer = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 340))
        }
        dialogView.addView(scroll)

        fun refreshList(query: String = "") {
            listContainer.removeAllViews()
            val notes = notesStore.searchNotes(query)
            if (notes.isEmpty()) {
                listContainer.addView(
                    styledText(activity, "No notes found.", 13f, palette.muted).apply {
                        setPadding(0, dp(activity, 30), 0, dp(activity, 30))
                        gravity = Gravity.CENTER
                    }
                )
                return
            }

            val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            for (n in notes) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    background = rippleDrawable(
                        roundedShape(
                            color = palette.surfaceElevated,
                            radiusPx = dp(activity, 14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(activity, 1)),
                            strokeColor = palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                }

                val info = column(activity).apply {
                    val pinPrefix = if (n.isPinned) "[Pinned] " else ""
                    addView(styledText(activity, "$pinPrefix${n.title}", 14.5f, palette.ink, true))
                    if (n.content.isNotBlank()) {
                        val preview = n.content.take(90).replace("\n", " ") +
                            (if (n.content.length > 90) "…" else "")
                        addView(
                            styledText(activity, preview, 12.5f, palette.muted).apply {
                                setPadding(0, dp(activity, 2), 0, dp(activity, 2))
                            }
                        )
                    }
                    val dateStr = dateFormatter.format(Date(n.updatedAt))
                    addView(styledText(activity, dateStr, 11f, palette.teal))
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                card.setOnClickListener {
                    promptEditNote(activity, palette, notesStore, n) {
                        refreshList(searchBox.text.toString())
                    }
                }

                val pinBtn = iconTouchButton(
                    context = activity,
                    palette = palette,
                    iconText = if (n.isPinned) "★" else "☆",
                    accessibilityLabel = if (n.isPinned) "Unpin note ${n.title}" else "Pin note ${n.title}",
                    textColor = if (n.isPinned) palette.teal else palette.muted,
                    textSizeSp = 15f,
                ) {
                    notesStore.pinNote(n.id, !n.isPinned)
                    refreshList(searchBox.text.toString())
                }
                card.addView(pinBtn)

                val delBtn = vectorIconButton(
                    context = activity,
                    palette = palette,
                    drawableRes = R.drawable.ic_close,
                    accessibilityLabel = "Delete note ${n.title}",
                    tintColor = palette.muted,
                    bgColor = Color.TRANSPARENT,
                    borderColor = null,
                    iconSizeDp = 16,
                ) {
                    notesStore.deleteNote(n.id)
                    refreshList(searchBox.text.toString())
                }
                card.addView(delBtn)

                listContainer.addView(card, marginBottom(activity, 8))
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshList()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Personal Notes (${notesStore.listNotes().size})")
            .setView(dialogView)
            .setPositiveButton("+ New Note") { _, _ ->
                promptEditNote(activity, palette, notesStore, null) {
                    refreshList(searchBox.text.toString())
                }
            }
            .setNeutralButton("Export All") { _, _ ->
                val md = notesStore.exportNotesAsMarkdown()
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, md)
                    putExtra(Intent.EXTRA_SUBJECT, "PocketAgent Notes Export")
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Export Notes"))
            }
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptEditNote(
        activity: Activity,
        palette: UiPalette,
        notesStore: PersonalNotesStore,
        existingNote: PersonalNotesStore.PersonalNote?,
        onSaved: () -> Unit,
    ) {
        val container = styleDialogContainer(activity, palette, paddingDp = 16)

        val titleInput = EditText(activity).apply {
            hint = "Note Title"
            textSize = 14f
            styleEditText(this, palette)
            if (existingNote != null) setText(existingNote.title)
        }
        container.addView(titleInput, marginBottom(activity, 10))

        val contentInput = EditText(activity).apply {
            hint = "Note Content…"
            textSize = 13.5f
            styleEditText(this, palette)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existingNote != null) setText(existingNote.content)
        }
        container.addView(contentInput, marginBottom(activity, 4))

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existingNote == null) "Create Note" else "Edit Note")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                if (title.isNotBlank()) {
                    if (existingNote == null) {
                        notesStore.createNote(title = title, content = content)
                    } else {
                        notesStore.saveNote(existingNote.withUpdatedContent(title, content))
                    }
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Prompts the user for a note title and saves [answerText] to [notesStore].
     */
    fun showSaveToNotesDialog(
        activity: Activity,
        palette: UiPalette,
        notesStore: PersonalNotesStore,
        answerText: String,
        lastPrompt: String,
        onStatusUpdate: (String) -> Unit,
    ) {
        val container = styleDialogContainer(activity, palette, paddingDp = 16)
        val suggested = lastPrompt.trim()
            .replace(Regex("\\s+"), " ")
            .take(56)
            .ifBlank { "PocketAgent Answer" }
        val titleInput = EditText(activity).apply {
            hint = "Note Title"
            textSize = 14f
            styleEditText(this, palette)
            setText(suggested)
            setSelection(text.length)
        }
        container.addView(titleInput, marginBottom(activity, 6))
        container.addView(
            styledText(activity, "${answerText.length} chars will be saved locally.", 12f, palette.muted)
        )
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Save Answer to Notes")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { "PocketAgent Answer" }
                notesStore.createNote(title = title, content = answerText, tags = listOf("saved-answer"))
                onStatusUpdate("Saved answer to Personal Notes ($title).")
                Toast.makeText(activity, "Saved to Notes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Prompts the user for an artifact title and saves [code] ([language]) into [artifactStore].
     */
    fun showSaveArtifactDialog(
        activity: Activity,
        palette: UiPalette,
        artifactStore: ArtifactStore,
        code: String,
        language: String?,
        lastPrompt: String,
        onWorkspaceUpdated: () -> Unit,
        onStatusUpdate: (String) -> Unit,
    ) {
        val container = styleDialogContainer(activity, palette, paddingDp = 16)
        val langLabel = language?.trim()?.takeIf { it.isNotEmpty() } ?: "html"
        val suggestedTitle = lastPrompt.trim().replace(Regex("\\s+"), " ").take(48)
            .ifBlank { "${langLabel.uppercase(Locale.ROOT)} Artifact" }
        val titleInput = EditText(activity).apply {
            hint = "Artifact Title"
            textSize = 14f
            styleEditText(this, palette)
            setText(suggestedTitle)
            setSelection(text.length)
        }
        container.addView(titleInput)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Save to Artifact Vault")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { "Artifact" }
                artifactStore.save(
                    ArtifactStore.Artifact(
                        title = title,
                        language = langLabel,
                        code = code,
                    )
                )
                onWorkspaceUpdated()
                onStatusUpdate("Artifact saved to vault ($title).")
                Toast.makeText(activity, "Saved to Artifact Vault", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Displays the Projects & Workspaces switcher/editor dialog.
     */
    fun showProjectsDialog(
        activity: Activity,
        palette: UiPalette,
        projectStore: ProjectStore,
        getActiveProjectId: () -> String?,
        onSelectProject: (String?) -> Unit,
        onInvalidateConversation: (String) -> Unit,
        onWorkspaceUpdated: () -> Unit,
        onStatusUpdate: (String) -> Unit,
        onBeforeShow: () -> Unit = {},
    ) {
        onBeforeShow()
        val root = styleDialogContainer(activity, palette, paddingDp = 16)
        root.addView(
            styledText(activity, "Switch project workspace or customize its system prompt:", 12.5f, palette.muted),
            marginBottom(activity, 10),
        )
        val listCol = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 320))
        }
        root.addView(scroll)

        lateinit var dialog: AlertDialog

        fun refreshProjectsList() {
            listCol.removeAllViews()
            val activeId = getActiveProjectId()
            val isDefaultSelected = activeId == null
            val defaultRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 48)
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                background = rippleDrawable(
                    roundedShape(
                        color = if (isDefaultSelected) palette.mint else palette.surfaceElevated,
                        radiusPx = dp(activity, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(activity, if (isDefaultSelected) 2 else 1)),
                        strokeColor = if (isDefaultSelected) palette.teal else palette.border,
                    ),
                    palette.rippleOnSurface,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (getActiveProjectId() != null) {
                        onSelectProject(null)
                        onInvalidateConversation("project switched to default")
                        onWorkspaceUpdated()
                    }
                    dialog.dismiss()
                    onStatusUpdate("Active workspace: Default")
                }
            }
            val defaultText = column(activity).apply {
                val label = if (isDefaultSelected) "Default Workspace (Active)" else "Default Workspace"
                addView(
                    styledText(
                        activity,
                        label,
                        14f,
                        if (isDefaultSelected) palette.teal else palette.ink,
                        true,
                    )
                )
                addView(styledText(activity, "Standard general-purpose offline assistant", 12f, palette.muted))
            }
            defaultRow.addView(defaultText, LinearLayout.LayoutParams(0, -2, 1f))
            listCol.addView(defaultRow, marginBottom(activity, 8))

            val projects = projectStore.loadAll()
            for (proj in projects) {
                val selected = proj.id == getActiveProjectId()
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, 48)
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    background = rippleDrawable(
                        roundedShape(
                            color = if (selected) palette.mint else palette.surfaceElevated,
                            radiusPx = dp(activity, 14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(activity, if (selected) 2 else 1)),
                            strokeColor = if (selected) palette.teal else palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onSelectProject(proj.id)
                        onInvalidateConversation("project switched")
                        onWorkspaceUpdated()
                        dialog.dismiss()
                        onStatusUpdate("Switched to project: ${proj.name}")
                    }
                }
                val info = column(activity).apply {
                    val label = if (selected) "${proj.name} (Active)" else proj.name
                    addView(styledText(activity, label, 14f, if (selected) palette.teal else palette.ink, true))
                    val preview = proj.systemInstructions.ifBlank { "No custom instructions" }
                    addView(
                        styledText(activity, preview, 12f, palette.muted).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }
                    )
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                val editBtn = iconTouchButton(
                    context = activity,
                    palette = palette,
                    iconText = "✎",
                    accessibilityLabel = "Edit project ${proj.name}",
                    textColor = palette.teal,
                    textSizeSp = 15f,
                ) {
                    promptProjectEditor(
                        activity = activity,
                        palette = palette,
                        projectStore = projectStore,
                        existing = proj,
                        onSelectProject = onSelectProject,
                        onInvalidateConversation = onInvalidateConversation,
                        onWorkspaceUpdated = onWorkspaceUpdated,
                    ) {
                        refreshProjectsList()
                    }
                }
                row.addView(editBtn)
                val delBtn = vectorIconButton(
                    context = activity,
                    palette = palette,
                    drawableRes = R.drawable.ic_close,
                    accessibilityLabel = "Delete project ${proj.name}",
                    tintColor = palette.muted,
                    bgColor = Color.TRANSPARENT,
                    borderColor = null,
                    iconSizeDp = 16,
                ) {
                    projectStore.delete(proj.id)
                    if (getActiveProjectId() == proj.id) {
                        onSelectProject(null)
                        onInvalidateConversation("active project deleted")
                        onWorkspaceUpdated()
                    }
                    refreshProjectsList()
                }
                row.addView(delBtn)
                listCol.addView(row, marginBottom(activity, 8))
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Projects & Workspaces")
            .setView(root)
            .setPositiveButton("+ New Project") { _, _ ->
                promptProjectEditor(
                    activity = activity,
                    palette = palette,
                    projectStore = projectStore,
                    existing = null,
                    onSelectProject = onSelectProject,
                    onInvalidateConversation = onInvalidateConversation,
                    onWorkspaceUpdated = onWorkspaceUpdated,
                ) {
                    onWorkspaceUpdated()
                    showProjectsDialog(
                        activity = activity,
                        palette = palette,
                        projectStore = projectStore,
                        getActiveProjectId = getActiveProjectId,
                        onSelectProject = onSelectProject,
                        onInvalidateConversation = onInvalidateConversation,
                        onWorkspaceUpdated = onWorkspaceUpdated,
                        onStatusUpdate = onStatusUpdate,
                        onBeforeShow = onBeforeShow,
                    )
                }
            }
            .setNegativeButton("Close", null)
            .create()
        refreshProjectsList()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptProjectEditor(
        activity: Activity,
        palette: UiPalette,
        projectStore: ProjectStore,
        existing: ProjectStore.Project?,
        onSelectProject: (String?) -> Unit,
        onInvalidateConversation: (String) -> Unit,
        onWorkspaceUpdated: () -> Unit,
        onSaved: () -> Unit,
    ) {
        val container = styleDialogContainer(activity, palette, paddingDp = 16)
        val nameInput = EditText(activity).apply {
            hint = "Project Name (e.g. Android Coach)"
            textSize = 14f
            styleEditText(this, palette)
            if (existing != null) setText(existing.name)
        }
        container.addView(nameInput, marginBottom(activity, 10))
        val instrInput = EditText(activity).apply {
            hint = "Custom System Instructions (role, rules, output format)…"
            textSize = 13.5f
            styleEditText(this, palette)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existing != null) setText(existing.systemInstructions)
        }
        container.addView(instrInput)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "Create Project" else "Edit Project")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val instr = instrInput.text.toString().trim()
                if (name.isNotBlank()) {
                    val toSave = if (existing == null) {
                        ProjectStore.Project(name = name, systemInstructions = instr)
                    } else {
                        existing.copy(
                            name = name,
                            systemInstructions = instr,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    if (projectStore.save(toSave)) {
                        onSelectProject(toSave.id)
                        onInvalidateConversation("project updated")
                        onWorkspaceUpdated()
                    }
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Displays the offline Document Knowledge Vault dialog for inspecting, adding, and removing
     * indexed PDFs and text files.
     */
    fun showDocumentVaultDialog(
        activity: Activity,
        palette: UiPalette,
        documentVault: DocumentVault,
        onChoosePdf: () -> Unit,
        onChooseTextFiles: () -> Unit,
        onWorkspaceUpdated: () -> Unit,
    ) {
        val root = styleDialogContainer(activity, palette, paddingDp = 16)
        root.addView(
            styledText(
                activity,
                "Attached PDFs & text files are indexed here offline and searched automatically across chats.",
                12f,
                palette.muted,
            ),
            marginBottom(activity, 10),
        )
        val listCol = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 320))
        }
        root.addView(scroll)

        fun refreshVault() {
            listCol.removeAllViews()
            val docs = documentVault.loadAll()
            if (docs.isEmpty()) {
                listCol.addView(
                    styledText(
                        activity,
                        "No documents indexed yet.\nUse + PDF or + Text in the composer to index files.",
                        13f,
                        palette.muted,
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(activity, 28), 0, dp(activity, 28))
                    }
                )
                return
            }
            for (doc in docs) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(activity, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(activity, 1)),
                        strokeColor = palette.border,
                    )
                }
                val info = column(activity).apply {
                    addView(styledText(activity, doc.name, 14f, palette.ink, true))
                    addView(
                        styledText(
                            activity,
                            "${doc.chunkCount} searchable chunks · ${doc.extractedText.length} chars",
                            11.5f,
                            palette.teal,
                        )
                    )
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                val delBtn = vectorIconButton(
                    context = activity,
                    palette = palette,
                    drawableRes = R.drawable.ic_close,
                    accessibilityLabel = "Remove ${doc.name}",
                    tintColor = palette.muted,
                    bgColor = Color.TRANSPARENT,
                    borderColor = null,
                    iconSizeDp = 16,
                ) {
                    documentVault.removeDocument(doc.id)
                    refreshVault()
                    onWorkspaceUpdated()
                }
                card.addView(delBtn)
                listCol.addView(card, marginBottom(activity, 8))
            }
        }
        refreshVault()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Document Knowledge Vault")
            .setView(root)
            .setPositiveButton("+ Add PDF") { _, _ -> onChoosePdf() }
            .setNeutralButton("+ Add Text") { _, _ -> onChooseTextFiles() }
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    /**
     * Displays the Artifact Vault dialog for previewing, exporting, and deleting saved HTML and
     * code artifacts.
     */
    fun showArtifactVaultDialog(
        activity: Activity,
        palette: UiPalette,
        artifactStore: ArtifactStore,
        onExportHtml: (titleSlug: String, html: String) -> Unit,
        onWorkspaceUpdated: () -> Unit,
    ) {
        val root = styleDialogContainer(activity, palette, paddingDp = 16)
        root.addView(
            styledText(activity, "Saved HTML games, visualizations, and code blocks stored offline.", 12f, palette.muted),
            marginBottom(activity, 10),
        )
        val listCol = column(activity)
        val scroll = ScrollView(activity).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 330))
        }
        root.addView(scroll)

        fun refreshArtifacts() {
            listCol.removeAllViews()
            val artifacts = artifactStore.loadAll()
            if (artifacts.isEmpty()) {
                listCol.addView(
                    styledText(
                        activity,
                        "No saved artifacts yet.\nOpen any Preview and tap Save to store it here.",
                        13f,
                        palette.muted,
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(activity, 28), 0, dp(activity, 28))
                    }
                )
                return
            }
            for (art in artifacts) {
                val card = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 12)
                card.addView(styledText(activity, art.title, 14f, palette.ink, true))
                card.addView(
                    styledText(
                        activity,
                        "${art.language.uppercase(Locale.ROOT).ifBlank { "TEXT" }} · ${art.code.length} chars",
                        11.5f,
                        palette.muted,
                    ).apply {
                        setPadding(0, dp(activity, 2), 0, dp(activity, 8))
                    }
                )
                val btnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (HtmlArtifactSandbox.isPreviewableCode(art.language, art.code)) {
                    btnRow.addView(
                        pillButton(activity, palette, "Preview", compact = true) {
                            HtmlArtifactSandbox.showPreviewDialog(activity, art.code, art.language, palette)
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(activity, 6) }
                        }
                    )
                }
                btnRow.addView(
                    secondaryOutlineButton(activity, palette, "Export .html", compact = true, textColor = palette.teal) {
                        val html = artifactStore.exportHtml(art)
                        val safeSlug = art.title
                            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            .take(32)
                            .ifBlank { "artifact" }
                        onExportHtml(safeSlug, html)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(activity, 6) }
                    }
                )
                btnRow.addView(
                    dangerButton(activity, palette, "Delete", compact = true) {
                        artifactStore.delete(art.id)
                        refreshArtifacts()
                        onWorkspaceUpdated()
                    }
                )
                card.addView(btnRow)
                listCol.addView(card, marginBottom(activity, 8))
            }
        }
        refreshArtifacts()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Artifact Vault")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }
}
