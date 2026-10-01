/**
 * @author Infinity
 * @description Valida e normalizza domanda e risposta di accesso del ricevitore.
 * @modified 01.10.2026 - Infinity | Prima implementazione condivisa.
 */
package it.xcc.findme.core

object ReceiverAccessPolicy {
    const val MIN_QUESTION_LENGTH = 3
    const val MAX_QUESTION_LENGTH = 160
    const val MAX_ANSWER_LENGTH = 160

    /** Normalizza domanda e risposta, rifiutando valori vuoti o fuori limite. */
    fun normalize(question: String, answer: String): Pair<String, String> {
        val normalizedQuestion = question.trim()
        val normalizedAnswer = answer.trim()
        require(normalizedQuestion.length in MIN_QUESTION_LENGTH..MAX_QUESTION_LENGTH) {
            "La domanda deve contenere da $MIN_QUESTION_LENGTH a $MAX_QUESTION_LENGTH caratteri"
        }
        require(normalizedAnswer.isNotEmpty()) { "La risposta non può essere vuota" }
        require(normalizedAnswer.length <= MAX_ANSWER_LENGTH) {
            "La risposta non può superare $MAX_ANSWER_LENGTH caratteri"
        }
        return normalizedQuestion to normalizedAnswer
    }

    /** Indica se entrambi i valori sono validi e quindi salvabili. */
    fun isValid(question: String, answer: String): Boolean =
        runCatching { normalize(question, answer) }.isSuccess
}
