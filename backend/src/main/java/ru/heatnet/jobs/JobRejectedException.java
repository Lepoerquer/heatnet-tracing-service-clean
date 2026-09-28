package ru.heatnet.jobs;

/** Очередь пула расчёта заполнена. */
public class JobRejectedException extends RuntimeException {

    public JobRejectedException(String message) {
        super(message);
    }
}
