package com.simplemq.simplemq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SimpleMqApplication

fun main(args: Array<String>) {
    runApplication<SimpleMqApplication>(*args)
}
