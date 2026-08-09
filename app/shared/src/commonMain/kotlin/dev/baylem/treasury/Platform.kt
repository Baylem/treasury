package dev.baylem.treasury

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform