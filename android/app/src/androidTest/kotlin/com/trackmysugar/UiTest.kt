package com.trackmysugar

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real front end inside the app's WebView: every interaction is a
 * DOM click or form submit, answered by the native bridge and SQLite.
 */
class UiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var web: WebView

    @Before
    fun setUp() {
        context.deleteDatabase(Database.DEFAULT_NAME)
        launch()
    }

    @After
    fun tearDown() {
        scenario?.close()
        context.deleteDatabase(Database.DEFAULT_NAME)
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { web = it.webView }
        waitFor("document.readyState === 'complete' && !!document.querySelector('#reading-rows tr')")
        // Auto-accept confirm() so delete flows can be driven.
        js("window.confirm = () => true")
    }

    /** Evaluates JS on the UI thread and returns the JSON-encoded result. */
    private fun js(script: String): String {
        var result = ""
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.evaluateJavascript("(function(){ return $script })()") {
                result = it
                latch.countDown()
            }
        }
        assertTrue("JS timed out: $script", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(cond: String, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (js("!!($cond)") == "true") return
            Thread.sleep(100)
        }
        fail("Timed out waiting for: $cond\npage text: ${js("document.body.innerText")}")
    }

    private fun text(selector: String) =
        js("document.querySelector('$selector').innerText").trim('"').replace("\\n", "\n")

    private fun rowCount(tbody: String) = js("document.querySelectorAll('$tbody tr:not(:has(.empty))').length").toInt()

    private fun tab(name: String) {
        js("document.querySelector('nav .tab[data-tab=\"$name\"]').click()")
        waitFor("!document.getElementById('$name').hidden")
    }

    /** Fills named fields and submits the form. */
    private fun submit(form: String, vararg fields: Pair<String, String>) {
        val sets = fields.joinToString(";") { (n, v) ->
            "f.elements['$n'].value = ${org.json.JSONObject.quote(v)}"
        }
        js("(function(){ var f = document.querySelector('$form'); $sets; f.requestSubmit(); })()")
    }

    private fun toast() = text("#message")
    private fun waitToast(contains: String) =
        waitFor("!document.getElementById('message').hidden && document.getElementById('message').innerText.includes(${org.json.JSONObject.quote(contains)})")

    private fun clickRowButton(tbody: String, attr: String, rowText: String) {
        js("""(function(){
            var tr = [...document.querySelectorAll('$tbody tr')].find(r => r.innerText.includes(${org.json.JSONObject.quote(rowText)}));
            tr.querySelector('[$attr]').click();
        })()""")
    }

    private fun addMetric(type: String = "BSL", desc: String = "Blood Sugar Level") {
        tab("metric-types")
        submit("#metric-type-form", "type" to type, "description" to desc)
        waitToast("Metric type added")
    }

    private fun addPerson(name: String = "Steve", email: String = "steve@example.com") {
        tab("persons")
        submit("#person-form", "name" to name, "email" to email)
        waitToast("Person added")
    }

    private fun addReading(person: String, metric: String, value: String, whenTs: String = "") {
        tab("readings")
        js("""(function(){
            var f = document.querySelector('#reading-form');
            var pick = (sel, label) => { var o = [...sel.options].find(o => o.text.includes(label)); sel.value = o.value; };
            pick(f.elements.person_id, ${org.json.JSONObject.quote(person)});
            pick(f.elements.metric_type_id, ${org.json.JSONObject.quote(metric)});
        })()""")
        submit("#reading-form", "health_metric" to value, "created_date_time" to whenTs)
        waitToast("Reading added")
    }

    // ---------------------------------------------------------------------

    @Test
    fun startsWithEmptyStatesAndAllTabsWork() {
        assertEquals("Track My Sugar", text("h1").trim())
        assertTrue(text("#reading-rows").contains("No readings yet"))
        tab("persons")
        assertTrue(text("#person-rows").contains("No people yet"))
        tab("metric-types")
        assertTrue(text("#metric-type-rows").contains("No metric types yet"))
        tab("readings")
        assertEquals("true", js("document.getElementById('persons').hidden").trim('"'))
    }

    @Test
    fun addEditDeleteMetricType() {
        addMetric()
        assertEquals(1, rowCount("#metric-type-rows"))
        assertTrue(text("#metric-type-rows").contains("Blood Sugar Level"))

        clickRowButton("#metric-type-rows", "data-edit", "BSL")
        assertTrue(text("#metric-type-form-title").startsWith("Edit metric type #1"))
        submit("#metric-type-form", "type" to "BSL", "description" to "Glucose")
        waitToast("Metric type updated")
        assertTrue(text("#metric-type-rows").contains("Glucose"))
        assertEquals("Add metric type", text("#metric-type-form-title"))

        clickRowButton("#metric-type-rows", "data-delete", "BSL")
        waitToast("Metric type deleted")
        assertTrue(text("#metric-type-rows").contains("No metric types yet"))
    }

    @Test
    fun addEditDeletePerson() {
        addPerson()
        assertTrue(text("#person-rows").contains("steve@example.com"))

        clickRowButton("#person-rows", "data-edit", "Steve")
        assertEquals("Steve", js("document.querySelector('#person-form').elements.name.value").trim('"'))
        submit("#person-form", "name" to "Stephen", "email" to "")
        waitToast("Person updated")
        assertTrue(text("#person-rows").contains("Stephen"))
        assertTrue(!text("#person-rows").contains("steve@example.com"))

        clickRowButton("#person-rows", "data-delete", "Stephen")
        waitToast("Person deleted")
        assertTrue(text("#person-rows").contains("No people yet"))
    }

    @Test
    fun cancelResetsEditForm() {
        addPerson()
        clickRowButton("#person-rows", "data-edit", "Steve")
        assertTrue(text("#person-form-title").startsWith("Edit person"))
        js("document.querySelector('#person-form [data-cancel]').click()")
        assertEquals("Add person", text("#person-form-title"))
        assertEquals("", js("document.querySelector('#person-form').elements.name.value").trim('"'))
    }

    @Test
    fun duplicateAndInvalidInputShowErrors() {
        addPerson()
        submit("#person-form", "name" to "Steve", "email" to "")
        waitToast("already exists")
        assertEquals("error", js("document.getElementById('message').className").trim('"'))
        assertEquals(1, rowCount("#person-rows"))

        // Whitespace-only name is trimmed by the page and rejected by the API.
        submit("#person-form", "name" to " ", "email" to "")
        waitToast("name must be a non-empty string")
        assertEquals(1, rowCount("#person-rows"))
    }

    @Test
    fun readingLifecycleWithFiltersEditAndDelete() {
        addMetric("BSL", "Blood Sugar Level")
        addMetric("HBA1C", "Glycated haemoglobin")
        addPerson("Steve", "steve@example.com")
        addPerson("Jo", "jo@example.com")

        addReading("Steve", "BSL", "5.6", "2026-01-01T08:00")
        addReading("Steve", "HBA1C", "48", "2026-01-02T08:00")
        addReading("Jo", "BSL", "7.2", "")
        assertEquals(3, rowCount("#reading-rows"))
        assertTrue(text("#reading-rows").contains("2026-01-01 08:00:00"))

        // Filters
        js("""(function(){ var s = document.querySelector('#reading-filter-person');
            s.value = [...s.options].find(o => o.text === 'Steve').value; s.dispatchEvent(new Event('change')); })()""")
        waitFor("document.querySelectorAll('#reading-rows tr').length === 2")
        js("""(function(){ var s = document.querySelector('#reading-filter-metric');
            s.value = [...s.options].find(o => o.text === 'HBA1C').value; s.dispatchEvent(new Event('change')); })()""")
        waitFor("document.querySelectorAll('#reading-rows tr').length === 1 && document.querySelector('#reading-rows').innerText.includes('48')")
        js("""(function(){ var s = document.querySelector('#reading-filter-person'); s.value = '';
            s.dispatchEvent(new Event('change')); })()""")
        waitFor("document.querySelectorAll('#reading-rows tr').length === 1")  // all people, HBA1C only
        js("""(function(){ var s = document.querySelector('#reading-filter-metric'); s.value = '';
            s.dispatchEvent(new Event('change')); })()""")
        waitFor("document.querySelectorAll('#reading-rows tr').length === 3")

        // Edit
        clickRowButton("#reading-rows", "data-edit", "5.6")
        assertEquals("5.6", js("document.querySelector('#reading-form').elements.health_metric.value").trim('"'))
        // The datetime-local control drops zero seconds, so only compare to the minute.
        assertTrue(js("document.querySelector('#reading-form').elements.created_date_time.value").trim('"').startsWith("2026-01-01T08:00"))
        submit("#reading-form", "health_metric" to "6.1")
        waitToast("Reading updated")
        assertTrue(text("#reading-rows").contains("6.1"))
        assertTrue(!text("#reading-rows").contains("5.6"))

        // A person with readings can't be deleted; the metric type neither.
        tab("persons")
        clickRowButton("#person-rows", "data-delete", "Steve")
        waitToast("still in use")
        assertEquals(2, rowCount("#person-rows"))
        tab("metric-types")
        clickRowButton("#metric-type-rows", "data-delete", "HBA1C")
        waitToast("still in use")

        // Delete the readings, then the person can go.
        tab("readings")
        clickRowButton("#reading-rows", "data-delete", "6.1")
        waitToast("Reading deleted")
        clickRowButton("#reading-rows", "data-delete", "48")
        waitToast("Reading deleted")
        assertEquals(1, rowCount("#reading-rows"))
        tab("persons")
        clickRowButton("#person-rows", "data-delete", "Steve")
        waitToast("Person deleted")
        assertEquals(1, rowCount("#person-rows"))
        // Readings table reflects remaining reading's person.
        tab("readings")
        assertTrue(text("#reading-rows").contains("Jo"))
    }

    @Test
    fun dataSurvivesRestart() {
        addMetric()
        addPerson()
        addReading("Steve", "BSL", "5.9")
        launch()
        assertEquals(1, rowCount("#reading-rows"))
        assertTrue(text("#reading-rows").contains("5.9"))
        assertTrue(text("#reading-rows").contains("Steve"))
    }

    @Test
    fun layoutIsUsableAtPhoneWidth() {
        addMetric(); addPerson(); addReading("Steve", "BSL", "5.6")
        // No horizontal scrolling, and the table is displayed as stacked rows.
        assertEquals("true", js("document.documentElement.scrollWidth <= window.innerWidth + 1").trim('"'))
        assertEquals("grid", js("getComputedStyle(document.querySelector('#reading-rows tr')).display").trim('"'))
        assertEquals("none", js("getComputedStyle(document.querySelector('#readings thead')).display").trim('"'))
    }
}
