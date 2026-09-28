package ru.heatnet.calc;

/**
 * Ошибка инженерного расчёта или нормативной конфигурации, после которой продолжать
 * расчёт варианта нельзя (разорванная цепочка к источнику, расход больше DN1400,
 * некорректное дерево и т.п.). Сообщение предназначено для человека.
 */
public class CalcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CalcException(String message) {
        super(message);
    }

    public CalcException(String message, Throwable cause) {
        super(message, cause);
    }
}
