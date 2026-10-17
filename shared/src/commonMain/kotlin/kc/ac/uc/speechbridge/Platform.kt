package kc.ac.uc.speechbridge

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform