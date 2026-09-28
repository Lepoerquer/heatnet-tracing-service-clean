package ru.heatnet.api;

import java.io.IOException;
import java.util.NoSuchElementException;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import ru.heatnet.api.dto.ApiErrorResponse;
import ru.heatnet.calc.CalcException;
import ru.heatnet.geo.IllegalCoordinateOrderException;
import ru.heatnet.jobs.JobNotReadyException;
import ru.heatnet.jobs.JobRejectedException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CalcException.class)
    public ResponseEntity<ApiErrorResponse> handleCalcException(CalcException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalCoordinateOrderException.class)
    public ResponseEntity<ApiErrorResponse> handleCoordinateOrder(
            IllegalCoordinateOrderException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> handleIo(IOException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Некорректный или обрезанный GeoJSON", request.getRequestURI());
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, readable(ex), request.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Метод не поддерживается", request.getRequestURI());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMedia(
            HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого", request.getRequestURI());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Файл слишком большой", request.getRequestURI());
    }

    @ExceptionHandler(JobNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReady(JobNotReadyException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(JobRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleRejected(JobRejectedException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFoundElement(
            NoSuchElementException ex,
            HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoHandlerFoundException ex,
            HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Ресурс не найден", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервиса", request.getRequestURI());
    }

    private static String readable(Exception ex) {
        if (ex instanceof MethodArgumentTypeMismatchException) {
            return "Некорректный параметр запроса";
        }
        if (ex instanceof MissingServletRequestPartException
                || ex instanceof MissingServletRequestParameterException) {
            return "Не передана обязательная часть запроса";
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "Некорректное тело запроса";
        }
        return "Некорректный запрос";
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message, String path) {
        ApiErrorResponse body = new ApiErrorResponse();
        body.setStatus(status.value());
        body.setError(status.getReasonPhrase());
        body.setMessage(message);
        body.setPath(path);
        return ResponseEntity.status(status).body(body);
    }
}
