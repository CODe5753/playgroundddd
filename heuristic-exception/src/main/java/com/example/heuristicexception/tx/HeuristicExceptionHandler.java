package com.example.heuristicexception.tx;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class HeuristicExceptionHandler {

    @ExceptionHandler(HeuristicCompletionException.class)
    public ResponseEntity<String> handle(HeuristicCompletionException ex) {
        return ResponseEntity.status(500).body(ex.getMessage());
    }
}
