package io.github.mangi.eta.agent.quickactions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherServiceSupportTest {
    @Test fun photonTownResultKeepsFullAdministrativeLineageAndCoordinates() {
        val json = JSONObject("""
            {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"osm_type":"N","osm_id":6746098888,"name":"池江镇","county":"大余县","state":"江西省","country":"中国","countrycode":"CN"},"geometry":{"type":"Point","coordinates":[114.555197,25.488228]}}]}
        """.trimIndent())
        val town = WeatherPlaces.photon(json).single()
        assertEquals("池江镇", town.name)
        assertEquals("大余县", town.admin3)
        assertEquals(25.488228, town.latitude, 0.000001)
        assertEquals(114.555197, town.longitude, 0.000001)
        assertTrue(town.locationSource.contains("OpenStreetMap"))
    }

    @Test fun photonNodeWayRelationIdsStayDistinct() {
        fun row(type: String) = JSONObject("""
            {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"osm_type":"$type","osm_id":9,"name":"同名镇","country":"中国","countrycode":"CN"},"geometry":{"type":"Point","coordinates":[114.0,25.0]}}]}
        """.trimIndent())
        val ids = listOf("N", "W", "R").map { WeatherPlaces.photon(row(it)).single().id }.toSet()
        assertEquals(3, ids.size)
    }

    @Test fun manualCoordinatesRejectOutOfRangeAndKeepValidLocation() {
        assertEquals(null, WeatherPlaces.manual("91", "114"))
        val city = WeatherPlaces.manual("25.488228", "114.555197")
        assertEquals("手动坐标", city?.name)
        assertEquals("auto", city?.timezone)
    }

    @Test fun detailedQueryRequiresLeafNameAndParentLineage() {
        val town = WeatherCity(1, "池江镇", "CN", "中国", "江西省", "赣州市", 25.4, 114.5, "auto", "大余县", "", "Photon")
        assertEquals(1, WeatherPlaces.select("江西省赣州市大余县池江镇", listOf(town)).size)
        assertTrue(WeatherPlaces.select("江西省赣州市大余县南康镇", listOf(town)).isEmpty())
    }
}
