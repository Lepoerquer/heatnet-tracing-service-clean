package ru.heatnet.jobs;

/** Обновление стадии расчёта. */
public interface JobProgress {

    void update(String stage, int progress);
}
