package com.site

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SiteApplication

fun main(args: Array<String>) {
    runApplication<SiteApplication>(*args)
}
