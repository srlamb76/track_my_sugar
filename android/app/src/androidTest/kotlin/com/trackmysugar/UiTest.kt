package com.trackmysugar

import android.provider.MediaStore
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.json.JSONArray
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
        deleteExports()
        launch()
    }

    @After
    fun tearDown() {
        scenario?.close()
        context.deleteDatabase(Database.DEFAULT_NAME)
        deleteExports()
    }

    // ---- Exported files (the app writes them to the Downloads folder) -----

    private val exportNameLike = "track-my-sugar-readings-%"

    /** Removes export files left by earlier runs, so each test counts only its own. */
    private fun deleteExports() {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf(exportNameLike)
        )
    }

    /** Name and text of every export file in Downloads, oldest first. */
    private fun exportedFiles(): List<Pair<String, String>> {
        val resolver = context.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val files = mutableListOf<Pair<String, String>>()
        resolver.query(
            uri, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf(exportNameLike),
            "${MediaStore.Downloads._ID} ASC"
        )?.use {
            while (it.moveToNext()) {
                val fileUri = android.content.ContentUris.withAppendedId(uri, it.getLong(0))
                val text = resolver.openInputStream(fileUri)!!.use { s -> s.readBytes().toString(Charsets.UTF_8) }
                files += it.getString(1) to text
            }
        }
        return files
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
        // People and Metric types live under the Administration tab.
        if (name == "persons" || name == "metric-types") {
            js("document.querySelector('header .tab[data-tab=\"administration\"]').click()")
            waitFor("!document.getElementById('administration').hidden")
            js("document.querySelector('.subnav .subtab[data-subtab=\"$name\"]').click()")
            waitFor("!document.getElementById('$name').hidden")
            return
        }
        js("document.querySelector('header .tab[data-tab=\"$name\"]').click()")
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
        assertEquals("true", js("document.getElementById('administration').hidden").trim('"'))
        tab("analytics")
        assertEquals("true", js("document.getElementById('readings').hidden").trim('"'))
    }

    @Test
    fun topLevelTabsAreReadingsAnalyticsAdministration() {
        assertEquals(
            "Readings,Analytics,Administration",
            js("[...document.querySelectorAll('header .tab')].map(t => t.innerText).join(',')").trim('"')
        )
    }

    @Test
    fun administrationHasPeopleAndMetricTypeSubsections() {
        tab("persons")
        assertEquals("People,Metric types",
            js("[...document.querySelectorAll('.subnav .subtab')].map(t => t.innerText).join(',')").trim('"'))
        assertEquals("true", js("document.getElementById('metric-types').hidden").trim('"'))
        tab("metric-types")
        assertEquals("true", js("document.getElementById('persons').hidden").trim('"'))
        assertEquals("false", js("document.getElementById('administration').hidden").trim('"'))
    }

    // ---- Analytics -------------------------------------------------------

    private fun seedAnalytics() {
        addMetric("BSL", "Blood Sugar Level")
        addMetric("HBA1C", "Glycated haemoglobin")
        addPerson("Steve", "steve@example.com")
        addPerson("Jo", "jo@example.com")
        addReading("Steve", "BSL", "5.6", "2026-01-01T08:00")
        addReading("Steve", "BSL", "6.2", "2026-01-05T08:00")
        addReading("Steve", "BSL", "7", "2026-01-10T08:00")
        addReading("Steve", "HBA1C", "48", "2026-01-03T08:00")
        addReading("Jo", "BSL", "7.2", "2026-01-02T08:00")
        tab("analytics")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 5")
    }

    private fun dots() = js("document.querySelectorAll('#chart-svg circle').length").toInt()
    private fun lines() = js("document.querySelectorAll('#chart-svg polyline').length").toInt()
    private fun seriesCount() = js("document.querySelectorAll('#an-stat-rows tr').length").toInt()

    private fun pick(select: String, label: String) {
        js("""(function(){ var s = document.querySelector('$select');
            s.value = [...s.options].find(o => o.text === ${org.json.JSONObject.quote(label)}).value;
            s.dispatchEvent(new Event('change')); })()""")
    }

    private fun setDate(input: String, value: String) {
        js("(function(){ var e = document.querySelector('$input'); e.value = '$value'; e.dispatchEvent(new Event('change')); })()")
    }

    @Test
    fun analyticsDrawsOneLinePerPersonAndMetric() {
        seedAnalytics()
        assertEquals(5, dots())
        assertEquals(1, lines())            // only Steve BSL has more than one point
        assertEquals(3, seriesCount())      // Jo BSL, Steve BSL, Steve HBA1C
        assertEquals(3, js("document.querySelectorAll('#chart-legend li').length").toInt())
        assertTrue(js("document.querySelectorAll('#chart-svg text').length").toInt() > 5)  // axis labels
    }

    @Test
    fun analyticsShowsSummaryStats() {
        seedAnalytics()
        val row = js("""[...document.querySelectorAll('#an-stat-rows tr')]
            .find(r => r.innerText.includes('Steve · BSL')).innerText""").replace("\t", " ")
        assertTrue(row, row.contains("3") && row.contains("5.6") && row.contains("6.27") && row.contains("7"))
    }

    @Test
    fun analyticsFiltersByPersonAndMetricType() {
        seedAnalytics()
        pick("#an-person", "Steve")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 4")
        assertEquals(2, seriesCount())
        pick("#an-metric", "BSL")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 3")
        assertEquals(1, seriesCount())
        pick("#an-person", "All people")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 4")  // everyone's BSL
        pick("#an-metric", "HBA1C")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 1")
    }

    @Test
    fun analyticsFiltersByDateRange() {
        seedAnalytics()
        setDate("#an-from", "2026-01-04")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 2")   // 5 Jan, 10 Jan
        setDate("#an-to", "2026-01-05")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 1")   // 5 Jan only (inclusive)
        setDate("#an-from", "2026-01-02")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 3")   // 2, 3 and 5 Jan
    }

    @Test
    fun analyticsEmptyResultAndClearFilters() {
        seedAnalytics()
        setDate("#an-from", "2027-01-01")
        waitFor("document.querySelector('#chart').innerText.includes('No readings match these filters')")
        assertEquals(0, dots())
        js("document.querySelector('#an-clear').click()")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 5")
        assertEquals("", js("document.querySelector('#an-from').value").trim('"'))
        assertEquals("", js("document.querySelector('#an-person').value").trim('"'))
    }

    @Test
    fun analyticsRefreshesAfterDataChanges() {
        seedAnalytics()
        addReading("Jo", "BSL", "8.1", "2026-01-12T08:00")
        tab("analytics")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 6")
    }

    @Test
    fun analyticsChartFitsPhoneWidth() {
        seedAnalytics()
        assertEquals("true", js("document.documentElement.scrollWidth <= window.innerWidth + 1").trim('"'))
        assertTrue(js("document.querySelector('#chart-svg').getBoundingClientRect().width").toDouble() > 200)
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

    // ---- Export ----------------------------------------------------------

    private val bom = "\uFEFF"
    private val csvHeader = "id,person_id,person,metric_type_id,metric_type,health_metric,created_date_time"

    private fun clickExport(format: String) = js("document.querySelector('#an-export-$format').click()")

    @Test
    fun exportCsvContainsAllReadingsOldestFirst() {
        seedAnalytics()
        clickExport("csv")
        waitToast("Exported 5 readings to track-my-sugar-readings-")
        val (name, text) = exportedFiles().single()
        assertTrue(name, Regex("track-my-sugar-readings-\\d{4}-\\d{2}-\\d{2}\\.csv").matches(name))
        assertTrue("starts with a UTF-8 BOM", text.startsWith(bom))
        val lines = text.removePrefix(bom).split("\r\n")
        assertEquals(csvHeader, lines[0])
        assertEquals("1,1,Steve,1,BSL,5.6,2026-01-01 08:00:00", lines[1])
        assertEquals("5,2,Jo,1,BSL,7.2,2026-01-02 08:00:00", lines[2])  // oldest first, not by id
        assertEquals(7, lines.size)  // header + 5 rows + trailing empty after the final CRLF
        assertEquals("", lines.last())
    }

    @Test
    fun exportJsonIsAnArrayWithTheSameFields() {
        seedAnalytics()
        clickExport("json")
        waitToast("Exported 5 readings to track-my-sugar-readings-")
        val (name, text) = exportedFiles().single()
        assertTrue(name, name.endsWith(".json"))
        val rows = JSONArray(text)
        assertEquals(5, rows.length())
        val first = rows.getJSONObject(0)
        assertEquals(7, first.length())
        assertEquals("Steve", first.getString("person"))
        assertEquals("BSL", first.getString("metric_type"))
        assertEquals(5.6, first.getDouble("health_metric"), 0.0)
        assertEquals("2026-01-01 08:00:00", first.getString("created_date_time"))
        assertEquals("Jo", rows.getJSONObject(1).getString("person"))
    }

    @Test
    fun exportAppliesPersonMetricAndDateFilters() {
        seedAnalytics()
        pick("#an-person", "Steve")
        pick("#an-metric", "BSL")
        setDate("#an-from", "2026-01-04")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 2")
        clickExport("csv")
        waitToast("Exported 2 readings")
        val lines = exportedFiles().single().second.removePrefix(bom).trim().split("\r\n")
        assertEquals(listOf(
            csvHeader,
            "2,1,Steve,1,BSL,6.2,2026-01-05 08:00:00",
            "3,1,Steve,1,BSL,7,2026-01-10 08:00:00",
        ), lines)
    }

    @Test
    fun exportJsonHonoursToDateInclusively() {
        seedAnalytics()
        setDate("#an-to", "2026-01-02")
        waitFor("document.querySelectorAll('#chart-svg circle').length === 2")
        clickExport("json")
        waitToast("Exported 2 readings")
        val rows = JSONArray(exportedFiles().single().second)
        assertEquals(listOf("Steve", "Jo"), (0 until rows.length()).map { rows.getJSONObject(it).getString("person") })
    }

    @Test
    fun exportWithNoMatchingReadingsSavesNothing() {
        seedAnalytics()
        setDate("#an-from", "2027-01-01")
        waitFor("document.querySelector('#chart').innerText.includes('No readings match')")
        clickExport("csv")
        waitToast("Nothing to export")
        assertEquals("error", js("document.getElementById('message').className").trim('"'))
        clickExport("json")
        waitToast("Nothing to export")
        assertEquals(0, exportedFiles().size)
    }

    @Test
    fun exportingTwiceKeepsBothFiles() {
        seedAnalytics()
        clickExport("csv")
        waitToast("Exported 5 readings")
        js("document.getElementById('message').hidden = true")
        clickExport("csv")
        waitToast("Exported 5 readings")
        val names = exportedFiles().map { it.first }
        assertEquals(2, names.size)
        assertEquals("the second file gets a different name", 2, names.toSet().size)
    }

    @Test
    fun csvEscapingQuotesCommasNewlinesAndFormulas() {
        val row = """{id:1,person_id:1,person:'He said "hi", ok',metric_type_id:2,metric_type:'=SUM(1)',
            health_metric:5.5,created_date_time:'2026-01-01 08:00:00'}"""
        val csv = JSONArray("[" + js("toCsv([$row])") + "]").getString(0)
        assertEquals(
            "$bom$csvHeader\r\n1,1,\"He said \"\"hi\"\", ok\",2,'=SUM(1),5.5,2026-01-01 08:00:00\r\n", csv
        )
        val multiline = JSONArray("[" + js("csvField('line1\\nline2')") + "]").getString(0)
        assertEquals("\"line1\nline2\"", multiline)
        // Numbers (even negative ones) and empty values are never altered.
        assertEquals("-3.5", JSONArray("[" + js("csvField(-3.5)") + "]").getString(0))
        assertEquals("", JSONArray("[" + js("csvField(null)") + "]").getString(0))
    }
}
