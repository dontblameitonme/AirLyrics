package com.andsi.airlyrics.songinfo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The eight built-in AI prompts must exist in every locale and use the documented placeholders. */
class AiPromptPresetResourcesTest {
    private val presets = listOf("intro", "meaning", "background", "music", "artist", "trivia", "similar", "translate")
    private val locales = listOf("values", "values-zh-rCN", "values-zh-rTW", "values-es")

    private fun strings(dir: String): Map<String, String> {
        val file = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun everyPresetHasNameAndTemplateInEveryLocale() {
        locales.forEach { locale ->
            val values = strings(locale)
            presets.forEach { key ->
                val name = values["ui_ai_preset_${key}_name"]
                val template = values["ui_ai_preset_$key"]
                assertTrue("$locale missing name for $key", !name.isNullOrBlank())
                assertTrue("$locale missing template for $key", !template.isNullOrBlank())
                assertTrue("$locale/$key must mention {title}", template!!.contains("{title}"))
                assertTrue("$locale/$key must mention {artist}", template.contains("{artist}"))
                val unknown = Regex("\\{([a-z]+)\\}").findAll(template).map { it.groupValues[1] }
                    .filterNot { it in setOf("title", "artist", "album", "lyrics", "search") }.toList()
                assertEquals("$locale/$key unknown placeholders", emptyList<String>(), unknown)
            }
        }
    }

    @Test
    fun presetsRenderWithoutLeftoverPlaceholders() {
        val request = AiSongIntroRequest("T", "A", "B", "L", search = "S")
        strings("values").filterKeys { key -> presets.any { key == "ui_ai_preset_$it" } }.values.forEach { raw ->
            val template = raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
            val rendered = AiSongIntroClient.renderPrompt(template, request)
            assertTrue(rendered, !rendered.contains("{"))
            assertTrue(rendered.endsWith("S"))
        }
    }
}
