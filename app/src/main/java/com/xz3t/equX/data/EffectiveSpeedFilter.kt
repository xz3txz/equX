package com.xz3t.equX.data

import kotlin.math.abs
import kotlin.math.exp

/**
 * «Эффективная скорость» для GALA и скоростного EQ: сырая скорость из любого источника
 * (ECARX / VHAL / shell / имитация, позже GPS) → сглаженная и устойчивая к шуму и пропаданию сигнала.
 *
 * 1. Сглаживание — EMA по времени: alpha = 1 − exp(−dt / [smoothingTimeSec]), поэтому результат не зависит
 *    от частоты источника (колбэк VHAL ~10 Гц, ECARX 1 Гц, shell раз в 2 с).
 * 2. Мёртвая зона — эффективное значение меняется, только когда сглаженное ушло от него дальше
 *    [deadBandKmh]: громкость не дёргается на границе шага, EQ не «дышит» от шума.
 *    Близкое к нулю значение (< [zeroSnapKmh]) прижимается к 0, чтобы остановка не «залипала» на 2–3 км/ч.
 * 3. Потеря сигнала — нет сэмплов дольше [staleMs]: держим последнее значение до [holdMs] от последнего
 *    сэмпла (тоннель — машина обычно едет как ехала), затем плавно снижаем с [decelMps2] (торможение
 *    двигателем). Экстренное торможение ≈ 7–8 м/с², т.е. со 100 км/ч машина физически не встанет быстрее ~4 с.
 *
 * Не потокобезопасен — вызывающий код синхронизирует доступ. Время передаётся явно (тестируемость).
 */
class EffectiveSpeedFilter(
    private val smoothingTimeSec: Float = 2.5f,
    private val deadBandKmh: Float = 3f,
    private val zeroSnapKmh: Float = 1f,
    private val staleMs: Long = 3_000L,
    private val holdMs: Long = 15_000L,
    private val decelMps2: Float = 1f,
) {
    private var smoothed: Float? = null
    private var lastSampleAtMs = 0L
    private var lastStepAtMs = 0L

    /** Текущая эффективная скорость, км/ч. */
    var effectiveKmh: Float = 0f
        private set

    /** Новый сэмпл скорости от источника. Возвращает эффективную скорость. */
    fun onSample(rawKmh: Float, nowMs: Long): Float {
        val raw = rawKmh.coerceAtLeast(0f)
        val prev = smoothed
        smoothed = if (prev == null) {
            raw
        } else {
            // dt ограничен staleMs: после паузы в сигнале новое значение догоняется плавно, а не скачком.
            val dtSec = ((nowMs - lastStepAtMs).coerceIn(0L, staleMs)) / 1000f
            val alpha = if (smoothingTimeSec <= 0f) 1f else 1f - exp(-dtSec / smoothingTimeSec)
            prev + alpha * (raw - prev)
        }
        lastSampleAtMs = nowMs
        lastStepAtMs = nowMs
        applyDeadBand()
        return effectiveKmh
    }

    /**
     * Периодический вызов (раз в секунду из цикла монитора). Обрабатывает пропадание сигнала;
     * пока сэмплы свежие, ничего не меняет. Возвращает эффективную скорость.
     */
    fun tick(nowMs: Long): Float {
        val current = smoothed ?: return effectiveKmh
        // Сэмплы свежие или идёт удержание — значение не трогаем.
        if (nowMs - lastSampleAtMs <= maxOf(staleMs, holdMs)) return effectiveKmh

        // Удержание закончилось — плавное снижение с момента предыдущего шага.
        val decayFrom = maxOf(lastStepAtMs, lastSampleAtMs + holdMs)
        val dtSec = ((nowMs - decayFrom).coerceAtLeast(0L)) / 1000f
        val decreased = (current - decelMps2 * 3.6f * dtSec).coerceAtLeast(0f)
        smoothed = decreased
        lastStepAtMs = nowMs
        // При снижении мёртвую зону не применяем — иначе скорость шла бы ступеньками по 3 км/ч.
        effectiveKmh = if (decreased < zeroSnapKmh) 0f else decreased
        return effectiveKmh
    }

    private fun applyDeadBand() {
        val s = smoothed ?: return
        if (s < zeroSnapKmh) {
            effectiveKmh = 0f
        } else if (abs(s - effectiveKmh) >= deadBandKmh) {
            effectiveKmh = s
        }
    }
}
