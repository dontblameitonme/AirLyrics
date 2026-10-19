package com.andsi.airlyrics.ui.pages.nowplaying

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.AiChatTurnUi
import com.andsi.airlyrics.ui.model.AiIntroProgressUi
import com.andsi.airlyrics.ui.model.AiSourceUi
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SearchEngineUi
import com.andsi.airlyrics.ui.model.UiRequestHandle

/** One bubble of the AI conversation. User entries keep the template that was asked. */
private class ChatEntry(val fromUser: Boolean, val label: String, val template: String = "") {
    val text = StringBuilder()
    val steps = mutableListOf<String>()
    var sources: List<AiSourceUi> = emptyList()
    var error: String? = null
    var needsKey = false
    var finished = false
}

/** Conversation of the song opened last; survives closing the sheet, cleared when the song changes. */
private object AiChatMemory {
    var key: String? = null
    val entries = mutableListOf<ChatEntry>()
}

private class BotViews(
    val container: LinearLayout,
    val status: TextView,
    val bubble: TextView,
    val sources: LinearLayout,
    val tools: LinearLayout
)

/**
 * AI Q&A sheet. Opening it never calls the API: it greets and waits for a quick question or
 * typed text. While a reply streams the question is pinned to the top and the answer grows
 * downward in place, so its first lines stay readable instead of sliding up.
 */
