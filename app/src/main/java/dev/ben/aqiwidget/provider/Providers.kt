package dev.ben.aqiwidget.provider

/**
 * The active data source.
 *
 * Compile-time constant by design. Runtime switching would need a settings UI, persistence,
 * and migration logic for a decision made perhaps twice in this app's life. The AqiProvider
 * interface is what buys flexibility; a picker would be furniture. To swap sources, write a
 * new AqiProvider and change this one line.
 */
object Providers {
    val ACTIVE: AqiProvider = OpenMeteoProvider()
}
