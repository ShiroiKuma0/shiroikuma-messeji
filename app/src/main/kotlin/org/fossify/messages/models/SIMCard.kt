package org.fossify.messages.models

/**
 * One choice in the compose screen's SIM selector — a real SIM, or (fork) a bridge transport, which
 * carries its own [badge] text and [color] instead of the slot number.
 */
data class SIMCard(
    val id: Int,
    val subscriptionId: Int,
    val label: String,
    val badge: String = id.toString(),
    val color: Int? = null,
    val textColor: Int? = null,
    val borderColor: Int? = null,
)
