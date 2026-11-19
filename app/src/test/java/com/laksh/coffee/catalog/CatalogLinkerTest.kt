package com.laksh.coffee.catalog

import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLinkerTest {

    /** The owner's 1.x row: the old preset said 10 clicks a turn. */
    private val oneXKUltra = GrinderEntity(
        brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 10, lowerIsFiner = true,
    )

    @Test
    fun normaliseStripsAccentsCaseAndPunctuation() {
        assertEquals("mahlkonigek43", CatalogLinker.norm("Mahlkönig EK-43"))
        assertEquals("1zpressokultra", CatalogLinker.norm("1Zpresso K-Ultra"))
    }

    @Test
    fun theOneXKUltraLinksAndRaisesTheScaleNotice() {
        val match = CatalogLinker.matchGrinder(oneXKUltra, Catalog.grinders)
        assertEquals("1zpresso-k-ultra", match?.id)
        assertTrue(CatalogLinker.scaleDiffers(oneXKUltra, match!!))
        val accepted = oneXKUltra.copy(clicksPerRotation = 100, micronsPerClick = 20.0)
        assertFalse(CatalogLinker.scaleDiffers(accepted, match))
    }

    @Test
    fun unknownGrindersStayUnlinked() {
        assertNull(CatalogLinker.matchGrinder(oneXKUltra.copy(brand = "Home", model = "Made"), Catalog.grinders))
        assertNull(CatalogLinker.matchGrinder(oneXKUltra.copy(brand = "", model = ""), Catalog.grinders))
    }

    @Test
    fun beansLinkByExactNameOnlyAndKeepTheirText() {
        val roaster = Catalog.roasters.first()
        val origin = Catalog.origins.first()
        val bean = BeanEntity(name = "Test", roaster = roaster.name.uppercase(), origin = origin.country.lowercase())
        val linked = CatalogLinker.linkBean(bean, Catalog.roasters, Catalog.origins)!!
        assertEquals(roaster.id, linked.roasterId)
        assertEquals(origin.id, linked.originId)
        assertEquals(bean.roaster, linked.roaster)
        assertEquals(bean.origin, linked.origin)
        assertNull(CatalogLinker.linkBean(BeanEntity(name = "x", roaster = "Nobody At All Coffee"), Catalog.roasters, emptyList()))
    }

    @Test
    fun aTrailingRoastersWordStillMatches() {
        val roaster = Catalog.roasters.first { r -> r.name.split(" ").size >= 2 && !r.name.endsWith("Roasters") }
        val match = CatalogLinker.matchRoaster(roaster.name + " Roasters", Catalog.roasters)
        assertEquals(roaster.id, match?.id)
    }
}
