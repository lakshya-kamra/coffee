package com.laksh.coffee.domain

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.recipes.ExpertRecipes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share-sheet report: what a person pastes into a chat when they have no key in the app. */
class BrewReportMarkdownTest {

    private val v60 = ExpertRecipes.byId("hoffmann-v60")!!

    private val kUltra = GrinderEntity(
        id = 1, brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 100, micronsPerClick = 20.0, lowerIsFiner = true, maxSetting = 200.0,
        catalogId = "1zpresso-k-ultra",
    )

    private val encore = GrinderEntity(
        id = 2, brand = "Baratza", model = "Encore", settingStyle = SettingStyle.Numbers,
        lowerIsFiner = true, minSetting = 1.0, maxSetting = 40.0, catalogId = "baratza-encore",
    )

    private val water = Catalog.water.first { it.ghPpm != null }

    private val bean = BeanEntity(
        id = 5,
        name = "Halo Beriti",
        roaster = "Onyx",
        originId = "ethiopia",
        region = "Yirgacheffe",
        farm = "Lot 4",
        producer = "Smallholders",
        washingStation = "Halo Beriti",
        lotTypeId = Catalog.beans.lotTypes.first().id,
        varietalIds = listOf("gesha"),
        varietal = "74110",
        processId = "washed",
        roastLevelId = "light",
        altitudeMasl = "2100",
        harvest = "2025/26",
        restDays = 10,
        isDecaf = true,
        notes = "Second bag",
    )

    private fun log(grinder: GrinderEntity = kUltra, clicks: Int? = 75, value: Double? = null) = BrewLogEntity(
        id = 10,
        startedAt = 1_789_373_520_000L,
        recipeId = v60.id,
        recipeName = v60.name,
        recipeAuthor = v60.author,
        brewer = v60.brewer.label,
        brewerId = v60.brewer.id,
        beanId = bean.id,
        beanLabel = bean.display,
        daysOffRoast = 14,
        grinderId = grinder.id,
        grinderLabel = grinder.display,
        grindClicks = clicks,
        grindValue = value,
        grindSetting = value?.toString().orEmpty(),
        doseG = 30.0,
        waterG = 500.0,
        tempC = 96,
        totalTimeSec = 208,
        overall = 8,
        waterId = water.id,
    )

    @Test
    fun theCoffeeSectionCarriesTheNewFields() {
        val md = BrewReport.markdown(log(), v60, bean, kUltra)
        listOf(
            "- Origin: Ethiopia Yirgacheffe",
            "- Farm or estate: Lot 4",
            "- Producer: Smallholders",
            "- Washing station: Halo Beriti",
            "- Lot: ${Catalog.beans.lotTypes.first().label}",
            "- Varietal: Gesha, 74110",
            "- Process: Washed (classic family)",
            "- Decaf: yes, method not recorded",
            "- Roast level: Light",
            "- Altitude: 2100 masl",
            "- Harvest: 2025/26",
            "- Days off roast: 14",
            "- Roaster suggests resting: 10 days",
            "- My notes on the bag: Second bag",
        ).forEach { assertTrue("missing: $it\n$md", md.contains(it)) }
    }

    @Test
    fun theBrewerAndTheWaterAreNamed() {
        val md = BrewReport.markdown(log(), v60, bean, kUltra)
        assertTrue(md, md.contains("- Brewer: ${v60.brewer.label} (pour-over, ${v60.brewer.filter} filter)"))
        assertTrue(md, md.contains("- Water source: ${water.label} (GH "))
    }

    @Test
    fun aNumberedClickDialIsExplainedTheWayItReads() {
        val md = BrewReport.markdown(log(), v60, bean, kUltra)
        assertTrue(md, md.contains("- Setting used: **75 clicks** out from zero (burrs touching)"))
        assertTrue(md, md.contains("- Dial: 100 clicks per full turn; 75 reads 7.5 on the dial"))
        assertTrue(md, md.contains("- Direction: fewer clicks is finer"))
        // Burr travel, in those words: clicks x microns is not a particle size.
        assertTrue(md, md.contains("- Burr travel from zero: about 1.5 mm (20.0 microns per click). This is how far the burrs have moved, not the size of the grounds."))
        assertFalse(md, md.contains("burr gap"))
        assertTrue(md, md.contains("- Catalog starting range for pour-over: 75 to 90 clicks (from 1Zpresso"))
        assertTrue(md, md.contains("confidence). A sanity check, not a target."))
        assertTrue(md, md.contains("- How this dial reads: External ring numbered 0-9"))
        assertTrue(md, md.contains("give it in this grinder's units, the way the dial reads, and say which way to turn"))
    }

    @Test
    fun aNumberedDialUsesItsDialNumber() {
        val md = BrewReport.markdown(log(grinder = encore, clicks = null, value = 14.0), v60, bean, encore)
        assertTrue(md, md.contains("- Setting used: **14** on the dial"))
        assertTrue(md, md.contains("- Direction: lower settings are finer"))
        assertTrue(md, md.contains("- Catalog starting range for pour-over: 15 to 25 (from Baratza"))
        assertTrue(md, md.contains("give it in this grinder's units, the way the dial reads"))
        assertFalse(md, md.contains("clicks** out from zero"))
    }

    @Test
    fun photosAreMentionedButNeverAttached() {
        assertFalse(BrewReport.markdown(log(), v60, bean, kUltra).contains("in the app (not attached)"))
        assertTrue(BrewReport.markdown(log(), v60, bean, kUltra, photoCount = 1).contains("1 photo of this brew is in the app (not attached)."))
        assertTrue(BrewReport.markdown(log(), v60, bean, kUltra, photoCount = 2).contains("2 photos of this brew are in the app (not attached)."))
    }

    @Test
    fun aBrewWithNoGrinderOrBeanStillMakesAReport() {
        val bare = log().copy(grinderId = null, grinderLabel = "", grindClicks = null, beanId = null, beanLabel = "", waterId = null)
        val md = BrewReport.markdown(bare, null, null, null)
        assertTrue(md.contains("- Setting: not recorded"))
        assertTrue(md.contains("2. The single change I should make next brew"))
        assertFalse(md.contains("- Water source:"))
    }
}
