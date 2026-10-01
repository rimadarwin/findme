/**
 * @author Infinity
 * @description Validazione messaggi testuali e dimensionamento adattivo del font.
 * @modified 29.09.2026 - MDS | Prima implementazione condivisa.
 */
package it.xcc.findme.core

object TextMessagePolicy {
    const val MAX_LENGTH = 500

    /** Normalizza il testo e rifiuta contenuti vuoti o oltre il limite. */
    fun normalize(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Il messaggio non può essere vuoto" }
        require(normalized.length <= MAX_LENGTH) {
            "Il messaggio non può superare $MAX_LENGTH caratteri"
        }
        return normalized
    }

    /** Riduce progressivamente il font per mantenere leggibili i testi lunghi. */
    fun fontSizeSp(length: Int): Float = when {
        length <= 50 -> 42f
        length <= 120 -> 34f
        length <= 250 -> 27f
        else -> 22f
    }

    /** Distingue gli stati conclusivi che non devono riaprire l'overlay. */
    fun isTerminal(status: TextMessageStatus): Boolean =
        status == TextMessageStatus.DISMISSED || status == TextMessageStatus.FAILED
}
