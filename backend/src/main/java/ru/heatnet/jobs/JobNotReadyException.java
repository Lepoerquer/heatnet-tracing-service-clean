package ru.heatnet.jobs;

/** Расчёт ещё не закончен — result/explain пока недоступны. */
public class JobNotReadyException extends RuntimeException {

    public JobNotReadyException(String message) {
        super(message);
    }
}
