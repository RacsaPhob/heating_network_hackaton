package ru.heatnet.api;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.heatnet.core.Geo;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException ex) {return error(422,"INVALID_DATA",ex.getMessage());}
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String,Object>> missing(NoSuchElementException ex) {return error(404,"NOT_FOUND",ex.getMessage());}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,Object>> size(MaxUploadSizeExceededException ex) {return error(413,"FILE_TOO_LARGE","MVP принимает файлы до 20 МБ");}
    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Map<String,Object>> busy(RejectedExecutionException ex) {return error(429,"QUEUE_FULL","Очередь заполнена. Повторите позже.");}
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class)
    public ResponseEntity<Map<String,Object>> json(com.fasterxml.jackson.core.JsonProcessingException ex) {return error(422,"INVALID_JSON","Не удалось прочитать JSON. Проверьте формат файла.");}
    private ResponseEntity<Map<String,Object>> error(int status,String code,String message) {return ResponseEntity.status(status).body(Geo.map("code",code,"message",message,"details",Collections.emptyList()));}
}
