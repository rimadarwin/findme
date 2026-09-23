/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Aggiorna il testo della domanda di accesso configurata sui ricevitori.
 * @modified 23.09.2026 - MDS | Sostituita la domanda "Dove sei nato?" con "Dove sono nato?".
 */
update public.receivers
set access_question = 'Dove sono nato?'
where access_question = 'Dove sei nato?';
