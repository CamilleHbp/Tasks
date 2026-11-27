package cn.super12138.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    private fun resources(locale: String): Map<String, Element> {
        val directory = File("src/main/res/$locale")
        assertTrue("Missing resources directory: $directory", directory.isDirectory)
        val entries = mutableMapOf<String, Element>()
        directory.listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            for (index in 0 until root.childNodes.length) {
                val element = root.childNodes.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") ||
                    element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                assertTrue("Duplicate $locale resource: $name", entries.put(name, element) == null)
            }
        }
        return entries
    }

    private fun arguments(text: String): List<String> =
        Regex("%(?:[0-9]+\\$)?[dsf]").findAll(text).map { it.value }.sorted().toList()

    @Test fun frenchCoversEveryEnglishResourceAndPreservesFormatArguments() {
        val english = resources("values")
        val french = resources("values-fr")
        assertEquals("English and French keys must stay in sync", english.keys, french.keys)
        english.forEach { (name, source) ->
            val translation = french.getValue(name)
            assertEquals(name, source.tagName, translation.tagName)
            if (source.tagName == "string") {
                assertTrue("Empty translation: $name", translation.textContent.isNotBlank())
                assertEquals(name, arguments(source.textContent), arguments(translation.textContent))
            } else if (source.tagName == "plurals") {
                val sourceItems = source.getElementsByTagName("item")
                val expected = arguments(sourceItems.item(0).textContent)
                val translatedItems = translation.getElementsByTagName("item")
                val quantities = (0 until translatedItems.length).map { index ->
                    val item = translatedItems.item(index) as Element
                    assertEquals("$name/${item.getAttribute("quantity")}", expected, arguments(item.textContent))
                    item.getAttribute("quantity")
                }.toSet()
                assertTrue("French plural forms missing: $name", quantities.containsAll(setOf("one", "many", "other")))
            }
        }
    }
}
