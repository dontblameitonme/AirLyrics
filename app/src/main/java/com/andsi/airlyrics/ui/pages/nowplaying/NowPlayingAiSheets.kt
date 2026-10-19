package com.andsi.airlyrics.ui.pages.nowplaying

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R

import com.andsi.airlyrics.ui.model.AiIntroProgressUi
import com.andsi.airlyrics.ui.model.AiIntroSettingsUi
import com.andsi.airlyrics.ui.model.AiPromptUi
import com.andsi.airlyrics.ui.model.AiSourceUi
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SearchEngineUi
import com.andsi.airlyrics.ui.model.UiRequestHandle
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout

internal fun SearchEngineUi.displayName(host: MainUiHost): String = host.getString(
    when (this) {
        SearchEngineUi.BING -> R.string.ui_search_engine_bing
        SearchEngineUi.GOOGLE -> R.string.ui_search_engine_google
        SearchEngineUi.TINYFISH -> R.string.ui_search_engine_tinyfish
        SearchEngineUi.CUSTOM -> R.string.ui_search_engine_custom
    }
)

// ---------------------------------------------------------------------------------------------
// AI & search settings
// ---------------------------------------------------------------------------------------------

internal fun showAiSettingsSheet(host: MainUiHost, ui: Context, onSaved: (() -> Unit)? = null) {
    val sheet = createSheet(host, ui, host.getString(R.string.ui_ai_search_settings), expanded = true)
    val scheme = sheet.scheme
    val current = host.aiIntroSettings()
    val prompts = current.prompts.toMutableList()
    var engine = current.searchEngine
    val enabled = current.enabledEngines.toMutableSet().apply { add(engine) }
    var resultCount = current.resultCount
    val content = sheet.content

    // --- AI endpoint
    content.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_ai_section_endpoint)))
    val (baseUrlLayout, baseUrl) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_ai_base_url), current.baseUrl,
        host.getString(R.string.ui_ai_base_url_helper)) { it.singleLine(InputType.TYPE_TEXT_VARIATION_URI) }
    val (modelLayout, model) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_ai_model), current.model) { it.singleLine() }
    val (apiKeyLayout, apiKey) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_ai_api_key), current.apiKey,
        host.getString(R.string.ui_ai_api_key_helper)) {
        it.singleLine(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        setEndIconTintList(ColorStateList.valueOf(scheme.onSurfaceVariant))
    }
    listOf(baseUrlLayout, modelLayout, apiKeyLayout).forEach(content::addView)

    // --- Prompts
    content.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_ai_section_prompts)))
    content.addView(TextView(ui).apply {
        setText(R.string.ui_ai_prompt_helper)
        textSize = 12f
        setTextColor(scheme.outline)
        setPadding(host.dp(8), 0, host.dp(8), host.dp(4))
    })
    val promptList = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    content.addView(promptList)
    lateinit var renderPrompts: () -> Unit
    renderPrompts = {
        promptList.removeAllViews()
        prompts.forEachIndexed { index, prompt ->
            if (index > 0) promptList.addView(ui.divider(host, scheme, startInsetDp = 8))
            promptList.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(10))
                background = rippleOf(scheme.onSurface, null, roundedRect(Color.WHITE, host.dp(8).toFloat()))
                addView(TextView(ui).apply {
                    text = prompt.name
                    textSize = 15f
                    setTextColor(scheme.onSurface)
                })
                addView(TextView(ui).apply {
                    text = prompt.template.replace('\n', ' ')
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(scheme.onSurfaceVariant)
                })
                setOnClickListener {
                    showPromptEditor(host, ui, prompt, canDelete = prompts.size > 1, onDelete = {
                        prompts.removeAt(index)
                        renderPrompts()
                    }) { edited ->
                        prompts[index] = edited
                        renderPrompts()
                    }
                }
            })
        }
    }
    renderPrompts()
    content.addView(LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, host.dp(4), 0, 0)
        addView(ui.pillButton(host, scheme, host.getString(R.string.ui_ai_new_prompt)).apply {
            setOnClickListener {
                val blank = AiPromptUi("custom_" + System.currentTimeMillis(), "", "")
                showPromptEditor(host, ui, blank, canDelete = false, onDelete = {}) { created ->
                    prompts += created
                    renderPrompts()
                }
            }
        })
        addView(ui.pillButton(host, scheme, host.getString(R.string.ui_ai_reset_prompts)).apply {
            setOnClickListener {
                prompts.clear()
                prompts += host.defaultAiPrompts()
                renderPrompts()
            }
        })
    })

    // --- Web search
    content.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_ai_section_search)))
    content.addView(TextView(ui).apply {
        setText(R.string.ui_search_engines_hint)
        textSize = 12f
        setTextColor(scheme.outline)
        setPadding(host.dp(8), 0, host.dp(8), host.dp(4))
    })
    val engineList = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    content.addView(engineList)
    lateinit var renderEngines: () -> Unit
    renderEngines = {
        engineList.removeAllViews()
        SearchEngineUi.entries.forEach { item ->
            val isDefault = item == engine
            val on = item in enabled
            engineList.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = host.dp(52)
                setPadding(host.dp(8), 0, host.dp(4), 0)
                addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ui).apply {
                        text = item.displayName(host)
                        textSize = 15f
                        setTextColor(scheme.onSurface)
                    })
                    addView(TextView(ui).apply {
                        text = host.getString(
                            when {
                                isDefault -> R.string.ui_search_engine_default
                                item == SearchEngineUi.BING -> R.string.ui_search_engine_bing_hint
                                item == SearchEngineUi.GOOGLE -> R.string.ui_search_engine_google_hint
                                item == SearchEngineUi.TINYFISH -> R.string.ui_search_engine_tinyfish_hint
                                else -> R.string.ui_search_engine_custom_hint
                            }
                        )
                        textSize = 12f
                        setTextColor(if (isDefault) scheme.primary else scheme.onSurfaceVariant)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (on && !isDefault) addView(ui.pillButton(host, scheme, host.getString(R.string.ui_search_set_default)).apply {
                    textSize = 12f
                    minHeight = host.dp(32)
                    setOnClickListener {
                        engine = item
                        renderEngines()
                    }
                })
                addView(ui.schemeSwitch(scheme).apply {
                    isChecked = on
                    isEnabled = !isDefault
                    alpha = if (isDefault) 0.6f else 1f
                })
                if (!isDefault) setOnClickListener {
                    if (on) enabled.remove(item) else enabled.add(item)
                    renderEngines()
                }
                background = rippleOf(scheme.onSurface, null, roundedRect(Color.WHITE, host.dp(8).toFloat()))
            })
        }
    }
    renderEngines()

    val countLabel = TextView(ui).apply {
        textSize = 15f
        setTextColor(scheme.onSurface)
    }
    fun renderCount() {
        countLabel.text = host.getString(R.string.ui_search_result_count, resultCount)
    }
    renderCount()
    content.addView(LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(8), host.dp(4), host.dp(4), host.dp(4))
        addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(-1 to "−", 1 to "+").forEach { (delta, label) ->
            addView(TextView(ui).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(scheme.onSecondaryContainer)
                background = rippleOf(scheme.onSecondaryContainer, android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(scheme.secondaryContainer)
                })
                setOnClickListener {
                    resultCount = (resultCount + delta).coerceIn(1, 10)
                    renderCount()
                }
            }, LinearLayout.LayoutParams(host.dp(36), host.dp(36)).apply { marginStart = host.dp(8) })
        }
    })

    val (googleKeyLayout, googleKey) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_google_key), current.googleApiKey,
        host.getString(R.string.ui_search_google_key_helper)) {
        it.singleLine(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    val (googleCxLayout, googleCx) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_google_cx), current.googleCx) { it.singleLine() }
    val (tinyFishLayout, tinyFishKey) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_tinyfish_key), current.tinyFishApiKey) {
        it.singleLine(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    val (customUrlLayout, customUrl) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_custom_url), current.customUrl,
        host.getString(R.string.ui_search_custom_url_helper)) { it.singleLine(InputType.TYPE_TEXT_VARIATION_URI) }
    val (headerNameLayout, headerName) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_custom_header_name), current.customHeaderName) { it.singleLine() }
    val (headerValueLayout, headerValue) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_search_custom_header_value), current.customHeaderValue) {
        it.singleLine(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    listOf(googleKeyLayout, googleCxLayout, tinyFishLayout, customUrlLayout, headerNameLayout, headerValueLayout).forEach(content::addView)

    // --- Save
    content.addView(actionRow(
        host,
        ui.pillButton(host, scheme, host.getString(R.string.ui_cancel)).apply { setOnClickListener { sheet.dialog.dismiss() } },
        ui.pillButton(host, scheme, host.getString(R.string.ui_save), filled = true).apply {
            setOnClickListener {
                val url = baseUrl.text?.toString().orEmpty().trim()
                if (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
                    baseUrlLayout.error = host.getString(R.string.ui_ai_base_url_invalid)
                    sheet.scroll.smoothScrollTo(0, 0)
                    return@setOnClickListener
                }
                val custom = customUrl.text?.toString().orEmpty().trim()
                if (SearchEngineUi.CUSTOM in enabled && custom.isNotEmpty() && !custom.startsWith("http")) {
                    customUrlLayout.error = host.getString(R.string.ui_ai_base_url_invalid)
                    return@setOnClickListener
                }
                host.saveAiIntroSettings(
                    AiIntroSettingsUi(
                        baseUrl = url,
                        model = model.text?.toString().orEmpty(),
                        apiKey = apiKey.text?.toString().orEmpty(),
                        prompts = prompts.toList(),
                        searchEngine = engine,
                        enabledEngines = enabled.toSet(),
                        resultCount = resultCount,
                        googleApiKey = googleKey.text?.toString().orEmpty(),
                        googleCx = googleCx.text?.toString().orEmpty(),
                        tinyFishApiKey = tinyFishKey.text?.toString().orEmpty(),
                        customUrl = custom,
                        customHeaderName = headerName.text?.toString().orEmpty(),
                        customHeaderValue = headerValue.text?.toString().orEmpty()
                    )
                )
                sheet.dialog.dismiss()
                onSaved?.invoke()
            }
        }
    ))
    dismissWithActivity(host, sheet.dialog)
    sheet.dialog.show()
}

private fun showPromptEditor(
    host: MainUiHost,
    ui: Context,
    prompt: AiPromptUi,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onSave: (AiPromptUi) -> Unit
) {
    val title = host.getString(if (prompt.template.isEmpty()) R.string.ui_ai_new_prompt else R.string.ui_ai_edit_prompt)
    val sheet = createSheet(host, ui, title, expanded = true)
    val scheme = sheet.scheme
    val (nameLayout, name) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_ai_prompt_name), prompt.name) { it.singleLine(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) }
    val (templateLayout, template) = schemeTextField(host, ui, scheme, host.getString(R.string.ui_ai_prompt), prompt.template,
        host.getString(R.string.ui_ai_prompt_helper)) { edit ->
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.minLines = 6
        edit.maxLines = 14
        edit.gravity = Gravity.TOP or Gravity.START
    }
    sheet.content.addView(nameLayout)
    sheet.content.addView(templateLayout)
    val buttons = mutableListOf<View>()
    if (canDelete) buttons += ui.pillButton(host, scheme, host.getString(R.string.ui_delete)).apply {
        setTextColor(scheme.error)
        setOnClickListener {
            sheet.dialog.dismiss()
            onDelete()
        }
    }
    buttons += ui.pillButton(host, scheme, host.getString(R.string.ui_cancel)).apply { setOnClickListener { sheet.dialog.dismiss() } }
    buttons += ui.pillButton(host, scheme, host.getString(R.string.ui_save), filled = true).apply {
        setOnClickListener {
            val nameText = name.text?.toString().orEmpty().trim()
            val templateText = template.text?.toString().orEmpty().trim()
            if (nameText.isEmpty()) {
                nameLayout.error = host.getString(R.string.ui_ai_prompt_required)
                return@setOnClickListener
            }
            if (templateText.isEmpty()) {
                templateLayout.error = host.getString(R.string.ui_ai_prompt_required)
                return@setOnClickListener
            }
            sheet.dialog.dismiss()
            onSave(prompt.copy(name = nameText, template = templateText))
        }
    }
    sheet.content.addView(actionRow(host, *buttons.toTypedArray()))
    dismissWithActivity(host, sheet.dialog)
    sheet.dialog.show()
}

