package com.xz3t.equX

import com.xz3t.equX.data.APP_SCALE_DEFAULT
import com.xz3t.equX.data.APP_SCALE_MAX
import com.xz3t.equX.data.APP_SCALE_MIN
import com.xz3t.equX.data.coerceAppScale
import com.xz3t.equX.data.computeRecommendedAppScale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия: масштаб мерцал, а после переоткрытия настройки сбрасывался в 100%.
 * Фиксируем единый диапазон и валидацию масштаба.
 */
class AppScaleTest {

    // --- Диапазон и валидация ---

    @Test
    fun `default scale is one hundred percent`() {
        assertEquals(1.0f, APP_SCALE_DEFAULT, 0.001f)
    }

    @Test
    fun `value inside range passes through`() {
        assertEquals(1.5f, coerceAppScale(1.5f), 0.001f)
    }

    @Test
    fun `value below min is clamped to min`() {
        assertEquals(APP_SCALE_MIN, coerceAppScale(0.1f), 0.001f)
    }

    @Test
    fun `value above max is clamped to max`() {
        assertEquals(APP_SCALE_MAX, coerceAppScale(3.0f), 0.001f)
    }

    @Test
    fun `min and max are sensible`() {
        assertEquals(0.5f, APP_SCALE_MIN, 0.001f)
        assertEquals(2.0f, APP_SCALE_MAX, 0.001f)
    }

    // --- Рекомендованный масштаб ---

    @Test
    fun `normal phone where densityDpi matches physical dpi keeps default`() {
        // vivo X100 Pro и аналоги: densityDpi совпадает с физическим DPI.
        val scale = computeRecommendedAppScale(densityDpi = 480f, physicalDpi = 480f)
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `normal phone where physical dpi is slightly lower keeps default`() {
        // Типичный случай: densityDpi берётся «с запасом», физический DPI ниже.
        val scale = computeRecommendedAppScale(densityDpi = 480f, physicalDpi = 440f)
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `head unit with halved densityDpi gets compensated`() {
        // ГУ: физический ~320 dpi, densityDpi=160 → ratio ~2.0.
        val scale = computeRecommendedAppScale(densityDpi = 160f, physicalDpi = 320.842f)
        // 2.005 * 0.8 ≈ 1.604 (160%).
        assertEquals(1.604f, scale, 0.01f)
    }

    @Test
    fun `zero densityDpi falls back to default`() {
        assertEquals(1.0f, computeRecommendedAppScale(densityDpi = 0f, physicalDpi = 320f), 0.001f)
    }

    @Test
    fun `zero physical dpi falls back to default`() {
        assertEquals(1.0f, computeRecommendedAppScale(densityDpi = 160f, physicalDpi = 0f), 0.001f)
    }

    @Test
    fun `extreme mismatch is clamped to max`() {
        // ratio 4.0 * 0.8 = 3.2 → должен упереться в APP_SCALE_MAX.
        val scale = computeRecommendedAppScale(densityDpi = 160f, physicalDpi = 640f)
        assertEquals(APP_SCALE_MAX, scale, 0.001f)
    }
}