internal fun showAiIntroSheet(host: MainUiHost, ui: Context, track: NowPlayingTrackUi, lyricsText: String) {
    val songKey = track.title + '\u0000' + track.artist
    if (AiChatMemory.key != songKey) {
        AiChatMemory.key = songKey
        AiChatMemory.entries.clear()
    }
    val entries = AiChatMemory.entries
    val sheet = createSheet(
        host,
        ui,
        host.getString(R.string.ui_ai_chat_title),
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - "),
        expanded = true,
        heightFraction = 0.9f
    )
    sheet.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val scheme = sheet.scheme
    val list = sheet.content
    val scroll = sheet.scroll
    var settings = host.aiIntroSettings()
    var webSearch = host.aiWebSearchOn()
    var request: UiRequestHandle? = null
    var streamingEntry: ChatEntry? = null
    var streamingViews: BotViews? = null
    var anchor: View? = null
    val spacer = View(ui)
    val input = EditText(ui)
    val sendButton = ImageView(ui)
    val chipRow = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(10))
    }

    lateinit var ask: (String, String) -> Unit
    lateinit var renderAll: () -> Unit
    lateinit var stop: () -> Unit

    fun setSending(sending: Boolean) {
        sendButton.setImageResource(if (sending) R.drawable.ic_air_stop else R.drawable.ic_air_send)
        sendButton.contentDescription = host.getString(if (sending) R.string.ui_ai_chat_stop else R.string.ui_ai_chat_send)
        val bg = if (sending) scheme.surfaceContainerHighest else scheme.primary
        val fg = if (sending) scheme.onSurface else scheme.onPrimary
        sendButton.imageTintList = ColorStateList.valueOf(fg)
        sendButton.background = rippleOf(fg, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) })
    }

    /** Keeps the pinned question at the top: pads the end so the scroll range never shrinks. */
    fun updateSpacer() {
        val pinned = anchor
        val params = spacer.layoutParams ?: return
        val wanted = if (pinned == null || pinned.parent !== list) 0 else {
            val index = list.indexOfChild(spacer)
            val contentBottom = if (index > 0) list.getChildAt(index - 1).bottom else pinned.bottom
            (scroll.height - (contentBottom - pinned.top) - host.dp(8)).coerceAtLeast(0)
        }
        if (params.height != wanted) {
            params.height = wanted
            spacer.layoutParams = params
        }
    }
    list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> list.post { updateSpacer() } }

    fun bubbleBackground(fromUser: Boolean): GradientDrawable = GradientDrawable().apply {
        val big = host.dp(18).toFloat()
        val small = host.dp(4).toFloat()
        cornerRadii = if (fromUser) {
            floatArrayOf(big, big, small, small, big, big, big, big)
        } else {
            floatArrayOf(small, small, big, big, big, big, big, big)
        }
        if (fromUser) setColor(scheme.primaryContainer) else {
            setColor(scheme.surfaceContainerHigh)
            setStroke(host.dp(1).coerceAtLeast(1), scheme.outlineVariant)
        }
    }

    fun botBubble(text: CharSequence): TextView = TextView(ui).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(0f, 1.35f)
        setTextColor(scheme.onSurface)
        setTextIsSelectable(true)
        setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
        background = bubbleBackground(fromUser = false)
    }

    fun userBubble(text: String): TextView = TextView(ui).apply {
        this.text = text
        textSize = 14f
        setTextColor(scheme.onPrimaryContainer)
        setTextIsSelectable(true)
        setPadding(host.dp(14), host.dp(9), host.dp(14), host.dp(9))
        background = bubbleBackground(fromUser = true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            marginStart = host.dp(56)
            topMargin = host.dp(20)
            bottomMargin = host.dp(8)
        }
    }

    fun smallAction(icon: Int, label: Int, onClick: () -> Unit): TextView = TextView(ui).apply {
        setText(label)
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(scheme.onSurfaceVariant)
        compoundDrawablePadding = host.dp(4)
        val drawable = ui.getDrawable(icon)?.mutate()?.apply {
            setTint(scheme.onSurfaceVariant)
            setBounds(0, 0, host.dp(14), host.dp(14))
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        setPadding(host.dp(10), host.dp(5), host.dp(12), host.dp(5))
        val radius = host.dp(14).toFloat()
        background = rippleOf(
            scheme.onSurface,
            roundedRect(scheme.surfaceContainer, radius, host.dp(1).coerceAtLeast(1), scheme.outlineVariant),
            roundedRect(Color.WHITE, radius)
        )
        setOnClickListener { onClick() }
    }

    fun bindBot(views: BotViews, entry: ChatEntry, isLast: Boolean) {
        val statusText = entry.error ?: entry.steps.joinToString("  \u203a  ")
        views.status.text = statusText
        views.status.setTextColor(if (entry.error != null) scheme.error else scheme.outline)
        views.status.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
        views.bubble.text = renderLightMarkdown(entry.text.toString())
        views.bubble.visibility = if (entry.text.isEmpty()) View.GONE else View.VISIBLE
        if (views.sources.childCount != entry.sources.size + (if (entry.sources.isEmpty()) 0 else 1)) {
            views.sources.removeAllViews()
            if (entry.sources.isNotEmpty()) {
                views.sources.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_ai_sources)).apply {
                    setPadding(host.dp(4), host.dp(10), host.dp(4), host.dp(2))
                })
                entry.sources.forEachIndexed { index, source ->
                    views.sources.addView(LinearLayout(ui).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(host.dp(4), host.dp(5), host.dp(4), host.dp(5))
                        background = rippleOf(scheme.onSurface, null, roundedRect(Color.WHITE, host.dp(8).toFloat()))
                        addView(TextView(ui).apply {
                            text = (index + 1).toString()
                            textSize = 10f
                            gravity = Gravity.CENTER
                            setTextColor(scheme.onSecondaryContainer)
                            background = roundedRect(scheme.secondaryContainer, host.dp(8).toFloat())
                        }, LinearLayout.LayoutParams(host.dp(16), host.dp(16)).apply { topMargin = host.dp(2) })
                        addView(TextView(ui).apply {
                            text = source.title + "  " + (source.site ?: "")
                            textSize = 12f
                            setTextColor(scheme.onSurfaceVariant)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = host.dp(8) })
                        setOnClickListener { host.uiActions.openUrl(source.url) }
                    })
                }
            }
        }
        views.tools.removeAllViews()
        views.tools.visibility = if (entry.finished) View.VISIBLE else View.GONE
        if (!entry.finished) return
        val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = host.dp(8) } }
        if (entry.needsKey) {
            views.tools.addView(smallAction(R.drawable.ic_air_tune, R.string.ui_ai_search_settings) {
                showAiSettingsSheet(host, ui) { settings = host.aiIntroSettings(); renderAll() }
            }, gap())
            return
        }
        if (entry.text.isNotEmpty()) views.tools.addView(smallAction(R.drawable.ic_air_content_copy, R.string.ui_ai_copy) {
            val clipboard = ui.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(track.title, entry.text.toString()))
            // Android 13+ confirms copies itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ui, R.string.ui_ai_copied, Toast.LENGTH_SHORT).show()
        }, gap())
        if (isLast) views.tools.addView(smallAction(R.drawable.ic_air_refresh, R.string.ui_ai_regenerate) {
            val question = entries.getOrNull(entries.lastIndex - 1)?.takeIf { it.fromUser } ?: return@smallAction
            entries.removeAt(entries.lastIndex)
            entries.removeAt(entries.lastIndex)
            ask(question.label, question.template)
        }, gap())
    }

    fun botViews(): BotViews {
        val status = TextView(ui).apply {
            textSize = 12f
            setPadding(host.dp(4), 0, host.dp(4), host.dp(6))
        }
        val bubble = botBubble("")
        val sources = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        val tools = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, host.dp(8), 0, 0)
        }
        val container = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = host.dp(20)
            })
            addView(sources)
            addView(tools)
        }
        return BotViews(container, status, bubble, sources, tools)
    }

    renderAll = {
        list.removeAllViews()
        list.setPadding(host.dp(16), host.dp(4), host.dp(16), host.dp(16))
        list.addView(botBubble(host.getString(R.string.ui_ai_chat_greeting, track.title.ifBlank { "?" })),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = host.dp(40)
            })
        list.addView(TextView(ui).apply {
            setText(R.string.ui_ai_disclaimer)
            textSize = 11f
            setTextColor(scheme.outline)
            setPadding(host.dp(4), host.dp(6), host.dp(4), 0)
        })
        anchor = null
        streamingViews = null
        entries.forEachIndexed { index, entry ->
            if (entry.fromUser) {
                list.addView(userBubble(entry.label).also { anchor = it })
            } else {
                val views = botViews()
                bindBot(views, entry, isLast = index == entries.lastIndex)
                list.addView(views.container)
                if (entry === streamingEntry) streamingViews = views
            }
        }
        list.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        val pinned = anchor
        list.post {
            updateSpacer()
            scroll.post { if (pinned != null) scroll.smoothScrollTo(0, pinned.top - host.dp(8)) }
        }
    }

    stop = stop@{
        val handle = request ?: return@stop
        handle.cancel()
        request = null
        streamingEntry?.let { entry ->
            entry.finished = true
            entry.steps.remove(host.getString(R.string.ui_ai_step_generating))
            entry.steps += host.getString(R.string.ui_ai_step_stopped)
            streamingViews?.let { bindBot(it, entry, isLast = entries.lastOrNull() === entry) }
        }
        streamingEntry = null
        setSending(false)
    }

    ask = { label, template ->
        stop()
        settings = host.aiIntroSettings()
        val history = mutableListOf<AiChatTurnUi>()
        entries.zipWithNext().forEach { (question, answer) ->
            if (question.fromUser && !answer.fromUser && answer.finished && answer.error == null && answer.text.isNotEmpty()) {
                history += AiChatTurnUi(true, question.template)
                history += AiChatTurnUi(false, answer.text.toString())
            }
        }
        val user = ChatEntry(true, label, template)
        val bot = ChatEntry(false, "")
        entries += user
        entries += bot
        if (settings.apiKey.isBlank()) {
            bot.error = host.getString(R.string.ui_ai_need_key)
            bot.needsKey = true
            bot.finished = true
            renderAll()
        } else {
            streamingEntry = bot
            renderAll()
            setSending(true)
            val engineName = settings.searchEngine.displayName(host)
            fun refresh() {
                streamingViews?.takeIf { streamingEntry === bot }?.let { bindBot(it, bot, isLast = true) }
            }
            request = host.streamAiChat(
                track,
                lyricsText,
                history,
                template,
                webSearch,
                onProgress = { event ->
                    when (event) {
                        is AiIntroProgressUi.Searching -> bot.steps += host.getString(R.string.ui_ai_step_searching, engineName)
                        is AiIntroProgressUi.Searched -> {
                            bot.steps.removeLastOrNull()
                            bot.steps += host.getString(R.string.ui_ai_step_found, event.engine.displayName(host), event.sources.size)
                            bot.sources = event.sources
                        }
                        is AiIntroProgressUi.SearchFailed -> {
                            bot.steps.removeLastOrNull()
                            bot.steps += host.getString(R.string.ui_ai_step_search_failed, event.message)
                        }
                        AiIntroProgressUi.Generating -> bot.steps += host.getString(R.string.ui_ai_step_generating)
                    }
                    refresh()
                },
                onDelta = { delta ->
                    bot.text.append(delta)
                    refresh()
                },
                onFinished = { error ->
                    request = null
                    streamingEntry = null
                    bot.finished = true
                    bot.steps.remove(host.getString(R.string.ui_ai_step_generating))
                    if (error != null) {
                        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                        bot.error = host.getString(R.string.ui_ai_failed, message)
                    }
                    setSending(false)
                    // Rebuild so only the newest answer offers "regenerate".
                    val pinnedScroll = scroll.scrollY
                    renderAll()
                    scroll.post { scroll.scrollTo(0, pinnedScroll) }
                }
            )
        }
    }

    fun chip(text: String, highlighted: Boolean = false): TextView = TextView(ui).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = host.dp(34)
        setPadding(host.dp(12), 0, host.dp(12), 0)
        setTextColor(if (highlighted) scheme.primary else scheme.onSurface)
        val radius = host.dp(10).toFloat()
        background = rippleOf(
            scheme.onSurface,
            roundedRect(scheme.surfaceContainer, radius, host.dp(1).coerceAtLeast(1), if (highlighted) scheme.primary else scheme.outlineVariant),
            roundedRect(Color.WHITE, radius)
        )
    }

    lateinit var renderChips: () -> Unit
    renderChips = {
        chipRow.removeAllViews()
        val params = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = host.dp(8) } }
        val webLabel = if (webSearch) host.getString(R.string.ui_ai_web_on, settings.searchEngine.displayName(host))
        else host.getString(R.string.ui_ai_web_off)
        chipRow.addView(chip("\uD83C\uDF10 $webLabel", highlighted = webSearch).apply {
            contentDescription = host.getString(R.string.ui_ai_web_search)
            setOnClickListener {
                webSearch = !webSearch
                host.setAiWebSearchOn(webSearch)
                renderChips()
            }
            setOnLongClickListener { anchorView ->
                val engines = SearchEngineUi.entries.filter { it in settings.enabledEngines }
                showNowPlayingMenu(host, ui, scheme, anchorView, listOf(engines.map { engine ->
                    NowPlayingMenuItem(engine.displayName(host), checked = engine == settings.searchEngine) {
                        host.setDefaultSearchEngine(engine)
                        settings = host.aiIntroSettings()
                        webSearch = true
                        host.setAiWebSearchOn(true)
                        renderChips()
                    }
                }))
                true
            }
        }, params())
        settings.prompts.forEach { prompt ->
            chipRow.addView(chip(prompt.name).apply { setOnClickListener { ask(prompt.name, prompt.template) } }, params())
        }
    }

    fun submitInput() {
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        input.setText("")
        ui.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        ask(text, text)
    }

    // --- Header menu
    sheet.header.addView(ui.iconButton(host, R.drawable.ic_air_more_vert, R.string.ui_more_options, scheme.onSurfaceVariant).apply {
        setOnClickListener { anchorView ->
            showNowPlayingMenu(host, ui, scheme, anchorView, listOf(
                listOf(NowPlayingMenuItem(host.getString(R.string.ui_ai_clear_chat), enabled = entries.isNotEmpty()) {
                    stop()
                    entries.clear()
                    renderAll()
                }),
                listOf(NowPlayingMenuItem(host.getString(R.string.ui_ai_search_settings)) {
                    showAiSettingsSheet(host, ui) {
                        settings = host.aiIntroSettings()
                        renderChips()
                    }
                })
            ))
        }
    })

    // --- Dock: quick questions + input
    val dock = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.divider(host, scheme))
        addView(HorizontalScrollView(ui).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(16), 0, host.dp(16), host.dp(4))
            addView(input.apply {
                hint = host.getString(R.string.ui_ai_chat_hint)
                textSize = 15f
                setTextColor(scheme.onSurface)
                setHintTextColor(scheme.onSurfaceVariant)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                maxLines = 4
                setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(10))
                background = roundedRect(scheme.surfaceContainerHighest, host.dp(22).toFloat())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.mutate()?.setTint(scheme.primary)
                setOnEditorActionListener { _, actionId, event ->
                    val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                        submitInput()
                        true
                    } else false
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton.apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = host.dp(11)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { if (request != null) stop() else submitInput() }
            }, LinearLayout.LayoutParams(host.dp(44), host.dp(44)).apply { marginStart = host.dp(8) })
        })
    }
    sheet.root.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    setSending(false)
    renderChips()
    renderAll()
    dismissWithActivity(host, sheet.dialog) { stop() }
    sheet.dialog.show()
}

