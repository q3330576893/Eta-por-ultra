package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLayersTest {
    @Test
    fun resolvePrefersHighestPriorityExplicitLayer() {
        val all = LayeredSetting("k", default = 1, remote = 2, device = 3, user = 4)
        assertEquals(4, SettingsLayers.resolve(all))
        assertEquals(SettingsLayer.USER, SettingsLayers.resolvedLayer(all))

        assertEquals(3, SettingsLayers.resolve(all.copy(user = null)))
        assertEquals(SettingsLayer.DEVICE, SettingsLayers.resolvedLayer(all.copy(user = null)))

        assertEquals(2, SettingsLayers.resolve(all.copy(user = null, device = null)))
        assertEquals(SettingsLayer.REMOTE, SettingsLayers.resolvedLayer(all.copy(user = null, device = null)))

        val onlyDefault = all.copy(remote = null, device = null, user = null)
        assertEquals(1, SettingsLayers.resolve(onlyDefault))
        assertEquals(SettingsLayer.DEFAULT, SettingsLayers.resolvedLayer(onlyDefault))
    }

    @Test
    fun explicitDefaultIsDistinctFromUnset() {
        // 显式设为默认值（0）与“未设置”（null）必须区分：前者仍生效。
        val explicit = LayeredSetting("k", default = 10, user = 0)
        assertEquals(0, SettingsLayers.resolve(explicit))
        assertEquals(SettingsLayer.USER, SettingsLayers.resolvedLayer(explicit))
    }

    @Test
    fun legacyValueMigratesToUserLayerIdempotently() {
        val base = LayeredSetting("k", default = 10)
        val migrated = SettingsLayers.migrateLegacy(base, 42)
        assertEquals(42, migrated.user)
        assertEquals(42, SettingsLayers.resolve(migrated))

        // 已有设备层时不降级旧值：迁移幂等，保留原结构。
        val withDevice = base.copy(device = 7)
        assertEquals(withDevice, SettingsLayers.migrateLegacy(withDevice, 42))

        // 旧值为 null 时不产生覆盖。
        assertEquals(base, SettingsLayers.migrateLegacy(base, null))
    }

    @Test
    fun resetLayerOnlyClearsThatLayerExceptDefaultWhichClearsAll() {
        val all = LayeredSetting("k", default = 1, remote = 2, device = 3, user = 4)
        assertEquals(3, SettingsLayers.resolve(SettingsLayers.resetLayer(all, SettingsLayer.USER)))
        assertEquals(4, SettingsLayers.resolve(SettingsLayers.resetLayer(all, SettingsLayer.DEVICE)))
        assertEquals(4, SettingsLayers.resolve(SettingsLayers.resetLayer(all, SettingsLayer.REMOTE)))

        val resetAll = SettingsLayers.resetLayer(all, SettingsLayer.DEFAULT)
        assertNull(resetAll.remote)
        assertNull(resetAll.device)
        assertNull(resetAll.user)
        assertEquals(1, SettingsLayers.resolve(resetAll))
    }

    @Test
    fun setLayerWritesExplicitValueAndNullClears() {
        val base = LayeredSetting("k", default = 1)
        val remote = SettingsLayers.setLayer(base, SettingsLayer.REMOTE, 2)
        assertEquals(2, SettingsLayers.resolve(remote))
        val cleared = SettingsLayers.setLayer(remote, SettingsLayer.REMOTE, null)
        assertEquals(1, SettingsLayers.resolve(cleared))

        // DEFAULT 层显式设置应改写默认值；null 对 DEFAULT 无意义，保持原值。
        val changedDefault = SettingsLayers.setLayer(base, SettingsLayer.DEFAULT, 9)
        assertEquals(9, SettingsLayers.resolve(changedDefault))
        assertEquals(changedDefault, SettingsLayers.setLayer(changedDefault, SettingsLayer.DEFAULT, null))
    }

    @Test
    fun mergeAppliesLayersLowToHighAndNullDeletesKey() {
        val merged = SettingsLayers.merge(
            SettingsLayer.DEFAULT to mapOf("a" to 1, "b" to 2),
            SettingsLayer.REMOTE to mapOf("a" to 10),
            SettingsLayer.DEVICE to mapOf("b" to null, "c" to 30),
            SettingsLayer.USER to mapOf("a" to 100),
        )
        assertEquals(100, merged["a"]) // USER 覆盖 REMOTE 覆盖 DEFAULT
        assertNull(merged["b"]) // DEVICE 的 null 删除了 DEFAULT 的键
        assertEquals(30, merged["c"])

        // 传入顺序不影响结果：内部按优先级排序。
        val reordered = SettingsLayers.merge(
            SettingsLayer.USER to mapOf("a" to 100),
            SettingsLayer.DEVICE to mapOf("b" to null, "c" to 30),
            SettingsLayer.DEFAULT to mapOf("a" to 1, "b" to 2),
            SettingsLayer.REMOTE to mapOf("a" to 10),
        )
        assertEquals(merged, reordered)
    }

    @Test
    fun layerPriorityOrderIsDefaultRemoteDeviceUser() {
        assertEquals(
            listOf(SettingsLayer.DEFAULT, SettingsLayer.REMOTE, SettingsLayer.DEVICE, SettingsLayer.USER),
            SettingsLayer.ORDER,
        )
    }
}
