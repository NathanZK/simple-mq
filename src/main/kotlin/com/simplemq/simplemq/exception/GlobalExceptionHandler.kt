package com.simplemq.simplemq.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Date

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        val status =
            when {
                ex.message?.contains("Message not found") == true -> HttpStatus.NOT_FOUND
                ex.message?.contains("Queue not found") == true -> HttpStatus.NOT_FOUND
                ex.message?.contains("Invalid UUID") == true -> HttpStatus.BAD_REQUEST
                else -> HttpStatus.BAD_REQUEST
            }

        return ResponseEntity
            .status(status)
            .body(
                mapOf(
                    "error" to (ex.message ?: "Invalid request"),
                    "timestamp" to Date().toString(),
                ),
            )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalStateException(ex: IllegalStateException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .body(
                mapOf(
                    "error" to (ex.message ?: "Queue error"),
                    "timestamp" to Date().toString(),
                ),
            )
    }
}
